package com.example.pocketagent

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** SHA-256 sidecar helpers for user-supplied models. No allow-list and no network access. */
internal object ModelIntegrity {
    const val SIDECAR_SUFFIX: String = ".sha256"

    private const val STREAM_BUFFER_SIZE = 256 * 1024
    private val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")

    fun sha256Hex(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Streams the file in bounded chunks so multi-gigabyte models are never held in memory. */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return toHex(digest.digest())
    }

    fun isValidHex(hex: String): Boolean = LOWERCASE_SHA256.matches(hex)

    fun sidecarFor(model: File): File = File(model.path + SIDECAR_SUFFIX)

    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { value ->
            val unsigned = value.toInt() and 0xff
            append(Character.forDigit(unsigned shr 4, 16))
            append(Character.forDigit(unsigned and 0x0f, 16))
        }
    }
}
