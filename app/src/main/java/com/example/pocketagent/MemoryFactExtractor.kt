package com.example.pocketagent

import java.util.Locale

/**
 * Purely local, rule-based heuristic fact extractor for PocketAgent.
 *
 * Automatically detects statements about user identity, preferences, constraints,
 * and goals from conversation inputs without sending data off-device.
 */
object MemoryFactExtractor {

    data class CandidateFact(
        val content: String,
        val category: UserMemoryStore.MemoryCategory,
        val confidence: Float,
    )

    private val PREFERENCE_PATTERNS = listOf(
        Regex("""(?i)\b(?:i prefer|always use|please respond in|always reply in|prefer responding in)\s+([^.,;!?\n]+)"""),
        Regex("""(?i)\b(?:remember that i prefer|note that i like|i like to receive)\s+([^.,;!?\n]+)"""),
        Regex("""(?:আমি পছন্দ করি|আমার পছন্দ)\s+([^.,;!?\n]+)"""),
        Regex("""(?:সবসময়|সর্বদা)\s+([^.,;!?\n]+)\s+(?:উত্তর দিও|বলো|লিখো)"""),
    )

    private val PROFILE_PATTERNS = listOf(
        Regex("""(?i)\b(?:my name is|i am a|i work as|i'm a|my profession is)\s+([^.,;!?\n]+)"""),
        Regex("""(?:আমার নাম)\s+([^.,;!?\n]+)"""),
        Regex("""(?:আমি একজন)\s+([^.,;!?\n]+)"""),
    )

    private val CONSTRAINT_PATTERNS = listOf(
        Regex("""(?i)\b(?:my phone has only|i have only|no internet|offline only|limited storage|limited ram)\s*([^.,;!?\n]*)"""),
        Regex("""(?:আমার ফোনে শুধুমাত্র|ইন্টারনেট নেই|অফলাইনে)\s*([^.,;!?\n]*)"""),
    )

    private val GOAL_PATTERNS = listOf(
        Regex("""(?i)\b(?:i am learning|my goal is to|i want to learn|i am studying)\s+([^.,;!?\n]+)"""),
        Regex("""(?:আমি শিখছি|আমার লক্ষ্য)\s+([^.,;!?\n]+)"""),
    )

    /**
     * Extracts potential long-term memory candidate facts from a user message.
     */
    fun extractCandidateFacts(text: String): List<CandidateFact> {
        val trimmed = text.trim()
        if (trimmed.length < 8) return emptyList()

        val results = mutableListOf<CandidateFact>()

        // 1. Preferences
        for (pattern in PREFERENCE_PATTERNS) {
            for (match in pattern.findAll(trimmed)) {
                val captured = match.groupValues.getOrNull(1)?.trim()
                if (!captured.isNullOrBlank() && captured.length in 3..120) {
                    results.add(
                        CandidateFact(
                            content = "Prefers $captured",
                            category = UserMemoryStore.MemoryCategory.PREFERENCE,
                            confidence = 0.85f,
                        )
                    )
                }
            }
        }

        // 2. Profile
        for (pattern in PROFILE_PATTERNS) {
            for (match in pattern.findAll(trimmed)) {
                val captured = match.groupValues.getOrNull(1)?.trim()
                if (!captured.isNullOrBlank() && captured.length in 2..80) {
                    results.add(
                        CandidateFact(
                            content = captured,
                            category = UserMemoryStore.MemoryCategory.PROFILE,
                            confidence = 0.90f,
                        )
                    )
                }
            }
        }

        // 3. Constraints
        for (pattern in CONSTRAINT_PATTERNS) {
            for (match in pattern.findAll(trimmed)) {
                val raw = match.value.trim()
                if (raw.isNotBlank() && raw.length in 6..100) {
                    results.add(
                        CandidateFact(
                            content = raw,
                            category = UserMemoryStore.MemoryCategory.CONSTRAINT,
                            confidence = 0.80f,
                        )
                    )
                }
            }
        }

        // 4. Goals
        for (pattern in GOAL_PATTERNS) {
            for (match in pattern.findAll(trimmed)) {
                val captured = match.groupValues.getOrNull(1)?.trim()
                if (!captured.isNullOrBlank() && captured.length in 3..100) {
                    results.add(
                        CandidateFact(
                            content = "Goal: $captured",
                            category = UserMemoryStore.MemoryCategory.GOAL,
                            confidence = 0.85f,
                        )
                    )
                }
            }
        }

        return results.distinctBy { it.content.lowercase(Locale.ROOT) }
    }
}
