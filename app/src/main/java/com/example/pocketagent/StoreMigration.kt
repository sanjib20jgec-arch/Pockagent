package com.example.pocketagent

import kotlinx.serialization.json.Json

/**
 * Adds a schema marker to locally written JSON while keeping every legacy file readable.
 * Legacy parsers remain the read path until a store is rewritten with the marker.
 */
internal object StoreMigration {
    const val SCHEMA_VERSION: Int = 1
    const val FIELD: String = "schema_version"

    /** Tagged files may carry fields added after the legacy hand-rolled codecs. */
    val json: Json = Json { ignoreUnknownKeys = true }

    /** True only when the key is on an outermost object, never a nested one. */
    fun hasSchemaVersion(json: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        var index = 0
        while (index < json.length) {
            val char = json[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                index++
                continue
            }
            when (char) {
                '"' -> {
                    val start = index + 1
                    var end = start
                    while (end < json.length) {
                        when (json[end]) {
                            '\\' -> end += 2
                            '"' -> break
                            else -> end++
                        }
                    }
                    val token = json.substring(start, minOf(end, json.length))
                    if (depth == 1 && token == FIELD) return true
                    index = minOf(end + 1, json.length)
                    continue
                }
                '{' -> depth++
                '}' -> if (depth > 0) depth--
            }
            index++
        }
        return false
    }

    fun tagWithSchemaVersion(json: String): String {
        if (hasSchemaVersion(json)) return json
        return when {
            json.trimStart().startsWith("{") -> {
                val index = json.indexOf('{')
                json.substring(0, index + 1) + "\n  \"$FIELD\": $SCHEMA_VERSION," + json.substring(index + 1)
            }
            json.trimStart().startsWith("[") -> tagArrayObjects(json)
            else -> json
        }
    }

    private fun tagArrayObjects(json: String): String {
        val output = StringBuilder()
        var depth = 0
        var inString = false
        var escaped = false
        for (char in json) {
            output.append(char)
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) output.append("\n    \"$FIELD\": $SCHEMA_VERSION,")
                    depth++
                }
                '}' -> if (depth > 0) depth--
            }
        }
        return output.toString()
    }
}
