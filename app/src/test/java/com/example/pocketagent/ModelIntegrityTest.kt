package com.example.pocketagent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelIntegrityTest {
    @Test
    fun sha256HexMatchesKnownVector() = assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        ModelIntegrity.sha256Hex("abc".toByteArray()),
    )

    @Test
    fun acceptsLowercaseSha256Hex() = assertTrue(ModelIntegrity.isValidHex("a".repeat(64)))

    @Test
    fun rejectsMalformedHex() {
        assertFalse(ModelIntegrity.isValidHex("xyz"))
        assertFalse(ModelIntegrity.isValidHex("A".repeat(64)))
        assertFalse(ModelIntegrity.isValidHex("a".repeat(63)))
    }

    @Test
    fun sidecarNameIsDerivedFromModelName() = assertEquals(
        "model.litertlm.sha256",
        ModelIntegrity.sidecarFor(File("dir/model.litertlm")).name,
    )

    @Test
    fun sha256HexStreamsLargeFilesWithoutLoadingThemWhole() {
        val temp = File.createTempFile("pockagent-integrity", ".bin")
        try {
            temp.writeBytes(ByteArray(300_000) { (it % 7).toByte() })
            assertEquals(ModelIntegrity.sha256Hex(temp.readBytes()), ModelIntegrity.sha256Hex(temp))
        } finally {
            temp.delete()
        }
    }
}
