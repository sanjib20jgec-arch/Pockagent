package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineKeywordIndexTest {

    private fun createSampleChunks(): List<DocumentChunker.DocumentChunk> {
        return listOf(
            DocumentChunker.DocumentChunk(
                id = "bread_0",
                content = "Baking sourdough bread requires flour, water, salt, and wild yeast starter. Ferment the dough for 12 hours.",
                source = "recipes.txt",
                chunkIndex = 0,
            ),
            DocumentChunker.DocumentChunk(
                id = "linux_1",
                content = "Installing Linux on a Raspberry Pi device involves flashing Ubuntu Server or Raspberry Pi OS to a microSD card.",
                source = "linux_guide.txt",
                chunkIndex = 1,
            ),
            DocumentChunker.DocumentChunk(
                id = "android_2",
                content = "PocketAgent runs quantized LiteRT models on Android CPU or GPU with zero cloud network connectivity for privacy.",
                source = "pocketagent_docs.txt",
                chunkIndex = 2,
            ),
            DocumentChunker.DocumentChunk(
                id = "astronomy_3",
                content = "The James Webb Space Telescope observes the distant universe in infrared light to study the earliest galaxies.",
                source = "astronomy.txt",
                chunkIndex = 3,
            ),
        )
    }

    @Test
    fun bm25RanksMostRelevantChunkFirst() {
        val index = OfflineKeywordIndex(createSampleChunks())

        // Query targeting linux guide
        val linuxResults = index.scoreAll("raspberry pi ubuntu linux")
        assertEquals("linux_1", linuxResults[0].chunk.id)
        assertTrue(linuxResults[0].score > 0.0)

        // Query targeting recipes
        val breadResults = index.scoreAll("sourdough yeast baking flour")
        assertEquals("bread_0", breadResults[0].chunk.id)
        assertTrue(breadResults[0].score > 0.0)

        // Query targeting pocketagent
        val aiResults = index.scoreAll("android litert privacy local model")
        assertEquals("android_2", aiResults[0].chunk.id)
        assertTrue(aiResults[0].score > 0.0)
    }

    @Test
    fun irrelevantChunksReceiveZeroScore() {
        val index = OfflineKeywordIndex(createSampleChunks())
        val results = index.scoreAll("quantum computing qubits superposition")

        for (result in results) {
            assertEquals(0.0, result.score, 0.0001)
        }
    }

    @Test
    fun higherTermFrequencyProducesHigherScore() {
        val chunkA = DocumentChunker.DocumentChunk(
            id = "single_mention",
            content = "The database stores user profiles securely and reliably.",
            chunkIndex = 0,
        )
        val chunkB = DocumentChunker.DocumentChunk(
            id = "repeated_mention",
            content = "The database cluster replicates database logs across database shards for resilient database performance.",
            chunkIndex = 1,
        )

        val index = OfflineKeywordIndex(listOf(chunkA, chunkB))
        val results = index.scoreAll("database")

        assertEquals("repeated_mention", results[0].chunk.id)
        assertTrue(results[0].score > results[1].score)
    }

    @Test
    fun searchReturnsOnlyMatchingChunksWhenPositiveScoreRequired() {
        val index = OfflineKeywordIndex(createSampleChunks())
        val topMatches = index.search("telescope infrared galaxies", topK = 5, requirePositiveScore = true)

        assertEquals(1, topMatches.size)
        assertEquals("astronomy_3", topMatches[0].id)
    }

    @Test
    fun retrieveWithinBudgetStrictlyRespectsMaxCharsLimit() {
        // Create 20 chunks of 200 characters each
        val chunks = (1..20).map { i ->
            DocumentChunker.DocumentChunk(
                id = "chunk_$i",
                content = "Knowledge chunk number $i containing important facts about local offline AI search indexing. ".repeat(2),
                source = "encyclopedia.txt",
                chunkIndex = i - 1,
            )
        }

        val index = OfflineKeywordIndex(chunks)
        val budget = 600

        val selected = index.retrieveWithinBudget("offline AI indexing", maxChars = budget, topK = 10)
        assertTrue("Selected should not be empty", selected.isNotEmpty())

        val totalChars = selected.sumOf { it.content.length }
        assertTrue("Total characters ($totalChars) must be <= budget ($budget)", totalChars <= budget)
    }

    @Test
    fun formatPromptContextStrictlyFitsWithinPromptCap() {
        val chunks = (1..15).map { i ->
            DocumentChunker.DocumentChunk(
                id = "doc_$i",
                content = "Paragraph $i discusses offline neural network execution on mobile edge devices with memory limits.",
                source = "paper.pdf",
                chunkIndex = i - 1,
            )
        }

        val index = OfflineKeywordIndex(chunks)
        val maxChars = 500

        val formatted = index.formatPromptContext("neural network mobile edge", maxChars = maxChars, topK = 5)
        assertTrue("Formatted length (${formatted.length}) must be <= maxChars ($maxChars)", formatted.length <= maxChars)
        assertTrue(formatted.contains("[Excerpt"))
        assertTrue(formatted.contains("neural network"))
    }

    @Test
    fun handlesEmptyIndexGracefully() {
        val emptyIndex = OfflineKeywordIndex(emptyList())
        assertTrue(emptyIndex.scoreAll("anything").isEmpty())
        assertTrue(emptyIndex.search("anything").isEmpty())
        assertTrue(emptyIndex.retrieveWithinBudget("anything", maxChars = 12_000).isEmpty())
        assertEquals("", emptyIndex.formatPromptContext("anything", maxChars = 12_000))
    }

    @Test
    fun handlesBlankOrStopWordOnlyQueryGracefully() {
        val index = OfflineKeywordIndex(createSampleChunks())

        val blankResults = index.scoreAll("")
        assertEquals(4, blankResults.size)

        val stopWordOnly = index.scoreAll("what is the")
        assertEquals(4, stopWordOnly.size)
    }
}
