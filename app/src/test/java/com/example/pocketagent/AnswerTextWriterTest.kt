package com.example.pocketagent

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnswerTextWriterTest {
    @Test
    fun writesTrimmedAnswerAsUtf8WithFinalNewline() {
        val output = ByteArrayOutputStream()

        AnswerTextWriter.write(output, "  PocketAgent: café  ")

        assertEquals("PocketAgent: café\n", output.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun rejectsEmptyAnswer() {
        assertThrows(IllegalArgumentException::class.java) {
            AnswerTextWriter.write(ByteArrayOutputStream(), " \n ")
        }
    }

    @Test
    fun rejectsAnswerOverBoundWithoutWritingAnyBytes() {
        val output = ByteArrayOutputStream()

        assertThrows(IllegalArgumentException::class.java) {
            AnswerTextWriter.write(output, "x".repeat(AnswerTextWriter.MAX_CHARS + 1))
        }

        assertEquals(0, output.size())
    }
}
