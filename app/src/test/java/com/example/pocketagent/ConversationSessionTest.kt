package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSessionTest {

    @Test
    fun createsValidSessionWithInitialPrompt() {
        val session = ConversationSession.createWithInitialPrompt("How do I fix my bike tire?", "session-bike-01")
        assertEquals("session-bike-01", session.id)
        assertEquals("How do I fix my bike tire?", session.title)
        assertEquals(0, session.messageCount)
        assertFalse(session.isPinned)
        assertNull(session.lastMessage)
    }

    @Test
    fun derivesConciseTitleAndTruncatesWhenLong() {
        val shortTitle = ConversationSession.deriveTitle("Short question")
        assertEquals("Short question", shortTitle)

        val multiline = ConversationSession.deriveTitle("\n\n  First valid line  \nSecond line")
        assertEquals("First valid line", multiline)

        val longPrompt = "A".repeat(120)
        val derived = ConversationSession.deriveTitle(longPrompt)
        assertTrue(derived.length <= 80)
        assertTrue(derived.endsWith("..."))
    }

    @Test
    fun appendsMessagesAndCalculatesTokens() {
        val session = ConversationSession(id = "sess-tokens", title = "Token Test")
        val uMsg = ChatMessage.user("Hello world")
        val aMsg = ChatMessage.assistant("Hi there, how can I help you today?")

        val updated = session.withMessage(uMsg).withMessage(aMsg)
        assertEquals(2, updated.messageCount)
        assertEquals(aMsg, updated.lastMessage)
        assertTrue(updated.totalEstimatedTokens > 0)
    }

    @Test
    fun withPinnedAndWithTitleProduceImmutableCopies() {
        val session = ConversationSession(id = "sess-immutable", title = "Old Title")
        assertFalse(session.isPinned)

        val pinned = session.withPinned(true)
        assertTrue(pinned.isPinned)
        assertFalse(session.isPinned) // original unchanged

        val renamed = pinned.withTitle("New Renamed Title")
        assertEquals("New Renamed Title", renamed.title)
        assertEquals("Old Title", pinned.title)
    }

    @Test
    fun exportsMarkdownAndPlainTextFormattedTurns() {
        val session = ConversationSession(
            id = "sess-export-fmt",
            title = "Cooking Recipe",
            messages = listOf(
                ChatMessage.user("How do I boil an egg?"),
                ChatMessage.assistant("1. Place egg in cold water.\n2. Bring to a boil.\n3. Turn off heat and wait 10 minutes."),
            )
        )

        val md = session.exportToMarkdown()
        assertTrue(md.contains("# Cooking Recipe"))
        assertTrue(md.contains("### 👤 User"))
        assertTrue(md.contains("How do I boil an egg?"))
        assertTrue(md.contains("### 🤖 Assistant"))
        assertTrue(md.contains("Bring to a boil."))

        val txt = session.exportToPlainText()
        assertTrue(txt.contains("Cooking Recipe"))
        assertTrue(txt.contains("[USER]:"))
        assertTrue(txt.contains("[ASSISTANT]:"))
    }

    @Test
    fun rejectsInvalidSessionIds() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationSession(id = "bad id with spaces", title = "Bad")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationSession(id = "../traversal", title = "Bad")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationSession(id = "", title = "Bad")
        }
    }
}
