package com.example.pocketagent

/**
 * Adaptive offline reasoning & language router for small on-device models (1B-3B).
 *
 * Tunes temperature, compact prompt bias, and Deep Think eligibility per query without adding
 * extra inference passes for everyday questions. 100% offline, zero Android imports.
 */
object ReasoningRouter {

    enum class TaskProfile {
        QUICK,
        LOGIC_CODE,
        GROUNDED_DOCS,
        CREATIVE,
    }

    enum class LanguageMode {
        ENGLISH,
        BENGALI_SCRIPT,
        BANGLISH,
    }

    data class RoutingDecision(
        val profile: TaskProfile,
        val languageMode: LanguageMode,
        val recommendedTemperature: Float,
        val compactDirective: String,
        val shouldAllowDeepThink: Boolean,
    )

    private val GREETING_ONLY_REGEX = Regex(
        """^(hi|hello|hey|hy|hii|yo|namaste|salam|nomoshkar|thanks|thank you|ok|okay|bye)[!?. ]*$""",
        RegexOption.IGNORE_CASE,
    )
    private val LOGIC_CODE_KEYWORDS = listOf(
        "code", "kotlin", "python", "java", "javascript", "html", "css", "sql", "regex", "bug",
        "debug", "error", "crash", "fix", "algorithm", "function", "class", "calculate", "math",
        "equation", "percent", "compare", "difference", "why", "how does", "step", "plan", "verify",
    )
    private val CREATIVE_KEYWORDS = listOf(
        "story", "poem", "essay", "caption", "tagline", "brainstorm", "joke", "lyrics", "script",
    )
    private val BANGLISH_MARKERS = setOf(
        "ami", "tumi", "apni", "amar", "tomar", "koro", "korbo", "kivabe", "keno", "ki", "kemon",
        "bhalo", "valo", "bolo", "bolun", "bangla", "banglay", "kichu", "kichhu", "ekta", "ache",
        "achhe", "hobe", "hoyeche", "kore", "dao", "debo", "chahiye", "chai", "jabe", "parbo",
    )
    private val WORD_SPLIT_REGEX = Regex("""[\p{L}\p{M}\p{N}]+""")

    fun detectLanguageMode(text: String): LanguageMode {
        if (text.any { it in '\u0980'..'\u09FF' }) {
            return LanguageMode.BENGALI_SCRIPT
        }
        val words = WORD_SPLIT_REGEX.findAll(text.lowercase()).map { it.value }.toList()
        val banglishHits = words.count { it in BANGLISH_MARKERS }
        if (banglishHits >= 2 || (words.size <= 5 && banglishHits >= 1)) {
            return LanguageMode.BANGLISH
        }
        return LanguageMode.ENGLISH
    }

    fun route(
        task: String,
        hasGroundedContext: Boolean,
        userTemperature: Float,
    ): RoutingDecision {
        val clean = task.trim()
        val lower = clean.lowercase()
        val lang = detectLanguageMode(clean)

        val profile = when {
            clean.isNotEmpty() && GREETING_ONLY_REGEX.matches(clean) && !hasGroundedContext ->
                TaskProfile.QUICK
            hasGroundedContext ->
                TaskProfile.GROUNDED_DOCS
            LOGIC_CODE_KEYWORDS.any { lower.contains(it) } || clean.any { it in "+-*/=" } && clean.any { it.isDigit() } ->
                TaskProfile.LOGIC_CODE
            CREATIVE_KEYWORDS.any { lower.contains(it) } ->
                TaskProfile.CREATIVE
            clean.length >= 48 ->
                TaskProfile.LOGIC_CODE
            else ->
                TaskProfile.QUICK
        }

        val clampedUserTemp = userTemperature.coerceIn(0.1f, 1.5f)
        val effectiveTemp = when (profile) {
            TaskProfile.LOGIC_CODE -> minOf(clampedUserTemp, 0.25f)
            TaskProfile.GROUNDED_DOCS -> minOf(clampedUserTemp, 0.35f)
            TaskProfile.QUICK, TaskProfile.CREATIVE -> clampedUserTemp
        }

        val taskBias = when (profile) {
            TaskProfile.LOGIC_CODE ->
                "Verify every step, edge case, and calculation carefully before writing the final answer. Be precise."
            TaskProfile.GROUNDED_DOCS ->
                "Base your answer strictly on the provided notes/documents. Quote exact figures or say if missing."
            TaskProfile.CREATIVE ->
                "Be expressive, vivid, and original."
            TaskProfile.QUICK ->
                "Answer directly and concisely."
        }

        val langBias = when (lang) {
            LanguageMode.BENGALI_SCRIPT ->
                "Reply in clear, natural Bengali (\u09AC\u09BE\u0982\u09B2\u09BE) with accurate grammar; keep code blocks in their original syntax."
            LanguageMode.BANGLISH ->
                "The user wrote in Banglish (romanized Bengali). Understand the Bengali intent and reply clearly in natural Bengali or English as fits best."
            LanguageMode.ENGLISH -> ""
        }

        val directive = listOf(taskBias, langBias)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        return RoutingDecision(
            profile = profile,
            languageMode = lang,
            recommendedTemperature = effectiveTemp,
            compactDirective = directive,
            shouldAllowDeepThink = profile != TaskProfile.QUICK,
        )
    }

    /**
     * Removes duplicate or near-duplicate excerpts while preserving relevance order.
     */
    fun deduplicateExcerpts(excerpts: List<String>): List<String> {
        if (excerpts.size <= 1) return excerpts.filter { it.isNotBlank() }
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<String>()
        for (raw in excerpts) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val signature = trimmed.lowercase().replace(Regex("""\s+"""), " ").take(120)
            if (seen.add(signature)) {
                result.add(trimmed)
            }
        }
        return result
    }

    /**
     * Builds a bounded second-pass self-critique prompt when Deep Think mode is enabled.
     */
    fun buildDeepThinkCritiquePrompt(originalTask: String, draftAnswer: String): String {
        val boundedDraft = draftAnswer.trim().take(2_400)
        return "User request:\n${originalTask.trim()}\n\n" +
            "Initial draft:\n$boundedDraft\n\n" +
            "Deep Think Verification: Carefully check the draft for arithmetic errors, broken code/syntax, " +
            "contradictions, or missed user constraints. Output ONLY the improved, accurate final answer."
    }
}
