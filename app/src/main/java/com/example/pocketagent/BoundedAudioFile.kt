package com.example.pocketagent

import java.io.File
import java.io.InputStream

/** Copies a chosen audio file to cache without trusting provider-reported size. */
internal object BoundedAudioFile {
    fun stage(input: InputStream, cacheDir: File, maxBytes: Long): File {
        require(maxBytes > 0) { "Audio size limit must be positive." }
        val staged = File.createTempFile("pocketagent-audio-", ".wav", cacheDir)
        try {
            input.use { source ->
                staged.outputStream().buffered().use { destination ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) {
                            val single = source.read()
                            if (single < 0) break
                            if (total >= maxBytes) throw IllegalArgumentException("WAV exceeds the ${maxBytes / (1024 * 1024)} MB size limit.")
                            destination.write(single)
                            total++
                            continue
                        }
                        if (count.toLong() > maxBytes - total) {
                            throw IllegalArgumentException("WAV exceeds the ${maxBytes / (1024 * 1024)} MB size limit.")
                        }
                        destination.write(buffer, 0, count)
                        total += count
                    }
                    require(total > 0) { "The selected WAV file is empty." }
                }
            }
            return staged
        } catch (error: Exception) {
            staged.delete()
            throw error
        }
    }

    private const val BUFFER_SIZE = 16 * 1024
}
