package com.example.pocketagent

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PcmWavReaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readsMonoPcm16SamplesAtTargetRate() {
        val file = wavFile(sampleRate = 16_000, channels = 1, samples = shortArrayOf(0, 16_384, -32_768))

        val samples = PcmWavReader.read(file)

        assertEquals(3, samples.size)
        assertEquals(0f, samples[0], 0.0001f)
        assertEquals(0.5f, samples[1], 0.0001f)
        assertEquals(-1f, samples[2], 0.0001f)
    }

    @Test
    fun downmixesStereoAndResamplesToSixteenKilohertz() {
        val file = wavFile(sampleRate = 8_000, channels = 2, samples = shortArrayOf(16_384, 16_384, 0, 0))

        val samples = PcmWavReader.read(file)

        assertEquals(4, samples.size)
        assertEquals(0.5f, samples[0], 0.0001f)
        assertEquals(0.25f, samples[1], 0.0001f)
    }

    @Test
    fun rejectsNonWaveInput() {
        val file = temporaryFolder.newFile("not-a-wave.wav").apply { writeBytes("not wav data".toByteArray()) }

        assertThrows(IllegalArgumentException::class.java) { PcmWavReader.read(file) }
    }

    @Test
    fun rejectsCompressedAndNonPcmWav() {
        val file = wavFile(sampleRate = 16_000, channels = 1, samples = shortArrayOf(0), formatCode = 3)

        assertThrows(IllegalArgumentException::class.java) { PcmWavReader.read(file) }
    }

    @Test
    fun rejectsDurationAboveLimit() {
        val file = wavFile(
            sampleRate = 16_000,
            channels = 1,
            samples = ShortArray(PcmWavReader.MAX_DURATION_SECONDS * 16_000 + 1),
        )

        assertThrows(IllegalArgumentException::class.java) { PcmWavReader.read(file) }
    }

    @Test
    fun rejectsTruncatedChunks() {
        val file = temporaryFolder.newFile("truncated.wav").apply {
            writeBytes(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(), 20, 0, 0, 0))
        }

        assertThrows(IOException::class.java) { PcmWavReader.read(file) }
    }

    private fun wavFile(
        sampleRate: Int,
        channels: Int,
        samples: ShortArray,
        formatCode: Int = 1,
    ): File {
        val pcm = ByteArrayOutputStream().apply {
            samples.forEach { value ->
                write(value.toInt() and 0xff)
                write((value.toInt() ushr 8) and 0xff)
            }
        }.toByteArray()
        val bytes = ByteArrayOutputStream().apply {
            writeAscii("RIFF")
            writeUInt32(4 + 8 + 16 + 8 + pcm.size)
            writeAscii("WAVE")
            writeAscii("fmt ")
            writeUInt32(16)
            writeUInt16(formatCode)
            writeUInt16(channels)
            writeUInt32(sampleRate)
            writeUInt32(sampleRate * channels * 2)
            writeUInt16(channels * 2)
            writeUInt16(16)
            writeAscii("data")
            writeUInt32(pcm.size)
            write(pcm)
        }.toByteArray()
        return temporaryFolder.newFile("sample.wav").apply { writeBytes(bytes) }
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))
    private fun ByteArrayOutputStream.writeUInt16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }
    private fun ByteArrayOutputStream.writeUInt32(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}
