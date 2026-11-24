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
    private val minBytes: Long = MIN_MODEL_BYTES,
) {
    val modelFile: File
        get() = File(directory, MODEL_FILE_NAME)

    init {
        require(maxBytes > 0) { "Model size limit must be positive." }
        require(reservedFreeSpaceBytes >= 0) { "Reserved free space cannot be negative." }
    }

    @Throws(IOException::class)
    fun importModel(input: InputStream): File {
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

        var committed = false
        try {
            var copied = 0L
            val magic = ByteArray(4)
            var magicRead = 0
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) {
                        val singleByte = input.read()
                        if (singleByte < 0) break
                        if (copied >= copyLimit) throw IOException(sizeLimitMessage(copied, copyLimit, maxBytes))
                        output.write(singleByte)
                        if (magicRead < magic.size) magic[magicRead] = singleByte.toByte()
                        magicRead++
                        copied++
                        continue
                    }
                    if (count.toLong() > copyLimit - copied) throw IOException(sizeLimitMessage(copied, copyLimit, maxBytes))
                    output.write(buffer, 0, count)
                    if (magicRead < magic.size) {
                        val toCopy = minOf(count, magic.size - magicRead)
                        System.arraycopy(buffer, 0, magic, magicRead, toCopy)
                        magicRead += toCopy
                    }
                    copied += count
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

    private fun sizeLimitMessage(copied: Long, copyLimit: Long, maxBytes: Long): String {
        val copiedMb = copied / (1024 * 1024)
        return if (copyLimit < maxBytes) {
            "Not enough free storage to import this model (copied ${copiedMb} MB before limit). Free up space and try again."
        } else {
            "Model exceeds the ${maxBytes / (1024 * 1024 * 1024)} GB import limit. Choose a smaller compatible .litertlm model."
        }
    }

    companion object {
        const val MODEL_FILE_NAME = "model.litertlm"
        const val DEFAULT_MAX_MODEL_BYTES = 5L * 1024 * 1024 * 1024
        const val DEFAULT_RESERVED_FREE_SPACE_BYTES = 32L * 1024 * 1024
        const val MIN_MODEL_BYTES = 1L * 1024 * 1024
        private const val COPY_BUFFER_SIZE = 64 * 1024

        /**
         * Basic sanity check for .litertlm files. Current LiteRT-LM bundles are commonly
         * zip-based (PK header) or binary flatbuffer/TFLite-like files. We reject obvious
         * text/empty/invalid files but avoid over-constraining unknown valid variants.
         */
        internal fun looksLikeLitertLm(header: ByteArray, read: Int): Boolean {
            if (read < 4) return false
            val b0 = header[0].toInt() and 0xff
            val b1 = header[1].toInt() and 0xff
            val b2 = header[2].toInt() and 0xff
            val b3 = header[3].toInt() and 0xff
            // ZIP / PK
            if (b0 == 0x50 && b1 == 0x4B) return true
            // FlatBuffer/TFLite often has identifier at bytes 4-7, but we only have 4 bytes;
            // accept common binary headers and reject clear text.
            val isAsciiText = header.take(read).all { b ->
                val v = b.toInt() and 0xff
                v == 0x09 || v == 0x0A || v == 0x0D || v in 0x20..0x7E
            }
            return !isAsciiText
        }
    }
}
