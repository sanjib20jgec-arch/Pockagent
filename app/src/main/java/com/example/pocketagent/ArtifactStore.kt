package com.example.pocketagent

import java.io.File
import java.util.UUID

/**
 * Persists code and HTML artifacts locally on-device with HTML export support.
 *
 * Invariants:
 * - 100% offline, zero telemetry, atomic writes via [JsonArrayFileStore].
 * - Max [MAX_ARTIFACTS] artifacts, max [MAX_CODE_CHARS] per artifact code.
 */
class ArtifactStore(val baseDir: File) {

    companion object {
        const val MAX_ARTIFACTS = 100
        const val MAX_CODE_CHARS = 50_000
        private const val ARTIFACTS_FILE = "artifacts.json"

        fun defaultDir(filesDir: File): File = File(filesDir, "artifacts")
    }

    data class Artifact(
        val id: String = UUID.randomUUID().toString(),
        val title: String,
        val language: String,
        val code: String,
        val version: Int = 1,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
    ) {
        init {
            require(id.isNotBlank()) { "Artifact id cannot be blank." }
            require(title.isNotBlank()) { "Artifact title cannot be blank." }
            require(createdAt > 0) { "createdAt must be positive." }
            require(updatedAt >= createdAt) { "updatedAt cannot be before createdAt." }
        }
    }

    private val file: File get() = File(baseDir, ARTIFACTS_FILE)

    @Synchronized
    fun loadAll(): List<Artifact> {
        if (!file.exists()) return emptyList()
        return try {
            JsonArrayFileStore.splitObjects(file.readText(Charsets.UTF_8))
                .mapNotNull(::parseArtifact)
                .sortedByDescending { it.updatedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(artifact: Artifact): Boolean {
        val sanitized = artifact.copy(
            title = artifact.title.trim().take(120),
            code = artifact.code.take(MAX_CODE_CHARS),
        )
        require(sanitized.title.isNotBlank()) { "Artifact title cannot be blank." }
        val all = loadAll().toMutableList()
        val idx = all.indexOfFirst { it.id == sanitized.id }
        if (idx >= 0) {
            all[idx] = sanitized
        } else {
            if (all.size >= MAX_ARTIFACTS) return false
            all.add(sanitized)
        }
        return JsonArrayFileStore.writeAtomic(file, File(baseDir, "artifacts.tmp"), encodeJson(all))
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val all = loadAll().toMutableList()
        return if (all.removeAll { it.id == id }) {
            JsonArrayFileStore.writeAtomic(file, File(baseDir, "artifacts.tmp"), encodeJson(all))
        } else {
            false
        }
    }

    fun exportHtml(artifact: Artifact): String {
        val normLang = artifact.language.trim().lowercase()
        if (normLang in setOf("html", "htm", "xhtml", "svg")) {
            return HtmlArtifactSandbox.prepareSandboxHtml(artifact.code, isDark = false)
        }
        val escapedTitle = escapeHtml(artifact.title)
        val escapedLang = escapeHtml(artifact.language.ifBlank { "text" })
        val escapedCode = escapeHtml(artifact.code)
        return """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>$escapedTitle</title>
<style>body{font-family:sans-serif;padding:16px;background:#1a1a2e;color:#eee;}
pre{background:#0f0f1a;padding:12px;border-radius:8px;overflow-x:auto;white-space:pre-wrap;}</style>
</head><body>
<h2>$escapedTitle</h2>
<p><em>Language: $escapedLang | Version: ${artifact.version}</em></p>
<pre><code>$escapedCode</code></pre>
</body></html>"""
    }

    private fun escapeHtml(raw: String): String =
        raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun encodeJson(list: List<Artifact>): String = buildString {
        append('[')
        list.forEachIndexed { i, a ->
            if (i > 0) append(',')
            append('{')
            append("\"id\":").append(JsonArrayFileStore.jsonString(a.id)).append(',')
            append("\"title\":").append(JsonArrayFileStore.jsonString(a.title)).append(',')
            append("\"language\":").append(JsonArrayFileStore.jsonString(a.language)).append(',')
            append("\"code\":").append(JsonArrayFileStore.jsonString(a.code)).append(',')
            append("\"version\":").append(a.version).append(',')
            append("\"createdAt\":").append(a.createdAt).append(',')
            append("\"updatedAt\":").append(a.updatedAt)
            append('}')
        }
        append(']')
    }

    private fun parseArtifact(obj: String): Artifact? = try {
        val id = JsonArrayFileStore.extractString(obj, "id") ?: return null
        val title = JsonArrayFileStore.extractString(obj, "title") ?: return null
        val lang = JsonArrayFileStore.extractString(obj, "language") ?: ""
        val code = JsonArrayFileStore.extractString(obj, "code") ?: ""
        val ver = JsonArrayFileStore.extractLong(obj, "version")?.toInt() ?: 1
        val ca = JsonArrayFileStore.extractLong(obj, "createdAt") ?: System.currentTimeMillis()
        val ua = JsonArrayFileStore.extractLong(obj, "updatedAt") ?: ca
        Artifact(
            id = id,
            title = title,
            language = lang,
            code = code,
            version = ver,
            createdAt = ca,
            updatedAt = maxOf(ca, ua),
        )
    } catch (_: Exception) {
        null
    }
}
