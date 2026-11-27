package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AudioResamplerTest {
    @Test
    fun pcm16LeBytesToFloatConvertsZeroMaxAndMin() {
        val bytes = byteArrayOf(
            0x00, 0x00,                         // 0
            0xFF.toByte(), 0x7F,                // +32767
            0x00, 0x80.toByte(),                // -32768
        )

        val floats = pcm16LeBytesToFloat(bytes, bytes.size)

        assertEquals(3, floats.size)
        assertEquals(0f, floats[0], 1e-6f)
        assertEquals(32767f / 32768f, floats[1], 1e-6f)
        assertEquals(-1f, floats[2], 1e-6f)
    }

    @Test
    fun resampleLinearTo16kMonoReturnsInputAsIsFor16kMono() {
        val input = floatArrayOf(0.1f, -0.25f, 0.5f, -0.75f)

        val output = resampleLinearTo16kMono(input, sourceSampleRate = 16_000, channelCount = 1)

        assertSame(input, output)
    }

    @Test
    fun resampleLinearTo16kMonoDownmixesAndResamples48kStereo() {
        // 6 stereo frames at 48 kHz -> 2 mono frames at 16 kHz (sample at frame 0 and frame 3)
        val input = floatArrayOf(
            0.2f, 0.6f,   // frame 0 -> mono 0.4
            0.0f, 0.0f,   // frame 1
            0.0f, 0.0f,   // frame 2
            -0.5f, -0.1f, // frame 3 -> mono -0.3
            0.0f, 0.0f,   // frame 4
            0.0f, 0.0f,   // frame 5
        )

        val output = resampleLinearTo16kMono(input, sourceSampleRate = 48_000, channelCount = 2)

        assertEquals(2, output.size)
        assertEquals(0.4f, output[0], 1e-5f)
        assertEquals(-0.3f, output[1], 1e-5f)
    }

    @Test
    fun resampleLinearTo16kMonoResamples44100MonoToExpectedLength() {
        val input = FloatArray(44_100) { index -> (index % 100) / 100f }

        val output = resampleLinearTo16kMono(input, sourceSampleRate = 44_100, channelCount = 1)

        assertEquals(16_000, output.size)
    }
}
