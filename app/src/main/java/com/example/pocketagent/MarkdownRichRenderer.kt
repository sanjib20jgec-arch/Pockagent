package com.example.pocketagent

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import java.util.Locale

/**
 * Pure-Kotlin offline single-pass syntax highlighter for fenced code blocks.
 *
 * Recognizes comments, quoted strings, numeric literals, and common programming/SQL keywords
 * without external dependencies or Android runtime calls.
 */
object CodeSyntaxHighlighter {

    enum class TokenType {
        KEYWORD,
        STRING,
        COMMENT,
        NUMBER,
    }

    data class HighlightSpan(
        val start: Int,
        val end: Int,
        val type: TokenType,
    )

    private val HASH_COMMENT_LANGUAGES = setOf(
        "python", "py", "sh", "bash", "zsh", "shell",
        "yaml", "yml", "toml", "ruby", "rb", "perl", "pl", "r",
        "dockerfile", "makefile", "conf", "ini",
    )

    private val SQL_LANGUAGES = setOf(
        "sql", "sqlite", "mysql", "postgres", "postgresql", "tsql", "plsql",
    )

    private val CODE_KEYWORDS = setOf(
        "fun", "val", "var", "class", "object", "interface", "enum", "sealed", "data",
        "if", "else", "for", "while", "do", "when", "switch", "case", "default",
        "return", "break", "continue", "import", "package", "export", "from",
        "def", "async", "await", "yield", "lambda", "pass", "raise", "with", "elif", "except",
        "const", "let", "function", "try", "catch", "finally", "throw", "throws",
        "true", "false", "null", "None", "True", "False", "undefined",
        "public", "private", "protected", "internal", "open", "abstract", "override",
        "static", "final", "void", "new", "this", "super", "companion", "suspend",
        "inline", "lateinit", "in", "is", "as", "by", "typeof", "instanceof",
        "fn", "pub", "mut", "struct", "impl", "trait", "match", "use", "mod", "type",
        "and", "or", "not",
    )

    private val SQL_KEYWORDS_UPPER = setOf(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
        "DELETE", "CREATE", "TABLE", "DROP", "ALTER", "JOIN", "INNER", "LEFT",
        "RIGHT", "OUTER", "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT",
        "OFFSET", "AS", "AND", "OR", "NOT", "NULL", "IS", "IN", "LIKE",
        "DISTINCT", "COUNT", "SUM", "AVG", "MIN", "MAX", "UNION", "ALL",
        "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "INDEX", "EXISTS",
    )

    /**
     * Scans [code] in a single pass and returns sorted, non-overlapping [HighlightSpan]s strictly
     * within `[0, code.length]`.
     */
    fun findHighlights(code: String, language: String?): List<HighlightSpan> {
        if (code.isEmpty()) return emptyList()

        val normalizedLang = language?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val supportsHashComments = normalizedLang in HASH_COMMENT_LANGUAGES
        val isSqlLanguage = normalizedLang in SQL_LANGUAGES
        val supportsSlashComments = !supportsHashComments && !isSqlLanguage

        val spans = mutableListOf<HighlightSpan>()
        val len = code.length
        var i = 0

        while (i < len) {
            val ch = code[i]

            // 1. Block comments: /* ... */
            if (ch == '/' && i + 1 < len && code[i + 1] == '*') {
                val closeIdx = code.indexOf("*/", i + 2)
                val end = if (closeIdx >= 0) closeIdx + 2 else len
                spans.add(HighlightSpan(i, end, TokenType.COMMENT))
                i = end
                continue
            }

            // 2. Line comments: // (general), # (python/sh/bash/yaml), -- (sql)
            if ((supportsSlashComments && ch == '/' && i + 1 < len && code[i + 1] == '/') ||
                (supportsHashComments && ch == '#') ||
                (isSqlLanguage && ch == '-' && i + 1 < len && code[i + 1] == '-')
            ) {
                val end = findLineEnd(code, i)
                spans.add(HighlightSpan(i, end, TokenType.COMMENT))
                i = end
                continue
            }

            // 3. Double or single quoted strings, respecting backslash escapes
            if (ch == '"' || ch == '\'') {
                val end = scanQuotedString(code, i, ch)
                spans.add(HighlightSpan(i, end, TokenType.STRING))
                i = end
                continue
            }

            // 4. Numeric literals at identifier boundaries
            if (ch.isDigit() && (i == 0 || !isIdentifierPart(code[i - 1]))) {
                val end = scanNumber(code, i)
                spans.add(HighlightSpan(i, end, TokenType.NUMBER))
                i = end
                continue
            }

            // 5. Identifiers and keywords
            if (isIdentifierStart(ch)) {
                var end = i + 1
                while (end < len && isIdentifierPart(code[end])) {
                    end++
                }
                val word = code.substring(i, end)
                val isKeyword = if (isSqlLanguage) {
                    word in CODE_KEYWORDS || word.uppercase(Locale.ROOT) in SQL_KEYWORDS_UPPER
                } else {
                    word in CODE_KEYWORDS || word in SQL_KEYWORDS_UPPER
                }
                if (isKeyword) {
                    spans.add(HighlightSpan(i, end, TokenType.KEYWORD))
                }
                i = end
                continue
            }

            i++
        }

        return spans
    }

    private fun findLineEnd(code: String, start: Int): Int {
        var i = start
        while (i < code.length && code[i] != '\n' && code[i] != '\r') {
            i++
        }
        return i
    }

    private fun scanQuotedString(code: String, start: Int, quote: Char): Int {
        var i = start + 1
        val len = code.length
        while (i < len) {
            val c = code[i]
            if (c == '\\') {
                i += if (i + 1 < len) 2 else 1
                continue
            }
            if (c == quote) {
                return i + 1
            }
            i++
        }
        return len
    }

    private fun scanNumber(code: String, start: Int): Int {
        val len = code.length
        var i = start
        if (code[i] == '0' && i + 1 < len && (code[i + 1] == 'x' || code[i + 1] == 'X')) {
            i += 2
            while (i < len && (isHexDigit(code[i]) || code[i] == '_')) {
                i++
            }
        } else if (code[i] == '0' && i + 1 < len && (code[i + 1] == 'b' || code[i + 1] == 'B')) {
            i += 2
            while (i < len && (code[i] == '0' || code[i] == '1' || code[i] == '_')) {
                i++
            }
        } else {
            while (i < len && (code[i].isDigit() || code[i] == '_')) {
                i++
            }
            if (i + 1 < len && code[i] == '.' && code[i + 1].isDigit()) {
                i++
                while (i < len && (code[i].isDigit() || code[i] == '_')) {
                    i++
                }
            }
            if (i < len && (code[i] == 'e' || code[i] == 'E')) {
                var expEnd = i + 1
                if (expEnd < len && (code[expEnd] == '+' || code[expEnd] == '-')) {
                    expEnd++
                }
                if (expEnd < len && code[expEnd].isDigit()) {
                    i = expEnd
                    while (i < len && (code[i].isDigit() || code[i] == '_')) {
                        i++
                    }
                }
            }
        }
        // Optional numeric suffix like L, f, F, u, U
        while (i < len && code[i] in charArrayOf('L', 'l', 'f', 'F', 'u', 'U')) {
            i++
        }
        return i
    }

    private fun isIdentifierStart(ch: Char): Boolean = ch.isLetter() || ch == '_'

    private fun isIdentifierPart(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    private fun isHexDigit(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
}

/**
 * Pure-Kotlin inline markdown tokenizer for bold-italic, bold, italic, inline code, and links.
 *
 * Strips raw markdown delimiters around valid spans while preserving plain text (such as arithmetic
 * expressions or snake_case identifiers) untouched.
 */
object InlineMarkdownParser {

    enum class InlineStyle {
        BOLD,
        ITALIC,
        BOLD_ITALIC,
        CODE,
        LINK,
    }

    data class StyledSegment(
        val text: String,
        val style: InlineStyle?,
        val linkUrl: String? = null,
    )

    /**
     * Parses [text] into an ordered list of [StyledSegment]s with inline delimiters stripped.
     */
    fun parseInline(text: String): List<StyledSegment> {
        if (text.isEmpty()) return emptyList()

        val segments = mutableListOf<StyledSegment>()
        val plainBuffer = StringBuilder()

        fun flushPlain() {
            if (plainBuffer.isNotEmpty()) {
                segments.add(StyledSegment(plainBuffer.toString(), style = null))
                plainBuffer.clear()
            }
        }

        val len = text.length
        var i = 0

        while (i < len) {
            val ch = text[i]

            // 1. Inline code: `code`
            if (ch == '`') {
                val closeIdx = text.indexOf('`', i + 1)
                if (closeIdx > i + 1) {
                    flushPlain()
                    segments.add(StyledSegment(text.substring(i + 1, closeIdx), InlineStyle.CODE))
                    i = closeIdx + 1
                    continue
                }
                plainBuffer.append(ch)
                i++
                continue
            }

            // 2. Markdown link: [label](url)
            if (ch == '[') {
                val parsedLink = tryParseLink(text, i)
                if (parsedLink != null) {
                    flushPlain()
                    segments.add(
                        StyledSegment(
                            text = parsedLink.label,
                            style = InlineStyle.LINK,
                            linkUrl = parsedLink.url,
                        )
                    )
                    i = parsedLink.nextIndex
                    continue
                }
                plainBuffer.append(ch)
                i++
                continue
            }

            // 3. Emphasis delimiters: *** / ___ / ** / __ / * / _
            if (ch == '*' || ch == '_') {
                val parsedEmphasis = tryParseEmphasis(text, i, ch)
                if (parsedEmphasis != null) {
                    flushPlain()
                    segments.add(StyledSegment(parsedEmphasis.content, parsedEmphasis.style))
                    i = parsedEmphasis.nextIndex
                    continue
                }
                plainBuffer.append(ch)
                i++
                continue
            }

            plainBuffer.append(ch)
            i++
        }

        flushPlain()
        return segments
    }

    private data class ParsedLink(val label: String, val url: String, val nextIndex: Int)

    private data class ParsedEmphasis(val content: String, val style: InlineStyle, val nextIndex: Int)

    private fun tryParseLink(text: String, start: Int): ParsedLink? {
        val closeBracket = text.indexOf(']', start + 1)
        if (closeBracket <= start + 1) return null
        if (closeBracket + 1 >= text.length || text[closeBracket + 1] != '(') return null

        val newlineInLabel = text.indexOf('\n', start + 1)
        if (newlineInLabel in (start + 1) until closeBracket) return null

        val closeParen = text.indexOf(')', closeBracket + 2)
        if (closeParen <= closeBracket + 2) return null

        val newlineInUrl = text.indexOf('\n', closeBracket + 2)
        if (newlineInUrl in (closeBracket + 2) until closeParen) return null

        val label = text.substring(start + 1, closeBracket)
        val url = text.substring(closeBracket + 2, closeParen)
        return ParsedLink(label = label, url = url, nextIndex = closeParen + 1)
    }

    private fun tryParseEmphasis(text: String, start: Int, marker: Char): ParsedEmphasis? {
        val len = text.length
        val remaining = len - start
        val isUnderscore = marker == '_'

        // Prevent breaking snake_case words like foo_bar_baz when '_' is inside an identifier.
        if (isUnderscore && start > 0 && text[start - 1].isLetterOrDigit()) {
            return null
        }

        val candidates = when {
            remaining >= 6 && text.startsWith("$marker$marker$marker", start) -> listOf(3, 2, 1)
            remaining >= 4 && text.startsWith("$marker$marker", start) -> listOf(2, 1)
            else -> listOf(1)
        }

        for (count in candidates) {
            val delim = marker.toString().repeat(count)
            val contentStart = start + count
            if (contentStart >= len || text[contentStart].isWhitespace()) continue

            val closeIdx = findClosingDelimiter(text, contentStart, delim, isUnderscore)
            if (closeIdx > contentStart) {
                val style = when (count) {
                    3 -> InlineStyle.BOLD_ITALIC
                    2 -> InlineStyle.BOLD
                    else -> InlineStyle.ITALIC
                }
                return ParsedEmphasis(
                    content = text.substring(contentStart, closeIdx),
                    style = style,
                    nextIndex = closeIdx + count,
                )
            }
        }
        return null
    }

    private fun findClosingDelimiter(
        text: String,
        searchFrom: Int,
        delim: String,
        isUnderscore: Boolean,
    ): Int {
        var idx = text.indexOf(delim, searchFrom)
        while (idx >= 0) {
            val afterIdx = idx + delim.length
            val hasContentBefore = idx > searchFrom && !text[idx - 1].isWhitespace()
            val boundaryOk = !isUnderscore || afterIdx >= text.length || !text[afterIdx].isLetterOrDigit()
            if (hasContentBefore && boundaryOk) {
                return idx
            }
            idx = text.indexOf(delim, idx + 1)
        }
        return -1
    }
}

/**
 * Renders structured [MarkdownFormatter.MarkdownElement] blocks into native Android Views styled
 * with [UiPalette], with inline formatting and syntax-highlighted fenced code cards.
 */
object MarkdownRichRenderer {

    /**
     * Converts inline markdown in [text] into a styled [CharSequence] using [palette].
     */
    fun buildSpannable(text: String, palette: UiPalette): CharSequence {
        if (text.isEmpty()) return ""
        val segments = InlineMarkdownParser.parseInline(text)
        if (segments.isEmpty()) return text

        val builder = SpannableStringBuilder()
        for (segment in segments) {
            val start = builder.length
            builder.append(segment.text)
            val end = builder.length
            if (start >= end) continue

            when (segment.style) {
                InlineMarkdownParser.InlineStyle.BOLD -> {
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                InlineMarkdownParser.InlineStyle.ITALIC -> {
                    builder.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                InlineMarkdownParser.InlineStyle.BOLD_ITALIC -> {
                    builder.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                InlineMarkdownParser.InlineStyle.CODE -> {
                    builder.setSpan(BackgroundColorSpan(palette.inlineCodeBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(ForegroundColorSpan(palette.inlineCodeInk), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                InlineMarkdownParser.InlineStyle.LINK -> {
                    builder.setSpan(ForegroundColorSpan(palette.teal), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                null -> Unit
            }
        }
        return builder
    }

    /**
     * Parses [markdown] via [MarkdownFormatter.parseElements] and populates [container] with
     * themed, selectable native views.
     */
    fun renderInto(
        container: LinearLayout,
        markdown: String,
        palette: UiPalette,
        onCopyCode: (code: String, language: String?) -> Unit,
    ) {
        container.removeAllViews()
        if (markdown.isBlank()) return

        val context = container.context
        val elements = MarkdownFormatter.parseElements(markdown)
        if (elements.isEmpty()) return

        for ((index, element) in elements.withIndex()) {
            when (element) {
                is MarkdownFormatter.MarkdownElement.Header -> {
                    val sizeSp = when (element.level) {
                        1 -> 20f
                        2 -> 17f
                        else -> 15.5f
                    }
                    val view = TextView(context).apply {
                        text = buildSpannable(element.text, palette)
                        textSize = sizeSp
                        setTextColor(palette.ink)
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                        setTextIsSelectable(true)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            topMargin = dp(context, if (index == 0) 2 else 10)
                            bottomMargin = dp(context, 4)
                        }
                    }
                    container.addView(view)
                }

                is MarkdownFormatter.MarkdownElement.Paragraph -> {
                    val view = TextView(context).apply {
                        text = buildSpannable(element.text, palette)
                        textSize = 15f
                        setTextColor(palette.ink)
                        setLineSpacing(dp(context, 4).toFloat(), 1f)
                        setTextIsSelectable(true)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            topMargin = dp(context, 2)
                            bottomMargin = dp(context, 6)
                        }
                    }
                    container.addView(view)
                }

                is MarkdownFormatter.MarkdownElement.CodeBlock -> {
                    container.addView(buildCodeBlockCard(context, element, palette, onCopyCode))
                }

                is MarkdownFormatter.MarkdownElement.BulletItem -> {
                    val row = buildListItemRow(
                        context = context,
                        marker = "•",
                        text = element.text,
                        indentDp = 3 + (element.indentLevel.coerceAtLeast(0) * 12),
                        palette = palette,
                    )
                    container.addView(row)
                }

                is MarkdownFormatter.MarkdownElement.NumberedItem -> {
                    val row = buildListItemRow(
                        context = context,
                        marker = "${element.number}.",
                        text = element.text,
                        indentDp = 2,
                        palette = palette,
                    )
                    container.addView(row)
                }

                is MarkdownFormatter.MarkdownElement.Blockquote -> {
                    container.addView(buildBlockquoteView(context, element.text, palette))
                }

                is MarkdownFormatter.MarkdownElement.Table -> {
                    container.addView(buildTableView(context, element, palette))
                }

                is MarkdownFormatter.MarkdownElement.HorizontalRule -> {
                    val divider = dividerView(context, palette).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            maxOf(1, dp(context, 1)),
                        ).apply {
                            topMargin = dp(context, 8)
                            bottomMargin = dp(context, 8)
                        }
                    }
                    container.addView(divider)
                }
            }
        }
    }

    private fun buildCodeBlockCard(
        context: Context,
        element: MarkdownFormatter.MarkdownElement.CodeBlock,
        palette: UiPalette,
        onCopyCode: (code: String, language: String?) -> Unit,
    ): LinearLayout {
        val radiusPx = dp(context, 14).toFloat()
        val strokePx = maxOf(1, dp(context, 1))

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedShape(
                color = palette.codeBg,
                radiusPx = radiusPx,
                strokeWidthPx = strokePx,
                strokeColor = palette.codeBorder,
            )
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 6)
                bottomMargin = dp(context, 8)
            }
        }

        val innerRadius = dp(context, 13).toFloat()
        val headerBackground = GradientDrawable().apply {
            setColor(palette.codeHeaderBg)
            cornerRadii = floatArrayOf(
                innerRadius, innerRadius,
                innerRadius, innerRadius,
                0f, 0f,
                0f, 0f,
            )
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = headerBackground
            setPadding(dp(context, 12), dp(context, 2), dp(context, 6), dp(context, 2))
        }

        val badgeLabel = (element.language?.takeIf { it.isNotBlank() } ?: "code").uppercase(Locale.ROOT)
        val badgeView = TextView(context).apply {
            text = badgeLabel
            textSize = 11f
            setTextColor(palette.codeComment)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(badgeView)

        val copyButton = Button(context).apply {
            val minTouchH = dp(context, 48)
            val minTouchW = dp(context, 48)
            text = "Copy"
            contentDescription = "Copy ${element.language ?: "code"} block"
            setAllCaps(false)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(palette.codeText)
            gravity = Gravity.CENTER
            minHeight = minTouchH
            minimumHeight = minTouchH
            minWidth = minTouchW
            minimumWidth = minTouchW
            setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
            background = rippleDrawable(
                roundedShape(palette.codeHeaderBg, dp(context, 10).toFloat()),
                palette.rippleOnAccent,
            )
            backgroundTintList = null
            stateListAnimator = null
            isFocusable = true
            setOnClickListener {
                onCopyCode(element.code, element.language)
                text = "Copied"
                postDelayed({ text = "Copy" }, 1400L)
            }
        }
        headerRow.addView(copyButton)
        card.addView(headerRow)

        val codeSpannable = buildHighlightedCode(element.code, element.language, palette)
        val codeTextView = TextView(context).apply {
            text = codeSpannable
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.codeText)
            setTextIsSelectable(true)
            setLineSpacing(dp(context, 2).toFloat(), 1f)
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 12))
        }

        val scrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            addView(codeTextView)
        }
        card.addView(scrollView)

        return card
    }

    private fun buildHighlightedCode(
        code: String,
        language: String?,
        palette: UiPalette,
    ): CharSequence {
        val builder = SpannableStringBuilder(code)
        val highlights = CodeSyntaxHighlighter.findHighlights(code, language)
        for (span in highlights) {
            if (span.start < 0 || span.end > builder.length || span.start >= span.end) continue
            val color = when (span.type) {
                CodeSyntaxHighlighter.TokenType.KEYWORD -> palette.codeKeyword
                CodeSyntaxHighlighter.TokenType.STRING -> palette.codeString
                CodeSyntaxHighlighter.TokenType.COMMENT -> palette.codeComment
                CodeSyntaxHighlighter.TokenType.NUMBER -> palette.codeNumber
            }
            builder.setSpan(
                ForegroundColorSpan(color),
                span.start,
                span.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return builder
    }

    private fun buildListItemRow(
        context: Context,
        marker: String,
        text: String,
        indentDp: Int,
        palette: UiPalette,
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(context, indentDp), dp(context, 2), 0, dp(context, 3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val markerView = TextView(context).apply {
            this.text = marker
            textSize = 15f
            setTextColor(palette.teal)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 0, dp(context, 8), 0)
        }
        row.addView(markerView)

        val contentView = TextView(context).apply {
            this.text = buildSpannable(text, palette)
            textSize = 15f
            setTextColor(palette.ink)
            setLineSpacing(dp(context, 4).toFloat(), 1f)
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(contentView)

        return row
    }

    private fun buildBlockquoteView(
        context: Context,
        text: String,
        palette: UiPalette,
    ): LinearLayout {
        val accentWidth = maxOf(dp(context, 3), 3)
        val surfaceLayer = roundedShape(palette.surfaceElevated, dp(context, 8).toFloat())
        val barLayer = GradientDrawable().apply {
            setColor(palette.teal)
            val r = dp(context, 2).toFloat()
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        }
        val layered = android.graphics.drawable.LayerDrawable(arrayOf(surfaceLayer, barLayer)).apply {
            setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
            setLayerSize(1, accentWidth, -1)
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            background = layered
            setPadding(dp(context, 12) + accentWidth, dp(context, 8), dp(context, 12), dp(context, 8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 4)
                bottomMargin = dp(context, 6)
            }
        }

        val quoteText = TextView(context).apply {
            this.text = buildSpannable(text, palette)
            textSize = 14.5f
            setTextColor(palette.muted)
            typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            setLineSpacing(dp(context, 3).toFloat(), 1f)
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(quoteText)

        return row
    }

    private fun buildTableView(
        context: Context,
        table: MarkdownFormatter.MarkdownElement.Table,
        palette: UiPalette,
    ): View {
        val strokePx = maxOf(1, dp(context, 1))
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedShape(
                color = palette.surface,
                radiusPx = dp(context, 12).toFloat(),
                strokeWidthPx = strokePx,
                strokeColor = palette.border,
            )
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 6)
                bottomMargin = dp(context, 8)
            }
        }

        val tableLayout = TableLayout(context).apply {
            isStretchAllColumns = false
            isShrinkAllColumns = false
        }

        fun createRowDivider(): View = View(context).apply {
            setBackgroundColor(palette.border)
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                strokePx,
            )
        }

        fun createCellTextView(cell: String, isHeader: Boolean): TextView = TextView(context).apply {
            text = buildSpannable(cell, palette)
            textSize = 13f
            setTextColor(palette.ink)
            if (isHeader) {
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            setTextIsSelectable(true)
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
        }

        val headerRow = TableRow(context).apply {
            setBackgroundColor(palette.surfaceElevated)
        }
        for (header in table.headers) {
            headerRow.addView(createCellTextView(header, isHeader = true))
        }
        tableLayout.addView(headerRow)

        if (table.rows.isNotEmpty()) {
            tableLayout.addView(createRowDivider())
        }

        for ((rowIndex, row) in table.rows.withIndex()) {
            val dataRow = TableRow(context)
            for (cell in row) {
                dataRow.addView(createCellTextView(cell, isHeader = false))
            }
            tableLayout.addView(dataRow)
            if (rowIndex < table.rows.lastIndex) {
                tableLayout.addView(createRowDivider())
            }
        }

        val scrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            addView(tableLayout)
        }
        container.addView(scrollView)

        return container
    }
}
