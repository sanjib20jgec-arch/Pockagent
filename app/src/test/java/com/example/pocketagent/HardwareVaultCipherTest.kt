package com.example.pocketagent

import java.io.File
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HardwareVaultCipherTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun generateAesKey(): SecretKey {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        return generator.generateKey()
    }

    @Test
    fun roundTripAsciiAndBengaliUtf8() {
        val key = generateAesKey()
        val cipher = AesGcmCipher { key }
        val plainText = "PocketAgent vault test: আমি বাংলায় গান গাই, café & 12345!"

        val encrypted1 = cipher.encrypt(plainText)
        val encrypted2 = cipher.encrypt(plainText)

        assertTrue(encrypted1.startsWith(ENCRYPTED_PREFIX))
        assertTrue(encrypted2.startsWith(ENCRYPTED_PREFIX))
        assertNotEquals("Each encryption should use a fresh random IV", encrypted1, encrypted2)
        assertFalse(encrypted1.contains("PocketAgent"))

        assertEquals(plainText, cipher.decrypt(encrypted1))
        assertEquals(plainText, cipher.decrypt(encrypted2))
    }

    @Test
    fun legacyPlaintextPassesThroughUnchanged() {
        val key = generateAesKey()
        val cipher = AesGcmCipher { key }
        val legacyJson = """{"id":"sess-1","title":"Legacy session"}"""

        assertEquals(legacyJson, cipher.decrypt(legacyJson))
    }

    @Test
    fun corruptedEncryptedPayloadThrowsVaultDecryptionException() {
        val key = generateAesKey()
        val otherKey = generateAesKey()
        val cipher = AesGcmCipher { key }
        val wrongKeyCipher = AesGcmCipher { otherKey }

        val encrypted = cipher.encrypt("sensitive-data")

        // Wrong key should fail GCM authentication tag verification
        assertThrows(VaultDecryptionException::class.java) {
            wrongKeyCipher.decrypt(encrypted)
        }

        // Tampered ciphertext byte should fail GCM authentication
        val rawBytes = Base64.getDecoder().decode(encrypted.removePrefix(ENCRYPTED_PREFIX))
        rawBytes[rawBytes.lastIndex] = (rawBytes[rawBytes.lastIndex].toInt() xor 0xFF).toByte()
        val tampered = ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(rawBytes)
        assertThrows(VaultDecryptionException::class.java) {
            cipher.decrypt(tampered)
        }

        // Invalid Base64 should fail cleanly
        assertThrows(VaultDecryptionException::class.java) {
            cipher.decrypt("${ENCRYPTED_PREFIX}not-valid-base64!!!")
        }

        // Truncated payload (too short for IV + tag) should fail cleanly
        val tooShort = ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(ByteArray(10))
        assertThrows(VaultDecryptionException::class.java) {
            cipher.decrypt(tooShort)
        }
    }

    @Test
    fun sessionStoreAndJsonArrayFileStoreEncryptAndMigrateLegacyPlaintext() {
        val key = generateAesKey()
        val cipher = AesGcmCipher { key }

        // 1. SessionStore encrypted round-trip + legacy plaintext migration
        val sessionsDir = tempDir.newFolder("encrypted_sessions")
        val legacyStore = SessionStore(sessionsDir)
        val legacySession = ConversationSession(
            id = "legacy-1",
            title = "Legacy Plaintext",
            createdAt = 1000L,
            updatedAt = 1000L,
            messages = listOf(
                ChatMessage(id = "m1", role = ChatMessage.Role.USER, content = "হ্যালো", timestamp = 1000L)
            ),
        )
        legacyStore.saveSession(legacySession)

        val encryptedStore = SessionStore(sessionsDir, vaultCipher = cipher)
        val migratedRead = encryptedStore.getSession("legacy-1")
        assertNotNull(migratedRead)
        assertEquals("হ্যালো", migratedRead!!.messages.first().content)

        val newSession = ConversationSession(
            id = "enc-2",
            title = "Encrypted Session",
            createdAt = 2000L,
            updatedAt = 2000L,
            messages = listOf(
                ChatMessage(id = "m2", role = ChatMessage.Role.USER, content = "Secret বাংলা", timestamp = 2000L)
            ),
        )
        val encFile = encryptedStore.saveSession(newSession)
        val rawOnDisk = encFile.readText(Charsets.UTF_8)
        assertTrue(rawOnDisk.startsWith(ENCRYPTED_PREFIX))
        assertFalse(rawOnDisk.contains("Secret"))

        val loadedEnc = encryptedStore.getSession("enc-2")
        assertNotNull(loadedEnc)
        assertEquals("Secret বাংলা", loadedEnc!!.messages.first().content)

        // 2. JsonArrayFileStore encrypted write & read
        val arrayFile = File(tempDir.root, "items.json")
        val arrayTmp = File(tempDir.root, "items.tmp")
        val json = """[{"id":"1","text":"আমার সোনার বাংলা"}]"""
        assertTrue(JsonArrayFileStore.writeAtomic(arrayFile, arrayTmp, json, cipher))
        assertTrue(arrayFile.readText(Charsets.UTF_8).startsWith(ENCRYPTED_PREFIX))
        assertEquals(json, JsonArrayFileStore.readText(arrayFile, cipher))
        assertEquals(1, JsonArrayFileStore.splitObjects(arrayFile.readText(Charsets.UTF_8), cipher).size)
    }
}
