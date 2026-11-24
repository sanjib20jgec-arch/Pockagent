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

    data class DocumentCitation(
        val documentName: String,
        val pageNumber: Int? = null,
        val excerptSnippet: String = "",
    ) {
        val citationLabel: String
            get() = if (pageNumber != null) "$documentName · Page $pageNumber" else documentName
    }

    data class RetrievalResult(
        val promptContext: String,
        val citations: List<DocumentCitation> = emptyList(),
        val totalChunksRetrieved: Int = 0,
    )

    /**
     * Scores pre-chunked documents (including PDF pages) against [query] and returns
     * both the bounded prompt context and distinct [DocumentCitation] badges.
     */
    fun retrieveWithCitations(
        query: String,
        chunks: List<DocumentChunker.DocumentChunk>,
        maxChars: Int = MAX_ATTACHMENT_BUDGET,
        topK: Int = 10,
    ): RetrievalResult {
        if (chunks.isEmpty()) return RetrievalResult("", emptyList(), 0)

        val index = OfflineKeywordIndex(chunks)
        val selectedChunks = index.retrieveWithinBudget(query = query, maxChars = maxChars, topK = topK)
        if (selectedChunks.isEmpty()) return RetrievalResult("", emptyList(), 0)

        val promptContext = index.formatPromptContext(query = query, maxChars = maxChars, topK = topK)
        val citations = selectedChunks.map { chunk ->
            val snippet = chunk.content.take(120).replace("\r\n", " ").replace("\n", " ").trim()
            DocumentCitation(
                documentName = chunk.source.ifBlank { "Document" },
                pageNumber = chunk.pageNumber,
                excerptSnippet = snippet,
            )
        }.distinctBy { "${it.documentName}_${it.pageNumber}" }

        return RetrievalResult(
            promptContext = promptContext,
            citations = citations,
            totalChunksRetrieved = selectedChunks.size,
        )
    }

    /**
     * Splits [documentText] into chunks, indexes them, and returns [RetrievalResult] with citations.
     */
    fun retrieveTextWithCitations(
        query: String,
        documentText: String,
        sourceName: String = "attachment",
        maxChars: Int = MAX_ATTACHMENT_BUDGET,
        chunkSize: Int = DocumentChunker.DEFAULT_CHUNK_SIZE,
        chunkOverlap: Int = DocumentChunker.DEFAULT_CHUNK_OVERLAP,
        topK: Int = 10,
    ): RetrievalResult {
        val trimmed = documentText.trim()
        if (trimmed.isEmpty()) return RetrievalResult("", emptyList(), 0)

        val chunks = DocumentChunker.chunkText(
            text = trimmed,
            source = sourceName,
            chunkSize = chunkSize,
            chunkOverlap = chunkOverlap,
        )
        return retrieveWithCitations(query, chunks, maxChars, topK)
    }
}
