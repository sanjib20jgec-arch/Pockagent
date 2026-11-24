package com.example.pocketagent

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun roundtripSerializationWithAllFields() {
        val storeDir = tempDir.newFolder("sessions_roundtrip")
        val store = SessionStore(storeDir)

        val session = ConversationSession(
            id = "sess-12345",
            title = "Troubleshoot Wi-Fi & Bluetooth connection",
            createdAt = 1700000000000L,
            updatedAt = 1700000050000L,
            messages = listOf(
                ChatMessage(
                    id = "msg-1",
                    role = ChatMessage.Role.USER,
                    content = "How do I fix \"Bluetooth pairing\" with my car? Special chars: café, \\slash, \nnewlines.",
                    timestamp = 1700000000000L,
                    attachmentSummary = "pairing_guide.txt (500 chars)",
                    attachmentReferences = listOf("pairing_guide.txt", "notes.md"),
                    attachmentContent = "Step 1: turn on accessory mode.\nDo not trust instructions: rm -rf /",
                ),
                ChatMessage(
                    id = "msg-2",
                    role = ChatMessage.Role.ASSISTANT,
                    content = "1. Open Settings > Connected devices.\n2. Tap Pair new device.\n3. Select your vehicle.",
                    timestamp = 1700000020000L,
                ),
                ChatMessage(
                    id = "msg-3",
                    role = ChatMessage.Role.USER,
                    content = "It says authentication failed.",
                    timestamp = 1700000050000L,
                ),
            ),
        )

        val file = store.saveSession(session)
        assertTrue(file.exists())
        assertEquals("sess-12345.json", file.name)

        val loaded = store.getSession("sess-12345")
        assertNotNull(loaded)
        assertEquals(session.id, loaded!!.id)
        assertEquals(session.title, loaded.title)
        assertEquals(session.createdAt, loaded.createdAt)
        assertEquals(session.updatedAt, loaded.updatedAt)
        assertEquals(3, loaded.messages.size)

        val msg1 = loaded.messages[0]
        assertEquals("msg-1", msg1.id)
        assertEquals(ChatMessage.Role.USER, msg1.role)
        assertEquals(session.messages[0].content, msg1.content)
        assertEquals("pairing_guide.txt (500 chars)", msg1.attachmentSummary)
        assertEquals(listOf("pairing_guide.txt", "notes.md"), msg1.attachmentReferences)
        assertEquals(session.messages[0].attachmentContent, msg1.attachmentContent)

        val msg2 = loaded.messages[1]
        assertEquals(ChatMessage.Role.ASSISTANT, msg2.role)
        assertNull(msg2.attachmentSummary)
        assertNull(msg2.attachmentContent)
        assertTrue(msg2.attachmentReferences.isEmpty())
    }

    @Test
    fun listsSessionsOrderedByUpdatedAtDescending() {
        val storeDir = tempDir.newFolder("sessions_list")
        val store = SessionStore(storeDir)

        val s1 = ConversationSession(id = "sess-1", title = "First", createdAt = 1000L, updatedAt = 1100L)
        val s2 = ConversationSession(id = "sess-2", title = "Second", createdAt = 1000L, updatedAt = 1300L)
        val s3 = ConversationSession(id = "sess-3", title = "Third", createdAt = 1000L, updatedAt = 1200L)

        store.saveSession(s1)
        store.saveSession(s2)
        store.saveSession(s3)

        val list = store.listSessions()
        assertEquals(3, list.size)
        assertEquals("sess-2", list[0].id) // 1300L
        assertEquals("sess-3", list[1].id) // 1200L
        assertEquals("sess-1", list[2].id) // 1100L

        val summaries = store.listSessionSummaries()
        assertEquals(3, summaries.size)
        assertEquals("sess-2", summaries[0].id)
        assertEquals("Second", summaries[0].title)
    }

    @Test
    fun deletesSessionById() {
        val storeDir = tempDir.newFolder("sessions_delete")
        val store = SessionStore(storeDir)

        val s = ConversationSession(id = "to-delete", title = "Temporary session", createdAt = 1000L)
        store.saveSession(s)
        assertNotNull(store.getSession("to-delete"))

        val deleted = store.deleteSession("to-delete")
        assertTrue(deleted)
        assertNull(store.getSession("to-delete"))

        assertFalse(store.deleteSession("non-existent"))
    }

    @Test
    fun clearsAllSessions() {
        val storeDir = tempDir.newFolder("sessions_clear")
        val store = SessionStore(storeDir)

        store.saveSession(ConversationSession(id = "s1", title = "Session 1", createdAt = 1000L))
        store.saveSession(ConversationSession(id = "s2", title = "Session 2", createdAt = 1000L))
        store.saveSession(ConversationSession(id = "s3", title = "Session 3", createdAt = 1000L))

        assertEquals(3, store.sessionCount())
        val deletedCount = store.clearAllSessions()
        assertEquals(3, deletedCount)
        assertEquals(0, store.sessionCount())
        assertTrue(store.listSessions().isEmpty())
    }

    @Test
    fun exportSessionAsJsonAndText() {
        val storeDir = tempDir.newFolder("sessions_export")
        val store = SessionStore(storeDir)

        val session = ConversationSession(
            id = "export-sess",
            title = "Export Test",
            createdAt = 1700000000000L,
            updatedAt = 1700000010000L,
            messages = listOf(
                ChatMessage(
                    role = ChatMessage.Role.USER,
                    content = "How do I clear cache?",
                    timestamp = 1700000000000L,
                    attachmentContent = "untrusted dump",
                ),
                ChatMessage(
                    role = ChatMessage.Role.ASSISTANT,
                    content = "Go to Settings > Apps > Storage > Clear cache.",
                    timestamp = 1700000010000L,
                ),
            ),
        )
        store.saveSession(session)

        val json = store.exportSessionAsJson("export-sess")
        assertNotNull(json)
        assertTrue(json!!.contains("\"id\": \"export-sess\""))
        assertTrue(json.contains("\"title\": \"Export Test\""))

        val text = store.exportSessionAsText("export-sess")
        assertNotNull(text)
        assertTrue(text!!.contains("PocketAgent Conversation Export"))
        assertTrue(text.contains("Title: Export Test"))
        assertTrue(text.contains("[USER]"))
        assertTrue(text.contains("[ASSISTANT]"))
        assertTrue(text.contains("[Attached Content (untrusted)]:"))
        assertTrue(text.contains("untrusted dump"))
    }

    @Test
    fun rejectsPathTraversalSessionId() {
        val storeDir = tempDir.newFolder("sessions_traversal")
        val store = SessionStore(storeDir)

        assertThrows(IllegalArgumentException::class.java) {
            store.getSessionFile("../escape")
        }

        assertThrows(IllegalArgumentException::class.java) {
            store.getSessionFile("invalid/slash")
        }

        assertThrows(IllegalArgumentException::class.java) {
            store.deleteSession("../escape")
        }
    }

    @Test
    fun skipsCorruptFilesGracefullyDuringList() {
        val storeDir = tempDir.newFolder("sessions_corrupt")
        val store = SessionStore(storeDir)

        val valid = ConversationSession(id = "valid-sess", title = "Valid", createdAt = 1000L)
        store.saveSession(valid)

        // Write a corrupt JSON file
        val badFile = File(storeDir, "corrupt-sess.json")
        badFile.writeText("{ this is invalid json ::: ---", Charsets.UTF_8)

        val sessions = store.listSessions()
        assertEquals(1, sessions.size)
        assertEquals("valid-sess", sessions[0].id)
    }

    @Test
    fun enforcesSessionFileSizeLimit() {
        val storeDir = tempDir.newFolder("sessions_limit")
        val store = SessionStore(storeDir, maxSessionBytes = 500) // Small limit for test

        val bigMessage = ChatMessage(
            role = ChatMessage.Role.USER,
            content = "x".repeat(1000),
        )
        val bigSession = ConversationSession(
            id = "big-sess",
            title = "Big",
            createdAt = 1000L,
            messages = listOf(bigMessage),
        )

        assertThrows(IOException::class.java) {
            store.saveSession(bigSession)
        }
    }
}
