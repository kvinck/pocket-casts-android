package au.com.shiftyjelly.pocketcasts.repositories.playback

import androidx.media3.common.audio.AudioProcessor

interface TrimSilenceAudioProcessor : AudioProcessor {
    var enabled: Boolean
    val skippedFrames: Long
    val inputFrames: Long
    val outputFrames: Long
}
