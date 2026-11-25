package com.example.pocketagent

import java.util.Locale

/**
 * Pure-Kotlin agent step tracking, live telemetry formatting, and local notes retrieval
 * for PocketAgent.
 *
 * Invariants:
 * - Zero Android imports: 100% JVM unit-testable.
 * - Zero external telemetry or network calls: formats only local timing/token stats.
 * - Bounded retrieval: [NotesRetriever] strictly respects [maxNotes] and [maxTotalChars].
 */
object AgentActivityTracker {

    enum class StepKind {
        MODEL_LOAD,
        MEMORY,
        NOTES,
        CALCULATOR,
        DOCUMENT,
        GENERATION,
        VERIFICATION,
    }

    enum class StepStatus {
        RUNNING,
        DONE,
        INFO,
        SKIPPED,
    }

    data class AgentStepBadge(
        val kind: StepKind,
        val title: String,
        val detail: String,
        val status: StepStatus = StepStatus.DONE,
    )
}

typealias StepKind = AgentActivityTracker.StepKind
typealias StepStatus = AgentActivityTracker.StepStatus
typealias AgentStepBadge = AgentActivityTracker.AgentStepBadge

/**
 * Formats live stage badges and completed generation metrics purely from local execution stats.
 */
object AgentTelemetryFormatter {

    /**
     * Formats the live status badge shown while loading, preparing context, or streaming tokens.
     */
    fun formatStreamingBadge(
        stageLabel: String,
        elapsedMs: Long,
        streamedChars: Int = 0,
        firstTokenElapsedMs: Long? = null,
    ): String {
        val duration = formatDuration(elapsedMs)
        if (firstTokenElapsedMs == null || streamedChars <= 0) {
            val label = stageLabel.trim().ifEmpty { "Thinking" }
            return "$label · $duration"
        }

        val tokens = (streamedChars / 4.0).toInt().coerceAtLeast(1)
        val decodeMs = elapsedMs - firstTokenElapsedMs
        return if (decodeMs >= 400L && tokens >= 2) {
            val tokPerSec = String.format(Locale.US, "%.1f", (tokens * 1000.0) / decodeMs)
            "Streaming · $duration · ~$tokens tok · $tokPerSec t/s"
        } else {
            "Streaming · $duration · ~$tokens tok"
        }
    }

    /**
     * Formats the final attribution and speed badge appended to completed assistant turns.
     */
    fun formatCompletedBadge(
        backendTag: String,
        elapsedMs: Long,
        finalChars: Int = 0,
        firstTokenElapsedMs: Long? = null,
    ): String {
        val tag = backendTag.trim().ifEmpty { "Local" }
        val duration = formatDuration(elapsedMs)
        if (firstTokenElapsedMs != null && finalChars >= 24) {
            val decodeMs = elapsedMs - firstTokenElapsedMs
            if (decodeMs >= 400L) {
                val tokens = (finalChars / 4.0).toInt().coerceAtLeast(1)
                val tokPerSec = String.format(Locale.US, "%.1f", (tokens * 1000.0) / decodeMs)
                return "PocketAgent · $tag · $duration · $tokPerSec t/s"
            }
        }
        return "PocketAgent · $tag · $duration"
    }

    /**
     * Formats elapsed milliseconds cleanly (`0.0s` below 60s, `%dm %02ds` at >= 60s).
     */
    fun formatDuration(elapsedMs: Long): String {
        if (elapsedMs < 0L) return "0.0s"
        if (elapsedMs < 60_000L) {
            return String.format(Locale.US, "%.1fs", elapsedMs / 1000.0)
        }
        val totalSeconds = elapsedMs / 1000L
        val mins = totalSeconds / 60L
        val secs = totalSeconds % 60L
        return String.format(Locale.US, "%dm %02ds", mins, secs)
    }
}

/**
 * Pure-Kotlin relevance retriever over [PersonalNotesStore.PersonalNote] entries.
 */
object NotesRetriever {

    private val ALPHANUMERIC_TOKEN_REGEX = Regex("[a-z0-9]+")
    private val EXTRA_STOP_WORDS = setOf(
        "can", "would", "please", "also", "like", "make", "tell", "give", "show", "find",
    )
    private val STOP_WORDS: Set<String> = OfflineKeywordIndex.STOP_WORDS + EXTRA_STOP_WORDS

    /**
     * Finds up to [maxNotes] positive-scoring personal notes relevant to [query],
     * bounded smoothly within [maxTotalChars] across note titles and contents.
     *
     * Query tokens (alphanumeric, length >= 3, non-stopword) are weighted:
     * - Title match: 2x
     * - Tag match: 2x
     * - Content match: 1x
     */
    fun findRelevantNotes(
        query: String,
        notes: List<PersonalNotesStore.PersonalNote>,
        maxNotes: Int = 2,
        maxTotalChars: Int = 900,
    ): List<PersonalNotesStore.PersonalNote> {
        if (query.isBlank() || notes.isEmpty() || maxNotes <= 0 || maxTotalChars <= 0) {
            return emptyList()
        }

        val queryTokens = tokenizeQuery(query)
        if (queryTokens.isEmpty()) return emptyList()

        val scored = notes.mapNotNull { note ->
            val titleTokens = tokenizeText(note.title)
            val tagTokens = note.tags.flatMapTo(HashSet()) { tokenizeText(it) }
            val contentTokens = tokenizeText(note.content)

            var score = 0
            for (token in queryTokens) {
                if (token in titleTokens) score += 2
                if (token in tagTokens) score += 2
                if (token in contentTokens) score += 1
            }

            if (score > 0) ScoredNote(note, score) else null
        }.sortedWith(
            compareByDescending<ScoredNote> { it.score }
                .thenByDescending { it.note.isPinned }
                .thenByDescending { it.note.updatedAt }
        )

        if (scored.isEmpty()) return emptyList()

        val selected = mutableListOf<PersonalNotesStore.PersonalNote>()
        var usedChars = 0

        for (entry in scored) {
            if (selected.size >= maxNotes) break
            val note = entry.note
            val noteChars = note.title.length + note.content.length

            if (usedChars + noteChars <= maxTotalChars) {
                selected.add(note)
                usedChars += noteChars
            } else {
                val remaining = maxTotalChars - usedChars
                if (selected.isEmpty() && remaining > 0) {
                    val trimmedTitle = note.title.take(remaining).trimEnd()
                    if (trimmedTitle.isNotBlank()) {
                        val contentBudget = (remaining - trimmedTitle.length).coerceAtLeast(0)
                        val trimmedContent = trimContentSmoothly(note.content, contentBudget)
                        if (trimmedTitle.length + trimmedContent.length <= maxTotalChars) {
                            selected.add(
                                note.copy(
                                    title = trimmedTitle,
                                    content = trimmedContent,
                                )
                            )
                            usedChars += trimmedTitle.length + trimmedContent.length
                        }
                    }
                }
                break
            }
        }

        return selected
    }

    private data class ScoredNote(
        val note: PersonalNotesStore.PersonalNote,
        val score: Int,
    )

    private fun tokenizeQuery(query: String): List<String> =
        ALPHANUMERIC_TOKEN_REGEX.findAll(query.lowercase(Locale.US))
            .map { it.value }
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .distinct()
            .toList()

    private fun tokenizeText(text: String): Set<String> =
        ALPHANUMERIC_TOKEN_REGEX.findAll(text.lowercase(Locale.US))
            .map { it.value }
            .toHashSet()

    private fun trimContentSmoothly(content: String, maxChars: Int): String {
        if (maxChars <= 0 || content.isEmpty()) return ""
        if (content.length <= maxChars) return content
        if (maxChars <= 3) return content.take(maxChars)
        val slice = content.substring(0, maxChars - 1)
        val lastSpace = slice.lastIndexOf(' ')
        val clean = if (lastSpace >= slice.length / 2) slice.substring(0, lastSpace) else slice
        return clean.trimEnd() + "…"
    }
}
