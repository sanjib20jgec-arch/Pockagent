package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingBlockTest {

    @Test fun `parse closed think block`() {
        val segments = StreamingCodePreviewParser.parse("<think>reasoning here</think>\nFinal answer")
        assertTrue(segments.any { it is StreamingCodePreviewParser.Segment.Thinking })
        assertTrue(segments.any { it is StreamingCodePreviewParser.Segment.Prose })
    }

    @Test fun `parse unclosed think block at streaming`() {
        val segments = StreamingCodePreviewParser.parse("<think>still thinking...")
        assertTrue(segments.any { it is StreamingCodePreviewParser.Segment.Thinking })
        val thinking = segments.filterIsInstance<StreamingCodePreviewParser.Segment.Thinking>().first()
        assertFalse(thinking.isClosed)
    }

    @Test fun `no think block gives no thinking segment`() {
        val segments = StreamingCodePreviewParser.parse("Hello world")
        assertFalse(segments.any { it is StreamingCodePreviewParser.Segment.Thinking })
    }

    @Test fun `think block content is captured`() {
        val segments = StreamingCodePreviewParser.parse("<think>my reasoning</think>Answer")
        val t = segments.filterIsInstance<StreamingCodePreviewParser.Segment.Thinking>().firstOrNull()
        assertNotNull(t)
        assertEquals("my reasoning", t!!.text)
        assertTrue(t.isClosed)
    }

    @Test fun `code block and think block mixed`() {
        val segments = StreamingCodePreviewParser.parse(
            "<think>thinking</think>\nHere is code:\n```kotlin\nval x = 1\n```")
        assertTrue(segments.any { it is StreamingCodePreviewParser.Segment.Thinking })
        assertTrue(segments.any { it is StreamingCodePreviewParser.Segment.Code })
    }

    @Test fun `stripToPlainText omits think reasoning for TTS and export`() {
        val raw = "<think>secret chain of thought</think>\n\n**Visible answer** here."
        val plain = MarkdownFormatter.stripToPlainText(raw)
        assertFalse(plain.contains("secret chain"))
        assertEquals("Visible answer here.", plain)
    }
}
