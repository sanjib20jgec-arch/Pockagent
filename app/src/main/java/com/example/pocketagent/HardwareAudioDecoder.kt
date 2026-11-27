package com.example.pocketagent

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.math.floor

const val TARGET_SAMPLE_RATE = 16_000
const val DEFAULT_MAX_DURATION_SEC = 180

/**
 * Decodes an audio [uri] via Android's hardware [MediaCodec] and [MediaExtractor]
 * into 16 kHz mono float PCM samples in [-1f, 1f], capped at [maxDurationSec].
 */
fun decodeTo16kMonoFloat(
    context: Context,
    uri: Uri,
    maxDurationSec: Int = DEFAULT_MAX_DURATION_SEC,
): FloatArray {
    require(maxDurationSec > 0) { "maxDurationSec must be positive." }
    val maxSamples = (maxDurationSec.toLong() * TARGET_SAMPLE_RATE)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    val chunks = ArrayList<FloatArray>()
    var totalCollected = 0

    try {
        extractor.setDataSource(context, uri, null)

        var audioTrackIndex = -1
        var trackFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio/")) {
                audioTrackIndex = i
                trackFormat = format
                break
            }
        }

        if (audioTrackIndex < 0 || trackFormat == null) {
            throw IllegalArgumentException("No audio track found in media: $uri")
        }

        extractor.selectTrack(audioTrackIndex)
        val mime = trackFormat.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalArgumentException("Audio track is missing MIME type.")
        var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        codec = MediaCodec.createDecoderByType(mime).apply {
            configure(trackFormat, null, null, 0)
            start()
        }

        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs = 10_000L
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }

                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val chunkBytes = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunkBytes)

                        val pcmFloats = pcm16LeBytesToFloat(chunkBytes, chunkBytes.size)
                        val resampled = resampleLinearTo16kMono(pcmFloats, sampleRate, channelCount)
                        val remaining = maxSamples - totalCollected
                        val toCopy = minOf(resampled.size, remaining)
                        if (toCopy > 0) {
                            chunks.add(if (toCopy == resampled.size) resampled else resampled.copyOf(toCopy))
                            totalCollected += toCopy
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (isEos || totalCollected >= maxSamples) {
                        outputDone = true
                    }
                }
            }
        }
    } finally {
        try {
            codec?.stop()
        } catch (_: IllegalStateException) {
            // Codec may not have been started if configuration failed.
        }
        try {
            codec?.release()
        } catch (_: Exception) {
            // Ignore release errors during cleanup.
        }
        try {
            extractor.release()
        } catch (_: Exception) {
            // Ignore release errors during cleanup.
        }
    }

    if (totalCollected == 0) {
        throw IllegalArgumentException("Decoded zero audio samples from media: $uri")
    }

    val result = FloatArray(totalCollected)
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(result, destinationOffset = offset)
        offset += chunk.size
    }
    return result
}

/**
 * Converts little-endian 16-bit signed PCM [bytes] (up to [length] bytes) to normalized floats in [-1f, 1f].
 */
fun pcm16LeBytesToFloat(bytes: ByteArray, length: Int): FloatArray {
    val safeLength = length.coerceIn(0, bytes.size)
    val sampleCount = safeLength / 2
    return FloatArray(sampleCount) { index ->
        val lo = bytes[index * 2].toInt() and 0xFF
        val hi = bytes[index * 2 + 1].toInt()
        val pcm16 = (hi shl 8) or lo
        pcm16 / 32768f
    }
}

/**
 * Downmixes [samples] with [channelCount] >= 2 to mono and linearly resamples from [sourceSampleRate]
 * to [TARGET_SAMPLE_RATE] (16 kHz). Returns [samples] as-is if already 16 kHz mono.
 */
fun resampleLinearTo16kMono(
    samples: FloatArray,
    sourceSampleRate: Int,
    channelCount: Int,
): FloatArray {
    require(sourceSampleRate > 0) { "sourceSampleRate must be positive." }
    require(channelCount >= 1) { "channelCount must be at least 1." }
    if (samples.isEmpty()) return samples
    if (sourceSampleRate == TARGET_SAMPLE_RATE && channelCount == 1) return samples

    val frameCount = samples.size / channelCount
    if (frameCount == 0) return FloatArray(0)

    val mono = if (channelCount == 1) {
        samples
    } else {
        FloatArray(frameCount) { frame ->
            var sum = 0f
            val base = frame * channelCount
            for (ch in 0 until channelCount) {
                sum += samples[base + ch]
            }
            sum / channelCount
        }
    }

    if (sourceSampleRate == TARGET_SAMPLE_RATE) return mono

    val targetCount = ((frameCount.toLong() * TARGET_SAMPLE_RATE) / sourceSampleRate).toInt()
    if (targetCount <= 0) return FloatArray(0)

    return FloatArray(targetCount) { targetIndex ->
        val position = targetIndex.toDouble() * sourceSampleRate / TARGET_SAMPLE_RATE
        val first = floor(position).toInt().coerceAtMost(frameCount - 1)
        val second = (first + 1).coerceAtMost(frameCount - 1)
        val fraction = (position - first).toFloat()
        mono[first] * (1f - fraction) + mono[second] * fraction
    }
}
