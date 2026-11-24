package com.example.pocketagent

import java.io.File
import java.io.InputStream

/** Stages a selected PDF without trusting its reported size or buffering it in memory. */
internal object BoundedPdfFile {
    fun stage(input: InputStream, cacheDir: File, maxBytes: Long): File {
        require(maxBytes > 0) { "PDF size limit must be positive." }
        val staged = File.createTempFile("pocketagent-pdf-", ".pdf", cacheDir)
        try {
            input.use { source ->
                staged.outputStream().buffered().use { destination ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (count.toLong() > maxBytes - total) {
                            throw IllegalArgumentException("PDF exceeds the ${maxBytes / (1024 * 1024)} MB size limit.")
                        }
                        destination.write(buffer, 0, count)
                        total += count
                    }
                    require(total > 0) { "The selected PDF is empty." }
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
