package com.example.pocketagent

import java.util.UUID

/**
 * Represents a local multi-turn conversation session.
 *
 * Persisted purely locally in the app's private files directory with no cloud sync.
 * Sessions can be cleared, exported, or deleted by the user at will.
 *
 * @property id Unique session identifier.
 * @property title Human-readable session title (typically derived from the initial user prompt).
 * @property createdAt Epoch millisecond timestamp when the session was created.
 * @property updatedAt Epoch millisecond timestamp of the last update or message.
 * @property messages Ordered list of turns in this session.
 */
data class ConversationSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val messages: List<ChatMessage> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "Session ID cannot be blank." }
        require(isValidSessionId(id)) {
            "Invalid session ID '$id'. Session IDs must be alphanumeric with dashes or underscores, 1-128 chars."
        }
        require(title.isNotBlank()) { "Session title cannot be blank." }
        require(createdAt > 0) { "createdAt timestamp must be positive." }
        require(updatedAt >= createdAt) { "updatedAt ($updatedAt) cannot be earlier than createdAt ($createdAt)." }
    }

    val totalEstimatedTokens: Int
        get() = messages.sumOf { it.estimatedTokens }

    val messageCount: Int
        get() = messages.size

    val lastMessage: ChatMessage?
        get() = messages.lastOrNull()

    /**
     * Returns an updated session instance containing the appended message.
     */
    fun withMessage(message: ChatMessage, customUpdatedAt: Long? = null): ConversationSession {
        val stamp = customUpdatedAt ?: maxOf(updatedAt, message.timestamp)
        return copy(
            updatedAt = stamp,
            messages = messages + message,
        )
    }

    /**
     * Returns a session instance with all messages cleared while preserving metadata.
     */
    fun clearMessages(customUpdatedAt: Long? = null): ConversationSession {
        val stamp = customUpdatedAt ?: System.currentTimeMillis()
        return copy(
            updatedAt = maxOf(updatedAt, stamp),
            messages = emptyList(),
        )
    }

    companion object {
        private val SESSION_ID_REGEX = "^[a-zA-Z0-9_-]{1,128}$".toRegex()
        private const val MAX_TITLE_CHARS = 80

        fun isValidSessionId(id: String): Boolean = SESSION_ID_REGEX.matches(id)

        fun createWithInitialPrompt(
            prompt: String,
            id: String = UUID.randomUUID().toString(),
            timestamp: Long = System.currentTimeMillis(),
        ): ConversationSession {
            val title = deriveTitle(prompt)
            return ConversationSession(
                id = id,
                title = title,
                createdAt = timestamp,
                updatedAt = timestamp,
                messages = emptyList(),
            )
        }

        fun deriveTitle(prompt: String): String {
            val trimmed = prompt.trim()
            if (trimmed.isEmpty()) return "New Conversation"
            val firstLine = trimmed.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "New Conversation"
            return if (firstLine.length > MAX_TITLE_CHARS) {
                firstLine.take(MAX_TITLE_CHARS - 3).trimEnd() + "..."
            } else {
                firstLine
            }
        }
    }
}
