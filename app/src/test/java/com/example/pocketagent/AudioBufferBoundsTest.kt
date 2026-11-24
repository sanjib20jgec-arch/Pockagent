package com.example.pocketagent

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioBufferBoundsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun respectsSixtySecondDurationCap() {
        val buffer = AudioRecordingBuffer(maxDurationSeconds = 60)
        assertEquals(960_000, buffer.maxSamples)

        val chunkSize = 32_000
        val chunk = ShortArray(chunkSize) { 100 }

        // Append 30 chunks = 960,000 samples (exactly 60 seconds)
        for (i in 0 until 30) {
            val added = buffer.append(chunk, 0, chunk.size)
            assertEquals(chunkSize, added)
        }

        assertEquals(960_000, buffer.sampleCount)
        assertEquals(60.0f, buffer.recordedDurationSeconds, 0.001f)
        assertTrue(buffer.isFull)

        // Attempting to append more must be rejected / capped at 0
        val overflow = buffer.append(chunk, 0, chunk.size)
        assertEquals(0, overflow)
        assertEquals(960_000, buffer.sampleCount)
    }

    @Test
    fun respectsByteCeiling() {
        // Limit to 200 bytes = 100 16-bit samples
        val buffer = AudioRecordingBuffer(maxDurationSeconds = 60, maxBytes = 200)
        assertEquals(100, buffer.maxSamples)

        val data = ShortArray(150) { 1 }
        val added = buffer.append(data, 0, data.size)

        assertEquals(100, added)
        assertEquals(100, buffer.sampleCount)
        assertEquals(200L, buffer.recordedBytes)
        assertTrue(buffer.isFull)
    }

    @Test
    fun convertsPcmShortsToNormalizedFloatsAccurately() {
        val buffer = AudioRecordingBuffer(maxDurationSeconds = 1)
        val samples = shortArrayOf(0, 32767, -32768, 16384, -16384)
        buffer.append(samples, 0, samples.size)

        val floats = buffer.toNormalizedFloats()
        assertEquals(5, floats.size)
        assertEquals(0.0f, floats[0], 0.0001f)
        assertEquals(32767f / 32768f, floats[1], 0.0001f)
        assertEquals(-1.0f, floats[2], 0.0001f)
        assertEquals(0.5f, floats[3], 0.0001f)
        assertEquals(-0.5f, floats[4], 0.0001f)
    }

    @Test
    fun appendsLittleEndianBytePairsCorrectly() {
        val buffer = AudioRecordingBuffer(maxDurationSeconds = 1)
        // Little-endian byte pairs: 0, 32767, -32768
        val bytes = byteArrayOf(
            0x00, 0x00,
            0xFF.toByte(), 0x7F.toByte(),
            0x00.toByte(), 0x80.toByte()
        )
        val acceptedBytes = buffer.append(bytes, 0, bytes.size)
        assertEquals(6, acceptedBytes)
        assertEquals(3, buffer.sampleCount)

        val floats = buffer.toNormalizedFloats()
        assertEquals(0.0f, floats[0], 0.0001f)
        assertEquals(32767f / 32768f, floats[1], 0.0001f)
        assertEquals(-1.0f, floats[2], 0.0001f)
    }

    @Test
    fun calculatesRmsAndDetectsSilenceCutoff() {
        // 1 second silence cutoff
        val buffer = AudioRecordingBuffer(
            maxDurationSeconds = 10,
            silenceThresholdRms = 0.012f,
            silenceCutoffSeconds = 1.0f
        )

        // Initial silence: speech has not started
        val silence = ShortArray(16_000) { 0 }
        buffer.append(silence, 0, silence.size)
        assertFalse(buffer.speechStarted)
        assertFalse(buffer.isSilenceCutoffReached)

        // Speech occurs (amplitude > 0.025f RMS)
        val speech = ShortArray(8_000) { 4000 }
        buffer.append(speech, 0, speech.size)
        assertTrue(buffer.speechStarted)
        assertFalse(buffer.isSilenceCutoffReached)

        // 0.5s of silence after speech (8,000 samples)
        val halfSecondSilence = ShortArray(8_000) { 0 }
        buffer.append(halfSecondSilence, 0, halfSecondSilence.size)
        assertFalse(buffer.isSilenceCutoffReached)

        // Another 0.5s of silence -> 1.0s total silence reached!
        buffer.append(halfSecondSilence, 0, halfSecondSilence.size)
        assertTrue(buffer.isSilenceCutoffReached)
    }

    @Test
    fun securelyWipesAudioBuffers() {
        val buffer = AudioRecordingBuffer(maxDurationSeconds = 1)
        val samples = ShortArray(100) { 1234 }
        buffer.append(samples, 0, samples.size)

        assertEquals(100, buffer.sampleCount)
        val floatsBeforeWipe = buffer.toNormalizedFloats()
        assertTrue(floatsBeforeWipe.any { it != 0f })

        // Wipe internal buffer
        buffer.wipe()
        assertEquals(0, buffer.sampleCount)
        assertFalse(buffer.speechStarted)
        assertEquals(0L, buffer.continuousSilentSamples)

        val floatsAfterWipe = buffer.toNormalizedFloats()
        assertEquals(0, floatsAfterWipe.size)

        // Wipe sample array
        AudioRecordingBuffer.wipe(floatsBeforeWipe)
        assertTrue(floatsBeforeWipe.all { it == 0f })

        // Wipe byte array
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        AudioRecordingBuffer.wipe(bytes)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun generatesValidRiffWavFileAndRoundTripsThroughPcmWavReader() {
        val buffer = AudioRecordingBuffer(maxDurationSeconds = 5)
        // 1 second of 16kHz audio: 16,000 samples
        val samples = ShortArray(16_000) { index -> (index % 1000).toShort() }
        buffer.append(samples, 0, samples.size)

        val tempWav = File(temporaryFolder.root, "test_output.wav")
        buffer.writeWavFile(tempWav)

        assertTrue(tempWav.exists())
        assertEquals(44L + 32_000L, tempWav.length())

        // Validate via PcmWavReader parser
        val parsedSamples = PcmWavReader.read(tempWav)
        assertEquals(16_000, parsedSamples.size)

        val expectedFloats = buffer.toNormalizedFloats()
        for (i in 0 until 16_000) {
            assertEquals(expectedFloats[i], parsedSamples[i], 0.0001f)
        }

        // Secure delete
        AudioRecordingBuffer.secureDelete(tempWav)
        assertFalse(tempWav.exists())
    }

    @Test
    fun inAppAudioRecorderCapturesAndCompletesUsingSyntheticSource() {
        val buffer = AudioRecordingBuffer(maxDurationSeconds = 2)
        val dataToProduce = ShortArray(1024) { 500 }
        val readCompleted = AtomicBoolean(false)

        val syntheticSource = object : AudioInputSource {
            private var started = false
            override fun startRecording() { started = true }
            override fun read(buf: ShortArray, offset: Int, size: Int): Int {
                if (!started) return -1
                if (!readCompleted.getAndSet(true)) {
                    val count = minOf(size, dataToProduce.size)
                    System.arraycopy(dataToProduce, 0, buf, offset, count)
                    return count
                }
                // Sleep briefly to avoid busy spin before stop
                try { Thread.sleep(10) } catch (_: InterruptedException) { return 0 }
                return 0
            }
            override fun stop() { started = false }
        }

        val recorder = InAppAudioRecorder(buffer = buffer, sourceFactory = { syntheticSource })
        val latch = CountDownLatch(1)
        val receivedSamples = AtomicReference<FloatArray?>()

        recorder.startRecording(object : AudioRecorderListener {
            override fun onRecordingComplete(samples: FloatArray) {
                receivedSamples.set(samples)
                latch.countDown()
            }
            override fun onError(error: Throwable) {
                latch.countDown()
            }
        })

        // Wait briefly for read, then stop
        Thread.sleep(50)
        recorder.stopRecording()

        assertTrue(latch.await(3, TimeUnit.SECONDS))
        val samples = receivedSamples.get()
        assertNotNull(samples)
        assertEquals(1024, samples!!.size)
        assertEquals(500f / 32768f, samples[0], 0.0001f)

        // Wipe verification
        recorder.wipe()
        assertEquals(0, buffer.sampleCount)
        recorder.wipeSamples(samples)
        assertTrue(samples.all { it == 0f })
    }

    @Test
    fun inAppAudioRecorderWipesOnCancel() {
        val buffer = AudioRecordingBuffer(maxDurationSeconds = 2)
        val syntheticSource = object : AudioInputSource {
            override fun startRecording() {}
            override fun read(buf: ShortArray, offset: Int, size: Int): Int {
                try { Thread.sleep(20) } catch (_: InterruptedException) { return -1 }
                buf[0] = 999
                return 1
            }
            override fun stop() {}
        }

        val recorder = InAppAudioRecorder(buffer = buffer, sourceFactory = { syntheticSource })
        val completedLatch = CountDownLatch(1)
        val completedCalled = AtomicBoolean(false)

        recorder.startRecording(object : AudioRecorderListener {
            override fun onRecordingComplete(samples: FloatArray) {
                completedCalled.set(true)
                completedLatch.countDown()
            }
            override fun onError(error: Throwable) {
                completedLatch.countDown()
            }
        })

        Thread.sleep(30)
        recorder.cancelRecording()

        assertFalse(recorder.isRecordingActive)
        assertEquals(0, buffer.sampleCount)
        assertFalse(completedCalled.get())
    }
}
