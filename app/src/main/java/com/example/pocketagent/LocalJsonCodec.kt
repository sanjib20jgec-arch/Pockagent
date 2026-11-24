package com.example.pocketagent

import java.util.Locale

/**
 * Shared offline JSON string escaping/unescaping for local stores so control characters, quotes,
 * backslashes, and unicode escapes round-trip faithfully without external JSON dependencies.
 */
internal object LocalJsonCodec {

    fun escape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code in 0x00..0x1F) {
                    sb.append(String.format(Locale.US, "\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    fun unescape(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (val esc = value[i + 1]) {
                    '"', '\\', '/' -> { sb.append(esc); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> if (i + 5 < value.length) {
                        val cp = value.substring(i + 2, i + 6).toIntOrNull(16)
                        if (cp != null) {
                            sb.append(cp.toChar())
                            i += 6
                        } else {
                            sb.append(esc)
                            i += 2
                        }
                    } else {
                        sb.append(esc)
                        i += 2
                    }
                    else -> { sb.append(esc); i += 2 }
                }
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}
