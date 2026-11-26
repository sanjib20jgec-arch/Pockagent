package com.example.pocketagent

import java.io.File

/**
 * Shared atomic JSON array persistence helper so local stores never duplicate brace-matching or
 * temp-file atomic rename logic.
 */
internal object JsonArrayFileStore {

    fun writeAtomic(targetFile: File, tempFile: File, json: String): Boolean {
        targetFile.parentFile?.mkdirs()
        return try {
            tempFile.writeText(json, Charsets.UTF_8)
            if (!tempFile.renameTo(targetFile)) {
                targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            }
            true
        } catch (_: Exception) {
            tempFile.delete()
            false
        }
    }

    fun splitObjects(json: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < json.length) {
            val start = json.indexOf('{', i)
            if (start < 0) break
            val end = findMatchingBrace(json, start)
            if (end < 0) break
            result.add(json.substring(start, end + 1))
            i = end + 1
        }
        return result
    }

    fun extractString(json: String, key: String): String? {
        val pattern = "\"$key\":"
        val ki = json.indexOf(pattern)
        if (ki < 0) return null
        val vi = json.indexOf('"', ki + pattern.length)
        if (vi < 0) return null
        var i = vi + 1
        var escaped = false
        while (i < json.length) {
            val ch = json[i]
            if (escaped) {
                escaped = false
                i++
                continue
            }
            if (ch == '\\') {
                escaped = true
                i++
                continue
            }
            if (ch == '"') {
                return LocalJsonCodec.unescape(json.substring(vi + 1, i))
            }
            i++
        }
        return null
    }

    fun extractLong(json: String, key: String): Long? = extractScalar(json, key)?.toLongOrNull()

    fun extractFloat(json: String, key: String): Float? = extractScalar(json, key)?.toFloatOrNull()

    fun extractBoolean(json: String, key: String): Boolean? =
        extractScalar(json, key)?.toBooleanStrictOrNull()

    fun extractStringList(json: String, key: String): List<String> {
        val pattern = "\"$key\":"
        val ki = json.indexOf(pattern)
        if (ki < 0) return emptyList()
        val arrStart = json.indexOf('[', ki + pattern.length)
        if (arrStart < 0) return emptyList()
        val arrEnd = findMatchingBracket(json, arrStart)
        if (arrEnd < 0) return emptyList()
        val segment = json.substring(arrStart + 1, arrEnd)
        return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(segment)
            .map { LocalJsonCodec.unescape(it.groupValues[1]) }
            .toList()
    }

    fun jsonString(value: String): String = "\"${LocalJsonCodec.escape(value)}\""

    fun jsonStringList(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { jsonString(it) }

    private fun extractScalar(json: String, key: String): String? {
        val pattern = "\"$key\":"
        val ki = json.indexOf(pattern)
        if (ki < 0) return null
        val vi = ki + pattern.length
        var end = json.length
        for (i in vi until json.length) {
            val ch = json[i]
            if (ch == ',' || ch == '}' || ch == '\n') {
                end = i
                break
            }
        }
        return json.substring(vi, end).trim()
    }

    private fun findMatchingBrace(json: String, open: Int): Int =
        findMatchingDelimiter(json, open, '{', '}')

    private fun findMatchingBracket(json: String, open: Int): Int =
        findMatchingDelimiter(json, open, '[', ']')

    private fun findMatchingDelimiter(json: String, open: Int, openCh: Char, closeCh: Char): Int {
        var depth = 0
        var inStr = false
        var escape = false
        for (i in open until json.length) {
            val ch = json[i]
            if (escape) {
                escape = false
                continue
            }
            if (ch == '\\' && inStr) {
                escape = true
                continue
            }
            if (ch == '"') {
                inStr = !inStr
                continue
            }
            if (!inStr) {
                if (ch == openCh) depth++
                else if (ch == closeCh) {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }
}
