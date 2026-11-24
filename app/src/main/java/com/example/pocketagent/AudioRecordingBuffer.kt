package com.example.pocketagent

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Arrays
import kotlin.math.sqrt

/**
 * Bounded memory buffer for PCM 16-bit 16kHz mono audio recording.
 *
 * Enforces hard duration limits (up to 60 seconds) and byte limits (up to 25 MiB).
 * Supports real-time RMS calculations, voice activity and trailing silence detection,
 * conversion to normalized float samples for Whisper inference, temporary WAV file export,
 * and secure zero-wipe of all audio data.
 */
class AudioRecordingBuffer(
    val maxDurationSeconds: Int = DEFAULT_MAX_DURATION_SECONDS,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val silenceThresholdRms: Float = DEFAULT_SILENCE_THRESHOLD_RMS,
    val silenceCutoffSeconds: Float = DEFAULT_SILENCE_CUTOFF_SECONDS
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val BYTES_PER_SAMPLE = 2
        const val DEFAULT_MAX_DURATION_SECONDS = 60
        const val DEFAULT_MAX_BYTES = 25L * 1024 * 1024 // 25 MiB hard cap
        const val DEFAULT_SILENCE_THRESHOLD_RMS = 0.012f // ~-38 dBFS
        const val SPEECH_TRIGGER_THRESHOLD_RMS = 0.025f // Threshold to mark speech start
        const val DEFAULT_SILENCE_CUTOFF_SECONDS = 3.0f

        /** Securely zero-wipes a float array containing audio samples. */
        fun wipe(samples: FloatArray?) {
            samples?.fill(0f)
        }

        /** Securely zero-wipes a byte array containing audio data. */
        fun wipe(bytes: ByteArray?) {
            bytes?.fill(0)
        }

        /** Securely overwrites and deletes a temporary audio file. */
        fun secureDelete(file: File?) {
            if (file != null && file.exists()) {
                try {
                    val len = file.length().coerceAtMost(65536L).toInt()
                    if (len > 0) {
                        file.writeBytes(ByteArray(len))
                    }
                } catch (_: Exception) {}
                file.delete()
            }
        }
    }

    val maxSamples: Int = (maxDurationSeconds * SAMPLE_RATE).coerceAtMost((maxBytes / BYTES_PER_SAMPLE).toInt())

    private var storage = ShortArray(maxSamples)
    var sampleCount: Int = 0
        private set

    var speechStarted: Boolean = false
        private set

    var continuousSilentSamples: Long = 0L
        private set

    val isFull: Boolean
        get() = sampleCount >= maxSamples

    val recordedDurationSeconds: Float
        get() = sampleCount.toFloat() / SAMPLE_RATE

    val recordedBytes: Long
        get() = sampleCount.toLong() * BYTES_PER_SAMPLE

    val silenceCutoffSamples: Long = (silenceCutoffSeconds * SAMPLE_RATE).toLong()

    /**
     * Appends short PCM samples to the buffer.
     * Returns the actual number of samples accepted (capped at maxSamples).
     */
    @Synchronized
    fun append(samples: ShortArray, offset: Int, count: Int): Int {
        require(offset >= 0 && count >= 0 && offset + count <= samples.size) {
            "Offset and count exceed source short array bounds."
        }
        if (count == 0 || isFull) return 0

        val toCopy = count.coerceAtMost(maxSamples - sampleCount)
        System.arraycopy(samples, offset, storage, sampleCount, toCopy)
        sampleCount += toCopy

        // Evaluate RMS and update speech / silence tracking
        val rms = calculateRms(samples, offset, toCopy)
        updateSpeechAndSilence(rms, toCopy)

        return toCopy
    }

    /**
     * Appends raw PCM 16-bit little-endian bytes to the buffer.
     * Returns the number of bytes accepted.
     */
    @Synchronized
    fun append(bytes: ByteArray, offset: Int, count: Int): Int {
        require(offset >= 0 && count >= 0 && offset + count <= bytes.size) {
            "Offset and count exceed source byte array bounds."
        }
        if (count == 0 || isFull) return 0

        val samplesToRead = count / BYTES_PER_SAMPLE
        val availableSamples = maxSamples - sampleCount
        val samplesToAccept = samplesToRead.coerceAtMost(availableSamples)
        if (samplesToAccept <= 0) return 0

        var byteIndex = offset
        for (i in 0 until samplesToAccept) {
            val b0 = bytes[byteIndex].toInt() and 0xFF
            val b1 = bytes[byteIndex + 1].toInt()
            val shortVal = (b0 or (b1 shl 8)).toShort()
            storage[sampleCount + i] = shortVal
            byteIndex += BYTES_PER_SAMPLE
        }

        val addedSamples = samplesToAccept
        sampleCount += addedSamples

        val rms = calculateRms(storage, sampleCount - addedSamples, addedSamples)
        updateSpeechAndSilence(rms, addedSamples)

        return addedSamples * BYTES_PER_SAMPLE
    }

    private fun updateSpeechAndSilence(rms: Float, sampleDelta: Int) {
        if (!speechStarted && rms >= SPEECH_TRIGGER_THRESHOLD_RMS) {
            speechStarted = true
            continuousSilentSamples = 0L
        } else if (speechStarted) {
            if (rms < silenceThresholdRms) {
                continuousSilentSamples += sampleDelta
            } else {
                continuousSilentSamples = 0L
            }
        }
    }

    /**
     * Returns true if initial speech was observed and trailing silence has reached the cutoff limit.
     */
    val isSilenceCutoffReached: Boolean
        get() = speechStarted && continuousSilentSamples >= silenceCutoffSamples

    /**
     * Calculates the RMS (Root Mean Square) normalized amplitude in [0.0, 1.0] for the given samples.
     */
    fun calculateRms(samples: ShortArray, offset: Int, count: Int): Float {
        if (count <= 0) return 0f
        var sumSquares = 0.0
        for (i in offset until offset + count) {
            val sampleVal = samples[i].toDouble()
            sumSquares += sampleVal * sampleVal
        }
        val meanSquare = sumSquares / count
        return (sqrt(meanSquare) / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Extracts normalized FloatArray [-1.0f, 1.0f] for transcription via Whisper.
     */
    @Synchronized
    fun toNormalizedFloats(): FloatArray {
        val count = sampleCount
        val result = FloatArray(count)
        for (i in 0 until count) {
            result[i] = storage[i].toFloat() / 32768.0f
        }
        return result
    }

    /**
     * Writes the recorded audio to a temporary 16kHz 16-bit mono RIFF WAV file.
     */
    @Synchronized
    fun writeWavFile(destination: File) {
        val dataBytes = sampleCount * BYTES_PER_SAMPLE
        val totalFileBytes = 44 + dataBytes
        require(totalFileBytes.toLong() <= maxBytes) { "WAV file exceeds maximum allowed bytes: $maxBytes" }

        FileOutputStream(destination).use { out ->
            // RIFF header
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.write(intToByteArrayLittleEndian(totalFileBytes - 8))
            out.write("WAVE".toByteArray(Charsets.US_ASCII))

            // fmt chunk
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.write(intToByteArrayLittleEndian(16)) // Subchunk1Size (16 for PCM)
            out.write(shortToByteArrayLittleEndian(1.toShort())) // AudioFormat (1 = PCM)
            out.write(shortToByteArrayLittleEndian(CHANNELS.toShort()))
            out.write(intToByteArrayLittleEndian(SAMPLE_RATE))
            out.write(intToByteArrayLittleEndian(SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE)) // ByteRate
            out.write(shortToByteArrayLittleEndian((CHANNELS * BYTES_PER_SAMPLE).toShort())) // BlockAlign
            out.write(shortToByteArrayLittleEndian(BITS_PER_SAMPLE.toShort()))

            // data chunk
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.write(intToByteArrayLittleEndian(dataBytes))

            // PCM samples
            val pcmBytes = ByteArray(1024 * BYTES_PER_SAMPLE)
            var writtenSamples = 0
            while (writtenSamples < sampleCount) {
                val chunkSize = (sampleCount - writtenSamples).coerceAtMost(1024)
                for (i in 0 until chunkSize) {
                    val sample = storage[writtenSamples + i].toInt()
                    pcmBytes[i * 2] = (sample and 0xFF).toByte()
                    pcmBytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                }
                out.write(pcmBytes, 0, chunkSize * BYTES_PER_SAMPLE)
                writtenSamples += chunkSize
            }
        }
    }

    /**
     * Securely wipes all recorded audio data in memory by zeroing out the buffer.
     */
    @Synchronized
    fun wipe() {
        Arrays.fill(storage, 0.toShort())
        sampleCount = 0
        speechStarted = false
        continuousSilentSamples = 0L
    }

    private fun intToByteArrayLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToByteArrayLittleEndian(value: Short): ByteArray {
        val v = value.toInt()
        return byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte()
        )
    }
}
