package au.com.shiftyjelly.pocketcasts.repositories.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import timber.log.Timber

@OptIn(UnstableApi::class)
class VocalBoostAudioProcessor(
    private val isEngineAvailable: () -> Boolean = { NativeVocalBoostEngine.isLibraryLoaded },
    private val engineFactory: () -> VocalBoostEngine = { NativeVocalBoostEngine() },
) : BaseAudioProcessor() {
    var boostEnabled = false
    private var processorEnabled = false
    private var processorFailed = false
    private var bytesPerFrame = 0
    private var lookaheadBytes = 0
    private var engine: VocalBoostEngine? = null

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        bytesPerFrame = inputAudioFormat.bytesPerFrame
        lookaheadBytes = bytesPerFrame * (inputAudioFormat.sampleRate / 4).coerceAtLeast(1)
        processorEnabled = isEngineAvailable()
        return if (processorEnabled) inputAudioFormat else AudioFormat.NOT_SET
    }

    override fun isActive(): Boolean = processorEnabled

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!boostEnabled || processorFailed) {
            outputPassthrough(inputBuffer)
            return
        }

        val inputBytes = inputBuffer.remaining()
        val inputFrameCount = inputBytes / bytesPerFrame
        val outputBuffer = replaceOutputBuffer(inputBytes).order(ByteOrder.nativeOrder())
        val outputFrameCapacity = outputBuffer.capacity() / bytesPerFrame
        val inputSlice = inputBuffer.slice().order(ByteOrder.nativeOrder())
        val outputFrameCount = engine?.process(
            inputSlice,
            inputFrameCount,
            outputBuffer,
            outputFrameCapacity,
        ) ?: NativeVocalBoostEngine.ERROR
        inputBuffer.position(inputBuffer.limit())

        if (outputFrameCount < 0) {
            Timber.e("Native vocal boost processing failed")
            processorFailed = true
            outputBuffer.clear()
            outputBuffer.limit(0)
            return
        }

        outputBuffer.position(outputFrameCount * bytesPerFrame)
        outputBuffer.flip()
    }

    override fun onQueueEndOfStream() {
        if (!boostEnabled || processorFailed) return

        val outputBuffer = replaceOutputBuffer(lookaheadBytes).order(ByteOrder.nativeOrder())
        val outputFrameCapacity = outputBuffer.capacity() / bytesPerFrame
        val outputFrameCount = engine?.drain(outputBuffer, outputFrameCapacity) ?: NativeVocalBoostEngine.ERROR
        if (outputFrameCount < 0) {
            Timber.e("Native vocal boost drain failed")
            processorFailed = true
            outputBuffer.clear()
            outputBuffer.limit(0)
            return
        }
        outputBuffer.position(outputFrameCount * bytesPerFrame)
        outputBuffer.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        super.onFlush(streamMetadata)
        processorFailed = false
        engine?.release()
        engine = null
        if (processorEnabled) {
            engine = engineFactory().takeIf { it.isValid }?.apply {
                configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
            }
            if (engine == null) {
                processorFailed = true
            }
        }
    }

    override fun onReset() {
        boostEnabled = false
        processorEnabled = false
        processorFailed = false
        engine?.release()
        engine = null
    }

    fun isProcessingBoost(): Boolean = processorEnabled && boostEnabled && !processorFailed

    fun currentGainDb(): Float {
        if (!isProcessingBoost()) return 0f
        return engine?.currentGainDb() ?: 0f
    }

    private fun outputPassthrough(inputBuffer: ByteBuffer) {
        val inputSlice = inputBuffer.slice()
        val outputBuffer = replaceOutputBuffer(inputSlice.remaining())
        outputBuffer.put(inputSlice)
        outputBuffer.flip()
        inputBuffer.position(inputBuffer.limit())
    }
}
