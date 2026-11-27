package com.example.pocketagent

/**
 * Pure-Kotlin incremental splitter for streaming assistant responses that contain fenced code
 * blocks.
 *
 * Unlike full markdown parsers, this keeps unclosed opening fences as [Segment.Code] with
 * `isClosed = false` so live streaming UI can render dark monospace preview cards immediately as
 * tokens arrive.
 */
object StreamingCodePreviewParser {

    sealed class Segment {
        data class Prose(val text: String) : Segment()
        data class Code(
            val language: String?,
            val code: String,
            val isClosed: Boolean,
        ) : Segment()
        data class Thinking(
            val text: String,
            val isClosed: Boolean,
        ) : Segment()
    }

    private const val THINK_OPEN = "<think>"
    private const val THINK_CLOSE = "</think>"

    /**
     * Splits [raw] into segments including [Segment.Thinking] for `<think>...</think>` blocks,
     * [Segment.Code] for fenced code blocks, and [Segment.Prose] for the rest.
     */
    fun parse(raw: String): List<Segment> {
        if (raw.isBlank()) return emptyList()
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val segments = mutableListOf<Segment>()
        var cursor = 0
        while (cursor < normalized.length) {
            val thinkStart = normalized.indexOf(THINK_OPEN, cursor)
            if (thinkStart >= 0) {
                if (thinkStart > cursor) {
                    val before = normalized.substring(cursor, thinkStart).trim()
                    if (before.isNotEmpty()) {
                        segments.addAll(splitForStreamingPreview(before))
                    }
                }
                val contentStart = thinkStart + THINK_OPEN.length
                val thinkEnd = normalized.indexOf(THINK_CLOSE, contentStart)
                if (thinkEnd >= 0) {
                    segments.add(Segment.Thinking(
                        text = normalized.substring(contentStart, thinkEnd).trim(),
                        isClosed = true,
                    ))
                    cursor = thinkEnd + THINK_CLOSE.length
                } else {
                    segments.add(Segment.Thinking(
                        text = normalized.substring(contentStart).trim(),
                        isClosed = false,
                    ))
                    cursor = normalized.length
                }
            } else {
                val remainder = normalized.substring(cursor).trim()
                if (remainder.isNotEmpty()) {
                    segments.addAll(splitForStreamingPreview(remainder))
                }
                cursor = normalized.length
            }
        }
        return segments
    }

    private val OPEN_FENCE_REGEX = Regex("""^[ \t]*```([a-zA-Z0-9_\-+.]*)(?:[ \t]+[^`]*)?[ \t]*$""")
    private val CLOSE_FENCE_REGEX = Regex("""^[ \t]*```[ \t]*$""")

    /**
     * Splits [raw] into an ordered list of [Segment.Prose] and [Segment.Code] chunks.
     * Gracefully handles unclosed opening fences during live token streaming.
     */
    fun splitForStreamingPreview(raw: String): List<Segment> {
        if (raw.isBlank()) return emptyList()

        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        if (!normalized.contains("```")) {
            return listOf(Segment.Prose(normalized.trim()))
        }

        val lines = normalized.split('\n')
        val segments = mutableListOf<Segment>()
        val proseBuffer = StringBuilder()
        val codeBuffer = StringBuilder()
        var inCodeBlock = false
        var codeLanguage: String? = null

        fun flushProse() {
            if (proseBuffer.isNotEmpty()) {
                val prose = proseBuffer.toString().trim()
                if (prose.isNotEmpty()) {
                    segments.add(Segment.Prose(prose))
                }
                proseBuffer.clear()
            }
        }

        for (line in lines) {
            if (!inCodeBlock) {
                val openMatch = OPEN_FENCE_REGEX.matchEntire(line)
                if (openMatch != null) {
                    flushProse()
                    inCodeBlock = true
                    val lang = openMatch.groupValues[1].trim()
                    codeLanguage = lang.ifEmpty { null }
                    continue
                }
                if (proseBuffer.isNotEmpty()) {
                    proseBuffer.append('\n')
                }
                proseBuffer.append(line)
            } else {
                if (CLOSE_FENCE_REGEX.matches(line)) {
                    segments.add(
                        Segment.Code(
                            language = codeLanguage,
                            code = codeBuffer.toString().trimEnd(),
                            isClosed = true,
                        )
                    )
                    codeBuffer.clear()
                    codeLanguage = null
                    inCodeBlock = false
                    continue
                }
                if (codeBuffer.isNotEmpty()) {
                    codeBuffer.append('\n')
                }
                codeBuffer.append(line)
            }
        }

        if (inCodeBlock) {
            segments.add(
                Segment.Code(
                    language = codeLanguage,
                    code = codeBuffer.toString().trimEnd(),
                    isClosed = false,
                )
            )
        } else {
            flushProse()
        }

        return segments
    }
}
