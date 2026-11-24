package com.example.pocketagent

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Copies a selected model transactionally, preserving the last working file if import fails. */
internal class ModelFileStore(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_MODEL_BYTES,
    private val reservedFreeSpaceBytes: Long = DEFAULT_RESERVED_FREE_SPACE_BYTES,
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

        if (expectedBytes != null && expectedBytes > 0L && expectedBytes > copyLimit) {
            throw IOException(sizeLimitMessage(0L, copyLimit, maxBytes, expectedBytes))
        }

        var committed = false
        try {
            var copied = 0L
            var lastReported = 0L
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) {
                        val singleByte = input.read()
                        if (singleByte < 0) break
                        if (copied >= copyLimit) throw IOException(sizeLimitMessage(copied, copyLimit, maxBytes, expectedBytes))
                        output.write(singleByte)
                        copied++
                    } else {
                        if (count.toLong() > copyLimit - copied) {
                            throw IOException(sizeLimitMessage(copied, copyLimit, maxBytes, expectedBytes))
                        }
                        output.write(buffer, 0, count)
                        copied += count
                    }
                    if (onProgress != null && copied - lastReported >= PROGRESS_INTERVAL_BYTES) {
                        lastReported = copied
                        onProgress.invoke(copied, expectedBytes)
                    }
                }
                if (copied == 0L) throw IOException("The selected model file is empty.")
                onProgress?.invoke(copied, expectedBytes ?: copied)
                output.fd.sync()
            }

            replaceCurrentModel(temporary, modelFile)
            committed = true
            return modelFile
        } finally {
            if (!committed && temporary.exists()) temporary.delete()
        }
    }

    /** Removes the stored model and any incomplete import/backup artifacts cleanly. */
    fun removeStoredModel(): Boolean {
        var ok = true
        listOf(
            modelFile,
            File(directory, "$MODEL_FILE_NAME.part"),
            File(directory, "$MODEL_FILE_NAME.backup"),
        ).forEach { file ->
            if (file.exists() && !file.delete()) ok = false
        }
        return ok
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
        copied: Long,
        copyLimit: Long,
        maxBytes: Long,
        expectedBytes: Long?,
    ): String {
        val neededBytes = expectedBytes ?: copied
        return if (copyLimit < maxBytes) {
            val existingNote = if (modelFile.exists() && modelFile.length() > 0L) {
                " Remove the current stored model first to free ${formatGbOrMb(modelFile.length())}."
            } else {
                ""
            }
            "Not enough free storage (need ${formatGbOrMb(neededBytes)}, only ${formatGbOrMb(copyLimit)} safely available).$existingNote"
        } else {
            "Model exceeds the ${maxBytes / (1024 * 1024 * 1024)} GB import limit. Choose a smaller compatible .litertlm model."
        }
    }

    private fun formatGbOrMb(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", bytes.toDouble() / (1024 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / 1024} KB"
    }

    companion object {
        const val MODEL_FILE_NAME = "model.litertlm"
        const val DEFAULT_MAX_MODEL_BYTES = 5L * 1024 * 1024 * 1024
        const val DEFAULT_RESERVED_FREE_SPACE_BYTES = 128L * 1024 * 1024
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val PROGRESS_INTERVAL_BYTES = 16L * 1024 * 1024

        /** Removes stale LiteRT/OpenCL/XNNPACK compiled cache files on model switch or wipe. */
        fun clearEngineCaches(cacheDir: File) {
            cacheDir.listFiles()?.forEach { entry ->
                val name = entry.name
                if (!name.startsWith("pocketagent-")) {
                    entry.deleteRecursively()
                }
            }
        }
    }
}
