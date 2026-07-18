package au.com.shiftyjelly.pocketcasts.repositories.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import au.com.shiftyjelly.pocketcasts.models.type.TrimMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import timber.log.Timber

@OptIn(UnstableApi::class)
class ShiftyAudioProcessorChain(
    private val customAudio: ShiftyCustomAudio,
    private val useNativeVocalBoost: Boolean,
    useSmoothTrimSilence: Boolean,
) : AudioProcessorChain {
    private val legacyLowProcessor = ShiftyTrimSilenceProcessor(
        416000.microseconds,
        291000.microseconds,
        ShiftyTrimSilenceProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
        ::onSkippedFrames,
    )
    private val legacyMediumProcessor =
        ShiftyTrimSilenceProcessor(
            300000.microseconds,
            225000.microseconds,
            ShiftyTrimSilenceProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
            ::onSkippedFrames,
        )
    private val legacyHighProcessor = ShiftyTrimSilenceProcessor(
        83000.microseconds,
        0.microseconds,
        ShiftyTrimSilenceProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
        ::onSkippedFrames,
    )
    private val smoothLowProcessor = SmoothTrimSilenceProcessor(
        minimumQuietDuration = 416000.microseconds,
        preservedQuietDuration = 120000.microseconds,
        quietSpeed = 2,
        silenceThresholdLevel = ShiftyTrimSilenceProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
        onSkippedListener = ::onSkippedFrames,
    )
    private val smoothMediumProcessor = SmoothTrimSilenceProcessor(
        minimumQuietDuration = 300000.microseconds,
        preservedQuietDuration = 90000.microseconds,
        quietSpeed = 3,
        silenceThresholdLevel = ShiftyTrimSilenceProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
        onSkippedListener = ::onSkippedFrames,
    )
    private val smoothHighProcessor = SmoothTrimSilenceProcessor(
        minimumQuietDuration = 83000.microseconds,
        preservedQuietDuration = 40000.microseconds,
        quietSpeed = 5,
        silenceThresholdLevel = ShiftyTrimSilenceProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
        onSkippedListener = ::onSkippedFrames,
    )
    private val sonicAudioProcessor = SonicAudioProcessor()
    private val vocalBoostProcessor = VocalBoostAudioProcessor(
        isEngineAvailable = { useNativeVocalBoost && NativeVocalBoostEngine.isLibraryLoaded },
    )

    private val trimProcessors: List<TrimSilenceAudioProcessor> = if (useSmoothTrimSilence) {
        listOf(smoothLowProcessor, smoothMediumProcessor, smoothHighProcessor)
    } else {
        listOf(legacyLowProcessor, legacyMediumProcessor, legacyHighProcessor)
    }
    private val audioProcessors = arrayOf(
        trimProcessors[0],
        trimProcessors[1],
        trimProcessors[2],
        sonicAudioProcessor,
        vocalBoostProcessor,
    )
    private var trimMode = TrimMode.OFF

    override fun getAudioProcessors(): Array<AudioProcessor> {
        return audioProcessors
    }

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        val speed = playbackParameters.speed
        val pitch = playbackParameters.pitch
        sonicAudioProcessor.setSpeed(speed)
        sonicAudioProcessor.setPitch(pitch)
        return PlaybackParameters(speed, pitch)
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        for (processor in trimProcessors) {
            processor.enabled = false
        }
        if (trimMode != TrimMode.OFF) {
            val index = trimMode.ordinal - 1
            trimProcessors[index].enabled = true
        }
        return trimMode != TrimMode.OFF
    }

    override fun getMediaDuration(playoutDuration: Long): Long {
        return sonicAudioProcessor.getMediaDuration(playoutDuration)
    }

    override fun getSkippedOutputFrameCount(): Long {
        return trimProcessors.sumOf { it.skippedFrames }
    }

    fun applyTrimModeForNextUpdate(trimMode: TrimMode) {
        this.trimMode = trimMode
    }

    fun setBoostVolume(boostVolume: Boolean) {
        vocalBoostProcessor.boostEnabled = boostVolume
    }

    fun playbackEffectsMetrics(basePlaybackSpeed: Double): PlaybackEffectsMetrics {
        val activeTrimProcessor = trimProcessors.firstOrNull { it.enabled }
        val trimInputFrames = activeTrimProcessor?.inputFrames ?: 0L
        val trimOutputFrames = activeTrimProcessor?.outputFrames ?: 0L

        val trimSpeedMultiplier = if (trimInputFrames > 0 && trimOutputFrames > 0) {
            trimInputFrames.toDouble() / trimOutputFrames.toDouble()
        } else {
            1.0
        }
        return PlaybackEffectsMetrics(
            effectivePlaybackSpeed = basePlaybackSpeed * trimSpeedMultiplier,
            trimSpeedMultiplier = trimSpeedMultiplier,
            vocalBoostGainDb = vocalBoostProcessor.currentGainDb().coerceAtLeast(0f),
        )
    }

    fun usesNativeVocalBoost(): Boolean {
        return useNativeVocalBoost && NativeVocalBoostEngine.isLibraryLoaded
    }

    private fun onSkippedFrames(duration: Duration) {
        if (duration == Duration.ZERO) return
        Timber.d("Skipped ${duration.inWholeMilliseconds}ms")
        customAudio.addSilenceSkippedTime(duration.inWholeMicroseconds)
    }
}
