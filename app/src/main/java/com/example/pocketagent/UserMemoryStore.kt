package com.example.pocketagent

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Sovereign local long-term memory store for PocketAgent.
 *
 * Persists user profile facts, preferences, and constraints across sessions
 * in a private JSON file on local device storage.
 *
 * Invariants:
 * - 100% offline, zero cloud telemetry or external network calls.
 * - Atomic write operations using temporary files and atomic replace.
 * - Strict capacity bounds: max [MAX_MEMORIES] entries, max [MAX_FACT_CHARS] per entry.
 */
class UserMemoryStore(
    val baseDir: File,
    private val maxMemories: Int = MAX_MEMORIES,
) {
    companion object {
        const val MAX_MEMORIES = 60
        const val MAX_FACT_CHARS = 240
        private const val MEMORIES_FILE_NAME = "user_memories.json"
        private const val TEMP_FILE_SUFFIX = ".tmp"

        fun defaultDir(filesDir: File): File = File(filesDir, "memories")
    }

    enum class MemoryCategory {
        PREFERENCE,
        PROFILE,
        CONSTRAINT,
        GOAL,
        CUSTOM;

        companion object {
            fun fromString(value: String): MemoryCategory =
                entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: CUSTOM
        }
    }

    data class UserMemory(
        val id: String = UUID.randomUUID().toString(),
        val content: String,
        val category: MemoryCategory = MemoryCategory.PREFERENCE,
        val createdAt: Long = System.currentTimeMillis(),
        val confidence: Float = 1.0f,
        val sourceSessionId: String? = null,
    ) {
        init {
            require(id.isNotBlank()) { "Memory ID cannot be blank." }
            require(content.isNotBlank()) { "Memory content cannot be blank." }
            require(createdAt > 0) { "createdAt must be positive." }
        }

        val displayLabel: String
            get() = when (category) {
                MemoryCategory.PREFERENCE -> "Preference"
                MemoryCategory.PROFILE -> "Profile"
                MemoryCategory.CONSTRAINT -> "Constraint"
                MemoryCategory.GOAL -> "Goal"
                MemoryCategory.CUSTOM -> "Custom"
            }
    }

    private val memoriesFile: File
        get() = File(baseDir, MEMORIES_FILE_NAME)

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }

    @Synchronized
    fun listMemories(): List<UserMemory> {
        if (!memoriesFile.exists() || !memoriesFile.isFile) {
            return emptyList()
        }
        return try {
            val json = memoriesFile.readText(Charsets.UTF_8)
            parseMemoriesJson(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveMemory(memory: UserMemory): UserMemory {
        val sanitizedContent = memory.content.trim().take(MAX_FACT_CHARS)
        require(sanitizedContent.isNotBlank()) { "Memory content cannot be blank." }

        val existing = listMemories().toMutableList()
        val index = existing.indexOfFirst { it.id == memory.id }
        val boundedMemory = memory.copy(content = sanitizedContent)

        if (index != -1) {
            existing[index] = boundedMemory
        } else {
            // Check for duplicate content (case-insensitive)
            val dupIndex = existing.indexOfFirst {
                it.content.equals(sanitizedContent, ignoreCase = true) && it.category == memory.category
            }
            if (dupIndex != -1) {
                return existing[dupIndex]
            }
            if (existing.size >= maxMemories) {
                // Drop oldest non-custom memory if limit reached
                val oldestNonCustom = existing.indexOfFirst { it.category != MemoryCategory.CUSTOM }
                if (oldestNonCustom != -1) {
                    existing.removeAt(oldestNonCustom)
                } else {
                    existing.removeAt(0)
                }
            }
            existing.add(boundedMemory)
        }

        writeMemoriesAtomic(existing)
        return boundedMemory
    }

    @Synchronized
    fun addFact(
        content: String,
        category: MemoryCategory = MemoryCategory.PREFERENCE,
        confidence: Float = 1.0f,
        sourceSessionId: String? = null,
    ): UserMemory {
        return saveMemory(
            UserMemory(
                content = content,
                category = category,
                confidence = confidence,
                sourceSessionId = sourceSessionId,
            )
        )
    }

    @Synchronized
    fun deleteMemory(id: String): Boolean {
        val existing = listMemories().toMutableList()
        val removed = existing.removeAll { it.id == id }
        if (removed) {
            writeMemoriesAtomic(existing)
        }
        return removed
    }

    @Synchronized
    fun clearMemories(): Boolean {
        if (memoriesFile.exists()) {
            return memoriesFile.delete()
        }
        return true
    }

    /**
     * Formats stored memories into a prompt-injection block within a strict token budget.
     */
    @Synchronized
    fun buildPromptContext(maxTokens: Int = 120): String {
        val memories = listMemories()
        if (memories.isEmpty()) return ""

        val sb = StringBuilder("[User Profile & Stored Preferences]:\n")
        var estimatedTokens = 10

        for (m in memories) {
            val line = "- ${m.displayLabel}: ${m.content}\n"
            val lineTokens = (line.length / 3.5).toInt().coerceAtLeast(1)
            if (estimatedTokens + lineTokens > maxTokens) break
            sb.append(line)
            estimatedTokens += lineTokens
        }

        return sb.toString().trim()
    }

    private fun writeMemoriesAtomic(memories: List<UserMemory>) {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        val tempFile = File(baseDir, "$MEMORIES_FILE_NAME$TEMP_FILE_SUFFIX")
        val json = serializeMemoriesJson(memories)

        try {
            tempFile.writeText(json, Charsets.UTF_8)
            if (!tempFile.renameTo(memoriesFile)) {
                memoriesFile.delete()
                if (!tempFile.renameTo(memoriesFile)) {
                    tempFile.copyTo(memoriesFile, overwrite = true)
                    tempFile.delete()
                }
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw IOException("Failed to write user memories atomically: ${e.message}", e)
        }
    }

    private fun serializeMemoriesJson(memories: List<UserMemory>): String {
        val sb = StringBuilder("[\n")
        for ((index, m) in memories.withIndex()) {
            sb.append("  {\n")
            sb.append("    \"id\": \"").append(escapeJson(m.id)).append("\",\n")
            sb.append("    \"content\": \"").append(escapeJson(m.content)).append("\",\n")
            sb.append("    \"category\": \"").append(m.category.name).append("\",\n")
            sb.append("    \"createdAt\": ").append(m.createdAt).append(",\n")
            sb.append("    \"confidence\": ").append(m.confidence).append(",\n")
            sb.append("    \"sourceSessionId\": ")
            if (m.sourceSessionId != null) {
                sb.append("\"").append(escapeJson(m.sourceSessionId)).append("\"\n")
            } else {
                sb.append("null\n")
            }
            sb.append("  }")
            if (index < memories.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]\n")
        return sb.toString()
    }

    private fun parseMemoriesJson(json: String): List<UserMemory> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()

        val results = mutableListOf<UserMemory>()
        val itemRegex = Regex("""\{([^{}]+)\}""")
        for (match in itemRegex.findAll(trimmed)) {
            val body = match.groupValues[1]
            try {
                val id = extractJsonField(body, "id") ?: UUID.randomUUID().toString()
                val content = extractJsonField(body, "content") ?: continue
                val catStr = extractJsonField(body, "category") ?: "CUSTOM"
                val category = MemoryCategory.fromString(catStr)
                val createdAt = extractJsonField(body, "createdAt")?.toLongOrNull() ?: System.currentTimeMillis()
                val confidence = extractJsonField(body, "confidence")?.toFloatOrNull() ?: 1.0f
                val sourceSessionId = extractJsonField(body, "sourceSessionId")

                if (content.isNotBlank()) {
                    results.add(
                        UserMemory(
                            id = id,
                            content = unescapeJson(content),
                            category = category,
                            createdAt = createdAt,
                            confidence = confidence,
                            sourceSessionId = sourceSessionId?.let(::unescapeJson),
                        )
                    )
                }
            } catch (_: Exception) {}
        }
        return results
    }

    private fun extractJsonField(body: String, field: String): String? {
        val pattern = Regex(""""$field"\s*:\s*("(?:\\.|[^"\\])*"|[0-9.]+|null)""")
        val match = pattern.find(body) ?: return null
        val raw = match.groupValues[1]
        if (raw == "null") return null
        return if (raw.startsWith("\"") && raw.endsWith("\"")) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
    }

    private fun escapeJson(value: String): String {
        val sb = StringBuilder()
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun unescapeJson(value: String): String {
        return value.replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }
}
