package com.example.pocketagent

import java.util.UUID

/**
 * Represents a single conversational turn in PocketAgent.
 *
 * @property id Unique message identifier.
 * @property role Role of the author (USER, ASSISTANT, or SYSTEM).
 * @property content The main textual message body.
 * @property timestamp Unix epoch timestamp in milliseconds when the message was created.
 * @property attachmentSummary Optional human-readable summary of attachments (e.g. file names or excerpt).
 * @property attachmentReferences Optional list of referenced attachment names, URIs, or IDs.
 * @property attachmentContent Optional untrusted raw or extracted attachment text.
 * @property estimatedTokens Estimated token count for this turn including attachments.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachmentSummary: String? = null,
    val attachmentReferences: List<String> = emptyList(),
    val attachmentContent: String? = null,
    val estimatedTokens: Int = estimateTokens(content, attachmentSummary, attachmentContent),
) {
    enum class Role {
        USER,
        ASSISTANT,
        SYSTEM;

        companion object {
            fun fromString(value: String): Role {
                return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                    ?: throw IllegalArgumentException("Unknown role: '$value'")
            }
        }
    }

    init {
        require(content.isNotBlank() || !attachmentContent.isNullOrBlank() || !attachmentSummary.isNullOrBlank()) {
            "ChatMessage must have non-blank content or attachment details."
        }
        require(estimatedTokens >= 0) { "Estimated tokens must not be negative." }
    }

    companion object {
        private val WHITESPACE_REGEX = "\\s+".toRegex()

        /**
         * Conservative token estimator for local Gemma / LiteRT-LM models.
         *
         * Accounts for word boundaries, character-to-token ratio (~3.5 chars/token),
         * and attachment formatting overhead so offline budget enforcement remains safe.
         */
        fun estimateTokens(
            content: String,
            attachmentSummary: String? = null,
            attachmentContent: String? = null,
        ): Int {
            var total = estimateTextTokens(content)
            if (!attachmentContent.isNullOrEmpty()) {
                total += estimateTextTokens(attachmentContent) + 12 // delimiter overhead
            } else if (!attachmentSummary.isNullOrEmpty()) {
                total += estimateTextTokens(attachmentSummary) + 8
            }
            return total.coerceAtLeast(1)
        }

        fun estimateTextTokens(text: String): Int {
            if (text.isBlank()) return 0
            val trimmed = text.trim()
            val charBased = Math.ceil(trimmed.length / 3.5).toInt()
            val wordBased = trimmed.split(WHITESPACE_REGEX).count { it.isNotEmpty() }
            return maxOf(charBased, wordBased).coerceAtLeast(1)
        }
    }
}

typealias ChatRole = ChatMessage.Role
