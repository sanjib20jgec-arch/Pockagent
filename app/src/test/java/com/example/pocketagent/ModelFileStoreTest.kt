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

class ModelFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun importsModelAndReplacesExistingFile() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 100, reservedFreeSpaceBytes = 0)
        store.modelFile.writeBytes(byteArrayOf(1, 2, 3))

        val result = store.importModel(ByteArrayInputStream(byteArrayOf(7, 8, 9, 10)))

        assertTrue(result.exists())
        assertArrayEquals(byteArrayOf(7, 8, 9, 10), result.readBytes())
        assertFalse(partFile(store).exists())
    }

    @Test
    fun emptyInputDoesNotReplaceExistingModel() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 100, reservedFreeSpaceBytes = 0)
        store.modelFile.writeBytes(byteArrayOf(1, 2, 3))

        assertThrows(IOException::class.java) { store.importModel(ByteArrayInputStream(byteArrayOf())) }

        assertArrayEquals(byteArrayOf(1, 2, 3), store.modelFile.readBytes())
        assertFalse(partFile(store).exists())
    }

    @Test
    fun oversizeInputDoesNotReplaceExistingModel() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 3, reservedFreeSpaceBytes = 0)
        store.modelFile.writeBytes(byteArrayOf(1, 2, 3))

        assertThrows(IOException::class.java) {
            store.importModel(ByteArrayInputStream(byteArrayOf(4, 5, 6, 7)))
        }

        assertArrayEquals(byteArrayOf(1, 2, 3), store.modelFile.readBytes())
        assertFalse(partFile(store).exists())
    }

    @Test
    fun insufficientFreeSpaceDoesNotReplaceExistingModel() {
        val store = ModelFileStore(
            temporaryFolder.root,
            maxBytes = 100,
            reservedFreeSpaceBytes = Long.MAX_VALUE,
        )
        store.modelFile.writeBytes(byteArrayOf(1, 2, 3))

        assertThrows(IOException::class.java) {
            store.importModel(ByteArrayInputStream(byteArrayOf(4)))
        }

        assertArrayEquals(byteArrayOf(1, 2, 3), store.modelFile.readBytes())
        assertFalse(partFile(store).exists())
    }

    @Test
    fun streamFailurePreservesExistingModelAndRemovesPartialCopy() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 100, reservedFreeSpaceBytes = 0)
        store.modelFile.writeBytes(byteArrayOf(1, 2, 3))
        val failingInput = object : FilterInputStream(ByteArrayInputStream(byteArrayOf(4, 5, 6, 7))) {
            private var sent = 0

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (sent >= 2) throw IOException("simulated read failure")
                val count = super.read(buffer, offset, minOf(length, 2 - sent))
                if (count > 0) sent += count
                return count
            }
        }

        assertThrows(IOException::class.java) { store.importModel(failingInput) }

        assertArrayEquals(byteArrayOf(1, 2, 3), store.modelFile.readBytes())
        assertFalse(partFile(store).exists())
    }

    @Test
    fun rejectsInvalidSizeLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelFileStore(temporaryFolder.root, maxBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelFileStore(temporaryFolder.root, maxBytes = 1, reservedFreeSpaceBytes = -1)
        }
    }

    private fun partFile(store: ModelFileStore) =
        java.io.File(store.modelFile.parentFile, "model.litertlm.part")
}
