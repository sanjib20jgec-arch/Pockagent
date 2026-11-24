package com.example.pocketagent

import java.util.Locale

/**
 * Splits large user documents (from PDF or text attachments exceeding prompt limits)
 * into semantic chunks with overlap.
 *
 * Preserves paragraph, sentence, and word boundaries where possible to avoid cutting
 * context mid-sentence or mid-word.
 */
object DocumentChunker {

    const val DEFAULT_CHUNK_SIZE = 800
    const val DEFAULT_CHUNK_OVERLAP = 150

    /**
     * Represents a single chunk of text with location and provenance metadata.
     */
    data class DocumentChunk(
        val id: String,
        val content: String,
        val source: String = "",
        val chunkIndex: Int = 0,
        val startOffset: Int = 0,
        val endOffset: Int = 0,
        val charCount: Int = content.length,
        val pageNumber: Int? = null,
    ) {
        init {
            require(id.isNotBlank()) { "Chunk ID cannot be blank." }
            require(content.isNotBlank()) { "Chunk content cannot be blank." }
            require(chunkIndex >= 0) { "Chunk index must be non-negative." }
            require(startOffset >= 0) { "Start offset must be non-negative." }
            require(endOffset >= startOffset) { "End offset cannot precede start offset." }
            if (pageNumber != null) {
                require(pageNumber > 0) { "Page number must be positive (got $pageNumber)." }
            }
        }
    }

    /**
     * Splits a text document into a list of [DocumentChunk]s respecting semantic boundaries.
     *
     * @param text The raw input document text.
     * @param source The identifier or file name of the document.
     * @param chunkSize The target maximum character length of each chunk.
     * @param chunkOverlap The number of characters of context to carry over between consecutive chunks.
     * @return A list of ordered [DocumentChunk]s.
     */
    fun chunkText(
        text: String,
        source: String = "document",
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        chunkOverlap: Int = DEFAULT_CHUNK_OVERLAP,
    ): List<DocumentChunk> {
        require(chunkSize > 0) { "chunkSize must be greater than 0 (got $chunkSize)." }
        require(chunkOverlap >= 0) { "chunkOverlap cannot be negative (got $chunkOverlap)." }
        require(chunkOverlap < chunkSize) {
            "chunkOverlap ($chunkOverlap) must be strictly less than chunkSize ($chunkSize)."
        }

        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Single chunk if text is smaller than or equal to chunkSize
        if (trimmed.length <= chunkSize) {
            return listOf(
                DocumentChunk(
                    id = formatChunkId(source, 0),
                    content = trimmed,
                    source = source,
                    chunkIndex = 0,
                    startOffset = 0,
                    endOffset = trimmed.length,
                )
            )
        }

        val chunks = mutableListOf<DocumentChunk>()
        var start = 0
        var chunkIndex = 0
        val textLength = trimmed.length

        // Minimum fraction of chunkSize before searching for semantic breakpoints
        val minLookbackRatio = 0.65

        while (start < textLength) {
            val maxEnd = minOf(start + chunkSize, textLength)

            val end = if (maxEnd >= textLength) {
                textLength
            } else {
                findSemanticBreakpoint(
                    text = trimmed,
                    windowStart = (start + (chunkSize * minLookbackRatio).toInt()).coerceAtMost(maxEnd),
                    windowEnd = maxEnd,
                )
            }

            val chunkSlice = trimmed.substring(start, end).trim()
            if (chunkSlice.isNotEmpty()) {
                chunks.add(
                    DocumentChunk(
                        id = formatChunkId(source, chunkIndex),
                        content = chunkSlice,
                        source = source,
                        chunkIndex = chunkIndex,
                        startOffset = start,
                        endOffset = end,
                    )
                )
                chunkIndex++
            }

            if (end >= textLength) {
                break
            }

            // Calculate next start with overlap
            var nextStart = end - chunkOverlap

            // Guarantee forward progress
            if (nextStart <= start) {
                nextStart = start + maxOf(1, chunkSize / 2)
            }

            // Skip leading whitespace or newline in next chunk
            while (nextStart < end && trimmed[nextStart].isWhitespace()) {
                nextStart++
            }

            start = nextStart
        }

        return chunks
    }

    /**
     * Splits multiple labeled documents (e.g., several attached files or PDF pages)
     * into a combined indexed sequence of chunks.
     */
    fun chunkDocuments(
        documents: Map<String, String>,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        chunkOverlap: Int = DEFAULT_CHUNK_OVERLAP,
    ): List<DocumentChunk> {
        val allChunks = mutableListOf<DocumentChunk>()
        var globalIndex = 0
        for ((source, text) in documents) {
            val docChunks = chunkText(text, source, chunkSize, chunkOverlap)
            for (chunk in docChunks) {
                allChunks.add(
                    chunk.copy(
                        id = "${source}_$globalIndex",
                        chunkIndex = globalIndex++,
                    )
                )
            }
        }
        return allChunks
    }

    /**
     * Represents a single page extracted from a multi-page document (e.g. PDF).
     */
    data class DocumentPage(
        val pageNumber: Int,
        val text: String,
        val charCount: Int = text.length,
    )

    /**
     * Splits extracted PDF pages into chunks with page-level attribution.
     */
    fun chunkPdfPages(
        pages: List<DocumentPage>,
        source: String = "document.pdf",
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        chunkOverlap: Int = DEFAULT_CHUNK_OVERLAP,
    ): List<DocumentChunk> {
        val allChunks = mutableListOf<DocumentChunk>()
        var globalIndex = 0
        for (page in pages) {
            val pageChunks = chunkText(page.text, source, chunkSize, chunkOverlap)
            for (chunk in pageChunks) {
                allChunks.add(
                    chunk.copy(
                        id = formatChunkId(source, globalIndex),
                        chunkIndex = globalIndex++,
                        pageNumber = page.pageNumber,
                    )
                )
            }
        }
        return allChunks
    }

    /**
     * Searches for natural text boundaries in priority order:
     * 1. Paragraph breaks ("\n\n" or "\r\n\r\n")
     * 2. Sentence ends (". ", "? ", "! ", ".\n", "!\n", "?\n")
     * 3. Line breaks ("\n")
     * 4. Word boundary (spaces)
     *
     * Falls back to windowEnd if no semantic boundary exists in the search window.
     */
    private fun findSemanticBreakpoint(
        text: String,
        windowStart: Int,
        windowEnd: Int,
    ): Int {
        if (windowStart >= windowEnd) return windowEnd

        val slice = text.substring(windowStart, windowEnd)

        // 1. Paragraph boundary: last \n\n in window
        val paragraphBreak = slice.lastIndexOf("\n\n")
        if (paragraphBreak != -1) {
            return windowStart + paragraphBreak + 2
        }

        // 2. Sentence boundary: last [.!?][ \t\n]
        val sentenceRegex = Regex("""[.!?][ \t\r\n]""")
        val sentenceMatches = sentenceRegex.findAll(slice).toList()
        if (sentenceMatches.isNotEmpty()) {
            val lastMatch = sentenceMatches.last()
            return windowStart + lastMatch.range.last + 1
        }

        // 3. Line break
        val lineBreak = slice.lastIndexOf('\n')
        if (lineBreak != -1) {
            return windowStart + lineBreak + 1
        }

        // 4. Word boundary (whitespace)
        val space = slice.lastIndexOf(' ')
        if (space != -1) {
            return windowStart + space + 1
        }

        // Fallback: hard cut at window end
        return windowEnd
    }

    private fun formatChunkId(source: String, index: Int): String {
        val sanitized = source.replace(Regex("""[^a-zA-Z0-9_-]"""), "_").take(30)
        return "${sanitized}_$index"
    }
}
