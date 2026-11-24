package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageTest {

    @Test
    fun createsValidUserMessageWithDefaults() {
        val msg = ChatMessage(
            role = ChatMessage.Role.USER,
            content = "How do I configure battery saver?",
        )
        assertNotNull(msg.id)
        assertTrue(msg.timestamp > 0)
        assertEquals(ChatMessage.Role.USER, msg.role)
        assertEquals("How do I configure battery saver?", msg.content)
        assertTrue(msg.estimatedTokens > 0)
    }

    @Test
    fun rejectsEmptyContentWithoutAttachment() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatMessage(
                role = ChatMessage.Role.USER,
                content = "   ",
            )
        }
    }

    @Test
    fun allowsEmptyContentWhenAttachmentPresent() {
        val msg = ChatMessage(
            role = ChatMessage.Role.USER,
            content = "",
            attachmentSummary = "document.pdf (12 pages)",
            attachmentReferences = listOf("document.pdf"),
        )
        assertEquals("", msg.content)
        assertEquals("document.pdf (12 pages)", msg.attachmentSummary)
        assertTrue(msg.estimatedTokens > 0)
    }

    @Test
    fun roleParsingCaseInsensitive() {
        assertEquals(ChatMessage.Role.USER, ChatMessage.Role.fromString("user"))
        assertEquals(ChatMessage.Role.USER, ChatMessage.Role.fromString("USER"))
        assertEquals(ChatMessage.Role.ASSISTANT, ChatMessage.Role.fromString("assistant"))
        assertEquals(ChatMessage.Role.ASSISTANT, ChatMessage.Role.fromString("ASSISTANT"))
        assertEquals(ChatMessage.Role.SYSTEM, ChatMessage.Role.fromString("system"))
        assertEquals(ChatMessage.Role.SYSTEM, ChatMessage.Role.fromString("SYSTEM"))

        assertThrows(IllegalArgumentException::class.java) {
            ChatMessage.Role.fromString("unknown_role")
        }
    }

    @Test
    fun tokenEstimatorProducesReasonableLowerAndUpperBounds() {
        assertEquals(0, ChatMessage.estimateTextTokens(""))
        assertEquals(0, ChatMessage.estimateTextTokens("   "))

        val shortText = "Turn on Wi-Fi"
        val shortTokens = ChatMessage.estimateTextTokens(shortText)
        assertTrue("Tokens should be >= 3 words", shortTokens >= 3)

        val longText = "a".repeat(350) // ~100 tokens with 3.5 chars/token
        val longTokens = ChatMessage.estimateTextTokens(longText)
        assertEquals(100, longTokens)

        val msgWithAttachment = ChatMessage.estimateTokens(
            content = "Analyze this report",
            attachmentSummary = "report.txt (100 lines)",
            attachmentContent = "Sample confidential text inside report",
        )
        assertTrue(msgWithAttachment > shortTokens)
    }
}
