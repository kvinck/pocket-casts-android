package au.com.shiftyjelly.pocketcasts.repositories.playback

data class PlaybackEffectsMetrics(
    val effectivePlaybackSpeed: Double = 1.0,
    val trimSpeedMultiplier: Double = 1.0,
    val vocalBoostGainDb: Float = 0f,
) {
    val isTrimActive: Boolean
        get() = trimSpeedMultiplier > 1.01

    val isVocalBoostActive: Boolean
        get() = vocalBoostGainDb > 0.1f
}
