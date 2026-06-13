#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <deque>
#include <limits>
#include <numeric>
#include <vector>

namespace {

constexpr float kTargetLufs = -14.0f;
constexpr float kMinGainDb = -12.0f;
constexpr float kMaxGainDb = 18.0f;
constexpr float kAbsoluteGateLufs = -70.0f;
constexpr float kLufsOffset = -0.691f;
constexpr float kLimiterCeiling = 0.89125094f; // -1 dBFS
constexpr float kMaxInt16 = 32767.0f;
constexpr float kMinInt16 = -32768.0f;
constexpr float kPi = 3.14159265358979323846f;
constexpr size_t kRecentBlockCount = 4; // Roughly 700 ms because 400 ms windows are updated every 100 ms.
constexpr int kPeakLookaheadDivisor = 100; // 10 ms.
constexpr int kLoudnessLookaheadDivisor = 4; // 250 ms.

float dbToLinear(float db) {
    return std::pow(10.0f, db / 20.0f);
}

float clampFloat(float value, float minValue, float maxValue) {
    return std::max(minValue, std::min(maxValue, value));
}

float meanSquareToLufs(double meanSquare) {
    if (meanSquare <= std::numeric_limits<double>::min()) {
        return -std::numeric_limits<float>::infinity();
    }
    return kLufsOffset + 10.0f * std::log10(static_cast<float>(meanSquare));
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

    void setHighShelf(float sampleRate, float frequency, float gainDb, float slope) {
        const float a = std::pow(10.0f, gainDb / 40.0f);
        const float omega = 2.0f * kPi * frequency / sampleRate;
        const float sinOmega = std::sin(omega);
        const float cosOmega = std::cos(omega);
        const float beta = std::sqrt(a) / slope;
        const float a0 = (a + 1.0f) - (a - 1.0f) * cosOmega + beta * sinOmega;

        b0 = a * ((a + 1.0f) + (a - 1.0f) * cosOmega + beta * sinOmega) / a0;
        b1 = -2.0f * a * ((a - 1.0f) + (a + 1.0f) * cosOmega) / a0;
        b2 = a * ((a + 1.0f) + (a - 1.0f) * cosOmega - beta * sinOmega) / a0;
        a1 = 2.0f * ((a - 1.0f) - (a + 1.0f) * cosOmega) / a0;
        a2 = ((a + 1.0f) - (a - 1.0f) * cosOmega - beta * sinOmega) / a0;
    }
};

struct ChannelWeighting {
    Biquad highPass;
    Biquad highShelf;

    void configure(float sampleRate) {
        highPass.setHighPass(sampleRate, 38.0f, 0.5f);
        highShelf.setHighShelf(sampleRate, 1681.974f, 4.0f, 1.0f);
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
        sampleRate = newSampleRate;
        channelCount = newChannelCount;
        peakLookaheadFrames = std::max(1, sampleRate / kPeakLookaheadDivisor);
        loudnessLookaheadFrames = std::max(peakLookaheadFrames, sampleRate / kLoudnessLookaheadDivisor);
        blockFrames = std::max(1, sampleRate / 10);
        weighting.assign(channelCount, ChannelWeighting());
        for (auto& channel : weighting) {
            channel.configure(static_cast<float>(sampleRate));
        }
        reset();
    }

    void reset() {
        delayedSamples.clear();
        peakDeque.clear();
        subBlockSums.clear();
        gatedBlockMeanSquares.clear();
        recentGatedBlockMeanSquares.clear();
        currentSubBlockSum = 0.0;
        currentSubBlockFrames = 0;
        currentGain = 1.0f;
        targetGain = 1.0f;
        limiterGain = 1.0f;
        integratedLufs = -std::numeric_limits<float>::infinity();
        recentLufs = -std::numeric_limits<float>::infinity();
        relativeGateLufs = -std::numeric_limits<float>::infinity();
        frameIndex = 0;
        lastSamples.assign(channelCount, 0.0f);
        for (auto& channel : weighting) {
            channel.reset();
        }
    }

    int process(const int16_t* input, int inputFrames, int16_t* output, int outputCapacityFrames) {
        if (sampleRate <= 0 || channelCount <= 0) {
            return 0;
        }

        int outputFrames = 0;
        for (int frame = 0; frame < inputFrames; ++frame) {
            float framePeak = 0.0f;
            for (int channel = 0; channel < channelCount; ++channel) {
                const int sampleIndex = frame * channelCount + channel;
                const float sample = static_cast<float>(input[sampleIndex]) / kMaxInt16;
                measureSample(sample, channel);

                const float interpolatedPeak = std::abs((sample + lastSamples[channel]) * 0.5f);
                framePeak = std::max(framePeak, std::max(std::abs(sample), interpolatedPeak));
                lastSamples[channel] = sample;
                delayedSamples.push_back(sample);
            }
            pushPeak(framePeak);
            ++frameIndex;

            while (queuedFrames() > loudnessLookaheadFrames && outputFrames < outputCapacityFrames) {
                writeDelayedFrame(output + outputFrames * channelCount);
                ++outputFrames;
            }
        }
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

private:
    int sampleRate = 0;
    int channelCount = 0;
    int peakLookaheadFrames = 1;
    int loudnessLookaheadFrames = 1;
    int blockFrames = 1;
    std::vector<ChannelWeighting> weighting;
    std::vector<float> lastSamples;
    std::deque<float> delayedSamples;
    std::deque<std::pair<int64_t, float>> peakDeque;
    std::deque<double> subBlockSums;
    std::vector<double> gatedBlockMeanSquares;
    std::deque<double> recentGatedBlockMeanSquares;
    double currentSubBlockSum = 0.0;
    int currentSubBlockFrames = 0;
    float currentGain = 1.0f;
    float targetGain = 1.0f;
    float limiterGain = 1.0f;
    float integratedLufs = -std::numeric_limits<float>::infinity();
    float recentLufs = -std::numeric_limits<float>::infinity();
    float relativeGateLufs = -std::numeric_limits<float>::infinity();
    int64_t frameIndex = 0;

    int queuedFrames() const {
        return static_cast<int>(delayedSamples.size() / static_cast<size_t>(channelCount));
    }

    void measureSample(float sample, int channel) {
        const float weighted = weighting[channel].process(sample);
        currentSubBlockSum += static_cast<double>(weighted) * static_cast<double>(weighted);
        if (channel == channelCount - 1) {
            ++currentSubBlockFrames;
            if (currentSubBlockFrames >= blockFrames) {
                finishSubBlock();
            }
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
                if (meanSquareToLufs(meanSquare) > kAbsoluteGateLufs) {
                    gatedBlockMeanSquares.push_back(meanSquare);
                    recentGatedBlockMeanSquares.push_back(meanSquare);
                    while (recentGatedBlockMeanSquares.size() > kRecentBlockCount) {
                        recentGatedBlockMeanSquares.pop_front();
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
        float gainDb = clampFloat(kTargetLufs - integratedLufs, kMinGainDb, kMaxGainDb);

        if (recentGatedBlockMeanSquares.size() >= 4) {
            const double recentSum = std::accumulate(
                recentGatedBlockMeanSquares.begin(),
                recentGatedBlockMeanSquares.end(),
                0.0);
            recentLufs = meanSquareToLufs(recentSum / static_cast<double>(recentGatedBlockMeanSquares.size()));

            if (recentLufs < integratedLufs - 3.0f && recentLufs > kAbsoluteGateLufs + 10.0f) {
                const float recentGainDb = clampFloat(kTargetLufs - recentLufs, kMinGainDb, kMaxGainDb);
                gainDb = std::max(gainDb, recentGainDb);
            }
        }

        targetGain = dbToLinear(gainDb);
    }

    void updateGain() {
        const float coefficient = targetGain < currentGain ? 0.0025f : 0.00008f;
        currentGain += (targetGain - currentGain) * coefficient;
    }

    void pushPeak(float peak) {
        while (!peakDeque.empty() && peakDeque.back().second <= peak) {
            peakDeque.pop_back();
        }
        peakDeque.emplace_back(frameIndex, peak);
    }

    void writeDelayedFrame(int16_t* output) {
        updateGain();

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
        const float boostedPeak = lookaheadPeak * currentGain;
        const float wantedLimiterGain = boostedPeak > kLimiterCeiling ? kLimiterCeiling / boostedPeak : 1.0f;
        const float limiterCoefficient = wantedLimiterGain < limiterGain ? 0.2f : 0.0008f;
        limiterGain += (wantedLimiterGain - limiterGain) * limiterCoefficient;

        for (int channel = 0; channel < channelCount; ++channel) {
            const float sample = delayedSamples.front() * currentGain * limiterGain;
            delayedSamples.pop_front();
            const float clipped = clampFloat(sample * kMaxInt16, kMinInt16, kMaxInt16);
            output[channel] = static_cast<int16_t>(std::lrintf(clipped));
        }
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
