package com.example.pocketagent

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedPdfFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stagesInputWithinByteLimit() {
        val cache = temporaryFolder.newFolder("cache")
        val staged = BoundedPdfFile.stage(ByteArrayInputStream(byteArrayOf(1, 2, 3)), cache, 3)

        assertEquals(3L, staged.length())
        assertEquals(1, cache.listFiles()?.size)
        staged.delete()
    }

    @Test
    fun rejectsOversizedStreamAndDeletesPartialFile() {
        val cache = temporaryFolder.newFolder("cache")

        assertThrows(IllegalArgumentException::class.java) {
            BoundedPdfFile.stage(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), cache, 3)
        }

        assertEquals(0, cache.listFiles()?.size)
    }

    @Test
    fun rejectsEmptyStreamAndDeletesStagedFile() {
        val cache = temporaryFolder.newFolder("cache")

        assertThrows(IllegalArgumentException::class.java) {
            BoundedPdfFile.stage(ByteArrayInputStream(byteArrayOf()), cache, 3)
        }

        assertFalse(cache.listFiles()?.isNotEmpty() ?: false)
    }
}
