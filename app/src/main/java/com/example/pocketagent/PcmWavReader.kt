package com.example.pocketagent

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.floor

/** Parses bounded PCM16 WAV and converts mono/stereo samples to 16 kHz mono floats. */
internal object PcmWavReader {
    const val TARGET_SAMPLE_RATE = 16_000
    const val MAX_DURATION_SECONDS = 60
    const val MAX_FILE_BYTES = 25L * 1024 * 1024
    private const val MIN_SAMPLE_RATE = 8_000
    private const val MAX_SAMPLE_RATE = 48_000

    fun read(file: File): FloatArray = RandomAccessFile(file, "r").use { wav ->
        require(file.length() in 12..MAX_FILE_BYTES) { "WAV file must be no larger than 25 MB." }
        require(readAscii(wav, 4) == "RIFF") { "Unsupported audio: choose a RIFF WAV file." }
        val riffSize = readUInt32(wav)
        require(readAscii(wav, 4) == "WAVE") { "Unsupported audio: choose a RIFF WAV file." }
        val riffEnd = 8L + riffSize
        require(riffSize >= 4 && riffEnd <= wav.length() && riffEnd >= wav.filePointer) {
            "WAV container is truncated or malformed."
        }

        var channels = 0
        var sampleRate = 0
        var byteRate = 0L
        var bitsPerSample = 0
        var formatCode = 0
        var blockAlign = 0
        var dataOffset = -1L
        var dataSize = -1L

        while (wav.filePointer + 8 <= riffEnd) {
            val chunkId = readAscii(wav, 4)
            val chunkSize = readUInt32(wav)
            val chunkStart = wav.filePointer
            val chunkEnd = chunkStart + chunkSize
            require(chunkEnd <= riffEnd && chunkEnd >= chunkStart) { "WAV chunk exceeds the declared file bounds." }

            if (chunkId == "fmt ") {
                require(chunkSize >= 16) { "WAV format chunk is incomplete." }
                formatCode = readUInt16(wav)
                channels = readUInt16(wav)
                sampleRate = readUInt32(wav).toInt()
                byteRate = readUInt32(wav)
                blockAlign = readUInt16(wav)
                bitsPerSample = readUInt16(wav)
            } else if (chunkId == "data" && dataOffset < 0) {
                dataOffset = chunkStart
                dataSize = chunkSize
            }

            val paddedEnd = chunkEnd + (chunkSize and 1L)
            require(paddedEnd <= riffEnd) { "WAV chunk padding is truncated." }
            wav.seek(paddedEnd)
        }
        require(wav.filePointer == riffEnd) { "WAV contains an incomplete chunk header." }

        require(formatCode == PCM_FORMAT) { "Only uncompressed PCM WAV audio is supported." }
        require(channels in 1..2) { "WAV must have one or two audio channels." }
        require(bitsPerSample == 16) { "Only 16-bit PCM WAV audio is supported." }
        require(sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) { "WAV sample rate must be 8–48 kHz." }
        require(blockAlign == channels * 2) { "WAV channel layout is not supported." }
        require(byteRate == sampleRate.toLong() * blockAlign) { "WAV sample rate metadata is inconsistent." }
        require(dataOffset >= 0 && dataSize > 0) { "WAV has no audio samples." }
        require(dataSize % blockAlign == 0L) { "WAV audio data is incomplete." }

        val frameCountLong = dataSize / blockAlign
        require(frameCountLong <= sampleRate.toLong() * MAX_DURATION_SECONDS) {
            "WAV duration exceeds the ${MAX_DURATION_SECONDS}-second limit."
        }
        require(frameCountLong > 0) { "WAV has no audio samples." }
        val frameCount = frameCountLong.toInt()
        val sourceSamples = ShortArray(frameCount * channels)
        wav.seek(dataOffset)
        for (index in sourceSamples.indices) sourceSamples[index] = readUInt16(wav).toShort()

        val targetCount = (frameCountLong * TARGET_SAMPLE_RATE / sampleRate).toInt()
        require(targetCount > 0) { "WAV is too short to transcribe." }
        FloatArray(targetCount) { targetIndex ->
            val position = targetIndex.toDouble() * sampleRate / TARGET_SAMPLE_RATE
            val firstFrame = floor(position).toInt().coerceAtMost(frameCount - 1)
            val secondFrame = (firstFrame + 1).coerceAtMost(frameCount - 1)
            val fraction = (position - firstFrame).toFloat()
            val first = monoSample(sourceSamples, firstFrame, channels)
            val second = monoSample(sourceSamples, secondFrame, channels)
            ((first * (1f - fraction)) + (second * fraction)) / 32768f
        }
    }

    private fun monoSample(samples: ShortArray, frame: Int, channels: Int): Float {
        val offset = frame * channels
        return if (channels == 1) samples[offset].toFloat()
        else (samples[offset].toFloat() + samples[offset + 1].toFloat()) * 0.5f
    }

    private fun readAscii(input: RandomAccessFile, count: Int): String {
        val bytes = ByteArray(count)
        input.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readUInt16(input: RandomAccessFile): Int {
        val low = input.readUnsignedByte()
        val high = input.readUnsignedByte()
        return low or (high shl 8)
    }

    private fun readUInt32(input: RandomAccessFile): Long {
        val b0 = input.readUnsignedByte().toLong()
        val b1 = input.readUnsignedByte().toLong()
        val b2 = input.readUnsignedByte().toLong()
        val b3 = input.readUnsignedByte().toLong()
        val value = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        if (value < 0) throw IOException("Invalid WAV size.")
        return value
    }

    private const val PCM_FORMAT = 1
}
