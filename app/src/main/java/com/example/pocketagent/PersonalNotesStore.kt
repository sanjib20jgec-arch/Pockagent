package com.example.pocketagent

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Sovereign local notes vault for PocketAgent.
 *
 * Persists user notes, quick captures, and action items locally in private device storage.
 *
 * Invariants:
 * - 100% offline, zero telemetry, zero network calls.
 * - Atomic write operations using temporary files.
 * - Strict limits: max [MAX_NOTES] entries, max [MAX_NOTE_CHARS] per note content.
 */
class PersonalNotesStore(
    val baseDir: File,
    private val maxNotes: Int = MAX_NOTES,
) {
    companion object {
        const val MAX_NOTES = 200
        const val MAX_TITLE_CHARS = 140
        const val MAX_NOTE_CHARS = 8_000
        private const val NOTES_FILE_NAME = "personal_notes.json"
        private const val TEMP_FILE_SUFFIX = ".tmp"

        fun defaultDir(filesDir: File): File = File(filesDir, "notes")
    }

    data class PersonalNote(
        val id: String = UUID.randomUUID().toString(),
        val title: String,
        val content: String,
        val tags: List<String> = emptyList(),
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        val isPinned: Boolean = false,
    ) {
        init {
            require(id.isNotBlank()) { "Note ID cannot be blank." }
            require(title.isNotBlank()) { "Note title cannot be blank." }
            require(createdAt > 0) { "createdAt must be positive." }
            require(updatedAt >= createdAt) { "updatedAt cannot be before createdAt." }
        }

        fun withUpdatedContent(newTitle: String = title, newContent: String = content): PersonalNote = copy(
            title = newTitle.trim().take(MAX_TITLE_CHARS),
            content = newContent.trim().take(MAX_NOTE_CHARS),
            updatedAt = System.currentTimeMillis(),
        )

        fun withPinned(pinned: Boolean): PersonalNote = copy(
            isPinned = pinned,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private val notesFile: File
        get() = File(baseDir, NOTES_FILE_NAME)

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }

    @Synchronized
    fun listNotes(): List<PersonalNote> {
        if (!notesFile.exists() || !notesFile.isFile) {
            return emptyList()
        }
        return try {
            val json = notesFile.readText(Charsets.UTF_8)
            parseNotesJson(json).sortedWith(
                compareByDescending<PersonalNote> { it.isPinned }
                    .thenByDescending { it.updatedAt }
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getNote(id: String): PersonalNote? {
        return listNotes().firstOrNull { it.id == id }
    }

    @Synchronized
    fun saveNote(note: PersonalNote): PersonalNote {
        val sanitizedTitle = note.title.trim().take(MAX_TITLE_CHARS)
        val sanitizedContent = note.content.trim().take(MAX_NOTE_CHARS)
        require(sanitizedTitle.isNotBlank()) { "Note title cannot be blank." }

        val boundedNote = note.copy(
            title = sanitizedTitle,
            content = sanitizedContent,
            updatedAt = System.currentTimeMillis()
        )

        val existing = listNotes().toMutableList()
        val index = existing.indexOfFirst { it.id == boundedNote.id }

        if (index != -1) {
            existing[index] = boundedNote
        } else {
            if (existing.size >= maxNotes) {
                // Drop oldest unpinned note
                val oldestUnpinned = existing.indexOfLast { !it.isPinned }
                if (oldestUnpinned != -1) {
                    existing.removeAt(oldestUnpinned)
                } else {
                    existing.removeAt(existing.size - 1)
                }
            }
            existing.add(0, boundedNote)
        }

        writeNotesAtomic(existing)
        return boundedNote
    }

    @Synchronized
    fun createNote(
        title: String,
        content: String,
        tags: List<String> = emptyList(),
        isPinned: Boolean = false,
    ): PersonalNote {
        return saveNote(
            PersonalNote(
                title = title,
                content = content,
                tags = tags,
                isPinned = isPinned,
            )
        )
    }

    @Synchronized
    fun deleteNote(id: String): Boolean {
        val existing = listNotes().toMutableList()
        val removed = existing.removeAll { it.id == id }
        if (removed) {
            writeNotesAtomic(existing)
        }
        return removed
    }

    @Synchronized
    fun pinNote(id: String, pinned: Boolean): PersonalNote? {
        val existing = listNotes().toMutableList()
        val index = existing.indexOfFirst { it.id == id }
        if (index == -1) return null

        val updated = existing[index].withPinned(pinned)
        existing[index] = updated
        writeNotesAtomic(existing)
        return updated
    }

    @Synchronized
    fun searchNotes(query: String): List<PersonalNote> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return listNotes()
        return listNotes().filter { note ->
            note.title.contains(trimmed, ignoreCase = true) ||
                    note.content.contains(trimmed, ignoreCase = true) ||
                    note.tags.any { it.contains(trimmed, ignoreCase = true) }
        }
    }

    @Synchronized
    fun clearNotes(): Boolean {
        if (notesFile.exists()) {
            return notesFile.delete()
        }
        return true
    }

    @Synchronized
    fun exportNotesAsMarkdown(): String {
        val notes = listNotes()
        if (notes.isEmpty()) return "# PocketAgent Notes\n\nNo notes saved."

        val sb = StringBuilder("# PocketAgent Notes\n\n")
        for (n in notes) {
            val pinPrefix = if (n.isPinned) "📌 " else ""
            sb.append("## ").append(pinPrefix).append(n.title).append("\n")
            if (n.tags.isNotEmpty()) {
                sb.append("*Tags: ").append(n.tags.joinToString(", ")).append("*\n\n")
            }
            sb.append(n.content).append("\n\n---\n\n")
        }
        return sb.toString().trim()
    }

    private fun writeNotesAtomic(notes: List<PersonalNote>) {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        val tempFile = File(baseDir, "$NOTES_FILE_NAME$TEMP_FILE_SUFFIX")
        val json = serializeNotesJson(notes)

        try {
            tempFile.writeText(json, Charsets.UTF_8)
            if (!tempFile.renameTo(notesFile)) {
                notesFile.delete()
                if (!tempFile.renameTo(notesFile)) {
                    tempFile.copyTo(notesFile, overwrite = true)
                    tempFile.delete()
                }
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw IOException("Failed to write personal notes atomically: ${e.message}", e)
        }
    }

    private fun serializeNotesJson(notes: List<PersonalNote>): String {
        val sb = StringBuilder("[\n")
        for (i in notes.indices) {
            val n = notes[i]
            sb.append("  {\n")
            sb.append("    \"id\": \"").append(escapeJson(n.id)).append("\",\n")
            sb.append("    \"title\": \"").append(escapeJson(n.title)).append("\",\n")
            sb.append("    \"content\": \"").append(escapeJson(n.content)).append("\",\n")
            sb.append("    \"tags\": [")
            sb.append(n.tags.joinToString(", ") { "\"${escapeJson(it)}\"" })
            sb.append("],\n")
            sb.append("    \"createdAt\": ").append(n.createdAt).append(",\n")
            sb.append("    \"updatedAt\": ").append(n.updatedAt).append(",\n")
            sb.append("    \"isPinned\": ").append(n.isPinned).append("\n")
            sb.append("  }")
            if (i < notes.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun parseNotesJson(json: String): List<PersonalNote> {
        val trimmed = json.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()

        val results = mutableListOf<PersonalNote>()
        val objectRegex = Regex("""\{([^{}]+)\}""")
        for (match in objectRegex.findAll(trimmed)) {
            val body = match.groupValues[1]
            try {
                val id = extractJsonField(body, "id") ?: UUID.randomUUID().toString()
                val title = extractJsonField(body, "title") ?: continue
                val content = extractJsonField(body, "content") ?: ""
                val createdAt = extractJsonLong(body, "createdAt") ?: System.currentTimeMillis()
                val updatedAt = extractJsonLong(body, "updatedAt") ?: createdAt
                val isPinned = extractJsonBoolean(body, "isPinned") ?: false

                val tags = mutableListOf<String>()
                val tagsBlock = Regex(""""tags"\s*:\s*\[(.*?)\]""").find(body)?.groupValues?.get(1)
                if (!tagsBlock.isNullOrBlank()) {
                    val tagItems = Regex(""""([^"]+)"""").findAll(tagsBlock)
                    for (t in tagItems) {
                        tags.add(t.groupValues[1])
                    }
                }

                results.add(
                    PersonalNote(
                        id = id,
                        title = title,
                        content = content,
                        tags = tags,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        isPinned = isPinned,
                    )
                )
            } catch (_: Exception) {}
        }
        return results
    }

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\b", "\\b")
        .replace("\u000C", "\\f")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun unescapeJson(value: String): String = value
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    private fun extractJsonField(jsonBody: String, field: String): String? {
        val regex = Regex(""""$field"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val match = regex.find(jsonBody) ?: return null
        return unescapeJson(match.groupValues[1])
    }

    private fun extractJsonLong(jsonBody: String, field: String): Long? {
        val regex = Regex(""""$field"\s*:\s*(\d+)""")
        val match = regex.find(jsonBody) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun extractJsonBoolean(jsonBody: String, field: String): Boolean? {
        val regex = Regex(""""$field"\s*:\s*(true|false)""")
        val match = regex.find(jsonBody) ?: return null
        return match.groupValues[1].toBoolean()
    }
}
