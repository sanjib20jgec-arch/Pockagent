package com.example.pocketagent

import java.io.File
import java.util.UUID

/**
 * Sovereign local workspace/project store for PocketAgent.
 * Each project carries its own custom system instructions and pinned note references.
 *
 * Invariants:
 * - 100% offline, zero telemetry, zero network calls.
 * - Atomic write operations via [JsonArrayFileStore].
 * - Max [MAX_PROJECTS] projects.
 */
class ProjectStore(val baseDir: File) {

    companion object {
        const val MAX_PROJECTS = 50
        const val MAX_NAME_CHARS = 80
        const val MAX_INSTRUCTIONS_CHARS = 4000
        private const val PROJECTS_FILE = "projects.json"

        fun defaultDir(filesDir: File): File = File(filesDir, "projects")
    }

    data class Project(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val systemInstructions: String = "",
        val pinnedNoteIds: List<String> = emptyList(),
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
    ) {
        init {
            require(id.isNotBlank()) { "Project id cannot be blank." }
            require(name.isNotBlank()) { "Project name cannot be blank." }
            require(createdAt > 0) { "createdAt must be positive." }
            require(updatedAt >= createdAt) { "updatedAt cannot be before createdAt." }
        }
    }

    private val file: File get() = File(baseDir, PROJECTS_FILE)

    @Synchronized
    fun loadAll(): List<Project> {
        if (!file.exists()) return emptyList()
        return try {
            JsonArrayFileStore.splitObjects(file.readText(Charsets.UTF_8))
                .mapNotNull(::parseProjectObj)
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getProject(id: String): Project? = loadAll().firstOrNull { it.id == id }

    @Synchronized
    fun save(project: Project): Boolean {
        val sanitized = project.copy(
            name = project.name.trim().take(MAX_NAME_CHARS),
            systemInstructions = project.systemInstructions.trim().take(MAX_INSTRUCTIONS_CHARS),
        )
        require(sanitized.name.isNotBlank()) { "Project name cannot be blank." }
        val all = loadAll().toMutableList()
        val idx = all.indexOfFirst { it.id == sanitized.id }
        if (idx >= 0) {
            all[idx] = sanitized
        } else {
            if (all.size >= MAX_PROJECTS) return false
            all.add(sanitized)
        }
        return JsonArrayFileStore.writeAtomic(file, File(baseDir, "projects.tmp"), encodeProjectsJson(all))
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val all = loadAll().toMutableList()
        return if (all.removeAll { it.id == id }) {
            JsonArrayFileStore.writeAtomic(file, File(baseDir, "projects.tmp"), encodeProjectsJson(all))
        } else {
            false
        }
    }

    private fun encodeProjectsJson(list: List<Project>): String = buildString {
        append('[')
        list.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append('{')
            append("\"id\":").append(JsonArrayFileStore.jsonString(p.id)).append(',')
            append("\"name\":").append(JsonArrayFileStore.jsonString(p.name)).append(',')
            append("\"systemInstructions\":").append(JsonArrayFileStore.jsonString(p.systemInstructions)).append(',')
            append("\"pinnedNoteIds\":").append(JsonArrayFileStore.jsonStringList(p.pinnedNoteIds)).append(',')
            append("\"createdAt\":").append(p.createdAt).append(',')
            append("\"updatedAt\":").append(p.updatedAt)
            append('}')
        }
        append(']')
    }

    private fun parseProjectObj(obj: String): Project? = try {
        val id = JsonArrayFileStore.extractString(obj, "id") ?: return null
        val name = JsonArrayFileStore.extractString(obj, "name") ?: return null
        val instructions = JsonArrayFileStore.extractString(obj, "systemInstructions") ?: ""
        val createdAt = JsonArrayFileStore.extractLong(obj, "createdAt") ?: System.currentTimeMillis()
        val updatedAt = JsonArrayFileStore.extractLong(obj, "updatedAt") ?: createdAt
        val notes = JsonArrayFileStore.extractStringList(obj, "pinnedNoteIds")
        Project(
            id = id,
            name = name,
            systemInstructions = instructions,
            pinnedNoteIds = notes,
            createdAt = createdAt,
            updatedAt = maxOf(createdAt, updatedAt),
        )
    } catch (_: Exception) {
        null
    }
}
