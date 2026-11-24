package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDocumentRetrieverTest {

    @Test
    fun returnsSmallDocumentAsIsWithoutChunking() {
        val shortDoc = "This is a short attachment that easily fits inside the prompt budget."
        val result = LocalDocumentRetriever.retrieveContext(
            query = "prompt budget",
            documentText = shortDoc,
            maxChars = 12_000,
        )

        assertEquals(shortDoc, result)
    }

    @Test
    fun retrievesTopRelevantChunksWhenDocumentExceedsBudget() {
        // Build a large document of 15,000 characters
        val section1 = "SECTION 1 (Cooking): To prepare handmade Italian pasta, mix semolina flour with farm-fresh eggs.\n\n"
        val section2 = "SECTION 2 (Gardening): Grow tomatoes in nutrient-rich soil with plenty of direct morning sunlight.\n\n"
        val filler = "SECTION 3 (Filler): General notes on household organisation and daily scheduling. ".repeat(180) + "\n\n"
        val largeDoc = section1 + filler + section2

        assertTrue("Document must exceed 12,000 chars", largeDoc.length > 12_000)

        val retrieved = LocalDocumentRetriever.retrieveContext(
            query = "handmade Italian pasta semolina",
            documentText = largeDoc,
            sourceName = "notes.txt",
            maxChars = 12_000,
        )

        assertTrue("Retrieved text (${retrieved.length}) must strictly satisfy <= 12,000 chars", retrieved.length <= 12_000)
        assertTrue("Must contain Italian pasta content", retrieved.contains("Italian pasta"))
    }

    @Test
    fun retrieveChunksReturnsDocumentChunkObjects() {
        val largeDoc = "PocketAgent executes local LiteRT models on Android edge hardware. ".repeat(300)
        val chunks = LocalDocumentRetriever.retrieveChunks(
            query = "LiteRT Android edge",
            documentText = largeDoc,
            maxChars = 2_000,
        )

        assertTrue(chunks.isNotEmpty())
        for (chunk in chunks) {
            assertTrue(chunk.content.isNotBlank())
            assertTrue(chunk.charCount <= 2_000)
        }
    }

    @Test
    fun handlesEmptyOrWhitespaceDocument() {
        assertEquals("", LocalDocumentRetriever.retrieveContext(query = "search", documentText = ""))
        assertEquals("", LocalDocumentRetriever.retrieveContext(query = "search", documentText = "   \n\t  "))
        assertTrue(LocalDocumentRetriever.retrieveChunks(query = "search", documentText = "").isEmpty())
    }

    @Test
    fun retrieveWithCitationsReturnsPromptContextAndDistinctBadges() {
        val chunks = listOf(
            DocumentChunker.DocumentChunk(
                id = "doc_p1_0",
                content = "Kotlin coroutines provide structured concurrency and non-blocking asynchronous programming.",
                source = "guide.pdf",
                chunkIndex = 0,
                pageNumber = 1,
            ),
            DocumentChunker.DocumentChunk(
                id = "doc_p2_0",
                content = "StateFlow and SharedFlow are reactive state holders designed for unidirectional data flow in MVI.",
                source = "guide.pdf",
                chunkIndex = 1,
                pageNumber = 2,
            ),
        )

        val result = LocalDocumentRetriever.retrieveWithCitations(
            query = "StateFlow reactive MVI",
            chunks = chunks,
            maxChars = 2000,
        )

        assertTrue(result.promptContext.isNotBlank())
        assertTrue(result.promptContext.contains("StateFlow and SharedFlow"))
        assertTrue(result.promptContext.contains("Page 2"))
        assertTrue(result.citations.isNotEmpty())
        assertEquals("guide.pdf", result.citations[0].documentName)
        assertTrue(result.citations.any { it.pageNumber == 2 })
    }

    @Test
    fun retrieveTextWithCitationsHandlesDocumentString() {
        val docText = "Quantum computing uses superposition and entanglement to solve discrete optimization problems."
        val result = LocalDocumentRetriever.retrieveTextWithCitations(
            query = "superposition entanglement",
            documentText = docText,
            sourceName = "quantum.txt",
            maxChars = 1000,
        )

        assertTrue(result.promptContext.contains("Quantum computing"))
        assertEquals(1, result.citations.size)
        assertEquals("quantum.txt", result.citations[0].documentName)
    }
}
