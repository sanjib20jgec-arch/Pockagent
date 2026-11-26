package com.example.pocketagent

import java.io.File

/**
 * Persistent multi-document knowledge vault backed by BM25 search via [OfflineKeywordIndex].
 *
 * Invariants:
 * - 100% offline, zero network calls, zero telemetry.
 * - Max [MAX_DOCUMENTS] documents, text capped at [MAX_TEXT_CHARS] chars per doc.
 */
class DocumentVault(val baseDir: File) {

    companion object {
        const val MAX_DOCUMENTS = 30
        const val MAX_TEXT_CHARS = 100_000
        private const val INDEX_FILE = "doc_vault_index.json"

        fun defaultDir(filesDir: File): File = File(filesDir, "doc_vault")
    }

    data class VaultDocument(
        val id: String,
        val name: String,
        val extractedText: String,
        val chunkCount: Int,
        val addedAt: Long = System.currentTimeMillis(),
    ) {
        init {
            require(id.isNotBlank()) { "Document id cannot be blank." }
            require(name.isNotBlank()) { "Document name cannot be blank." }
        }
    }

    private val indexFile: File get() = File(baseDir, INDEX_FILE)

    @Synchronized
    fun addDocument(id: String, name: String, text: String): VaultDocument? {
        val cleanText = text.trim().take(MAX_TEXT_CHARS)
        val all = loadAll().toMutableList()
        val existing = all.firstOrNull { it.id == id }
        if (existing != null) return existing
        if (all.size >= MAX_DOCUMENTS) return null
        val chunks = if (cleanText.isNotEmpty()) {
            DocumentChunker.chunkText(cleanText, source = name, chunkSize = 550, chunkOverlap = 100)
        } else {
            emptyList()
        }
        val doc = VaultDocument(
            id = id,
            name = name.trim().take(120),
            extractedText = cleanText,
            chunkCount = chunks.size,
        )
        all.add(doc)
        JsonArrayFileStore.writeAtomic(indexFile, File(baseDir, "doc_vault.tmp"), encodeJson(all))
        return doc
    }

    @Synchronized
    fun removeDocument(id: String): Boolean {
        val all = loadAll().toMutableList()
        return if (all.removeAll { it.id == id }) {
            JsonArrayFileStore.writeAtomic(indexFile, File(baseDir, "doc_vault.tmp"), encodeJson(all))
        } else {
            false
        }
    }

    @Synchronized
    fun search(query: String, topK: Int = 5): List<String> {
        if (query.isBlank() || topK <= 0) return emptyList()
        val all = loadAll()
        if (all.isEmpty()) return emptyList()

        val allChunks = all.flatMap { doc ->
            DocumentChunker.chunkText(
                text = doc.extractedText,
                source = doc.name,
                chunkSize = 550,
                chunkOverlap = 100,
            )
        }
        if (allChunks.isEmpty()) return emptyList()

        val index = OfflineKeywordIndex(allChunks)
        val ranked = index.search(query, topK = topK, requirePositiveScore = true)
        return ranked.map { chunk ->
            val src = chunk.source.ifBlank { "Document" }
            "[$src] ${chunk.content}"
        }
    }

    @Synchronized
    fun loadAll(): List<VaultDocument> {
        if (!indexFile.exists()) return emptyList()
        return try {
            JsonArrayFileStore.splitObjects(indexFile.readText(Charsets.UTF_8))
                .mapNotNull(::parseDoc)
                .sortedByDescending { it.addedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encodeJson(list: List<VaultDocument>): String = buildString {
        append('[')
        list.forEachIndexed { i, d ->
            if (i > 0) append(',')
            append('{')
            append("\"id\":").append(JsonArrayFileStore.jsonString(d.id)).append(',')
            append("\"name\":").append(JsonArrayFileStore.jsonString(d.name)).append(',')
            append("\"extractedText\":").append(JsonArrayFileStore.jsonString(d.extractedText)).append(',')
            append("\"chunkCount\":").append(d.chunkCount).append(',')
            append("\"addedAt\":").append(d.addedAt)
            append('}')
        }
        append(']')
    }

    private fun parseDoc(obj: String): VaultDocument? = try {
        val id = JsonArrayFileStore.extractString(obj, "id") ?: return null
        val name = JsonArrayFileStore.extractString(obj, "name") ?: return null
        val text = JsonArrayFileStore.extractString(obj, "extractedText") ?: ""
        val chunks = JsonArrayFileStore.extractLong(obj, "chunkCount")?.toInt() ?: 0
        val added = JsonArrayFileStore.extractLong(obj, "addedAt") ?: System.currentTimeMillis()
        VaultDocument(
            id = id,
            name = name,
            extractedText = text,
            chunkCount = chunks,
            addedAt = added,
        )
    } catch (_: Exception) {
        null
    }
}
