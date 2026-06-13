package au.com.shiftyjelly.pocketcasts.repositories.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(UnstableApi::class)
@Suppress("DEPRECATION")
class SmoothTrimSilenceProcessorTest {
    @Test
    fun `processor passes through audio when disabled`() {
        val processor = processor()

        val outputFormat = processor.configure(audioFormat())

        assertEquals(AudioFormat.NOT_SET, outputFormat)
    }

    @Test
    fun `short quiet regions pass through unchanged`() {
        val processor = configuredProcessor()
        val input = shortArrayOf(1000, 1, 2, 1000)

        processor.queueInput(directBuffer(input))

        assertArrayEquals(input, processor.getOutput().toShortArray())
        assertEquals(0, processor.skippedFrames)
    }

    @Test
    fun `confirmed quiet regions are compressed`() {
        val processor = configuredProcessor()
        val input = shortArrayOf(1000, 1, 2, 3, 4, 5, 6, 1000)

        processor.queueInput(directBuffer(input))

        assertArrayEquals(shortArrayOf(1000, 1, 2, 3, 5, 6, 1000), processor.getOutput().toShortArray())
        assertEquals(1, processor.skippedFrames)
    }

    @Test
    fun `quiet tail is preserved before noise resumes`() {
        val processor = configuredProcessor()
        val input = shortArrayOf(1000, 1, 2, 3, 4, 5, 6, 7, 8, 1000)

        processor.queueInput(directBuffer(input))

        assertArrayEquals(shortArrayOf(1000, 1, 2, 3, 5, 7, 8, 1000), processor.getOutput().toShortArray())
        assertEquals(2, processor.skippedFrames)
    }

    @Test
    fun `listener receives skipped duration on flush`() {
        var skippedDuration = Duration.ZERO
        val processor = configuredProcessor {
            skippedDuration = it
        }

        processor.queueInput(directBuffer(shortArrayOf(1000, 1, 2, 3, 4, 5, 6, 1000)))
        processor.getOutput()
        processor.flush()

        assertEquals(1000.microseconds, skippedDuration)
    }

    private fun configuredProcessor(onSkipped: (Duration) -> Unit = {}): SmoothTrimSilenceProcessor {
        return processor(onSkipped).apply {
            enabled = true
            configure(audioFormat())
            flush()
        }
    }

    private fun processor(onSkipped: (Duration) -> Unit = {}): SmoothTrimSilenceProcessor {
        return SmoothTrimSilenceProcessor(
            minimumQuietDuration = 3_000.microseconds,
            preservedQuietDuration = 2_000.microseconds,
            quietSpeed = 2,
            silenceThresholdLevel = 10,
            onSkippedListener = onSkipped,
        )
    }

    private fun audioFormat() = AudioFormat(1000, 1, C.ENCODING_PCM_16BIT)

    private fun directBuffer(samples: ShortArray): ByteBuffer {
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
}
