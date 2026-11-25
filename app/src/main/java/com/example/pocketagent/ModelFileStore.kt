package com.example.pocketagent

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale

/** Copies a selected model transactionally, preserving the last working file if import fails. */
internal class ModelFileStore(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_MODEL_BYTES,
    private val reservedFreeSpaceBytes: Long = DEFAULT_RESERVED_FREE_SPACE_BYTES,
    private val minBytes: Long = MIN_MODEL_BYTES,
) {
    val modelFile: File
        get() = File(directory, MODEL_FILE_NAME)

    init {
        require(maxBytes > 0) { "Model size limit must be positive." }
        require(reservedFreeSpaceBytes >= 0) { "Reserved free space cannot be negative." }
    }

    @JvmOverloads
    @Throws(IOException::class)
    fun importModel(
        input: InputStream,
        expectedBytes: Long? = null,
        onProgress: ((copiedBytes: Long, totalBytes: Long?) -> Unit)? = null,
    ): File {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create private model storage.")
        }
        if (!directory.isDirectory) throw IOException("Private model storage is not a directory.")

        val temporary = File(directory, "$MODEL_FILE_NAME.part")
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("Could not clear an incomplete earlier model import.")
        }

        val availableAtStart = directory.usableSpace
        val copyLimit = if (availableAtStart > 0L) {
            minOf(maxBytes, (availableAtStart - reservedFreeSpaceBytes).coerceAtLeast(0L))
        } else {
            maxBytes
        }

        val normalizedExpectedBytes = expectedBytes?.takeIf { it > 0L }
        if (normalizedExpectedBytes != null && normalizedExpectedBytes > copyLimit) {
            throw IOException(sizeLimitMessage(0L, copyLimit, maxBytes, normalizedExpectedBytes))
        }

        var committed = false
        try {
            var copied = 0L
            var lastReported = 0L
            val magic = ByteArray(HEADER_PEEK_BYTES)
            var magicRead = 0
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) {
                        val singleByte = input.read()
                        if (singleByte < 0) break
                        if (copied >= copyLimit) {
                            val needed = if (copied < Long.MAX_VALUE) copied + 1L else Long.MAX_VALUE
                            throw IOException(sizeLimitMessage(needed, copyLimit, maxBytes, normalizedExpectedBytes))
                        }
                        output.write(singleByte)
                        if (magicRead < magic.size) {
                            magic[magicRead] = singleByte.toByte()
                            magicRead++
                        }
                        copied++
                    } else {
                        if (count.toLong() > copyLimit - copied) {
                            val needed = if (copied <= Long.MAX_VALUE - count) copied + count else Long.MAX_VALUE
                            throw IOException(sizeLimitMessage(needed, copyLimit, maxBytes, normalizedExpectedBytes))
                        }
                        output.write(buffer, 0, count)
                        if (magicRead < magic.size) {
                            val toCopy = minOf(count, magic.size - magicRead)
                            System.arraycopy(buffer, 0, magic, magicRead, toCopy)
                            magicRead += toCopy
                        }
                        copied += count
                    }
                    if (onProgress != null && copied - lastReported >= PROGRESS_INTERVAL_BYTES) {
                        lastReported = copied
                        onProgress.invoke(copied, normalizedExpectedBytes)
                    }
                }
                if (copied == 0L) throw IOException("The selected model file is empty.")
                if (copied < minBytes) {
                    throw IOException("The selected file is too small to be a valid .litertlm model.")
                }
                if (minBytes >= MIN_MODEL_BYTES && !looksLikeLitertLm(magic, magicRead)) {
                    throw IOException("The selected file does not look like a valid .litertlm model.")
                }
                output.fd.sync()
            }

            replaceCurrentModel(temporary, modelFile)
            committed = true
            onProgress?.invoke(copied, normalizedExpectedBytes ?: copied)
            return modelFile
        } finally {
            if (!committed && temporary.exists()) temporary.delete()
        }
    }

    private fun replaceCurrentModel(temporary: File, target: File) {
        try {
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            replaceWithRollback(temporary, target)
        }
    }

    private fun replaceWithRollback(temporary: File, target: File) {
        val backup = File(directory, "$MODEL_FILE_NAME.backup")
        if (backup.exists() && !backup.delete()) throw IOException("Could not clear a previous model backup.")

        val hadPreviousModel = target.exists()
        if (hadPreviousModel) Files.move(target.toPath(), backup.toPath(), REPLACE_EXISTING)
        try {
            Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
            if (backup.exists()) Files.delete(backup.toPath())
        } catch (failure: Exception) {
            if (hadPreviousModel && backup.exists()) {
                try {
                    Files.move(backup.toPath(), target.toPath(), REPLACE_EXISTING)
                } catch (restoreFailure: Exception) {
                    failure.addSuppressed(restoreFailure)
                }
            }
            throw failure
        }
    }

    private fun sizeLimitMessage(
        neededOrCopiedBytes: Long,
        copyLimit: Long,
        maxBytes: Long,
        expectedBytes: Long?,
    ): String {
        val neededBytes = expectedBytes ?: neededOrCopiedBytes
        return if (copyLimit < maxBytes) {
            val existingNote = if (modelFile.exists() && modelFile.length() > 0L) {
                " Remove the current stored model first to free ${formatStorageSize(modelFile.length())}."
            } else {
                ""
            }
            "Not enough free storage (need ${formatStorageSize(neededBytes)}, only ${formatStorageSize(copyLimit)} safely available).$existingNote"
        } else {
            val limitLabel = if (maxBytes >= 1024L * 1024 * 1024 && maxBytes % (1024L * 1024 * 1024) == 0L) {
                "${maxBytes / (1024 * 1024 * 1024)} GB"
            } else {
                formatStorageSize(maxBytes)
            }
            "Model exceeds the $limitLabel import limit. Choose a smaller compatible .litertlm model."
        }
    }

    private fun formatStorageSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1024 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "${bytes / (1024 * 1024)} MB"
        bytes >= 1024L -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /** Removes the stored model file and any partial/backup artifacts from private storage. */
    fun removeStoredModel(): Boolean {
        var ok = true
        listOf(
            modelFile,
            File(directory, "$MODEL_FILE_NAME.part"),
            File(directory, "$MODEL_FILE_NAME.backup"),
        ).forEach { f ->
            if (f.exists() && !f.delete()) ok = false
        }
        return ok
    }

    companion object {
        /** Clears LiteRT-LM/engine cache files so the next load rebuilds them cleanly. */
        @JvmStatic
        fun clearEngineCaches(cacheDir: File) {
            if (!cacheDir.exists() || !cacheDir.isDirectory) return
            cacheDir.listFiles()?.forEach { child ->
                if (child.name.startsWith("pocketagent")) return@forEach
                try {
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                } catch (_: Exception) {
                }
            }
        }

        const val MODEL_FILE_NAME = "model.litertlm"
        const val DEFAULT_MAX_MODEL_BYTES = 5L * 1024 * 1024 * 1024
        const val DEFAULT_RESERVED_FREE_SPACE_BYTES = 32L * 1024 * 1024
        const val MIN_MODEL_BYTES = 1L * 1024 * 1024
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val PROGRESS_INTERVAL_BYTES = 8L * 1024 * 1024
        private const val HEADER_PEEK_BYTES = 64

        /**
         * Basic sanity check for .litertlm files. Current LiteRT-LM bundles are commonly
         * zip-based (PK header) or binary flatbuffer/TFLite-like files. Inspecting up to 64
         * header bytes avoids rejecting valid binary models whose first 4 bytes are printable
         * ASCII (e.g. "TFL3" or "LTLM") while still rejecting pure ASCII text files.
         */
        internal fun looksLikeLitertLm(header: ByteArray, read: Int): Boolean {
            val inspectCount = minOf(read, header.size)
            if (inspectCount < 4) return false
            val b0 = header[0].toInt() and 0xff
            val b1 = header[1].toInt() and 0xff
            // ZIP / PK
            if (b0 == 0x50 && b1 == 0x4B) return true
            // Accept binary headers (including FlatBuffer/TFLite identifiers) and reject pure ASCII text.
            val isAsciiText = (0 until inspectCount).all { index ->
                val v = header[index].toInt() and 0xff
                v == 0x09 || v == 0x0A || v == 0x0D || v in 0x20..0x7E
            }
            return !isAsciiText
        }
    }
}
