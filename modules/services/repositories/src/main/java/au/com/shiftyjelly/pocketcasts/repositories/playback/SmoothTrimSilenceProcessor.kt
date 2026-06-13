package au.com.shiftyjelly.pocketcasts.repositories.playback

import android.annotation.SuppressLint
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import au.com.shiftyjelly.pocketcasts.repositories.playback.SmoothTrimSilenceProcessor.NoiseState.MaybeQuiet
import au.com.shiftyjelly.pocketcasts.repositories.playback.SmoothTrimSilenceProcessor.NoiseState.Noisy
import au.com.shiftyjelly.pocketcasts.repositories.playback.SmoothTrimSilenceProcessor.NoiseState.Quiet
import java.nio.ByteBuffer
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * A trim-silence processor that compresses quiet regions instead of deleting them outright.
 *
 * Quiet regions are first buffered for [minimumQuietDuration]. Once confirmed, the start and end of
 * the quiet region are preserved at normal speed while the middle is frame-decimated by
 * [quietSpeed]. This intentionally keeps the implementation cheap enough for the realtime audio
 * path. Because it only compresses very quiet audio, pitch correction is not currently applied.
 */
@OptIn(UnstableApi::class)
class SmoothTrimSilenceProcessor(
    private val minimumQuietDuration: Duration,
    private val preservedQuietDuration: Duration,
    private val quietSpeed: Int,
    private val silenceThresholdLevel: Short,
    private var onSkippedListener: SkippedListener,
) : BaseAudioProcessor(),
    TrimSilenceAudioProcessor {
    private var bytesPerFrame = 0
    private var maybeQuietBuffer = Util.EMPTY_BYTE_ARRAY
    private var maybeQuietBufferSize = 0
    private var preservedQuietBytes = 0
    private var quietTailBuffer = Util.EMPTY_BYTE_ARRAY
    private var quietTailBufferSize = 0
    private var quietFrameIndex = 0
    private var state = Noisy

    override var enabled = false

    override var skippedFrames = 0L
        private set

    @Volatile override var inputFrames = 0L
        private set

    @Volatile override var outputFrames = 0L
        private set

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return if (enabled) inputAudioFormat else AudioFormat.NOT_SET
    }

    @SuppressLint("MissingSuperCall")
    override fun isActive(): Boolean = enabled

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining() || hasPendingOutput()) return

        val startPosition = inputBuffer.position()
        val output = OutputAccumulator(inputBuffer.remaining() + maybeQuietBufferSize + quietTailBufferSize)
        while (inputBuffer.hasRemaining()) {
            when (state) {
                Noisy -> processNoisy(inputBuffer, output)
                MaybeQuiet -> processMaybeQuiet(inputBuffer, output)
                Quiet -> processQuiet(inputBuffer, output)
            }
        }
        inputFrames += (inputBuffer.position() - startPosition) / bytesPerFrame
        output(output)
    }

    override fun onQueueEndOfStream() {
        val output = OutputAccumulator(maybeQuietBufferSize + quietTailBufferSize)
        when (state) {
            Noisy -> Unit

            MaybeQuiet -> {
                output.write(maybeQuietBuffer, 0, maybeQuietBufferSize)
                maybeQuietBufferSize = 0
            }

            Quiet -> flushQuietTail(output)
        }
        output(output)
        state = Noisy
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        super.onFlush(streamMetadata)
        if (enabled) {
            onSkippedListener.onSkippedFrames(skippedFrames.frameCountToDuration())

            bytesPerFrame = inputAudioFormat.bytesPerFrame
            val maybeQuietBufferSize = minimumQuietDuration.toFrameCount() * bytesPerFrame
            if (maybeQuietBuffer.size != maybeQuietBufferSize) {
                maybeQuietBuffer = ByteArray(maybeQuietBufferSize)
            }
            preservedQuietBytes = preservedQuietDuration.toFrameCount() * bytesPerFrame
            if (quietTailBuffer.size != preservedQuietBytes) {
                quietTailBuffer = ByteArray(preservedQuietBytes)
            }
        }
        state = Noisy
        skippedFrames = 0
        inputFrames = 0
        outputFrames = 0
        maybeQuietBufferSize = 0
        quietTailBufferSize = 0
        quietFrameIndex = 0
    }

    override fun onReset() {
        enabled = false
        maybeQuietBuffer = Util.EMPTY_BYTE_ARRAY
        maybeQuietBufferSize = 0
        preservedQuietBytes = 0
        quietTailBuffer = Util.EMPTY_BYTE_ARRAY
        quietTailBufferSize = 0
        quietFrameIndex = 0
        inputFrames = 0
        outputFrames = 0
        state = Noisy
    }

    private fun processNoisy(inputBuffer: ByteBuffer, output: OutputAccumulator) {
        if (isQuietFrame(inputBuffer, inputBuffer.position())) {
            state = MaybeQuiet
            processMaybeQuiet(inputBuffer, output)
        } else {
            output.writeFrame(inputBuffer, inputBuffer.position())
            inputBuffer.position(inputBuffer.position() + bytesPerFrame)
        }
    }

    private fun processMaybeQuiet(inputBuffer: ByteBuffer, output: OutputAccumulator) {
        if (!isQuietFrame(inputBuffer, inputBuffer.position())) {
            output.write(maybeQuietBuffer, 0, maybeQuietBufferSize)
            maybeQuietBufferSize = 0
            state = Noisy
            processNoisy(inputBuffer, output)
            return
        }

        inputBuffer.copyFrameTo(maybeQuietBuffer, maybeQuietBufferSize)
        maybeQuietBufferSize += bytesPerFrame
        inputBuffer.position(inputBuffer.position() + bytesPerFrame)

        if (maybeQuietBufferSize == maybeQuietBuffer.size) {
            val preservedStartBytes = min(preservedQuietBytes, maybeQuietBufferSize)
            output.write(maybeQuietBuffer, 0, preservedStartBytes)
            quietTailBufferSize = 0
            quietFrameIndex = 0
            var offset = preservedStartBytes
            while (offset < maybeQuietBufferSize) {
                processQuietFrame(maybeQuietBuffer, offset, output)
                offset += bytesPerFrame
            }
            maybeQuietBufferSize = 0
            state = Quiet
        }
    }

    private fun processQuiet(inputBuffer: ByteBuffer, output: OutputAccumulator) {
        if (!isQuietFrame(inputBuffer, inputBuffer.position())) {
            flushQuietTail(output)
            state = Noisy
            processNoisy(inputBuffer, output)
            return
        }

        processQuietFrame(inputBuffer, inputBuffer.position(), output)
        inputBuffer.position(inputBuffer.position() + bytesPerFrame)
    }

    private fun processQuietFrame(source: ByteBuffer, offset: Int, output: OutputAccumulator) {
        if (quietTailBuffer.size == 0) {
            outputCompressedQuietFrame(source, offset, output)
            return
        }

        if (quietTailBufferSize == quietTailBuffer.size) {
            outputCompressedQuietFrame(quietTailBuffer, 0, output)
            quietTailBuffer.copyInto(
                destination = quietTailBuffer,
                destinationOffset = 0,
                startIndex = bytesPerFrame,
                endIndex = quietTailBufferSize,
            )
            quietTailBufferSize -= bytesPerFrame
        }
        source.copyFrameTo(quietTailBuffer, quietTailBufferSize, offset)
        quietTailBufferSize += bytesPerFrame
    }

    private fun processQuietFrame(source: ByteArray, offset: Int, output: OutputAccumulator) {
        if (quietTailBuffer.size == 0) {
            outputCompressedQuietFrame(source, offset, output)
            return
        }

        if (quietTailBufferSize == quietTailBuffer.size) {
            outputCompressedQuietFrame(quietTailBuffer, 0, output)
            quietTailBuffer.copyInto(
                destination = quietTailBuffer,
                destinationOffset = 0,
                startIndex = bytesPerFrame,
                endIndex = quietTailBufferSize,
            )
            quietTailBufferSize -= bytesPerFrame
        }
        source.copyInto(
            destination = quietTailBuffer,
            destinationOffset = quietTailBufferSize,
            startIndex = offset,
            endIndex = offset + bytesPerFrame,
        )
        quietTailBufferSize += bytesPerFrame
    }

    private fun outputCompressedQuietFrame(source: ByteBuffer, offset: Int, output: OutputAccumulator) {
        if (quietFrameIndex % quietSpeed == 0) {
            output.writeFrame(source, offset)
        } else {
            skippedFrames++
        }
        quietFrameIndex++
    }

    private fun outputCompressedQuietFrame(source: ByteArray, offset: Int, output: OutputAccumulator) {
        if (quietFrameIndex % quietSpeed == 0) {
            output.write(source, offset, bytesPerFrame)
        } else {
            skippedFrames++
        }
        quietFrameIndex++
    }

    private fun flushQuietTail(output: OutputAccumulator) {
        output.write(quietTailBuffer, 0, quietTailBufferSize)
        quietTailBufferSize = 0
        quietFrameIndex = 0
    }

    private fun output(output: OutputAccumulator) {
        val size = output.size
        if (size == 0) return
        replaceOutputBuffer(size).put(output.data, 0, size).flip()
        outputFrames += size / bytesPerFrame
    }

    private fun isQuietFrame(buffer: ByteBuffer, offset: Int): Boolean {
        var maxLevel = 0
        for (i in offset until offset + bytesPerFrame step Short.SIZE_BYTES) {
            maxLevel = max(maxLevel, buffer.getShort(i).toInt().absoluteValue)
        }
        return maxLevel <= silenceThresholdLevel
    }

    private fun ByteBuffer.copyFrameTo(destination: ByteArray, destinationOffset: Int, sourceOffset: Int = position()) {
        for (i in 0 until bytesPerFrame) {
            destination[destinationOffset + i] = get(sourceOffset + i)
        }
    }

    private fun OutputAccumulator.writeFrame(buffer: ByteBuffer, offset: Int) {
        for (i in 0 until bytesPerFrame) {
            write(buffer.get(offset + i))
        }
    }

    private fun Duration.toFrameCount() = (inWholeMicroseconds * inputAudioFormat.sampleRate / C.MICROS_PER_SECOND)
        .toInt()

    private fun Long.frameCountToDuration() = (this * C.MICROS_PER_SECOND / inputAudioFormat.sampleRate).microseconds

    fun interface SkippedListener {
        fun onSkippedFrames(duration: Duration)
    }

    private enum class NoiseState {
        Noisy,
        MaybeQuiet,
        Quiet,
    }

    private class OutputAccumulator(capacity: Int) {
        val data = ByteArray(capacity)
        var size = 0
            private set

        fun write(source: ByteArray, offset: Int, length: Int) {
            source.copyInto(
                destination = data,
                destinationOffset = size,
                startIndex = offset,
                endIndex = offset + length,
            )
            size += length
        }

        fun write(value: Byte) {
            data[size] = value
            size++
        }
    }
}
