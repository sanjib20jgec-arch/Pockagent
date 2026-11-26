package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeDrawerHelperTest {

    private fun sampleSession(
        id: String,
        title: String,
        updatedAt: Long = 1_700_000_000_000L,
        messages: List<ChatMessage> = emptyList(),
        isPinned: Boolean = false,
    ): ConversationSession = ConversationSession(
        id = id,
        title = title,
        createdAt = 1_699_000_000_000L,
        updatedAt = updatedAt,
        messages = messages,
        isPinned = isPinned,
    )

    @Test
    fun filterSessionsReturnsAllSessionsWhenQueryIsBlank() {
        val sessions = listOf(
            sampleSession("s1", "Android Architecture"),
            sampleSession("s2", "Offline RAG Pipeline"),
        )

        assertEquals(sessions, filterSessions(sessions, ""))
        assertEquals(sessions, filterSessions(sessions, "   \t\n "))
    }

    @Test
    fun filterSessionsMatchesTitleCaseInsensitivelyAndTrimsQuery() {
        val s1 = sampleSession("s1", "Android Architecture")
        val s2 = sampleSession("s2", "Offline RAG Pipeline")
        val s3 = sampleSession("s3", "Grocery Checklist")
        val sessions = listOf(s1, s2, s3)

        assertEquals(listOf(s2), filterSessions(sessions, "  rag "))
        assertEquals(listOf(s1), filterSessions(sessions, "ANDROID"))
        assertTrue(filterSessions(sessions, "nonexistent").isEmpty())
    }

    @Test
    fun filterSessionsMatchesMessageContentWhenTitleDoesNotMatch() {
        val s1 = sampleSession(
            id = "s1",
            title = "General Chat",
            messages = listOf(
                ChatMessage(
                    role = ChatMessage.Role.USER,
                    content = "Explain LiteRT-LM quantization",
                    timestamp = 1_700_000_000_000L,
                ),
            ),
        )
        val s2 = sampleSession(
            id = "s2",
            title = "Weekend Notes",
            messages = listOf(
                ChatMessage(
                    role = ChatMessage.Role.ASSISTANT,
                    content = "Pack hiking boots",
                    timestamp = 1_700_000_000_000L,
                ),
            ),
        )

        assertEquals(listOf(s1), filterSessions(listOf(s1, s2), "quantization"))
        assertEquals(listOf(s2), filterSessions(listOf(s1, s2), "BOOTS"))
    }

    @Test
    fun formatRelativeSessionTimeFormatsRecentAndOlderTimestamps() {
        val now = 1_700_000_000_000L
        assertEquals("Just now", formatRelativeSessionTime(now - 10_000L, now))
        assertEquals("Just now", formatRelativeSessionTime(now + 5_000L, now))
        assertEquals("5m ago", formatRelativeSessionTime(now - 5 * 60_000L, now))
        assertEquals("3h ago", formatRelativeSessionTime(now - 3 * 3_600_000L, now))
        assertEquals("2d ago", formatRelativeSessionTime(now - 2 * 86_400_000L, now))

        val older = formatRelativeSessionTime(now - 20L * 86_400_000L, now)
        assertTrue("Expected formatted date for older timestamp, got: $older", older.isNotBlank())
    }
}
