package com.example.pocketagent

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedTextReaderTest {
    @Test
    fun readsOnlyUpToLimitAcrossShortReads() {
        val reader = object : StringReader("PocketAgent attachment") {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 3))
        }

        assertEquals("Pocket", BoundedTextReader.read(reader, 6))
    }

    @Test
    fun zeroLimitReturnsEmptyText() {
        assertEquals("", BoundedTextReader.read(StringReader("ignored"), 0))
    }

    @Test
    fun rejectsNegativeLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundedTextReader.read(StringReader("text"), -1)
        }
    }
}
