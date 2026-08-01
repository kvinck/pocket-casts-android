#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <deque>
#include <limits>
#include <numeric>
#include <vector>

namespace {

// VoiceBoostN targets -17 LUFS before compression, producing approximately -14 LUFS at the output.
constexpr float kTargetLufs = -17.0f;
constexpr float kMinGainDb = -12.0f;
constexpr float kMaxGainDb = 24.0f;
constexpr float kAbsoluteGateLufs = -70.0f;
constexpr float kLufsOffset = -0.691f;
constexpr float kLimiterCeiling = 0.7943282f; // -2 dBFS
constexpr float kMaxInt16 = 32767.0f;
constexpr float kMinInt16 = -32768.0f;
constexpr float kPi = 3.14159265358979323846f;
constexpr size_t kMaxLoudnessBlockCount = 30; // Three seconds at one measurement every 100 ms.
constexpr float kHighPassFrequency = 80.0f;
constexpr float kHighPassQ = 0.707f;
constexpr float kCompressorThresholdDb = -8.0f;
constexpr float kCompressorRatio = 2.0f;
constexpr float kCompressorAttackMs = 100.0f;
constexpr float kCompressorReleaseMs = 400.0f;
constexpr float kGainSmoothingMs = 200.0f;
constexpr float kLimiterLookaheadMs = 5.0f;
constexpr float kLimiterReleaseMs = 100.0f;

float dbToLinear(float db) {
    return std::pow(10.0f, db / 20.0f);
}

float meanSquareToLufs(double meanSquare) {
    if (meanSquare <= std::numeric_limits<double>::min()) {
        return -std::numeric_limits<float>::infinity();
    }
    return kLufsOffset + static_cast<float>(10.0 * std::log10(meanSquare));
}

struct Biquad {
    float b0 = 1.0f;
    float b1 = 0.0f;
    float b2 = 0.0f;
    float a1 = 0.0f;
    float a2 = 0.0f;
    float z1 = 0.0f;
    float z2 = 0.0f;

    float process(float x) {
        const float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }

    void reset() {
        z1 = 0.0f;
        z2 = 0.0f;
    }

    void setCoefficients(
        double newB0,
        double newB1,
        double newB2,
        double newA1,
        double newA2) {
        b0 = static_cast<float>(newB0);
        b1 = static_cast<float>(newB1);
        b2 = static_cast<float>(newB2);
        a1 = static_cast<float>(newA1);
        a2 = static_cast<float>(newA2);
    }

    void setHighPass(float sampleRate, float frequency, float q) {
        const float omega = 2.0f * kPi * frequency / sampleRate;
        const float sinOmega = std::sin(omega);
        const float cosOmega = std::cos(omega);
        const float alpha = sinOmega / (2.0f * q);
        const float a0 = 1.0f + alpha;

        b0 = ((1.0f + cosOmega) / 2.0f) / a0;
        b1 = (-(1.0f + cosOmega)) / a0;
        b2 = ((1.0f + cosOmega) / 2.0f) / a0;
        a1 = (-2.0f * cosOmega) / a0;
        a2 = (1.0f - alpha) / a0;
    }

};

struct ChannelWeighting {
    Biquad highPass;
    Biquad highShelf;

    void configure(float sampleRate) {
        constexpr double highShelfFrequency = 1681.9744509555319;
        constexpr double highShelfGainDb = 3.99984385397;
        constexpr double highShelfQ = 0.7071752369554193;
        const double highShelfK = std::tan(kPi * highShelfFrequency / sampleRate);
        const double highShelfK2 = highShelfK * highShelfK;
        const double highShelfGain = std::pow(10.0, highShelfGainDb / 20.0);
        const double highShelfIntermediateGain = std::pow(highShelfGain, 0.499666774155);
        const double highShelfA0 = 1.0 + highShelfK / highShelfQ + highShelfK2;
        highShelf.setCoefficients(
            (highShelfGain + highShelfIntermediateGain * highShelfK / highShelfQ + highShelfK2) / highShelfA0,
            2.0 * (highShelfK2 - highShelfGain) / highShelfA0,
            (highShelfGain - highShelfIntermediateGain * highShelfK / highShelfQ + highShelfK2) / highShelfA0,
            2.0 * (highShelfK2 - 1.0) / highShelfA0,
            (1.0 - highShelfK / highShelfQ + highShelfK2) / highShelfA0);

        constexpr double highPassFrequency = 38.13547087613982;
        constexpr double highPassQ = 0.5003270373253953;
        const double highPassK = std::tan(kPi * highPassFrequency / sampleRate);
        const double highPassK2 = highPassK * highPassK;
        const double highPassA0 = 1.0 + highPassK / highPassQ + highPassK2;
        highPass.setCoefficients(
            1.0,
            -2.0,
            1.0,
            2.0 * (highPassK2 - 1.0) / highPassA0,
            (1.0 - highPassK / highPassQ + highPassK2) / highPassA0);
        reset();
    }

    float process(float sample) {
        return highShelf.process(highPass.process(sample));
    }

    void reset() {
        highPass.reset();
        highShelf.reset();
    }
};

class VocalBoostEngine {
public:
    void configure(int newSampleRate, int newChannelCount) {
        if (newSampleRate <= 0 || newChannelCount <= 0) {
            sampleRate = 0;
            channelCount = 0;
            highPassFilters.clear();
            compressorEnvelopes.clear();
            reset();
            return;
        }

        sampleRate = newSampleRate;
        channelCount = newChannelCount;
        peakLookaheadFrames = std::max(1, static_cast<int>(sampleRate * kLimiterLookaheadMs / 1000.0f));
        blockFrames = std::max(1, sampleRate / 10);
        weighting.configure(static_cast<float>(sampleRate));
        highPassFilters.assign(channelCount, Biquad());
        for (auto& filter : highPassFilters) {
            filter.setHighPass(static_cast<float>(sampleRate), kHighPassFrequency, kHighPassQ);
        }
        compressorThreshold = dbToLinear(kCompressorThresholdDb);
        compressorSlope = 1.0f - 1.0f / kCompressorRatio;
        compressorAttackCoefficient = std::exp(
            -1.0f / (static_cast<float>(sampleRate) * kCompressorAttackMs / 1000.0f));
        compressorReleaseCoefficient = std::exp(
            -1.0f / (static_cast<float>(sampleRate) * kCompressorReleaseMs / 1000.0f));
        gainSmoothingCoefficient = 1.0f - std::exp(
            -1.0f / (static_cast<float>(sampleRate) * kGainSmoothingMs / 1000.0f));
        limiterReleaseCoefficient = 1.0f - std::exp(
            -2.2f / (static_cast<float>(sampleRate) * kLimiterReleaseMs / 1000.0f));
        reset();
    }

    void reset() {
        delayedSamples.clear();
        peakDeque.clear();
        subBlockSums.clear();
        gatedBlockMeanSquares.clear();
        currentSubBlockSum = 0.0;
        currentSubBlockFrames = 0;
        currentGain = 1.0f;
        displayedGainDb.store(0.0f, std::memory_order_relaxed);
        targetGain = 1.0f;
        limiterGain = 1.0f;
        integratedLufs = -std::numeric_limits<float>::infinity();
        relativeGateLufs = -std::numeric_limits<float>::infinity();
        frameIndex = 0;
        compressorEnvelopes.assign(channelCount, 0.0f);
        weighting.reset();
        for (auto& filter : highPassFilters) {
            filter.reset();
        }
    }

    int process(const int16_t* input, int inputFrames, int16_t* output, int outputCapacityFrames) {
        if (sampleRate <= 0 || channelCount <= 0) {
            return 0;
        }

        int outputFrames = 0;
        for (int frame = 0; frame < inputFrames; ++frame) {
            const float measuredSample = static_cast<float>(input[frame * channelCount]) / kMaxInt16;
            measureSample(measuredSample);
            updateGain();

            float framePeak = 0.0f;
            for (int channel = 0; channel < channelCount; ++channel) {
                const int sampleIndex = frame * channelCount + channel;
                const float sample = static_cast<float>(input[sampleIndex]) / kMaxInt16;
                const float equalizedSample = highPassFilters[channel].process(sample * currentGain);
                const float compressedSample = compress(equalizedSample, channel);
                framePeak = std::max(framePeak, std::abs(compressedSample));
                delayedSamples.push_back(compressedSample);
            }
            pushPeak(framePeak);
            ++frameIndex;

            while (queuedFrames() > peakLookaheadFrames && outputFrames < outputCapacityFrames) {
                writeDelayedFrame(output + outputFrames * channelCount);
                ++outputFrames;
            }
        }
        displayedGainDb.store(linearToDb(currentGain), std::memory_order_relaxed);
        return outputFrames;
    }

    int drain(int16_t* output, int outputCapacityFrames) {
        int outputFrames = 0;
        while (queuedFrames() > 0 && outputFrames < outputCapacityFrames) {
            writeDelayedFrame(output + outputFrames * channelCount);
            ++outputFrames;
        }
        return outputFrames;
    }

    float currentGainDb() const {
        return displayedGainDb.load(std::memory_order_relaxed);
    }

private:
    int sampleRate = 0;
    int channelCount = 0;
    int peakLookaheadFrames = 1;
    int blockFrames = 1;
    ChannelWeighting weighting;
    std::vector<Biquad> highPassFilters;
    std::vector<float> compressorEnvelopes;
    std::deque<float> delayedSamples;
    std::deque<std::pair<int64_t, float>> peakDeque;
    std::deque<double> subBlockSums;
    std::deque<double> gatedBlockMeanSquares;
    double currentSubBlockSum = 0.0;
    int currentSubBlockFrames = 0;
    float currentGain = 1.0f;
    std::atomic<float> displayedGainDb{0.0f};
    float targetGain = 1.0f;
    float limiterGain = 1.0f;
    float integratedLufs = -std::numeric_limits<float>::infinity();
    float relativeGateLufs = -std::numeric_limits<float>::infinity();
    float compressorThreshold = 1.0f;
    float compressorSlope = 0.5f;
    float compressorAttackCoefficient = 0.0f;
    float compressorReleaseCoefficient = 0.0f;
    float gainSmoothingCoefficient = 0.0f;
    float limiterReleaseCoefficient = 0.0f;
    int64_t frameIndex = 0;

    int queuedFrames() const {
        return static_cast<int>(delayedSamples.size() / static_cast<size_t>(channelCount));
    }

    void measureSample(float sample) {
        const float weighted = weighting.process(sample);
        currentSubBlockSum += static_cast<double>(weighted) * static_cast<double>(weighted);
        ++currentSubBlockFrames;
        if (currentSubBlockFrames >= blockFrames) {
            finishSubBlock();
        }
    }

    void finishSubBlock() {
        subBlockSums.push_back(currentSubBlockSum);
        if (subBlockSums.size() > 4) {
            subBlockSums.pop_front();
        }
        if (subBlockSums.size() == 4) {
            double sum = 0.0;
            for (double value : subBlockSums) {
                sum += value;
            }
            const double meanSquare = sum / static_cast<double>(blockFrames * 4);
            if (meanSquare > std::numeric_limits<double>::min()) {
                const float blockLufs = meanSquareToLufs(meanSquare);
                if (blockLufs > kAbsoluteGateLufs) {
                    gatedBlockMeanSquares.push_back(meanSquare);
                    if (gatedBlockMeanSquares.size() > kMaxLoudnessBlockCount) {
                        gatedBlockMeanSquares.pop_front();
                    }
                    updateTargetGain();
                }
            }
        }
        currentSubBlockSum = 0.0;
        currentSubBlockFrames = 0;
    }

    void updateTargetGain() {
        if (gatedBlockMeanSquares.empty()) {
            return;
        }

        const double absoluteGatedSum = std::accumulate(
            gatedBlockMeanSquares.begin(),
            gatedBlockMeanSquares.end(),
            0.0);
        const double preliminaryMeanSquare = absoluteGatedSum / static_cast<double>(gatedBlockMeanSquares.size());
        const float preliminaryLufs = meanSquareToLufs(preliminaryMeanSquare);
        relativeGateLufs = preliminaryLufs - 10.0f;

        double relativeGatedSum = 0.0;
        int relativeGatedCount = 0;
        for (double meanSquare : gatedBlockMeanSquares) {
            const float blockLufs = meanSquareToLufs(meanSquare);
            if (blockLufs > relativeGateLufs) {
                relativeGatedSum += meanSquare;
                ++relativeGatedCount;
            }
        }
        if (relativeGatedCount == 0) {
            return;
        }

        const double integratedMeanSquare = relativeGatedSum / static_cast<double>(relativeGatedCount);
        integratedLufs = meanSquareToLufs(integratedMeanSquare);
        const float gainDb = std::clamp(kTargetLufs - integratedLufs, kMinGainDb, kMaxGainDb);
        targetGain = dbToLinear(gainDb);
    }

    void updateGain() {
        currentGain += (targetGain - currentGain) * gainSmoothingCoefficient;
    }

    float compress(float sample, int channel) {
        const float inputLevel = std::abs(sample);
        float& envelope = compressorEnvelopes[channel];
        const float coefficient = inputLevel > envelope
            ? compressorAttackCoefficient
            : compressorReleaseCoefficient;
        envelope = coefficient * envelope + (1.0f - coefficient) * inputLevel;

        if (envelope > compressorThreshold) {
            const float ratio = compressorThreshold / envelope;
            const float gain = std::max(0.1f, 1.0f - compressorSlope * (1.0f - ratio));
            return sample * gain;
        }
        return sample;
    }

    void pushPeak(float peak) {
        while (!peakDeque.empty() && peakDeque.back().second <= peak) {
            peakDeque.pop_back();
        }
        peakDeque.emplace_back(frameIndex, peak);
    }

    void writeDelayedFrame(int16_t* output) {
        const int64_t outputFrameIndex = frameIndex - queuedFrames();
        while (!peakDeque.empty() && peakDeque.front().first < outputFrameIndex) {
            peakDeque.pop_front();
        }

        float lookaheadPeak = 0.0f;
        for (const auto& peak : peakDeque) {
            if (peak.first > outputFrameIndex + peakLookaheadFrames) {
                break;
            }
            lookaheadPeak = std::max(lookaheadPeak, peak.second);
        }
        const float wantedLimiterGain = lookaheadPeak > kLimiterCeiling ? kLimiterCeiling / lookaheadPeak : 1.0f;
        if (wantedLimiterGain < limiterGain) {
            limiterGain = wantedLimiterGain;
        } else {
            limiterGain += (1.0f - limiterGain) * limiterReleaseCoefficient;
        }

        for (int channel = 0; channel < channelCount; ++channel) {
            const float sample = delayedSamples.front() * limiterGain;
            delayedSamples.pop_front();
            const float clipped = std::clamp(sample * kMaxInt16, kMinInt16, kMaxInt16);
            output[channel] = static_cast<int16_t>(std::lrintf(clipped));
        }
    }

    static float linearToDb(float value) {
        if (value <= 0.0f) {
            return 0.0f;
        }
        return 20.0f * std::log10(value);
    }
};

VocalBoostEngine* getEngine(jlong handle) {
    return reinterpret_cast<VocalBoostEngine*>(handle);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_au_com_shiftyjelly_pocketcasts_repositories_playback_NativeVocalBoostEngine_nativeCreate(
    JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new VocalBoostEngine());
}

extern "C" JNIEXPORT void JNICALL
Java_au_com_shiftyjelly_pocketcasts_repositories_playback_NativeVocalBoostEngine_nativeConfigure(
    JNIEnv*, jobject, jlong handle, jint sampleRate, jint channelCount) {
    auto* engine = getEngine(handle);
    if (engine != nullptr) {
        engine->configure(sampleRate, channelCount);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_au_com_shiftyjelly_pocketcasts_repositories_playback_NativeVocalBoostEngine_nativeProcess(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject inputBuffer,
    jint inputFrames,
    jobject outputBuffer,
    jint outputCapacityFrames) {
    auto* input = static_cast<int16_t*>(env->GetDirectBufferAddress(inputBuffer));
    auto* output = static_cast<int16_t*>(env->GetDirectBufferAddress(outputBuffer));
    auto* engine = getEngine(handle);
    if (input == nullptr || output == nullptr || engine == nullptr) {
        return -1;
    }
    const int outputFrames = engine->process(input, inputFrames, output, outputCapacityFrames);
    return outputFrames;
}

extern "C" JNIEXPORT jint JNICALL
Java_au_com_shiftyjelly_pocketcasts_repositories_playback_NativeVocalBoostEngine_nativeDrain(
    JNIEnv* env, jobject, jlong handle, jobject outputBuffer, jint outputCapacityFrames) {
    auto* output = static_cast<int16_t*>(env->GetDirectBufferAddress(outputBuffer));
    auto* engine = getEngine(handle);
    if (output == nullptr || engine == nullptr) {
        return -1;
    }
    return engine->drain(output, outputCapacityFrames);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_au_com_shiftyjelly_pocketcasts_repositories_playback_NativeVocalBoostEngine_nativeCurrentGainDb(
    JNIEnv*, jobject, jlong handle) {
    auto* engine = getEngine(handle);
    if (engine == nullptr) {
        return 0.0f;
    }
    return engine->currentGainDb();
}

extern "C" JNIEXPORT void JNICALL
Java_au_com_shiftyjelly_pocketcasts_repositories_playback_NativeVocalBoostEngine_nativeFlush(
    JNIEnv*, jobject, jlong handle) {
    auto* engine = getEngine(handle);
    if (engine != nullptr) {
        engine->reset();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_au_com_shiftyjelly_pocketcasts_repositories_playback_NativeVocalBoostEngine_nativeRelease(
    JNIEnv*, jobject, jlong handle) {
    delete getEngine(handle);
}
