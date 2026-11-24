package com.example.pocketagent

import java.util.Locale
import kotlin.math.ln

/**
 * Offline BM25 (Best Matching 25) keyword search index for local document chunks.
 *
 * Provides fast, purely offline, deterministic relevance ranking of user document chunks
 * against a query, fitting selected context strictly within the 12,000 character prompt cap.
 */
class OfflineKeywordIndex(
    val chunks: List<DocumentChunker.DocumentChunk>,
    val k1: Double = DEFAULT_K1,
    val b: Double = DEFAULT_B,
    private val customStopWords: Set<String>? = null,
) {
    companion object {
        const val DEFAULT_K1 = 1.2
        const val DEFAULT_B = 0.75
        const val DEFAULT_MAX_CHARS = 12_000

        val STOP_WORDS: Set<String> = setOf(
            "a", "about", "above", "after", "again", "against", "all", "am", "an", "and", "any", "are",
            "as", "at", "be", "because", "been", "before", "being", "below", "between", "both",
            "but", "by", "could", "did", "do", "does", "doing", "down", "during", "each", "few",
            "for", "from", "further", "had", "has", "have", "having", "he", "her", "here", "hers",
            "herself", "him", "himself", "his", "how", "i", "if", "in", "into", "is", "it", "its",
            "itself", "just", "me", "more", "most", "my", "myself", "no", "nor", "not", "now",
            "of", "off", "on", "once", "only", "or", "other", "our", "ours", "ourselves", "out",
            "over", "own", "s", "same", "she", "should", "so", "some", "such", "t", "than", "that",
            "the", "their", "theirs", "them", "themselves", "then", "there", "these", "they", "this",
            "those", "through", "to", "too", "under", "until", "up", "very", "was", "we", "were",
            "what", "when", "where", "which", "while", "who", "whom", "why", "will", "with", "you",
            "your", "yours", "yourself", "yourselves"
        )

        /**
         * Tokenizes a text string into normalized lowercase terms, optionally removing stopwords.
         */
        fun tokenize(text: String, stopWords: Set<String> = STOP_WORDS): List<String> {
            val terms = mutableListOf<String>()
            val regex = Regex("""\b[\p{L}\p{N}_-]+\b""")
            for (match in regex.findAll(text.lowercase(Locale.ROOT))) {
                val token = match.value.trim('-', '_')
                if (token.length >= 2) {
                    terms.add(token)
                }
            }
            val filtered = terms.filter { it !in stopWords }
            // If all terms were filtered out by stopwords, retain original terms so search isn't empty
            return if (filtered.isEmpty() && terms.isNotEmpty()) terms else filtered
        }
    }

    private val effectiveStopWords = customStopWords ?: STOP_WORDS

    data class ScoredChunk(
        val chunk: DocumentChunker.DocumentChunk,
        val score: Double,
    ) : Comparable<ScoredChunk> {
        override fun compareTo(other: ScoredChunk): Int {
            val scoreCmp = other.score.compareTo(this.score)
            return if (scoreCmp != 0) scoreCmp else this.chunk.chunkIndex.compareTo(other.chunk.chunkIndex)
        }
    }

    // Document length (in tokens) per chunk ID
    private val docLengths = mutableMapOf<String, Int>()

    // Term frequencies per chunk ID: chunkId -> (term -> count)
    private val docTermFreqs = mutableMapOf<String, Map<String, Int>>()

    // Inverted index: term -> set of chunk IDs containing term
    private val invertedIndex = mutableMapOf<String, MutableSet<String>>()

    // Total documents
    val totalDocuments: Int = chunks.size

    // Average document length in tokens
    val avgDocLength: Double

    init {
        var totalLength = 0L
        for (chunk in chunks) {
            val tokens = tokenize(chunk.content, effectiveStopWords)
            val length = tokens.size
            docLengths[chunk.id] = length
            totalLength += length

            val freqs = mutableMapOf<String, Int>()
            for (t in tokens) {
                freqs[t] = (freqs[t] ?: 0) + 1
            }
            docTermFreqs[chunk.id] = freqs

            for (term in freqs.keys) {
                invertedIndex.computeIfAbsent(term) { mutableSetOf() }.add(chunk.id)
            }
        }
        avgDocLength = if (totalDocuments > 0) totalLength.toDouble() / totalDocuments else 0.0
    }

    /**
     * Scores all chunks in the corpus against the query using Lucene-style BM25 formula.
     * Chunks are sorted in descending order of score.
     */
    fun scoreAll(query: String): List<ScoredChunk> {
        if (chunks.isEmpty()) return emptyList()

        val queryTerms = tokenize(query, effectiveStopWords)
        if (queryTerms.isEmpty()) {
            // Neutral scoring: all chunks score 0.0, ordered by sequential chunk index
            return chunks.map { ScoredChunk(it, 0.0) }
        }

        val scores = mutableMapOf<String, Double>()

        for (term in queryTerms) {
            val matchingDocs = invertedIndex[term] ?: continue
            val docFreq = matchingDocs.size // n(t)

            // Strictly non-negative BM25 IDF
            val idf = ln(1.0 + (totalDocuments - docFreq + 0.5) / (docFreq + 0.5))

            for (docId in matchingDocs) {
                val tf = docTermFreqs[docId]?.get(term) ?: 0
                val docLen = docLengths[docId] ?: 0
                val lenNorm = if (avgDocLength > 0.0) docLen / avgDocLength else 1.0

                val tfComponent = (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * lenNorm))
                val termScore = idf * tfComponent

                scores[docId] = (scores[docId] ?: 0.0) + termScore
            }
        }

        val scoredList = chunks.map { chunk ->
            ScoredChunk(chunk, scores[chunk.id] ?: 0.0)
        }

        return scoredList.sorted()
    }

    /**
     * Retrieves the top-K chunks matching the query, optionally requiring score > 0.
     */
    fun search(query: String, topK: Int = 5, requirePositiveScore: Boolean = true): List<DocumentChunker.DocumentChunk> {
        val scored = scoreAll(query)
        val filtered = if (requirePositiveScore && query.isNotBlank()) {
            scored.filter { it.score > 0.0 }
        } else {
            scored
        }
        return filtered.take(topK).map { it.chunk }
    }

    /**
     * Retrieves the most relevant chunks whose combined character length fits strictly
     * within [maxChars] (default 12,000 characters).
     */
    fun retrieveWithinBudget(
        query: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
        topK: Int = 10,
    ): List<DocumentChunker.DocumentChunk> {
        require(maxChars > 0) { "maxChars must be positive (got $maxChars)." }

        val scored = scoreAll(query)
        val candidates = if (query.isNotBlank()) {
            val positive = scored.filter { it.score > 0.0 }
            if (positive.isNotEmpty()) positive else scored
        } else {
            scored
        }

        val selected = mutableListOf<DocumentChunker.DocumentChunk>()
        var currentChars = 0

        for (scoredChunk in candidates.take(topK)) {
            val chunkLen = scoredChunk.chunk.content.length
            if (currentChars + chunkLen <= maxChars) {
                selected.add(scoredChunk.chunk)
                currentChars += chunkLen
            } else {
                // If this chunk cannot fit completely and we have nothing yet, take a truncated slice
                if (selected.isEmpty()) {
                    val safeSlice = scoredChunk.chunk.content.take(maxChars)
                    selected.add(
                        scoredChunk.chunk.copy(
                            content = safeSlice,
                            charCount = safeSlice.length,
                        )
                    )
                }
                break
            }
        }

        return selected
    }

    /**
     * Formats the retrieved chunks into a prompt-injection string, ensuring that
     * the returned text strictly satisfies `result.length <= maxChars`.
     */
    fun formatPromptContext(
        query: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
        topK: Int = 10,
    ): String {
        require(maxChars > 0) { "maxChars must be positive." }

        val chunks = retrieveWithinBudget(query, maxChars, topK)
        if (chunks.isEmpty()) return ""

        val sb = StringBuilder()
        for ((idx, chunk) in chunks.withIndex()) {
            val header = "\n\n[Excerpt ${idx + 1} from ${chunk.source.ifBlank { "attachment" }} (chunk ${chunk.chunkIndex})]\n"
            val nextTotal = sb.length + header.length + chunk.content.length

            if (nextTotal <= maxChars) {
                sb.append(header)
                sb.append(chunk.content)
            } else {
                val remaining = maxChars - sb.length
                if (remaining > header.length + 20) {
                    sb.append(header)
                    val contentSpace = maxChars - sb.length
                    sb.append(chunk.content.take(contentSpace))
                }
                break
            }
        }

        return sb.toString().trim()
    }
}
