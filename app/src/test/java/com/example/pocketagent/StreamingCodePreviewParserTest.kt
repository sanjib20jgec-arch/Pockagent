package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingCodePreviewParserTest {

    @Test
    fun returnsEmptyForEmptyOrWhitespaceInput() {
        assertTrue(StreamingCodePreviewParser.splitForStreamingPreview("").isEmpty())
        assertTrue(StreamingCodePreviewParser.splitForStreamingPreview("   \n\t  ").isEmpty())
    }

    @Test
    fun returnsSingleProseSegmentWhenNoCodeFencePresent() {
        val segments = StreamingCodePreviewParser.splitForStreamingPreview("Hello from PocketAgent.")
        assertEquals(
            listOf(StreamingCodePreviewParser.Segment.Prose("Hello from PocketAgent.")),
            segments,
        )
    }

    @Test
    fun parsesUnclosedStreamingCodeFenceAsOpenCodeSegment() {
        val raw = """
            Here is DX-Ball:
            ```html
            <!DOCTYPE html>
            <canvas id="game" width="320" height="420"></canvas>
        """.trimIndent()

        val segments = StreamingCodePreviewParser.splitForStreamingPreview(raw)
        assertEquals(2, segments.size)
        assertEquals(
            StreamingCodePreviewParser.Segment.Prose("Here is DX-Ball:"),
            segments[0],
        )
        val codeSeg = segments[1] as StreamingCodePreviewParser.Segment.Code
        assertEquals("html", codeSeg.language)
        assertFalse(codeSeg.isClosed)
        assertEquals(
            "<!DOCTYPE html>\n<canvas id=\"game\" width=\"320\" height=\"420\"></canvas>",
            codeSeg.code,
        )
    }

    @Test
    fun handlesOpeningFenceWithNoCodeLinesYet() {
        val withLang = StreamingCodePreviewParser.splitForStreamingPreview("Sure!\n```html\n")
        assertEquals(
            listOf(
                StreamingCodePreviewParser.Segment.Prose("Sure!"),
                StreamingCodePreviewParser.Segment.Code(language = "html", code = "", isClosed = false),
            ),
            withLang,
        )

        val bareFence = StreamingCodePreviewParser.splitForStreamingPreview("```")
        assertEquals(1, bareFence.size)
        val codeSeg = bareFence[0] as StreamingCodePreviewParser.Segment.Code
        assertNull(codeSeg.language)
        assertEquals("", codeSeg.code)
        assertFalse(codeSeg.isClosed)
    }

    @Test
    fun parsesMultipleClosedAndTrailingStreamingBlocksAndNormalizesCrlf() {
        val raw = "Step 1:\r\n```\r\necho hi\r\n```\r\nNext:\r\n  ```kotlin info=demo  \r\nval x = 1\r\n\r\nval y = 2"

        val segments = StreamingCodePreviewParser.splitForStreamingPreview(raw)
        assertEquals(4, segments.size)
        assertEquals(StreamingCodePreviewParser.Segment.Prose("Step 1:"), segments[0])

        val firstCode = segments[1] as StreamingCodePreviewParser.Segment.Code
        assertNull(firstCode.language)
        assertEquals("echo hi", firstCode.code)
        assertTrue(firstCode.isClosed)

        assertEquals(StreamingCodePreviewParser.Segment.Prose("Next:"), segments[2])

        val secondCode = segments[3] as StreamingCodePreviewParser.Segment.Code
        assertEquals("kotlin", secondCode.language)
        assertEquals("val x = 1\n\nval y = 2", secondCode.code)
        assertFalse(secondCode.isClosed)
    }

    @Test
    fun doesNotTreatInlineTripleBackticksInProseAsCodeFence() {
        val raw = "Use ```inline``` carefully in prose.\nAnd another line."
        val segments = StreamingCodePreviewParser.splitForStreamingPreview(raw)
        assertEquals(
            listOf(StreamingCodePreviewParser.Segment.Prose(raw)),
            segments,
        )
    }
}
