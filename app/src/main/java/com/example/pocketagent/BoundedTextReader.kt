package com.example.pocketagent

import java.io.Reader

/** Reads at most [maxChars] UTF-16 code units without loading the full source into memory. */
internal object BoundedTextReader {
    fun read(reader: Reader, maxChars: Int): String {
        require(maxChars >= 0) { "Character limit cannot be negative." }
        if (maxChars == 0) return ""

        val result = StringBuilder(minOf(maxChars, BUFFER_SIZE))
        val buffer = CharArray(minOf(maxChars, BUFFER_SIZE))
        while (result.length < maxChars) {
            val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - result.length))
            if (count < 0) break
            if (count == 0) continue
            result.append(buffer, 0, count)
        }
        return result.toString()
    }

    private const val BUFFER_SIZE = 8_192
}
