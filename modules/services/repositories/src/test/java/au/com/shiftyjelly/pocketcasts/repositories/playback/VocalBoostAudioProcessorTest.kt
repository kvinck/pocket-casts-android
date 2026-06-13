package au.com.shiftyjelly.pocketcasts.repositories.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(UnstableApi::class)
@Suppress("DEPRECATION")
class VocalBoostAudioProcessorTest {
    @Test
    fun `processor is inactive when native engine is unavailable`() {
        val processor = VocalBoostAudioProcessor(isEngineAvailable = { false })

        val outputFormat = processor.configure(audioFormat())

        assertEquals(AudioFormat.NOT_SET, outputFormat)
        assertFalse(processor.isActive())
    }

    @Test
    fun `processor passes audio through when boost is disabled`() {
        val engine = FakeVocalBoostEngine()
        val processor = VocalBoostAudioProcessor(
            isEngineAvailable = { true },
            engineFactory = { engine },
        )
        val input = directBuffer(1, 2, 3, 4)

        processor.configure(audioFormat())
        processor.flush()
        processor.boostEnabled = false
        processor.queueInput(input)

        assertArrayEquals(shortArrayOf(1, 2, 3, 4), processor.getOutput().toShortArray())
        assertEquals(0, engine.processCalls)
    }

    @Test
    fun `processor can pass through multiple buffers without self-copying`() {
        val engine = FakeVocalBoostEngine()
        val processor = VocalBoostAudioProcessor(
            isEngineAvailable = { true },
            engineFactory = { engine },
        )

        processor.configure(audioFormat())
        processor.flush()
        processor.boostEnabled = false
        processor.queueInput(directBuffer(1, 2))
        assertArrayEquals(shortArrayOf(1, 2), processor.getOutput().toShortArray())

        processor.queueInput(directBuffer(3, 4))
        assertArrayEquals(shortArrayOf(3, 4), processor.getOutput().toShortArray())
    }

    @Test
    fun `processor uses native engine when boost is enabled`() {
        val engine = FakeVocalBoostEngine()
        val processor = VocalBoostAudioProcessor(
            isEngineAvailable = { true },
            engineFactory = { engine },
        )
        val input = directBuffer(1, 2, 3, 4)

        processor.configure(audioFormat())
        processor.flush()
        processor.boostEnabled = true
        processor.queueInput(input)

        assertArrayEquals(shortArrayOf(2, 4, 6, 8), processor.getOutput().toShortArray())
        assertEquals(1, engine.processCalls)
        assertEquals(44100, engine.sampleRate)
        assertEquals(2, engine.channelCount)
    }

    @Test
    fun `processor drains delayed frames at end of stream`() {
        val engine = FakeVocalBoostEngine(drainSamples = shortArrayOf(10, 20))
        val processor = VocalBoostAudioProcessor(
            isEngineAvailable = { true },
            engineFactory = { engine },
        )

        processor.configure(audioFormat())
        processor.flush()
        processor.boostEnabled = true
        processor.queueEndOfStream()

        assertArrayEquals(shortArrayOf(10, 20), processor.getOutput().toShortArray())
        assertEquals(1, engine.drainCalls)
    }

    private fun audioFormat() = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)

    private fun directBuffer(vararg samples: Short): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
        samples.forEach(buffer::putShort)
        buffer.flip()
        return buffer
    }

    private fun ByteBuffer.toShortArray(): ShortArray {
        val buffer = slice().order(ByteOrder.nativeOrder())
        return ShortArray(buffer.remaining() / Short.SIZE_BYTES) {
            buffer.short
        }
    }

    private class FakeVocalBoostEngine(
        private val drainSamples: ShortArray = shortArrayOf(),
    ) : VocalBoostEngine {
        override val isValid = true
        var sampleRate = 0
            private set
        var channelCount = 0
            private set
        var processCalls = 0
            private set
        var drainCalls = 0
            private set

        override fun configure(sampleRate: Int, channelCount: Int) {
            this.sampleRate = sampleRate
            this.channelCount = channelCount
        }

        override fun process(
            inputBuffer: ByteBuffer,
            inputFrameCount: Int,
            outputBuffer: ByteBuffer,
            outputFrameCapacity: Int,
        ): Int {
            processCalls++
            val samplesToWrite = inputFrameCount * channelCount
            repeat(samplesToWrite) { index ->
                val sample = inputBuffer.getShort(index * Short.SIZE_BYTES)
                outputBuffer.putShort(index * Short.SIZE_BYTES, (sample * 2).toShort())
            }
            return inputFrameCount.coerceAtMost(outputFrameCapacity)
        }

        override fun drain(outputBuffer: ByteBuffer, outputFrameCapacity: Int): Int {
            drainCalls++
            drainSamples.forEachIndexed { index, sample ->
                outputBuffer.putShort(index * Short.SIZE_BYTES, sample)
            }
            return (drainSamples.size / channelCount).coerceAtMost(outputFrameCapacity)
        }

        override fun flush() = Unit

        override fun release() = Unit
    }
}
