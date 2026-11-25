package com.example.pocketagent

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 100, reservedFreeSpaceBytes = 0, minBytes = 1L)
        store.modelFile.writeBytes(byteArrayOf(1, 2, 3))

        val result = store.importModel(ByteArrayInputStream(byteArrayOf(7, 8, 9, 10)))

        assertTrue(result.exists())
        assertArrayEquals(byteArrayOf(7, 8, 9, 10), result.readBytes())
        assertFalse(partFile(store).exists())
    }

    @Test
    fun emptyInputDoesNotReplaceExistingModel() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 100, reservedFreeSpaceBytes = 0, minBytes = 1L)
        store.modelFile.writeBytes(byteArrayOf(1, 2, 3))

        assertThrows(IOException::class.java) { store.importModel(ByteArrayInputStream(byteArrayOf())) }

        assertArrayEquals(byteArrayOf(1, 2, 3), store.modelFile.readBytes())
        assertFalse(partFile(store).exists())
    }

    @Test
    fun oversizeInputDoesNotReplaceExistingModel() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 3, reservedFreeSpaceBytes = 0, minBytes = 1L)
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
            minBytes = 1L,
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
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 100, reservedFreeSpaceBytes = 0, minBytes = 1L)
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

    @Test
    fun expectedBytesExceedingLimitFailsBeforeReadingStream() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 100, reservedFreeSpaceBytes = 0, minBytes = 1L)
        val unreadStream = object : java.io.InputStream() {
            override fun read(): Int = throw AssertionError("Stream should not be read when expectedBytes exceeds limit.")
        }

        assertThrows(IOException::class.java) {
            store.importModel(unreadStream, expectedBytes = 250L)
        }
        assertFalse(partFile(store).exists())
    }

    @Test
    fun importModelReportsCompletionProgress() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 100, reservedFreeSpaceBytes = 0, minBytes = 1L)
        val updates = mutableListOf<Pair<Long, Long?>>()

        store.importModel(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), expectedBytes = 4L) { copied, total ->
            updates += copied to total
        }

        assertTrue(updates.isNotEmpty())
        assertEquals(4L to 4L, updates.last())
    }

    @Test
    fun removeStoredModelDeletesPrimaryPartialAndBackupFiles() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 100, reservedFreeSpaceBytes = 0, minBytes = 1L)
        store.modelFile.writeBytes(byteArrayOf(1, 2))
        partFile(store).writeBytes(byteArrayOf(3, 4))
        val backup = java.io.File(temporaryFolder.root, "model.litertlm.backup").apply { writeBytes(byteArrayOf(5)) }

        assertTrue(store.removeStoredModel())
        assertFalse(store.modelFile.exists())
        assertFalse(partFile(store).exists())
        assertFalse(backup.exists())
    }

    @Test
    fun clearEngineCachesDeletesCompilerArtifactsButPreservesPocketAgentDirs() {
        val cacheDir = temporaryFolder.newFolder("cache")
        val keepDir = java.io.File(cacheDir, "pocketagent-kept").apply { mkdirs() }
        val keepFile = java.io.File(keepDir, "note.txt").apply { writeText("keep") }
        val openClDir = java.io.File(cacheDir, "opencl_cache").apply { mkdirs() }
        java.io.File(openClDir, "shader.bin").writeBytes(byteArrayOf(9, 8))

        ModelFileStore.clearEngineCaches(cacheDir)

        assertTrue(keepFile.exists())
        assertFalse(openClDir.exists())
    }

    @Test
    fun rejectsTooSmallModelWhenMinBytesEnforced() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 10_000_000, reservedFreeSpaceBytes = 0)
        val bytes = ByteArray(1024) { 0x50.toByte() } // PK-like but too small with default min
        val ex = assertThrows(IOException::class.java) {
            store.importModel(ByteArrayInputStream(bytes))
        }
        assertTrue(ex.message!!.contains("too small"))
    }

    @Test
    fun rejectsObviousTextFileWhenIntegrityCheckEnabled() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 10_000_000, reservedFreeSpaceBytes = 0)
        val bytes = ("not a model " + "x".repeat(2_000_000)).toByteArray()
        val ex = assertThrows(IOException::class.java) {
            store.importModel(ByteArrayInputStream(bytes))
        }
        assertTrue(ex.message!!.contains("does not look like a valid"))
    }

    @Test
    fun acceptsZipLikeBinaryModelHeader() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 10_000_000, reservedFreeSpaceBytes = 0)
        val bytes = ByteArray(1_200_000)
        bytes[0] = 0x50.toByte(); bytes[1] = 0x4B.toByte(); bytes[2] = 0x03.toByte(); bytes[3] = 0x04.toByte()
        val result = store.importModel(ByteArrayInputStream(bytes))
        assertTrue(result.exists())
        assertEquals(bytes.size.toLong(), result.length())
    }

    private fun partFile(store: ModelFileStore) =
        java.io.File(store.modelFile.parentFile, "model.litertlm.part")
}
