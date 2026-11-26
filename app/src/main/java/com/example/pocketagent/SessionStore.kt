package com.example.pocketagent

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant

/**
 * Lightweight metadata summary of a persisted conversation session.
 */
data class SessionSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val totalEstimatedTokens: Int,
    val isPinned: Boolean = false,
)

/**
 * Persists conversation sessions locally in the app's private files directory.
 *
 * Strict Privacy & Offline Invariants:
 * - Pure local storage in app private storage (no cloud sync, no telemetry).
 * - Sessions can be exported, inspected, deleted individually, or completely cleared by the user.
 * - Atomic write operations guarantee files cannot be corrupted by abrupt termination.
 * - Path traversal attempts in session IDs are strictly rejected.
 */
class SessionStore(
    val directory: File,
    private val maxSessionBytes: Long = DEFAULT_MAX_SESSION_BYTES,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
) {
    init {
        require(maxSessionBytes > 0) { "maxSessionBytes must be positive." }
        require(maxSessions > 0) { "maxSessions must be positive." }
    }

    companion object {
        const val DEFAULT_MAX_SESSION_BYTES = 2 * 1024 * 1024L // 2 MiB per session
        const val DEFAULT_MAX_SESSIONS = 500
        const val SESSIONS_DIR_NAME = "sessions"
        private const val JSON_EXT = ".json"
        private const val TMP_EXT = ".json.tmp"
        private const val BACKUP_EXT = ".json.backup"

        fun defaultDir(contextFilesDir: File): File = File(contextFilesDir, SESSIONS_DIR_NAME)
    }

    private fun ensureDirectory(): File {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create private session directory: ${directory.absolutePath}")
        }
        if (!directory.isDirectory) {
            throw IOException("Session path is not a directory: ${directory.absolutePath}")
        }
        return directory
    }

    fun getSessionFile(sessionId: String): File {
        require(ConversationSession.isValidSessionId(sessionId)) {
            "Invalid session ID '$sessionId'. Session IDs must be alphanumeric with dashes or underscores."
        }
        val target = File(directory, "$sessionId$JSON_EXT")
        val parentCanonical = directory.canonicalFile
        val targetCanonical = target.canonicalFile
        require(targetCanonical.parentFile == parentCanonical) {
            "Path traversal detected for session ID '$sessionId'."
        }
        return target
    }

    @Throws(IOException::class)
    fun saveSession(session: ConversationSession): File {
        ensureDirectory()
        val target = getSessionFile(session.id)

        // Session count bound check (only when creating a new session)
        if (!target.exists() && sessionCount() >= maxSessions) {
            throw IOException("Session limit of $maxSessions reached. Clear or delete older sessions first.")
        }

        val jsonString = StoreMigration.tagWithSchemaVersion(SessionJsonCodec.serialize(session))
        val jsonBytes = jsonString.toByteArray(Charsets.UTF_8)
        if (jsonBytes.size > maxSessionBytes) {
            throw IOException("Session size (${jsonBytes.size} bytes) exceeds maximum limit of $maxSessionBytes bytes.")
        }

        val temporary = File(directory, "${session.id}$TMP_EXT")
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("Could not clear previous temporary file: ${temporary.name}")
        }

        var committed = false
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(jsonBytes)
                stream.fd.sync()
            }
            commitAtomic(temporary, target)
            committed = true
            return target
        } finally {
            if (!committed && temporary.exists()) {
                temporary.delete()
            }
        }
    }

    fun getSession(sessionId: String): ConversationSession? {
        if (!directory.exists() || !directory.isDirectory) return null
        return try {
            val file = getSessionFile(sessionId)
            if (!file.exists() || !file.isFile) null else loadSessionFromFile(file)
        } catch (_: Exception) {
            null
        }
    }

    fun listSessions(): List<ConversationSession> {
        if (!directory.exists() || !directory.isDirectory) return emptyList()
        val files = directory.listFiles { _, name -> name.endsWith(JSON_EXT) } ?: return emptyList()
        val sessions = mutableListOf<ConversationSession>()
        for (file in files) {
            try {
                val session = loadSessionFromFile(file)
                if (session != null) sessions.add(session)
            } catch (_: Exception) {
                // Ignore corrupt or unreadable files so one bad file doesn't block the session list
            }
        }
        return sessions.sortedByDescending { it.updatedAt }
    }

    fun listSessionSummaries(): List<SessionSummary> {
        return listSessions().map {
            SessionSummary(
                id = it.id,
                title = it.title,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
                messageCount = it.messageCount,
                totalEstimatedTokens = it.totalEstimatedTokens,
                isPinned = it.isPinned,
            )
        }.sortedWith(compareByDescending<SessionSummary> { it.isPinned }.thenByDescending { it.updatedAt })
    }

    fun pinSession(sessionId: String, pinned: Boolean): ConversationSession? {
        val session = getSession(sessionId) ?: return null
        val updated = session.withPinned(pinned)
        saveSession(updated)
        return updated
    }

    fun renameSession(sessionId: String, newTitle: String): ConversationSession? {
        val session = getSession(sessionId) ?: return null
        val updated = session.withTitle(newTitle)
        saveSession(updated)
        return updated
    }

    fun searchSessions(query: String): List<ConversationSession> {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return listSessions()
        return listSessions().filter { session ->
            session.title.lowercase().contains(trimmed) ||
                session.messages.any { it.content.lowercase().contains(trimmed) }
        }
    }

    fun exportSessionAsMarkdown(sessionId: String): String? {
        val session = getSession(sessionId) ?: return null
        return session.exportToMarkdown()
    }

    fun deleteSession(sessionId: String): Boolean {
        val file = getSessionFile(sessionId)
        if (!directory.exists() || !directory.isDirectory) return false
        return try {
            val temp = File(directory, "$sessionId$TMP_EXT")
            val backup = File(directory, "$sessionId$BACKUP_EXT")
            if (temp.exists()) temp.delete()
            if (backup.exists()) backup.delete()
            file.exists() && file.delete()
        } catch (_: IOException) {
            false
        }
    }

    fun clearAllSessions(): Int {
        if (!directory.exists() || !directory.isDirectory) return 0
        val files = directory.listFiles { _, name ->
            name.endsWith(JSON_EXT) || name.endsWith(TMP_EXT) || name.endsWith(BACKUP_EXT)
        } ?: return 0
        var deletedCount = 0
        for (file in files) {
            if (file.delete()) deletedCount++
        }
        return deletedCount
    }

    fun sessionCount(): Int {
        if (!directory.exists() || !directory.isDirectory) return 0
        return directory.listFiles { _, name -> name.endsWith(JSON_EXT) }?.size ?: 0
    }

    fun exportSessionAsJson(sessionId: String): String? {
        val session = getSession(sessionId) ?: return null
        return SessionJsonCodec.serialize(session)
    }

    fun exportSessionAsText(sessionId: String): String? {
        val session = getSession(sessionId) ?: return null
        val sb = StringBuilder()
        sb.append("PocketAgent Conversation Export\n")
        sb.append("Title: ").append(session.title).append("\n")
        sb.append("Session ID: ").append(session.id).append("\n")
        sb.append("Created: ").append(Instant.ofEpochMilli(session.createdAt)).append("\n")
        sb.append("Updated: ").append(Instant.ofEpochMilli(session.updatedAt)).append("\n")
        sb.append("Messages: ").append(session.messageCount).append("\n")
        sb.append("Estimated Tokens: ").append(session.totalEstimatedTokens).append("\n")
        sb.append("========================================\n\n")

        for (msg in session.messages) {
            val roleHeader = when (msg.role) {
                ChatMessage.Role.USER -> "[USER]"
                ChatMessage.Role.ASSISTANT -> "[ASSISTANT]"
                ChatMessage.Role.SYSTEM -> "[SYSTEM]"
            }
            sb.append(roleHeader).append(" (").append(Instant.ofEpochMilli(msg.timestamp)).append("):\n")
            sb.append(msg.content.trim()).append("\n")
            if (!msg.attachmentContent.isNullOrBlank()) {
                sb.append("\n[Attached Content (untrusted)]:\n").append(msg.attachmentContent.trim()).append("\n")
            } else if (!msg.attachmentSummary.isNullOrBlank()) {
                sb.append("\n[Attachment Summary]: ").append(msg.attachmentSummary.trim()).append("\n")
            }
            if (msg.attachmentReferences.isNotEmpty()) {
                sb.append("[Attachment References]: ").append(msg.attachmentReferences.joinToString(", ")).append("\n")
            }
            sb.append("\n----------------------------------------\n\n")
        }
        return sb.toString()
    }

    private fun loadSessionFromFile(file: File): ConversationSession? {
        if (!file.exists() || !file.isFile || file.length() == 0L) return null
        if (file.length() > maxSessionBytes) {
            throw IOException("Session file ${file.name} exceeds max size bound.")
        }
        val text = file.readText(Charsets.UTF_8)
        return if (StoreMigration.hasSchemaVersion(text)) {
            StoreMigration.json.decodeFromString<ConversationSession>(text)
        } else {
            SessionJsonCodec.deserialize(text)
        }
    }

    private fun commitAtomic(temporary: File, target: File) {
        try {
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            commitWithRollback(temporary, target)
        }
    }

    private fun commitWithRollback(temporary: File, target: File) {
        val backup = File(directory, "${target.name.removeSuffix(JSON_EXT)}$BACKUP_EXT")
        if (backup.exists() && !backup.delete()) {
            throw IOException("Could not clear previous backup file: ${backup.name}")
        }
        val hadPrevious = target.exists()
        if (hadPrevious) {
            Files.move(target.toPath(), backup.toPath(), REPLACE_EXISTING)
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
            if (backup.exists()) Files.delete(backup.toPath())
        } catch (failure: Exception) {
            if (hadPrevious && backup.exists()) {
                try {
                    Files.move(backup.toPath(), target.toPath(), REPLACE_EXISTING)
                } catch (restoreFailure: Exception) {
                    failure.addSuppressed(restoreFailure)
                }
            }
            throw failure
        }
    }
}

/**
 * Pure Kotlin JSON serializer and deserializer for ConversationSession and ChatMessage.
 *
 * Implemented without external JSON libraries or Android SDK dependencies so it runs identically
 * in unit tests (JVM) and on Android devices without stub or reflection issues.
 */
internal object SessionJsonCodec {
    fun serialize(session: ConversationSession): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"id\": \"").append(escapeJson(session.id)).append("\",\n")
        sb.append("  \"title\": \"").append(escapeJson(session.title)).append("\",\n")
        sb.append("  \"createdAt\": ").append(session.createdAt).append(",\n")
        sb.append("  \"updatedAt\": ").append(session.updatedAt).append(",\n")
        sb.append("  \"isPinned\": ").append(session.isPinned).append(",\n")
        sb.append("  \"messages\": [\n")
        for ((index, msg) in session.messages.withIndex()) {
            sb.append("    {\n")
            sb.append("      \"id\": \"").append(escapeJson(msg.id)).append("\",\n")
            sb.append("      \"role\": \"").append(msg.role.name).append("\",\n")
            sb.append("      \"content\": \"").append(escapeJson(msg.content)).append("\",\n")
            sb.append("      \"timestamp\": ").append(msg.timestamp).append(",\n")
            if (msg.attachmentSummary != null) {
                sb.append("      \"attachmentSummary\": \"").append(escapeJson(msg.attachmentSummary)).append("\",\n")
            } else {
                sb.append("      \"attachmentSummary\": null,\n")
            }
            if (msg.attachmentContent != null) {
                sb.append("      \"attachmentContent\": \"").append(escapeJson(msg.attachmentContent)).append("\",\n")
            } else {
                sb.append("      \"attachmentContent\": null,\n")
            }
            sb.append("      \"attachmentReferences\": [")
            for ((rIdx, ref) in msg.attachmentReferences.withIndex()) {
                sb.append("\"").append(escapeJson(ref)).append("\"")
                if (rIdx < msg.attachmentReferences.size - 1) sb.append(", ")
            }
            sb.append("],\n")
            sb.append("      \"estimatedTokens\": ").append(msg.estimatedTokens).append("\n")
            sb.append("    }")
            if (index < session.messages.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}")
        return sb.toString()
    }

    fun deserialize(json: String): ConversationSession {
        val parsed = SimpleJsonParser(json).parse() as? Map<*, *>
            ?: throw IllegalArgumentException("Expected JSON object at root.")

        val id = parsed["id"] as? String
            ?: throw IllegalArgumentException("Missing or invalid 'id' in session JSON.")
        val title = parsed["title"] as? String
            ?: throw IllegalArgumentException("Missing or invalid 'title' in session JSON.")
        val createdAt = (parsed["createdAt"] as? Number)?.toLong()
            ?: throw IllegalArgumentException("Missing or invalid 'createdAt' in session JSON.")
        val updatedAt = (parsed["updatedAt"] as? Number)?.toLong() ?: createdAt

        val rawMessages = parsed["messages"] as? List<*> ?: emptyList<Any?>()
        val messages = mutableListOf<ChatMessage>()
        for (item in rawMessages) {
            val map = item as? Map<*, *> ?: continue
            val msgId = map["id"] as? String ?: java.util.UUID.randomUUID().toString()
            val roleStr = map["role"] as? String
                ?: throw IllegalArgumentException("Missing 'role' in message item.")
            val role = ChatMessage.Role.fromString(roleStr)
            val content = map["content"] as? String ?: ""
            val timestamp = (map["timestamp"] as? Number)?.toLong() ?: createdAt
            val attachmentSummary = map["attachmentSummary"] as? String
            val attachmentContent = map["attachmentContent"] as? String
            val rawRefs = map["attachmentReferences"] as? List<*> ?: emptyList<Any?>()
            val references = rawRefs.mapNotNull { it as? String }
            val estimatedTokens = (map["estimatedTokens"] as? Number)?.toInt()
                ?: ChatMessage.estimateTokens(content, attachmentSummary, attachmentContent)

            messages.add(
                ChatMessage(
                    id = msgId,
                    role = role,
                    content = content,
                    timestamp = timestamp,
                    attachmentSummary = attachmentSummary,
                    attachmentReferences = references,
                    attachmentContent = attachmentContent,
                    estimatedTokens = estimatedTokens,
                )
            )
        }

        val isPinned = (parsed["isPinned"] as? Boolean) ?: false

        return ConversationSession(
            id = id,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt,
            messages = messages,
            isPinned = isPinned,
        )
    }

    fun escapeJson(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code in 0x00..0x1F) {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }
}

/**
 * Minimal recursive-descent JSON parser for safe offline parsing.
 */
internal class SimpleJsonParser(private val src: String) {
    private var index = 0

    fun parse(): Any? {
        skipWhitespace()
        val result = parseValue()
        skipWhitespace()
        if (index < src.length) {
            throw IllegalArgumentException("Unexpected trailing characters in JSON at position $index")
        }
        return result
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        if (index >= src.length) throw IllegalArgumentException("Unexpected end of JSON input")
        return when (val ch = src[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            '-', in '0'..'9' -> parseNumber()
            else -> throw IllegalArgumentException("Unexpected character '$ch' at index $index")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        val map = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            return map
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') throw IllegalArgumentException("Expected string key in object at index $index")
            val key = parseString()
            skipWhitespace()
            expect(':')
            val value = parseValue()
            map[key] = value
            skipWhitespace()
            val next = peek()
            if (next == '}') {
                index++
                break
            } else if (next == ',') {
                index++
            } else {
                throw IllegalArgumentException("Expected ',' or '}' in object at index $index, found '$next'")
            }
        }
        return map
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val list = ArrayList<Any?>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return list
        }
        while (true) {
            val value = parseValue()
            list.add(value)
            skipWhitespace()
            val next = peek()
            if (next == ']') {
                index++
                break
            } else if (next == ',') {
                index++
            } else {
                throw IllegalArgumentException("Expected ',' or ']' in array at index $index, found '$next'")
            }
        }
        return list
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (index < src.length) {
            val ch = src[index++]
            if (ch == '"') return sb.toString()
            if (ch == '\\') {
                if (index >= src.length) throw IllegalArgumentException("Unterminated escape sequence in JSON string")
                when (val esc = src[index++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (index + 4 > src.length) throw IllegalArgumentException("Incomplete unicode escape in JSON")
                        val hex = src.substring(index, index + 4)
                        index += 4
                        sb.append(hex.toInt(16).toChar())
                    }
                    else -> throw IllegalArgumentException("Unsupported escape '\\$esc' at index ${index - 1}")
                }
            } else {
                sb.append(ch)
            }
        }
        throw IllegalArgumentException("Unterminated string literal in JSON")
    }

    private fun parseNumber(): Number {
        val start = index
        if (src[index] == '-') index++
        while (index < src.length && src[index] in '0'..'9') index++
        var isFloating = false
        if (index < src.length && src[index] == '.') {
            isFloating = true
            index++
            while (index < src.length && src[index] in '0'..'9') index++
        }
        if (index < src.length && (src[index] == 'e' || src[index] == 'E')) {
            isFloating = true
            index++
            if (index < src.length && (src[index] == '+' || src[index] == '-')) index++
            while (index < src.length && src[index] in '0'..'9') index++
        }
        val text = src.substring(start, index)
        return if (isFloating) {
            text.toDouble()
        } else {
            text.toLong()
        }
    }

    private fun parseBoolean(): Boolean {
        return if (src.startsWith("true", index)) {
            index += 4
            true
        } else if (src.startsWith("false", index)) {
            index += 5
            false
        } else {
            throw IllegalArgumentException("Expected boolean value at index $index")
        }
    }

    private fun parseNull(): Any? {
        if (src.startsWith("null", index)) {
            index += 4
            return null
        }
        throw IllegalArgumentException("Expected 'null' at index $index")
    }

    private fun skipWhitespace() {
        while (index < src.length) {
            val ch = src[index]
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                index++
            } else {
                break
            }
        }
    }

    private fun peek(): Char {
        if (index >= src.length) throw IllegalArgumentException("Unexpected end of JSON input")
        return src[index]
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        if (index >= src.length || src[index] != expected) {
            val actual = if (index < src.length) "'${src[index]}'" else "EOF"
            throw IllegalArgumentException("Expected '$expected' at index $index, found $actual")
        }
        index++
    }
}
