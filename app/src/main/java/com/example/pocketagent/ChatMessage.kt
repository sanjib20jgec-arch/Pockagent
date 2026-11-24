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

    val isUser: Boolean get() = role == Role.USER
    val isAssistant: Boolean get() = role == Role.ASSISTANT
    val isSystem: Boolean get() = role == Role.SYSTEM

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

        fun user(
            content: String,
            attachmentSummary: String? = null,
            attachmentReferences: List<String> = emptyList(),
            attachmentContent: String? = null,
        ): ChatMessage = ChatMessage(
            role = Role.USER,
            content = content,
            attachmentSummary = attachmentSummary,
            attachmentReferences = attachmentReferences,
            attachmentContent = attachmentContent,
        )

        fun assistant(content: String): ChatMessage =
            ChatMessage(role = Role.ASSISTANT, content = content)

        fun system(content: String): ChatMessage =
            ChatMessage(role = Role.SYSTEM, content = content)

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

/**
 * Decides which prior turns belong in the next prompt.
 *
 * The turn currently being answered is always passed to the model separately, so including it in
 * history would send the same question twice. A regenerate also drops the answer being replaced.
 */
object HistoryPolicy {

    fun forPrompt(messages: List<ChatMessage>, pendingTask: String): List<ChatMessage> {
        val task = pendingTask.trim()

        // Regenerating a completed turn: drop the old answer and its identical prompt.
        if (messages.size >= 2) {
            val last = messages[messages.size - 1]
            val previous = messages[messages.size - 2]
            if (last.isAssistant && previous.isUser && previous.content.trim() == task) {
                return messages.dropLast(2)
            }
        }

        // The in-progress prompt may already be persisted as the trailing user turn.
        if (messages.isNotEmpty()) {
            val last = messages[messages.size - 1]
            if (last.isUser && last.content.trim() == task) {
                return messages.dropLast(1)
            }
        }

        return messages.toList()
    }
}
