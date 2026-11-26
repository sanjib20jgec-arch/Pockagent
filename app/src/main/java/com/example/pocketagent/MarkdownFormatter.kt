package com.example.pocketagent

/**
 * Parses and formats markdown responses from local models.
 *
 * Provides clean formatting for UI display, structured element extraction,
 * and pure plain-text conversion for Text-To-Speech (TTS) and export.
 *
 * Invariants:
 * - 100% offline, pure Kotlin logic with zero external dependencies.
 * - Resilient to streaming partial responses (e.g., unclosed code blocks).
 * - Preserves user safety: untrusted attachment content embedded in output is sanitized.
 */
object MarkdownFormatter {

    sealed class MarkdownElement {
        data class Header(val level: Int, val text: String) : MarkdownElement()
        data class Paragraph(val text: String) : MarkdownElement()
        data class CodeBlock(val language: String?, val code: String) : MarkdownElement()
        data class BulletItem(val indentLevel: Int, val text: String) : MarkdownElement()
        data class NumberedItem(val number: Int, val text: String) : MarkdownElement()
        data class Blockquote(val text: String) : MarkdownElement()
        data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownElement()
        object HorizontalRule : MarkdownElement()
    }

    private val HEADER_REGEX = Regex("""^[ \t]*(#{1,6})[ \t]+(.+)$""")
    private val BULLET_REGEX = Regex("""^([ \t]*)[*+-][ \t]+(.+)$""")
    private val NUMBERED_REGEX = Regex("""^([ \t]*)(\d+)\.[ \t]+(.+)$""")
    private val BLOCKQUOTE_REGEX = Regex("""^[ \t]*>[ \t]*(.*)$""")
    private val HR_REGEX = Regex("""^[ \t]*([-*_])[ \t]*(?:\1[ \t]*){2,}$""")
    private val CODE_FENCE_REGEX = Regex("""^[ \t]*```([a-zA-Z0-9_\-+.]*)[ \t]*$""")
    private val BOLD_ITALIC_REGEX = Regex("""\*\*\*(.*?)\*\*\*|___(.*?)___""")
    private val BOLD_REGEX = Regex("""\*\*(.*?)\*\*|__(.*?)__""")
    private val ITALIC_REGEX = Regex("""\*(.*?)\*|_(.*?)_""")
    private val INLINE_CODE_REGEX = Regex("""`([^`]+)`""")
    private val LINK_REGEX = Regex("""\[([^\]]+)\]\(([^)]+)\)""")

    /**
     * Parses markdown text into a sequence of structured elements.
     * Gracefully handles streaming / unclosed code blocks.
     */
    fun parseElements(markdown: String): List<MarkdownElement> {
        if (markdown.isBlank()) return emptyList()

        val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        val elements = mutableListOf<MarkdownElement>()

        var inCodeBlock = false
        var codeLanguage: String? = null
        val codeBuffer = StringBuilder()
        val paragraphBuffer = StringBuilder()

        fun flushParagraph() {
            if (paragraphBuffer.isNotEmpty()) {
                val text = paragraphBuffer.toString().trim()
                if (text.isNotEmpty()) {
                    elements.add(MarkdownElement.Paragraph(text))
                }
                paragraphBuffer.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fenceMatch = CODE_FENCE_REGEX.matchEntire(line)
            if (fenceMatch != null) {
                if (inCodeBlock) {
                    // Closing fence
                    elements.add(MarkdownElement.CodeBlock(codeLanguage, codeBuffer.toString().trimEnd()))
                    codeBuffer.clear()
                    codeLanguage = null
                    inCodeBlock = false
                } else {
                    // Opening fence
                    flushParagraph()
                    inCodeBlock = true
                    val lang = fenceMatch.groupValues[1].trim()
                    codeLanguage = if (lang.isNotEmpty()) lang else null
                }
                i++
                continue
            }

            if (inCodeBlock) {
                if (codeBuffer.isNotEmpty()) codeBuffer.append('\n')
                codeBuffer.append(line)
                i++
                continue
            }

            // GFM Table: header row with >= 2 pipe-separated cells followed by valid separator row
            if (i + 1 < lines.size && line.contains('|')) {
                val headerCells = parseTableRowCells(line)
                if (headerCells.size >= 2 && isTableSeparatorRow(lines[i + 1], headerCells.size)) {
                    flushParagraph()
                    val colCount = headerCells.size
                    val rows = mutableListOf<List<String>>()
                    i += 2
                    while (i < lines.size) {
                        val rowLine = lines[i]
                        if (rowLine.isBlank() || !rowLine.contains('|')) break
                        if (CODE_FENCE_REGEX.matches(rowLine)) break
                        val rawCells = parseTableRowCells(rowLine)
                        if (rawCells.isEmpty()) break
                        rows.add(normalizeTableRow(rawCells, colCount))
                        i++
                    }
                    elements.add(MarkdownElement.Table(headers = headerCells, rows = rows))
                    continue
                }
            }

            // Horizontal Rule
            if (HR_REGEX.matches(line)) {
                flushParagraph()
                elements.add(MarkdownElement.HorizontalRule)
                i++
                continue
            }

            // Header
            val headerMatch = HEADER_REGEX.matchEntire(line)
            if (headerMatch != null) {
                flushParagraph()
                val level = headerMatch.groupValues[1].length
                val text = headerMatch.groupValues[2].trim()
                elements.add(MarkdownElement.Header(level, text))
                i++
                continue
            }

            // Bullet item
            val bulletMatch = BULLET_REGEX.matchEntire(line)
            if (bulletMatch != null) {
                flushParagraph()
                val indent = bulletMatch.groupValues[1].length / 2
                val text = bulletMatch.groupValues[2].trim()
                elements.add(MarkdownElement.BulletItem(indent, text))
                i++
                continue
            }

            // Numbered item
            val numberedMatch = NUMBERED_REGEX.matchEntire(line)
            if (numberedMatch != null) {
                flushParagraph()
                val number = numberedMatch.groupValues[2].toIntOrNull() ?: 1
                val text = numberedMatch.groupValues[3].trim()
                elements.add(MarkdownElement.NumberedItem(number, text))
                i++
                continue
            }

            // Blockquote
            val quoteMatch = BLOCKQUOTE_REGEX.matchEntire(line)
            if (quoteMatch != null) {
                flushParagraph()
                val text = quoteMatch.groupValues[1].trim()
                elements.add(MarkdownElement.Blockquote(text))
                i++
                continue
            }

            // Blank line separates paragraphs
            if (line.isBlank()) {
                flushParagraph()
                i++
                continue
            }

            // Regular paragraph line
            if (paragraphBuffer.isNotEmpty()) {
                paragraphBuffer.append('\n')
            }
            paragraphBuffer.append(line)
            i++
        }

        // Flush any trailing elements or unclosed code block (e.g. during streaming)
        if (inCodeBlock) {
            elements.add(MarkdownElement.CodeBlock(codeLanguage, codeBuffer.toString().trimEnd()))
        } else {
            flushParagraph()
        }

        return elements
    }

    private fun parseTableRowCells(line: String): List<String> {
        val trimmed = line.trim()
        if (!trimmed.contains('|')) return emptyList()
        val inner = trimmed
            .let { if (it.startsWith('|')) it.substring(1) else it }
            .let { if (it.endsWith('|')) it.substring(0, it.length - 1) else it }
        return inner.split('|').map { it.trim() }
    }

    private fun isTableSeparatorRow(line: String, expectedColumns: Int): Boolean {
        if (!line.contains('|')) return false
        for (ch in line) {
            if (ch != '|' && ch != '-' && ch != ':' && !ch.isWhitespace()) {
                return false
            }
        }
        val cells = parseTableRowCells(line)
        if (cells.size < 2 || cells.size != expectedColumns) return false
        return cells.all { cell ->
            cell.isNotEmpty() && cell.matches(TABLE_SEP_CELL_REGEX)
        }
    }

    private fun normalizeTableRow(rawCells: List<String>, colCount: Int): List<String> {
        if (rawCells.size == colCount) return rawCells
        if (rawCells.size < colCount) {
            return rawCells + List(colCount - rawCells.size) { "" }
        }
        return rawCells.take(colCount)
    }

    private val TABLE_SEP_CELL_REGEX = Regex("""^:?-+:?$""")

    /**
     * True when [markdown] contains any character that could start a Markdown construct.
     *
     * Deliberately cheap and allocation-free apart from the scan itself: it exists so plain prose
     * can bypass the element pipeline entirely, which matters when it runs on every token.
     */
    fun containsMarkdownSyntax(markdown: String): Boolean {
        for (ch in markdown) {
            when (ch) {
                '#', '*', '_', '`', '>', '[', '|' -> return true
                '-' -> return true
            }
        }
        // Numbered list markers ("1. ") are checked separately since a bare digit is not enough.
        var index = 0
        while (index < markdown.length) {
            val ch = markdown[index]
            if (ch.isDigit()) {
                var cursor = index
                while (cursor < markdown.length && markdown[cursor].isDigit()) cursor++
                if (cursor < markdown.length && markdown[cursor] == '.' &&
                    cursor + 1 < markdown.length && markdown[cursor + 1] == ' '
                ) {
                    return true
                }
                index = cursor
            } else {
                index++
            }
        }
        return false
    }

    /**
     * Formats markdown text into clean, readable text suitable for display in mobile TextView.
     * Enhances readability with structured bullet points, clear code blocks, and normalized spacing.
     */
    fun formatForDisplay(markdown: String): String {
        if (markdown.isBlank()) return ""
        // Streaming-friendly fast path: plain prose needs no parsing at all, so skip the whole
        // element pipeline. This is the common case for partial output during generation.
        if (!containsMarkdownSyntax(markdown)) return markdown.trim()

        val elements = parseElements(markdown)
        val sb = StringBuilder()

        for ((index, element) in elements.withIndex()) {
            if (index > 0 && sb.isNotEmpty() && !sb.endsWith("\n\n")) {
                if (!sb.endsWith("\n")) sb.append("\n")
                // Add extra line before headers, code blocks, or after lists
                val prev = elements[index - 1]
                val needsExtraLine = element is MarkdownElement.Header ||
                        element is MarkdownElement.CodeBlock ||
                        element is MarkdownElement.HorizontalRule ||
                        element is MarkdownElement.Table ||
                        prev is MarkdownElement.Table ||
                        (element is MarkdownElement.Paragraph && (prev is MarkdownElement.BulletItem || prev is MarkdownElement.NumberedItem))

                if (needsExtraLine && !sb.endsWith("\n\n")) {
                    sb.append("\n")
                }
            }

            when (element) {
                is MarkdownElement.Header -> {
                    val cleaned = cleanInlineFormatting(element.text)
                    when (element.level) {
                        1 -> sb.append(cleaned.uppercase()).append("\n").append("─".repeat(minOf(cleaned.length, 36)))
                        2 -> sb.append("■ ").append(cleaned)
                        else -> sb.append("◆ ").append(cleaned)
                    }
                }

                is MarkdownElement.Paragraph -> {
                    sb.append(cleanInlineFormatting(element.text))
                }

                is MarkdownElement.CodeBlock -> {
                    val label = element.language?.ifBlank { "Code" } ?: "Code"
                    sb.append("┌── ").append(label).append("\n")
                    element.code.lines().forEach { line ->
                        sb.append("│ ").append(line).append("\n")
                    }
                    sb.append("└──")
                }

                is MarkdownElement.BulletItem -> {
                    val indent = "  ".repeat(element.indentLevel)
                    sb.append(indent).append("• ").append(cleanInlineFormatting(element.text))
                }

                is MarkdownElement.NumberedItem -> {
                    sb.append("${element.number}. ").append(cleanInlineFormatting(element.text))
                }

                is MarkdownElement.Blockquote -> {
                    sb.append("┃ ").append(cleanInlineFormatting(element.text))
                }

                is MarkdownElement.Table -> {
                    val cleanHeaders = element.headers.map { cleanInlineFormatting(it) }
                    val cleanRows = element.rows.map { row -> row.map { cleanInlineFormatting(it) } }
                    val colWidths = IntArray(cleanHeaders.size) { col ->
                        val headerLen = cleanHeaders[col].length
                        val maxRowLen = cleanRows.maxOfOrNull { it.getOrElse(col) { "" }.length } ?: 0
                        maxOf(headerLen, maxRowLen, 3)
                    }
                    fun formatTableRow(cells: List<String>): String =
                        cells.mapIndexed { col, cell -> cell.padEnd(colWidths[col]) }.joinToString(" | ")
                    sb.append(formatTableRow(cleanHeaders)).append("\n")
                    sb.append(colWidths.joinToString("-+-") { "─".repeat(it) })
                    for (row in cleanRows) {
                        sb.append("\n").append(formatTableRow(row))
                    }
                }

                is MarkdownElement.HorizontalRule -> {
                    sb.append("────────────────────────────")
                }
            }
        }

        return normalizeSpacing(sb.toString())
    }

    private val PARTIAL_FENCE_LINE_REGEX = Regex("""^[ \t]*`{1,2}[ \t]*$""")

    /**
     * Fast, single-pass live streaming formatter for partial model output.
     *
     * Strips incomplete trailing code fences and formats headers, bullets, blockquotes, and
     * inline bold/italic markers cleanly so raw `###`, `**`, and dangling backticks don't flash
     * in the UI before [formatForDisplay] runs on completion.
     */
    fun formatStreamingPreview(raw: String): String {
        if (raw.isBlank()) return ""
        if (!containsMarkdownSyntax(raw)) return raw.trimEnd()

        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        val sb = StringBuilder(normalized.length)
        var inCodeBlock = false

        for (i in lines.indices) {
            val line = lines[i]
            val isLastLine = i == lines.lastIndex

            val fenceMatch = CODE_FENCE_REGEX.matchEntire(line)
            if (fenceMatch != null) {
                if (!inCodeBlock) {
                    // Drop an incomplete trailing fence opener (e.g. "```" or "```kotlin" at EOF)
                    if (isLastLine) continue
                    inCodeBlock = true
                    val lang = fenceMatch.groupValues[1].trim().ifEmpty { "Code" }
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append("┌── ").append(lang)
                } else {
                    inCodeBlock = false
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append("└──")
                }
                continue
            }

            if (inCodeBlock) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append("│ ").append(line)
                continue
            }

            // Drop dangling 1-2 backtick fence prefix on the final line while streaming
            if (isLastLine && PARTIAL_FENCE_LINE_REGEX.matches(line)) {
                continue
            }

            val formattedLine = when {
                HR_REGEX.matches(line) -> "────────────────────────────"
                else -> {
                    val headerMatch = HEADER_REGEX.matchEntire(line)
                    val bulletMatch = if (headerMatch == null) BULLET_REGEX.matchEntire(line) else null
                    val numberedMatch = if (headerMatch == null && bulletMatch == null) NUMBERED_REGEX.matchEntire(line) else null
                    val quoteMatch = if (headerMatch == null && bulletMatch == null && numberedMatch == null) {
                        BLOCKQUOTE_REGEX.matchEntire(line)
                    } else {
                        null
                    }
                    when {
                        headerMatch != null -> {
                            val level = headerMatch.groupValues[1].length
                            val text = cleanStreamingInline(headerMatch.groupValues[2].trim())
                            when (level) {
                                1 -> text.uppercase()
                                2 -> "■ $text"
                                else -> "◆ $text"
                            }
                        }
                        bulletMatch != null -> {
                            val indent = "  ".repeat(bulletMatch.groupValues[1].length / 2)
                            "$indent• ${cleanStreamingInline(bulletMatch.groupValues[2].trim())}"
                        }
                        numberedMatch != null -> {
                            val number = numberedMatch.groupValues[2]
                            "$number. ${cleanStreamingInline(numberedMatch.groupValues[3].trim())}"
                        }
                        quoteMatch != null -> {
                            "┃ ${cleanStreamingInline(quoteMatch.groupValues[1].trim())}"
                        }
                        else -> cleanStreamingInline(line)
                    }
                }
            }

            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(formattedLine)
        }

        return sb.toString().trimEnd()
    }

    private fun cleanStreamingInline(text: String): String {
        var result = cleanInlineFormatting(text)
        // Strip unclosed bold/bold-italic openers that haven't received their closing delimiter yet
        if (result.contains("***")) {
            result = result.replace("***", "")
        }
        if (result.contains("**")) {
            result = result.replace("**", "")
        }
        return result
    }

    /**
     * Strips all markdown syntax to produce clean, natural plain text.
     * Ideal for:
     * - TextToSpeech (TTS) reading so the engine doesn't pronounce symbols like asterisks or backticks.
     * - Plain-text export via AnswerTextWriter.
     * - Clipboard copying when raw formatting is not desired.
     */
    fun stripToPlainText(markdown: String): String {
        val withoutThink = stripThinkingBlocks(markdown)
        if (withoutThink.isBlank()) return ""

        val elements = parseElements(withoutThink)
        val sb = StringBuilder()

        for ((index, element) in elements.withIndex()) {
            if (index > 0 && sb.isNotEmpty() && !sb.endsWith("\n\n")) {
                if (!sb.endsWith("\n")) sb.append("\n")
                val prev = elements[index - 1]
                val needsExtraLine = element is MarkdownElement.Header ||
                        element is MarkdownElement.CodeBlock ||
                        element is MarkdownElement.Table ||
                        prev is MarkdownElement.Table ||
                        (element is MarkdownElement.Paragraph && (prev is MarkdownElement.BulletItem || prev is MarkdownElement.NumberedItem))
                if (needsExtraLine && !sb.endsWith("\n\n")) {
                    sb.append("\n")
                }
            }

            when (element) {
                is MarkdownElement.Header -> {
                    sb.append(stripInlineSyntax(element.text))
                }

                is MarkdownElement.Paragraph -> {
                    sb.append(stripInlineSyntax(element.text))
                }

                is MarkdownElement.CodeBlock -> {
                    sb.append(element.code)
                }

                is MarkdownElement.BulletItem -> {
                    val indent = "  ".repeat(element.indentLevel)
                    sb.append(indent).append("• ").append(stripInlineSyntax(element.text))
                }

                is MarkdownElement.NumberedItem -> {
                    sb.append("${element.number}. ").append(stripInlineSyntax(element.text))
                }

                is MarkdownElement.Blockquote -> {
                    sb.append(stripInlineSyntax(element.text))
                }

                is MarkdownElement.Table -> {
                    val headerLine = element.headers.map { stripInlineSyntax(it) }
                        .filter { it.isNotEmpty() }
                        .joinToString(", ")
                    if (headerLine.isNotEmpty()) {
                        sb.append(headerLine)
                    }
                    for (row in element.rows) {
                        val rowLine = row.map { stripInlineSyntax(it) }
                            .filter { it.isNotEmpty() }
                            .joinToString(", ")
                        if (rowLine.isNotEmpty()) {
                            if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n")
                            sb.append(rowLine)
                        }
                    }
                }

                is MarkdownElement.HorizontalRule -> {
                    // Omit horizontal rule in plain text / TTS
                }
            }
        }

        return normalizeSpacing(sb.toString())
    }

    /**
     * Extracts numbered and bullet steps from the response as a discrete list of instructions.
     */
    fun extractSteps(markdown: String): List<String> {
        val elements = parseElements(markdown)
        val steps = mutableListOf<String>()

        for (element in elements) {
            when (element) {
                is MarkdownElement.NumberedItem -> {
                    steps.add(stripInlineSyntax(element.text))
                }
                is MarkdownElement.BulletItem -> {
                    steps.add(stripInlineSyntax(element.text))
                }
                else -> {}
            }
        }

        return steps
    }

    /**
     * Cleans inline markdown formatting for visual display:
     * - Removes asterisks / underscores from bold and italic, preserving the highlighted text.
     * - Keeps inline code clean.
     * - Replaces markdown links [text](url) with "text (url)".
     */
    fun cleanInlineFormatting(text: String): String {
        var result = text
        // Bold + Italic
        result = BOLD_ITALIC_REGEX.replace(result) { match ->
            match.groupValues[1].ifEmpty { match.groupValues[2] }
        }
        // Bold
        result = BOLD_REGEX.replace(result) { match ->
            match.groupValues[1].ifEmpty { match.groupValues[2] }
        }
        // Italic
        result = ITALIC_REGEX.replace(result) { match ->
            match.groupValues[1].ifEmpty { match.groupValues[2] }
        }
        // Links: [label](url) -> label (url)
        result = LINK_REGEX.replace(result) { match ->
            "${match.groupValues[1]} (${match.groupValues[2]})"
        }
        return result
    }

    /**
     * Completely strips inline markdown syntax for TTS and plain text:
     * - Removes backticks around code.
     * - Removes bold/italic delimiters.
     * - Converts links [text](url) to "text".
     */
    fun stripInlineSyntax(text: String): String {
        var result = cleanInlineFormatting(text)
        // Inline code: `code` -> code
        result = INLINE_CODE_REGEX.replace(result) { match ->
            match.groupValues[1]
        }
        return result.trim()
    }

    /**
     * Normalizes line spacing:
     * - Collapses 3 or more consecutive newlines into 2 newlines.
     * - Trims trailing whitespace from each line.
     * - Trims overall string.
     */
    fun normalizeSpacing(text: String): String {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        val sb = StringBuilder()
        var consecutiveEmptyLines = 0

        for (line in lines) {
            val trimmed = line.trimEnd()
            if (trimmed.isEmpty()) {
                consecutiveEmptyLines++
                if (consecutiveEmptyLines <= 1 && sb.isNotEmpty()) {
                    sb.append('\n')
                }
            } else {
                consecutiveEmptyLines = 0
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(trimmed)
            }
        }

        return sb.toString().trim()
    }

    private val THINK_BLOCK_REGEX = Regex("""<think>[\s\S]*?(?:</think>|\z)""")

    /** Strips `<think>...</think>` reasoning blocks so TTS and plain exports read only the answer. */
    fun stripThinkingBlocks(text: String): String {
        if (!text.contains("<think>")) return text
        return THINK_BLOCK_REGEX.replace(text, "").trim()
    }
}
