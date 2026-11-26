package com.example.pocketagent

import kotlin.math.roundToInt

/**
 * Calm, actionable hardware backend status and optimization guidance.
 *
 * @property isCpuFallback True when the user prefers GPU acceleration but the engine fell back to CPU.
 * @property headline Short status label suitable for a settings row or diagnostic banner.
 * @property tip Concise, calm explanation of why the current backend is active or how to improve speed.
 */
data class BackendDiagnostic(
    val isCpuFallback: Boolean,
    val headline: String,
    val tip: String,
)

/**
 * Pure-Kotlin helpers for conversational context continuity and hardware backend diagnostics.
 *
 * Keeps all logic 100% offline and JVM-testable with zero Android dependencies.
 */
object ContextContinuityHelper {

    private const val DEFAULT_THRESHOLD_RATIO = 0.75f
    private const val MAX_CARRY_OVER_TURNS = 6
    private const val MIN_SINGLE_TURN_CHARS = 24
    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Returns true when token usage has reached or exceeded [thresholdRatio] of [maxTokens],
     * signalling that the user should be offered a smooth carry-over into a fresh chat.
     */
    fun shouldSuggestContinue(
        usedTokens: Int,
        maxTokens: Int,
        thresholdRatio: Float = DEFAULT_THRESHOLD_RATIO,
    ): Boolean {
        if (maxTokens <= 0 || usedTokens <= 0 || thresholdRatio.isNaN()) return false
        val effectiveThreshold = thresholdRatio.coerceIn(0f, 1f)
        val usageRatio = usedTokens.toFloat() / maxTokens.toFloat()
        return usageRatio >= effectiveThreshold
    }

    /**
     * Formats a concise banner label showing how full the current context window is.
     */
    fun formatContextAlert(usedTokens: Int, maxTokens: Int): String {
        val percent = if (maxTokens <= 0 || usedTokens <= 0) {
            0
        } else {
            ((usedTokens.toDouble() / maxTokens.toDouble()) * 100.0)
                .roundToInt()
                .coerceIn(0, 100)
        }
        return "Context $percent% full · Summarize & continue in a fresh chat"
    }

    /**
     * Builds a deterministic, local carry-over summary of the most recent user/assistant turns
     * from [session] so a fresh chat can be seeded without losing conversational context.
     *
     * Never exceeds [maxChars] and returns an empty string when [session] is null, has no
     * user/assistant turns, or [maxChars] is non-positive.
     */
    fun buildCarryOverSeed(
        session: ConversationSession?,
        maxChars: Int = 1200,
    ): String {
        if (session == null || maxChars <= 0) return ""

        val relevantTurns = session.messages.filter {
            it.role == ChatMessage.Role.USER || it.role == ChatMessage.Role.ASSISTANT
        }
        if (relevantTurns.isEmpty()) return ""

        val cleanTitle = collapseWhitespace(session.title)
        val header = "Continuing from previous chat (\"$cleanTitle\"):"
        if (header.length >= maxChars) {
            return truncateWithEllipsis(header, maxChars)
        }

        val perTurnBudget = (maxChars / 3).coerceIn(80, 280)
        val candidateLines = relevantTurns
            .takeLast(MAX_CARRY_OVER_TURNS)
            .mapNotNull { msg -> formatTurnLine(msg, perTurnBudget) }

        if (candidateLines.isEmpty()) return ""

        val selectedReversed = mutableListOf<String>()
        var currentLength = header.length

        for (line in candidateLines.asReversed()) {
            val addedLength = 1 + line.length // '\n' + line
            if (currentLength + addedLength <= maxChars) {
                selectedReversed.add(line)
                currentLength += addedLength
            } else {
                break
            }
        }

        // If even the newest turn was slightly too long for the remaining budget,
        // include a truncated version of it rather than returning only a bare header.
        if (selectedReversed.isEmpty()) {
            val remaining = maxChars - header.length - 1
            if (remaining >= MIN_SINGLE_TURN_CHARS) {
                selectedReversed.add(truncateWithEllipsis(candidateLines.last(), remaining))
            } else {
                return header
            }
        }

        return buildString(maxChars.coerceAtMost(2048)) {
            append(header)
            for (line in selectedReversed.asReversed()) {
                append('\n').append(line)
            }
        }
    }

    /**
     * Produces a calm, clear diagnostic describing the active inference backend,
     * why a CPU fallback occurred (if any), and practical tips to optimize speed.
     */
    fun formatBackendDiagnostic(
        activeBackend: ActiveBackend?,
        preference: BackendPreference,
        lastFallbackReason: String?,
        visionEnabled: Boolean,
    ): BackendDiagnostic {
        val cleanReason = lastFallbackReason
            ?.let { collapseWhitespace(it) }
            ?.takeIf { it.isNotEmpty() }

        val isCpuFallback = activeBackend == ActiveBackend.CPU &&
            preference == BackendPreference.GPU_PREFERRED

        return when (activeBackend) {
            ActiveBackend.GPU -> BackendDiagnostic(
                isCpuFallback = false,
                headline = "Running on GPU",
                tip = if (visionEnabled) {
                    "GPU acceleration is active for both text and image inputs. Keeping chats concise helps maintain fast replies."
                } else {
                    "GPU acceleration is active for the fastest on-device responses."
                },
            )

            ActiveBackend.CPU -> if (isCpuFallback) {
                val reasonClause = if (cleanReason != null) " ($cleanReason)" else ""
                val speedHint = if (visionEnabled) {
                    "Vision inputs on CPU take extra time; turn off vision for text-only chats or start a fresh chat to stay responsive."
                } else {
                    "Keeping chats shorter or switching to a lighter model will help speed up replies."
                }
                BackendDiagnostic(
                    isCpuFallback = true,
                    headline = "Running on CPU (GPU fallback)",
                    tip = "GPU acceleration wasn't available$reasonClause, so PocketAgent switched to CPU for stability. $speedHint",
                )
            } else {
                BackendDiagnostic(
                    isCpuFallback = false,
                    headline = "Running on CPU",
                    tip = if (visionEnabled) {
                        "CPU-only mode is active. Image analysis on CPU is slower than text; switch to GPU Preferred if your device supports it."
                    } else {
                        "CPU-only mode is active. If your device supports GPU acceleration, switching to GPU Preferred can speed up replies."
                    },
                )
            }

            null -> {
                val tip = when {
                    cleanReason != null ->
                        "Previous GPU initialization fell back ($cleanReason). The engine will load on your next message."
                    preference == BackendPreference.GPU_PREFERRED ->
                        "Engine on standby. Ready to initialize with GPU acceleration on your next message."
                    else ->
                        "Engine on standby. Ready to initialize in CPU-only mode on your next message."
                }
                BackendDiagnostic(
                    isCpuFallback = false,
                    headline = "Engine on standby",
                    tip = tip,
                )
            }
        }
    }

    private fun formatTurnLine(message: ChatMessage, maxBodyChars: Int): String? {
        val roleLabel = when (message.role) {
            ChatMessage.Role.USER -> "User"
            ChatMessage.Role.ASSISTANT -> "Assistant"
            ChatMessage.Role.SYSTEM -> return null
        }

        val cleanedContent = collapseWhitespace(message.content)
        val body = if (cleanedContent.isNotEmpty()) {
            cleanedContent
        } else {
            val attachmentLabel = message.attachmentSummary
                ?.let { collapseWhitespace(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: message.attachmentReferences
                    .map { collapseWhitespace(it) }
                    .filter { it.isNotEmpty() }
                    .joinToString(", ")
                    .takeIf { it.isNotEmpty() }
            if (attachmentLabel != null) "[Attachment: $attachmentLabel]" else return null
        }

        val snippet = truncateWithEllipsis(body, maxBodyChars)
        return "- $roleLabel: $snippet"
    }

    private fun collapseWhitespace(text: String): String =
        text.replace(WHITESPACE_REGEX, " ").trim()

    private fun truncateWithEllipsis(text: String, maxLength: Int): String {
        if (maxLength <= 0) return ""
        if (text.length <= maxLength) return text
        if (maxLength == 1) return "…"
        return text.take(maxLength - 1).trimEnd() + "…"
    }
}
