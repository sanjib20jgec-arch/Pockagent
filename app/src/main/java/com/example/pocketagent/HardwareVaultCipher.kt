package com.example.pocketagent

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

const val ENCRYPTED_PREFIX = "ENCv1:"

/**
 * Abstraction for encrypting and decrypting persisted plaintext payloads.
 */
interface VaultCipher {
    fun encrypt(plainText: String): String
    fun decrypt(payload: String): String
}

/**
 * Thrown when an encrypted vault payload cannot be authenticated or decrypted.
 */
class VaultDecryptionException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * AES-256-GCM cipher using a 12-byte IV and 128-bit authentication tag.
 *
 * Uses [java.util.Base64] so it works identically in JVM unit tests and on Android (minSdk 28+).
 */
class AesGcmCipher(
    private val keyProvider: () -> SecretKey,
) : VaultCipher {

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = cipher.iv
        require(iv != null && iv.size == GCM_IV_LENGTH_BYTES) {
            "Unexpected GCM IV length: ${iv?.size ?: 0}"
        }
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
        return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(combined)
    }

    override fun decrypt(payload: String): String {
        if (!payload.startsWith(ENCRYPTED_PREFIX)) {
            return payload
        }
        val encoded = payload.removePrefix(ENCRYPTED_PREFIX)
        return try {
            val combined = Base64.getDecoder().decode(encoded)
            if (combined.size <= GCM_IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES) {
                throw VaultDecryptionException("Encrypted payload is truncated or malformed.")
            }
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val cipherBytes = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyProvider(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
            )
            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: VaultDecryptionException) {
            throw e
        } catch (e: AEADBadTagException) {
            throw VaultDecryptionException("AES-GCM authentication failed for encrypted payload.", e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw VaultDecryptionException("Hardware vault key was permanently invalidated.", e)
        } catch (e: Exception) {
            throw VaultDecryptionException("Failed to decrypt encrypted payload.", e)
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
    }
}

/**
 * Hardware-backed [VaultCipher] using AndroidKeyStore (StrongBox when available, TEE fallback).
 */
object HardwareVaultCipher : VaultCipher {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "pocketagent_vault_master_v1"
    private const val KEY_SIZE_BITS = 256

    @Volatile
    private var cachedKey: SecretKey? = null

    private val delegate = AesGcmCipher { getOrCreateSecretKey() }

    override fun encrypt(plainText: String): String = delegate.encrypt(plainText)

    override fun decrypt(payload: String): String = delegate.decrypt(payload)

    fun readTextSafe(file: File, cipher: VaultCipher = this): String {
        if (!file.exists() || !file.isFile) return ""
        val raw = file.readText(Charsets.UTF_8)
        return cipher.decrypt(raw)
    }

    @Throws(IOException::class)
    fun writeTextAtomic(file: File, plainText: String, cipher: VaultCipher = this) {
        val encrypted = cipher.encrypt(plainText)
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create directory: ${parent.absolutePath}")
        }
        val temporary = File(parent, "${file.name}.tmp")
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("Could not clear previous temporary file: ${temporary.name}")
        }
        var committed = false
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(encrypted.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
            }
            committed = true
        } finally {
            if (!committed && temporary.exists()) {
                temporary.delete()
            }
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) {
                cachedKey = existing
                return existing
            }
            val created = generateSecretKey()
            cachedKey = created
            return created
        }
    }

    private fun generateSecretKey(): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateKeyWithSpec(useStrongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                // Fall back to TEE-backed keystore when StrongBox is unavailable.
            } catch (_: ProviderException) {
                // Some devices throw ProviderException instead of StrongBoxUnavailableException.
            }
        }
        return generateKeyWithSpec(useStrongBox = false)
    }

    private fun generateKeyWithSpec(useStrongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)

        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }
}
