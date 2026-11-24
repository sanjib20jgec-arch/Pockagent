package com.example.pocketagent

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Stages a user-selected ggml model and commits only after native loading succeeds. */
internal class WhisperModelStore(
    private val directory: File,
    private val maxBytes: Long = MAX_MODEL_BYTES,
    private val reservedFreeSpaceBytes: Long = RESERVED_FREE_SPACE_BYTES,
) {
    val modelFile: File get() = File(directory, MODEL_FILE_NAME)
    val candidateFile: File get() = File(directory, CANDIDATE_FILE_NAME)

    init {
        require(maxBytes > 0) { "Whisper model size limit must be positive." }
        require(reservedFreeSpaceBytes >= 0) { "Reserved free space cannot be negative." }
    }

    fun stage(input: InputStream): File {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not create private model storage.")
        if (!directory.isDirectory) throw IOException("Private model storage is not a directory.")
        if (candidateFile.exists() && !candidateFile.delete()) throw IOException("Could not clear an earlier incomplete model import.")

        val availableAtStart = directory.usableSpace
        val copyLimit = if (availableAtStart > 0L) {
            minOf(maxBytes, (availableAtStart - reservedFreeSpaceBytes).coerceAtLeast(0L))
        } else maxBytes

        var complete = false
        try {
            var copied = 0L
            input.use { source ->
                FileOutputStream(candidateFile).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) {
                            val single = source.read()
                            if (single < 0) break
                            if (copied >= copyLimit) throw IOException(limitMessage())
                            output.write(single)
                            copied++
                            continue
                        }
                        if (count.toLong() > copyLimit - copied) throw IOException(limitMessage())
                        output.write(buffer, 0, count)
                        copied += count
                    }
                    if (copied == 0L) throw IOException("The selected Whisper model is empty.")
                    output.fd.sync()
                }
            }
            validateGgmlHeader(candidateFile)
            complete = true
            return candidateFile
        } finally {
            if (!complete) candidateFile.delete()
        }
    }

    fun commitCandidate(): File {
        if (!candidateFile.isFile || candidateFile.length() == 0L) {
            throw IOException("No staged Whisper model is available to commit.")
        }
        try {
            Files.move(candidateFile.toPath(), modelFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            replaceWithRollback()
        }
        return modelFile
    }

    fun discardCandidate() {
        if (candidateFile.exists()) candidateFile.delete()
    }

    private fun replaceWithRollback() {
        val backup = File(directory, BACKUP_FILE_NAME)
        if (backup.exists() && !backup.delete()) throw IOException("Could not clear the previous model backup.")
        val hadPrevious = modelFile.exists()
        if (hadPrevious) Files.move(modelFile.toPath(), backup.toPath(), REPLACE_EXISTING)
        try {
            Files.move(candidateFile.toPath(), modelFile.toPath(), REPLACE_EXISTING)
            if (backup.exists()) Files.delete(backup.toPath())
        } catch (failure: Exception) {
            if (hadPrevious && backup.exists()) {
                try {
                    Files.move(backup.toPath(), modelFile.toPath(), REPLACE_EXISTING)
                } catch (restoreFailure: Exception) {
                    failure.addSuppressed(restoreFailure)
                }
            }
            throw failure
        }
    }

    private fun validateGgmlHeader(file: File) {
        val magic = file.inputStream().use { input ->
            val bytes = ByteArray(4)
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) throw IOException("Selected file is too short to be a ggml model.")
                if (count == 0) continue
                offset += count
            }
            (bytes[0].toInt() and 0xff) or
                ((bytes[1].toInt() and 0xff) shl 8) or
                ((bytes[2].toInt() and 0xff) shl 16) or
                ((bytes[3].toInt() and 0xff) shl 24)
        }
        if (magic != GGML_FILE_MAGIC) throw IOException("Selected file does not have the whisper.cpp ggml model signature.")
    }

    private fun limitMessage() = "Whisper model exceeds the 512 MB limit or available storage."

    companion object {
        const val MAX_MODEL_BYTES = 512L * 1024 * 1024
        const val MODEL_FILE_NAME = "whisper-model.bin"
        const val CANDIDATE_FILE_NAME = "$MODEL_FILE_NAME.part"
        const val RESERVED_FREE_SPACE_BYTES = 64L * 1024 * 1024
        private const val GGML_FILE_MAGIC = 0x67676d6c
        private const val BACKUP_FILE_NAME = "$MODEL_FILE_NAME.backup"
        private const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
