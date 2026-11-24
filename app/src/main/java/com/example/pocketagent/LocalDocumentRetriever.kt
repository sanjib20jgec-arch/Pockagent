package com.example.pocketagent

/**
 * High-level offline RAG (Retrieval-Augmented Generation) coordinator for PocketAgent.
 *
 * Coordinates [DocumentChunker] and [OfflineKeywordIndex] to handle large user attachments
 * exceeding the model's 12,000 character prompt budget.
 *
 * Invariants:
 * - 100% offline, purely local CPU execution.
 * - Strict 12,000 character prompt bound is never exceeded.
 * - Semantic chunks retain document source and position attribution.
 */
object LocalDocumentRetriever {
    const val MAX_ATTACHMENT_BUDGET = 12_000

    /**
     * Prepares prompt-ready reference context for prompt injection.
     *
     * - If [documentText] is within [maxChars], returns it directly without slicing.
     * - If [documentText] exceeds [maxChars], splits it into semantic chunks and
     *   selects the top BM25-ranked chunks for [query] that fit strictly within [maxChars].
     */
    fun retrieveContext(
        query: String,
        documentText: String,
        sourceName: String = "attachment",
        maxChars: Int = MAX_ATTACHMENT_BUDGET,
        chunkSize: Int = DocumentChunker.DEFAULT_CHUNK_SIZE,
        chunkOverlap: Int = DocumentChunker.DEFAULT_CHUNK_OVERLAP,
        topK: Int = 10,
    ): String {
        require(maxChars > 0) { "maxChars must be positive (got $maxChars)." }
        val trimmed = documentText.trim()
        if (trimmed.isEmpty()) return ""

        // If the entire text fits cleanly within the prompt budget, retain it completely
        if (trimmed.length <= maxChars) {
            return trimmed
        }

        // Exceeds limit: chunk into semantic windows and index with BM25
        val chunks = DocumentChunker.chunkText(
            text = trimmed,
            source = sourceName,
            chunkSize = chunkSize,
            chunkOverlap = chunkOverlap,
        )

        val index = OfflineKeywordIndex(chunks)
        val formatted = index.formatPromptContext(
            query = query,
            maxChars = maxChars,
            topK = topK,
        )

        return formatted.take(maxChars)
    }

    /**
     * Retrieves the list of relevant [DocumentChunker.DocumentChunk] objects matching [query].
     */
    fun retrieveChunks(
        query: String,
        documentText: String,
        sourceName: String = "attachment",
        maxChars: Int = MAX_ATTACHMENT_BUDGET,
        chunkSize: Int = DocumentChunker.DEFAULT_CHUNK_SIZE,
        chunkOverlap: Int = DocumentChunker.DEFAULT_CHUNK_OVERLAP,
        topK: Int = 10,
    ): List<DocumentChunker.DocumentChunk> {
        val trimmed = documentText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val chunks = DocumentChunker.chunkText(
            text = trimmed,
            source = sourceName,
            chunkSize = chunkSize,
            chunkOverlap = chunkOverlap,
        )

        val index = OfflineKeywordIndex(chunks)
        return index.retrieveWithinBudget(query = query, maxChars = maxChars, topK = topK)
    }
}
