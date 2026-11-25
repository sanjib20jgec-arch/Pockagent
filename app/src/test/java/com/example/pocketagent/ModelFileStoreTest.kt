package com.example.pocketagent

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
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
    fun importModelRejectsWhenStreamExceedsSizeLimit() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 4, reservedFreeSpaceBytes = 0, minBytes = 1L)

        assertThrows(IOException::class.java) {
            store.importModel(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)))
        }
        assertFalse(partFile(store).exists())
    }

    @Test
    fun importModelStoresValidSmallFile() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 100, reservedFreeSpaceBytes = 0, minBytes = 1L)
        val data = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        val file = store.importModel(ByteArrayInputStream(data))

        assertTrue(file.exists())
        assertArrayEquals(data, file.readBytes())
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

    @Test
    fun acceptsBinaryModelWhoseFirstFourBytesArePrintableAscii() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 10_000_000, reservedFreeSpaceBytes = 0)
        val bytes = ByteArray(1_200_000)
        // Simulate a FlatBuffer/TFLite header whose first 4 bytes are printable ASCII ("TFL3"),
        // followed by binary vtable/offset bytes within the 64-byte header window.
        bytes[0] = 'T'.code.toByte()
        bytes[1] = 'F'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = '3'.code.toByte()
        bytes[4] = 0x1C.toByte()
        bytes[5] = 0x00.toByte()

        val result = store.importModel(ByteArrayInputStream(bytes))

        assertTrue(result.exists())
        assertEquals(bytes.size.toLong(), result.length())
    }

    @Test
    fun acceptsBinaryModelWithAsciiPrefixFollowedByBinaryWithinSixtyFourBytes() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 10_000_000, reservedFreeSpaceBytes = 0)
        val bytes = ByteArray(1_200_000)
        val prefix = "LTLM_V01".toByteArray(Charsets.US_ASCII)
        System.arraycopy(prefix, 0, bytes, 0, prefix.size)
        bytes[12] = 0x00.toByte()
        bytes[13] = 0xFF.toByte()

        val result = store.importModel(ByteArrayInputStream(bytes))

        assertTrue(result.exists())
        assertEquals(bytes.size.toLong(), result.length())
    }

    @Test
    fun rejectsMultilineAsciiTextOverEntireSixtyFourByteHeader() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 10_000_000, reservedFreeSpaceBytes = 0)
        val header = "LTLM config\nversion=1\tmode=text\r\n" + "a".repeat(80)
        val bytes = (header + "b".repeat(1_200_000)).toByteArray(Charsets.US_ASCII)

        val ex = assertThrows(IOException::class.java) {
            store.importModel(ByteArrayInputStream(bytes))
        }
        assertTrue(ex.message!!.contains("does not look like a valid"))
    }

    @Test
    fun reportsLiveProgressAndCompletionWithExpectedBytes() {
        val totalBytes = 9L * 1024 * 1024
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 20_000_000, reservedFreeSpaceBytes = 0)
        val progressEvents = mutableListOf<Pair<Long, Long?>>()

        val result = store.importModel(
            input = syntheticZipStream(totalBytes),
            expectedBytes = totalBytes,
        ) { copied, expected ->
            progressEvents.add(copied to expected)
        }

        assertEquals(totalBytes, result.length())
        assertTrue(progressEvents.size >= 2)
        assertTrue(progressEvents.first().first >= 8L * 1024 * 1024)
        assertTrue(progressEvents.first().first < totalBytes)
        assertEquals(totalBytes, progressEvents.first().second)
        assertEquals(totalBytes to totalBytes, progressEvents.last())
    }

    @Test
    fun reportsLiveProgressWithNullExpectedBytesAndResolvesTotalAtCompletion() {
        val totalBytes = 9L * 1024 * 1024
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 20_000_000, reservedFreeSpaceBytes = 0)
        val progressEvents = mutableListOf<Pair<Long, Long?>>()

        val result = store.importModel(
            input = syntheticZipStream(totalBytes),
            onProgress = { copied, expected -> progressEvents.add(copied to expected) },
        )

        assertEquals(totalBytes, result.length())
        assertTrue(progressEvents.size >= 2)
        assertEquals(null, progressEvents.first().second)
        assertEquals(totalBytes to totalBytes, progressEvents.last())
    }

    @Test
    fun rejectsOversizedExpectedBytesBeforeDrainingStream() {
        val store = ModelFileStore(temporaryFolder.root, maxBytes = 100, reservedFreeSpaceBytes = 0, minBytes = 1L)
        store.modelFile.writeBytes(byteArrayOf(1, 2, 3))
        val guardedInput = UnreadGuardedInputStream()

        val ex = assertThrows(IOException::class.java) {
            store.importModel(guardedInput, expectedBytes = 101L)
        }

        assertFalse(guardedInput.wasRead)
        assertTrue(ex.message!!.contains("import limit"))
        assertArrayEquals(byteArrayOf(1, 2, 3), store.modelFile.readBytes())
        assertFalse(partFile(store).exists())
    }

    @Test
    fun rejectsExpectedBytesExceedingFreeSpaceAndMentionsExistingModel() {
        val store = ModelFileStore(
            temporaryFolder.root,
            maxBytes = 10_000_000,
            reservedFreeSpaceBytes = Long.MAX_VALUE,
            minBytes = 1L,
        )
        store.modelFile.writeBytes(ByteArray(2048) { 1 })
        val guardedInput = UnreadGuardedInputStream()

        val ex = assertThrows(IOException::class.java) {
            store.importModel(guardedInput, expectedBytes = 5_000L)
        }

        assertFalse(guardedInput.wasRead)
        assertTrue(ex.message!!.contains("Not enough free storage"))
        assertTrue(ex.message!!.contains("Remove the current stored model first to free"))
        assertFalse(partFile(store).exists())
    }

    private class UnreadGuardedInputStream : InputStream() {
        var wasRead = false
            private set

        override fun read(): Int {
            wasRead = true
            return 0
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            wasRead = true
            return 0
        }
    }

    private fun syntheticZipStream(totalBytes: Long): InputStream = object : InputStream() {
        private var position = 0L
        private val header = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        override fun read(): Int {
            if (position >= totalBytes) return -1
            val value = if (position < header.size) header[position.toInt()].toInt() and 0xff else 0
            position++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position >= totalBytes) return -1
            val toRead = minOf(length.toLong(), totalBytes - position).toInt()
            var index = 0
            while (index < toRead && position < header.size) {
                buffer[offset + index] = header[position.toInt()]
                position++
                index++
            }
            if (index < toRead) {
                java.util.Arrays.fill(buffer, offset + index, offset + toRead, 0.toByte())
                position += (toRead - index)
            }
            return toRead
        }
    }

    private fun partFile(store: ModelFileStore) =
        java.io.File(store.modelFile.parentFile, "model.litertlm.part")
}
