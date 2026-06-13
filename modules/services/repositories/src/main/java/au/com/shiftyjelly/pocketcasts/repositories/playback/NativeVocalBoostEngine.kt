package au.com.shiftyjelly.pocketcasts.repositories.playback

import java.nio.ByteBuffer
import timber.log.Timber

interface VocalBoostEngine {
    val isValid: Boolean

    fun configure(sampleRate: Int, channelCount: Int)
    fun process(
        inputBuffer: ByteBuffer,
        inputFrameCount: Int,
        outputBuffer: ByteBuffer,
        outputFrameCapacity: Int,
    ): Int
    fun drain(outputBuffer: ByteBuffer, outputFrameCapacity: Int): Int
    fun currentGainDb(): Float
    fun flush()
    fun release()
}

class NativeVocalBoostEngine : VocalBoostEngine {
    private var handle = 0L

    override val isValid: Boolean
        get() = handle != 0L

    init {
        if (isLibraryLoaded) {
            handle = nativeCreate()
        }
    }

    override fun configure(sampleRate: Int, channelCount: Int) {
        if (handle == 0L) return
        nativeConfigure(handle, sampleRate, channelCount)
    }

    override fun process(
        inputBuffer: ByteBuffer,
        inputFrameCount: Int,
        outputBuffer: ByteBuffer,
        outputFrameCapacity: Int,
    ): Int {
        if (handle == 0L) return ERROR
        return nativeProcess(handle, inputBuffer, inputFrameCount, outputBuffer, outputFrameCapacity)
    }

    override fun drain(outputBuffer: ByteBuffer, outputFrameCapacity: Int): Int {
        if (handle == 0L) return ERROR
        return nativeDrain(handle, outputBuffer, outputFrameCapacity)
    }

    override fun currentGainDb(): Float {
        if (handle == 0L) return 0f
        return nativeCurrentGainDb(handle)
    }

    override fun flush() {
        if (handle != 0L) {
            nativeFlush(handle)
        }
    }

    override fun release() {
        val handleToRelease = handle
        handle = 0L
        if (handleToRelease != 0L) {
            nativeRelease(handleToRelease)
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeConfigure(handle: Long, sampleRate: Int, channelCount: Int)
    private external fun nativeProcess(
        handle: Long,
        inputBuffer: ByteBuffer,
        inputFrameCount: Int,
        outputBuffer: ByteBuffer,
        outputFrameCapacity: Int,
    ): Int
    private external fun nativeDrain(handle: Long, outputBuffer: ByteBuffer, outputFrameCapacity: Int): Int
    private external fun nativeCurrentGainDb(handle: Long): Float
    private external fun nativeFlush(handle: Long)
    private external fun nativeRelease(handle: Long)

    companion object {
        const val ERROR = -1

        val isLibraryLoaded: Boolean by lazy {
            try {
                System.loadLibrary("vocalboost")
                true
            } catch (e: UnsatisfiedLinkError) {
                Timber.e(e, "Failed to load vocal boost engine")
                false
            }
        }
    }
}
