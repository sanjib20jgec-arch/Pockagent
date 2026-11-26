package com.example.pocketagent

import java.io.File
import java.util.Locale

/**
 * Manages multiple local `.litertlm` models so users can switch between downloaded/imported
 * models and compare measured on-device speed (`tok/s`).
 *
 * Invariants:
 * - 100% offline, zero network, atomic writes via [JsonArrayFileStore].
 * - Max [MAX_MODELS] tracked entries.
 */
class ModelLibraryStore(val modelsDir: File) {

    companion object {
        const val MAX_MODELS = 20
        const val MODEL_EXTENSION = ".litertlm"
        private const val METADATA_FILE = "model_library.json"

        fun defaultDir(filesDir: File): File = File(filesDir, "models")
    }

    data class ModelEntry(
        val id: String,
        val displayName: String,
        val filePath: String,
        val fileSizeBytes: Long,
        val tokensPerSecond: Float = 0f,
        val benchmarkScore: Float = 0f,
        val isActive: Boolean = false,
        val addedAt: Long = System.currentTimeMillis(),
    ) {
        init {
            require(id.isNotBlank()) { "Model id cannot be blank." }
            require(displayName.isNotBlank()) { "Model displayName cannot be blank." }
        }

        val sizeLabel: String
            get() = when {
                fileSizeBytes >= 1024L * 1024 * 1024 ->
                    String.format(Locale.US, "%.1f GB", fileSizeBytes / (1024.0 * 1024 * 1024))
                fileSizeBytes >= 1024L * 1024 ->
                    String.format(Locale.US, "%.1f MB", fileSizeBytes / (1024.0 * 1024))
                else -> "${fileSizeBytes / 1024} KB"
            }

        val tokensPerSecondLabel: String
            get() = if (tokensPerSecond > 0f) String.format(Locale.US, "%.1f tok/s", tokensPerSecond) else "\u2014"
    }

    private val metaFile: File get() = File(modelsDir, METADATA_FILE)

    @Synchronized
    fun loadAll(): List<ModelEntry> {
        if (!metaFile.exists()) return emptyList()
        return try {
            JsonArrayFileStore.splitObjects(metaFile.readText(Charsets.UTF_8))
                .mapNotNull(::parseEntry)
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getActive(): ModelEntry? = loadAll().firstOrNull { it.isActive }

    @Synchronized
    fun registerModel(
        filePath: String,
        displayName: String,
        tokensPerSecond: Float = 0f,
        makeActive: Boolean = false,
    ): ModelEntry? {
        val file = File(filePath)
        if (!file.exists() || !file.name.endsWith(MODEL_EXTENSION, ignoreCase = true)) return null
        val existingList = loadAll().toMutableList()
        val cleanName = displayName.trim().ifBlank { file.nameWithoutExtension }.take(80)
        val existingIndex = existingList.indexOfFirst { it.filePath == filePath }
        if (existingIndex >= 0) {
            val current = existingList[existingIndex]
            val updated = current.copy(
                displayName = cleanName,
                fileSizeBytes = file.length(),
                tokensPerSecond = if (tokensPerSecond > 0f) tokensPerSecond else current.tokensPerSecond,
                isActive = makeActive || current.isActive,
            )
            val normalized = existingList.mapIndexed { idx, e ->
                when {
                    idx == existingIndex -> updated
                    makeActive -> e.copy(isActive = false)
                    else -> e
                }
            }
            return if (JsonArrayFileStore.writeAtomic(metaFile, File(modelsDir, "model_library.tmp"), encodeJson(normalized))) {
                updated
            } else {
                null
            }
        }
        if (existingList.size >= MAX_MODELS) return null
        val baseId = file.parentFile?.takeIf { it.parentFile == modelsDir }?.name
            ?: file.nameWithoutExtension
        val uniqueId = if (existingList.none { it.id == baseId }) {
            baseId
        } else {
            "${baseId}_${System.currentTimeMillis()}"
        }
        val shouldActivate = makeActive || existingList.none { it.isActive }
        val newList = existingList.map { if (shouldActivate) it.copy(isActive = false) else it }.toMutableList()
        val entry = ModelEntry(
            id = uniqueId,
            displayName = cleanName,
            filePath = filePath,
            fileSizeBytes = file.length(),
            tokensPerSecond = tokensPerSecond,
            isActive = shouldActivate,
        )
        newList.add(entry)
        return if (JsonArrayFileStore.writeAtomic(metaFile, File(modelsDir, "model_library.tmp"), encodeJson(newList))) {
            entry
        } else {
            null
        }
    }

    @Synchronized
    fun setActive(id: String): Boolean {
        val current = loadAll()
        if (current.none { it.id == id }) return false
        val updated = current.map { it.copy(isActive = it.id == id) }
        return JsonArrayFileStore.writeAtomic(metaFile, File(modelsDir, "model_library.tmp"), encodeJson(updated))
    }

    @Synchronized
    fun updateBenchmark(id: String, tokensPerSecond: Float): Boolean {
        val all = loadAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return false
        all[idx] = all[idx].copy(tokensPerSecond = tokensPerSecond)
        return JsonArrayFileStore.writeAtomic(metaFile, File(modelsDir, "model_library.tmp"), encodeJson(all))
    }

    @Synchronized
    fun updateActiveBenchmark(tokensPerSecond: Float): Boolean {
        val active = getActive() ?: return false
        return updateBenchmark(active.id, tokensPerSecond)
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val all = loadAll().toMutableList()
        val removed = all.removeAll { it.id == id }
        return if (removed) {
            JsonArrayFileStore.writeAtomic(metaFile, File(modelsDir, "model_library.tmp"), encodeJson(all))
        } else {
            false
        }
    }

    private fun encodeJson(list: List<ModelEntry>): String = buildString {
        append('[')
        list.forEachIndexed { i, e ->
            if (i > 0) append(',')
            append('{')
            append("\"id\":").append(JsonArrayFileStore.jsonString(e.id)).append(',')
            append("\"displayName\":").append(JsonArrayFileStore.jsonString(e.displayName)).append(',')
            append("\"filePath\":").append(JsonArrayFileStore.jsonString(e.filePath)).append(',')
            append("\"fileSizeBytes\":").append(e.fileSizeBytes).append(',')
            append("\"tokensPerSecond\":").append(e.tokensPerSecond).append(',')
            append("\"benchmarkScore\":").append(e.benchmarkScore).append(',')
            append("\"isActive\":").append(e.isActive).append(',')
            append("\"addedAt\":").append(e.addedAt)
            append('}')
        }
        append(']')
    }

    private fun parseEntry(obj: String): ModelEntry? = try {
        val id = JsonArrayFileStore.extractString(obj, "id") ?: return null
        val name = JsonArrayFileStore.extractString(obj, "displayName") ?: id
        val path = JsonArrayFileStore.extractString(obj, "filePath") ?: return null
        val size = JsonArrayFileStore.extractLong(obj, "fileSizeBytes") ?: 0L
        val tps = JsonArrayFileStore.extractFloat(obj, "tokensPerSecond") ?: 0f
        val bench = JsonArrayFileStore.extractFloat(obj, "benchmarkScore") ?: 0f
        val active = JsonArrayFileStore.extractBoolean(obj, "isActive") ?: false
        val added = JsonArrayFileStore.extractLong(obj, "addedAt") ?: System.currentTimeMillis()
        ModelEntry(
            id = id,
            displayName = name,
            filePath = path,
            fileSizeBytes = size,
            tokensPerSecond = tps,
            benchmarkScore = bench,
            isActive = active,
            addedAt = added,
        )
    } catch (_: Exception) {
        null
    }
}
