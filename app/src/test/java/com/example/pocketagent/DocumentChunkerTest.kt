package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentChunkerTest {

    @Test
    fun returnsSingleChunkWhenTextFitsWithinChunkSize() {
        val text = "PocketAgent is an offline-only privacy-preserving assistant."
        val chunks = DocumentChunker.chunkText(text, source = "readme.txt", chunkSize = 200, chunkOverlap = 50)

        assertEquals(1, chunks.size)
        assertEquals("readme_txt_0", chunks[0].id)
        assertEquals(text, chunks[0].content)
        assertEquals(0, chunks[0].startOffset)
        assertEquals(text.length, chunks[0].endOffset)
        assertEquals(0, chunks[0].chunkIndex)
        assertEquals("readme.txt", chunks[0].source)
    }

    @Test
    fun returnsEmptyListForEmptyOrWhitespaceText() {
        assertTrue(DocumentChunker.chunkText("").isEmpty())
        assertTrue(DocumentChunker.chunkText("    \n\t  ").isEmpty())
    }

    @Test
    fun splitsAtParagraphBreakWhenAvailable() {
        val paragraph1 = "This is the first paragraph with important context."
        val paragraph2 = "This is the second paragraph with subsequent details."
        val text = "$paragraph1\n\n$paragraph2"

        // Set chunkSize so both cannot fit in one chunk, but paragraph1 fits in first
        val chunks = DocumentChunker.chunkText(
            text = text,
            source = "doc",
            chunkSize = paragraph1.length + 15,
            chunkOverlap = 10,
        )

        assertTrue(chunks.size >= 2)
        assertEquals(paragraph1, chunks[0].content)
        assertTrue(chunks[1].content.contains("second paragraph"))
    }

    @Test
    fun splitsAtSentenceBoundaryWhenNoParagraphBreak() {
        val sentence1 = "PocketAgent executes local LiteRT models on Android."
        val sentence2 = "All user documents and weights remain strictly on the device."
        val text = "$sentence1 $sentence2"

        val chunks = DocumentChunker.chunkText(
            text = text,
            source = "doc",
            chunkSize = sentence1.length + 15,
            chunkOverlap = 10,
        )

        assertTrue(chunks.size >= 2)
        assertEquals(sentence1, chunks[0].content)
        assertTrue(chunks[1].content.contains("All user documents") || chunks[1].content.contains("strictly on the device"))
    }

    @Test
    fun maintainsOverlapBetweenConsecutiveChunks() {
        val sentenceA = "Apple banana cherry date fig grape honeydew kiwi lemon mango nectarine orange."
        val sentenceB = "Papaya quince raspberry strawberry tangerine uva watermelon xigua yam zucchini."
        val sentenceC = "Almond cashew hazelnut macadamia peanut pecan pine pistachio walnut chestnut."
        val text = "$sentenceA $sentenceB $sentenceC"

        val chunks = DocumentChunker.chunkText(
            text = text,
            source = "fruits",
            chunkSize = 90,
            chunkOverlap = 25,
        )

        assertTrue("Expected multiple chunks, got ${chunks.size}", chunks.size > 1)

        // Verify that consecutive chunks contain overlapping content or that next chunk starts before previous ends
        for (i in 0 until chunks.size - 1) {
            val curr = chunks[i]
            val next = chunks[i + 1]
            assertTrue(
                "Chunk $i endOffset (${curr.endOffset}) should be greater than Chunk ${i + 1} startOffset (${next.startOffset}) due to overlap",
                curr.endOffset > next.startOffset,
            )
        }
    }

    @Test
    fun accuratelyTracksOffsetsInOriginalText() {
        val text = "Section 1: Setup.\n\nSection 2: Configuration.\n\nSection 3: Execution."
        val chunks = DocumentChunker.chunkText(text, chunkSize = 30, chunkOverlap = 10)

        for (chunk in chunks) {
            val originalSlice = text.substring(chunk.startOffset, chunk.endOffset).trim()
            assertEquals(chunk.content, originalSlice)
        }
    }

    @Test
    fun splitsMultipleDocumentsWithOrderedGlobalIndexing() {
        val docs = mapOf(
            "doc1.txt" to "Content for document one with some detailed information.",
            "doc2.txt" to "Content for document two with another separate set of facts.",
        )

        val chunks = DocumentChunker.chunkDocuments(docs, chunkSize = 50, chunkOverlap = 10)
        assertTrue(chunks.size >= 2)

        for (i in chunks.indices) {
            assertEquals(i, chunks[i].chunkIndex)
        }
        assertTrue(chunks.any { it.source == "doc1.txt" })
        assertTrue(chunks.any { it.source == "doc2.txt" })
    }

    @Test
    fun handlesUnbrokenTextWithoutCrashingOrInfiniteLoop() {
        // Continuous string with no spaces or line breaks
        val text = "A".repeat(500)
        val chunks = DocumentChunker.chunkText(text, chunkSize = 100, chunkOverlap = 20)

        assertTrue(chunks.size >= 5)
        for (chunk in chunks) {
            assertTrue(chunk.charCount <= 100)
        }
    }

    @Test
    fun rejectsInvalidParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            DocumentChunker.chunkText("text", chunkSize = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentChunker.chunkText("text", chunkSize = 100, chunkOverlap = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentChunker.chunkText("text", chunkSize = 100, chunkOverlap = 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentChunker.chunkText("text", chunkSize = 100, chunkOverlap = 120)
        }
    }

    @Test
    fun chunksPdfPagesWithExactPageAttribution() {
        val pages = listOf(
            PdfTextExtractor.ExtractedPdfPage(pageNumber = 1, text = "Page one content with introductory facts."),
            PdfTextExtractor.ExtractedPdfPage(pageNumber = 2, text = "Page two content with advanced architectural analysis."),
        )
        val chunks = DocumentChunker.chunkPdfPages(pages, source = "report.pdf", chunkSize = 200, chunkOverlap = 50)

        assertEquals(2, chunks.size)
        assertEquals(1, chunks[0].pageNumber)
        assertEquals(2, chunks[1].pageNumber)
        assertEquals("report.pdf", chunks[0].source)
        assertEquals("report.pdf", chunks[1].source)
        assertEquals(0, chunks[0].chunkIndex)
        assertEquals(1, chunks[1].chunkIndex)
    }
}
