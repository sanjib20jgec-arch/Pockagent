package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PrivacyAuditCenterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        cacheDir = tempFolder.newFolder("cache")
    }

    @Test
    fun `auditStorage calculates accurate directory sizes`() {
        // Create mock model files
        val model = File(filesDir, "model.litertlm").apply { writeBytes(ByteArray(2048)) }
        val whisper = File(filesDir, "whisper-model.bin").apply { writeBytes(ByteArray(1024)) }

        // Create mock sessions
        val sessionsDir = File(filesDir, "sessions").apply { mkdirs() }
        File(sessionsDir, "sess1.json").writeBytes(ByteArray(512))

        // Create mock memories
        val memoriesDir = File(filesDir, "memories").apply { mkdirs() }
        File(memoriesDir, "user_memories.json").writeBytes(ByteArray(256))

        // Create mock notes
        val notesDir = File(filesDir, "notes").apply { mkdirs() }
        File(notesDir, "personal_notes.json").writeBytes(ByteArray(128))

        // Create mock cache
        File(cacheDir, "temp.wav").writeBytes(ByteArray(100))

        val audit = PrivacyAuditCenter.auditStorage(filesDir, cacheDir)

        assertEquals(3072L, audit.modelBytes)
        assertEquals(512L, audit.sessionsBytes)
        assertEquals(256L, audit.memoriesBytes)
        assertEquals(128L, audit.notesBytes)
        assertEquals(100L, audit.cacheBytes)
        assertEquals(4068L, audit.totalBytes)
    }

    @Test
    fun `wipeAllUserData clears personal data but preserves models by default`() {
        val model = File(filesDir, "model.litertlm").apply { writeBytes(ByteArray(1024)) }
        val sessionsDir = File(filesDir, "sessions").apply { mkdirs() }
        val sessFile = File(sessionsDir, "sess1.json").apply { writeBytes(ByteArray(512)) }

        val memoriesDir = File(filesDir, "memories").apply { mkdirs() }
        val memFile = File(memoriesDir, "mem.json").apply { writeBytes(ByteArray(256)) }

        val success = PrivacyAuditCenter.wipeAllUserData(filesDir, cacheDir, purgeModels = false)
        assertTrue(success)

        assertTrue(model.exists()) // Model preserved
        assertFalse(sessFile.exists())
        assertFalse(memFile.exists())
    }

    @Test
    fun `wipeAllUserData purges models when requested`() {
        val model = File(filesDir, "model.litertlm").apply { writeBytes(ByteArray(1024)) }
        val whisper = File(filesDir, "whisper-model.bin").apply { writeBytes(ByteArray(512)) }

        val success = PrivacyAuditCenter.wipeAllUserData(filesDir, cacheDir, purgeModels = true)
        assertTrue(success)

        assertFalse(model.exists())
        assertFalse(whisper.exists())
    }

    @Test
    fun `wipeAllUserData removes staged and backup artifacts`() {
        val artifacts = listOf(
            "model.litertlm.part",
            "model.litertlm.backup",
            "whisper-model.bin.part",
            "whisper-model.bin.backup",
        ).map { name -> File(filesDir, name).apply { writeBytes(ByteArray(128)) } }

        val success = PrivacyAuditCenter.wipeAllUserData(filesDir, cacheDir, purgeModels = false)
        assertTrue(success)
        artifacts.forEach { assertFalse(it.exists()) }
    }

    @Test
    fun `formatBytes returns clean formatted units`() {
        assertEquals("500 B", PrivacyAuditCenter.StorageAudit.formatBytes(500))
        assertEquals("10 KB", PrivacyAuditCenter.StorageAudit.formatBytes(10 * 1024))
        assertEquals("2.5 MB", PrivacyAuditCenter.StorageAudit.formatBytes((2.5 * 1024 * 1024).toLong()))
        assertEquals("1.5 GB", PrivacyAuditCenter.StorageAudit.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }
}
