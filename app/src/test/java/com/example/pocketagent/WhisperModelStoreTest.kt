package com.example.pocketagent

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WhisperModelStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun commitsCandidateOnlyWhenRequested() {
        val store = store()
        store.modelFile.writeBytes(byteArrayOf(1, 2))

        val candidateBytes = validGgmlPrefix + byteArrayOf(3, 4)
        store.stage(ByteArrayInputStream(candidateBytes))

        assertArrayEquals(byteArrayOf(1, 2), store.modelFile.readBytes())
        assertArrayEquals(candidateBytes, store.candidateFile.readBytes())
        store.commitCandidate()
        assertArrayEquals(candidateBytes, store.modelFile.readBytes())
        assertFalse(store.candidateFile.exists())
    }

    @Test
    fun rejectsOversizedCandidateWithoutReplacingExistingModel() {
        val store = store(maxBytes = 3)
        store.modelFile.writeBytes(byteArrayOf(1, 2))

        assertThrows(IOException::class.java) {
            store.stage(ByteArrayInputStream(byteArrayOf(3, 4, 5, 6)))
        }

        assertArrayEquals(byteArrayOf(1, 2), store.modelFile.readBytes())
        assertFalse(store.candidateFile.exists())
    }

    @Test
    fun emptyInvalidAndFailingStreamsDoNotLeaveCandidateFiles() {
        val store = store()
        assertThrows(IOException::class.java) { store.stage(ByteArrayInputStream(byteArrayOf())) }
        assertFalse(store.candidateFile.exists())
        assertThrows(IOException::class.java) {
            store.stage(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)))
        }
        assertFalse(store.candidateFile.exists())

        val broken = object : FilterInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3))) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                throw IOException("simulated stream failure")
            }
        }
        assertThrows(IOException::class.java) { store.stage(broken) }
        assertFalse(store.candidateFile.exists())
    }

    @Test
    fun discardCandidateLeavesCurrentModelUntouched() {
        val store = store()
        store.modelFile.writeBytes(byteArrayOf(9))
        store.stage(ByteArrayInputStream(validGgmlPrefix + byteArrayOf(1, 2)))

        store.discardCandidate()

        assertArrayEquals(byteArrayOf(9), store.modelFile.readBytes())
        assertFalse(store.candidateFile.exists())
        assertTrue(store.modelFile.exists())
    }

    private fun store(maxBytes: Long = 100) =
        WhisperModelStore(temporaryFolder.root, maxBytes = maxBytes, reservedFreeSpaceBytes = 0)

    private companion object {
        val validGgmlPrefix = byteArrayOf(0x6c, 0x6d, 0x67, 0x67)
    }
}
