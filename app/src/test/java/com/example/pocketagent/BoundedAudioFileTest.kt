package com.example.pocketagent

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedAudioFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stagesWithinLimitAndClosesTemporaryFileOnDelete() {
        val cache = temporaryFolder.newFolder("cache")
        val file = BoundedAudioFile.stage(ByteArrayInputStream(byteArrayOf(1, 2, 3)), cache, 3)

        assertEquals(3L, file.length())
        file.delete()
        assertEquals(0, cache.listFiles()?.size)
    }

    @Test
    fun rejectsOversizedAndEmptyAudioStreamsWithoutKeepingPartialFiles() {
        val cache = temporaryFolder.newFolder("cache")

        assertThrows(IllegalArgumentException::class.java) {
            BoundedAudioFile.stage(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), cache, 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedAudioFile.stage(ByteArrayInputStream(byteArrayOf()), cache, 3)
        }

        assertEquals(0, cache.listFiles()?.size)
    }
}
