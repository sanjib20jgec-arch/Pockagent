package com.example.pocketagent

import java.io.IOException
import java.io.OutputStream

/** Writes only a bounded, completed answer to a destination explicitly chosen by the user. */
internal object AnswerTextWriter {
    const val MAX_CHARS = 64 * 1024

    @Throws(IOException::class)
    fun write(output: OutputStream, text: String) {
        require(text.isNotBlank()) { "There is no answer to save." }
        require(text.length <= MAX_CHARS) { "The answer is too large to save as a text result." }
        output.write((text.trimEnd() + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }
}
