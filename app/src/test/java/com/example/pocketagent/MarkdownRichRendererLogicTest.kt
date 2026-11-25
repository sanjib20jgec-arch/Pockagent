package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MarkdownRichRendererLogicTest {

    @Test
    fun syntaxHighlighterRecognizesKotlinTokensAndRespectsEscapes() {
        val code = """
            // Load local config
            /* Multi-line
               comment */
            fun greet(user2: String): Int {
                val msg = "Hi \"Pocket\""
                if (true) return 42
                return 0x1A
            }
        """.trimIndent()

        val spans = CodeSyntaxHighlighter.findHighlights(code, "kotlin")
        assertValidSortedNonOverlapping(spans, code.length)

        val byType = spans.groupBy { it.type }
        val comments = byType[CodeSyntaxHighlighter.TokenType.COMMENT].orEmpty().map { code.substring(it.start, it.end) }
        val strings = byType[CodeSyntaxHighlighter.TokenType.STRING].orEmpty().map { code.substring(it.start, it.end) }
        val keywords = byType[CodeSyntaxHighlighter.TokenType.KEYWORD].orEmpty().map { code.substring(it.start, it.end) }
        val numbers = byType[CodeSyntaxHighlighter.TokenType.NUMBER].orEmpty().map { code.substring(it.start, it.end) }

        assertTrue(comments.any { it.startsWith("// Load local config") })
        assertTrue(comments.any { it.startsWith("/* Multi-line") && it.endsWith("*/") })
        assertEquals(listOf("\"Hi \\\"Pocket\\\"\""), strings)
        assertTrue(keywords.containsAll(listOf("fun", "val", "if", "true", "return")))
        assertEquals(listOf("42", "0x1A"), numbers)
    }

    @Test
    fun syntaxHighlighterRecognizesPythonCommentsStringsKeywordsAndNumbers() {
        val code = """
            # Compute score
            async def calc(limit=3.5):
                name = 'offline\'s'
                if name is None:
                    return 0
                return limit
        """.trimIndent()

        val spans = CodeSyntaxHighlighter.findHighlights(code, "python")
        assertValidSortedNonOverlapping(spans, code.length)

        val comments = spans.filter { it.type == CodeSyntaxHighlighter.TokenType.COMMENT }
            .map { code.substring(it.start, it.end) }
        val strings = spans.filter { it.type == CodeSyntaxHighlighter.TokenType.STRING }
            .map { code.substring(it.start, it.end) }
        val keywords = spans.filter { it.type == CodeSyntaxHighlighter.TokenType.KEYWORD }
            .map { code.substring(it.start, it.end) }
        val numbers = spans.filter { it.type == CodeSyntaxHighlighter.TokenType.NUMBER }
            .map { code.substring(it.start, it.end) }

        assertEquals(listOf("# Compute score"), comments)
        assertEquals(listOf("'offline\\'s'"), strings)
        assertTrue(keywords.containsAll(listOf("async", "def", "if", "is", "None", "return")))
        assertEquals(listOf("3.5", "0"), numbers)
    }

    @Test
    fun syntaxHighlighterRecognizesSqlKeywordsCommentsStringsAndNumbers() {
        val code = """
            -- Fetch recent notes
            SELECT id, title FROM notes WHERE priority >= 2 AND tag = 'work';
            /* trailing */
            select id from archive;
        """.trimIndent()

        val spans = CodeSyntaxHighlighter.findHighlights(code, "sql")
        assertValidSortedNonOverlapping(spans, code.length)

        val comments = spans.filter { it.type == CodeSyntaxHighlighter.TokenType.COMMENT }
            .map { code.substring(it.start, it.end) }
        val strings = spans.filter { it.type == CodeSyntaxHighlighter.TokenType.STRING }
            .map { code.substring(it.start, it.end) }
        val keywords = spans.filter { it.type == CodeSyntaxHighlighter.TokenType.KEYWORD }
            .map { code.substring(it.start, it.end) }
        val numbers = spans.filter { it.type == CodeSyntaxHighlighter.TokenType.NUMBER }
            .map { code.substring(it.start, it.end) }

        assertEquals(listOf("-- Fetch recent notes", "/* trailing */"), comments)
        assertEquals(listOf("'work'"), strings)
        assertTrue(keywords.containsAll(listOf("SELECT", "FROM", "WHERE", "AND", "select", "from")))
        assertEquals(listOf("2"), numbers)
    }

    @Test
    fun syntaxHighlighterHandlesStreamingUnclosedTokensSafely() {
        val code = "val s = \"unclosed\n/* unclosed block"
        val spans = CodeSyntaxHighlighter.findHighlights(code, null)
        assertValidSortedNonOverlapping(spans, code.length)
        assertTrue(spans.any { it.type == CodeSyntaxHighlighter.TokenType.KEYWORD })
        assertTrue(spans.any { it.type == CodeSyntaxHighlighter.TokenType.STRING })
    }

    @Test
    fun inlineMarkdownParserParsesAllStylesWithoutRawDelimiters() {
        val input =
            "Mix ***both***, **bold**, __altBold__, *italic*, _altItalic_, `val x = *1*`, and [Docs](https://example.org)."

        val segments = InlineMarkdownParser.parseInline(input)

        val formattedTexts = segments.filter { it.style != null }
        assertEquals(
            listOf(
                InlineMarkdownParser.StyledSegment("both", InlineMarkdownParser.InlineStyle.BOLD_ITALIC),
                InlineMarkdownParser.StyledSegment("bold", InlineMarkdownParser.InlineStyle.BOLD),
                InlineMarkdownParser.StyledSegment("altBold", InlineMarkdownParser.InlineStyle.BOLD),
                InlineMarkdownParser.StyledSegment("italic", InlineMarkdownParser.InlineStyle.ITALIC),
                InlineMarkdownParser.StyledSegment("altItalic", InlineMarkdownParser.InlineStyle.ITALIC),
                InlineMarkdownParser.StyledSegment("val x = *1*", InlineMarkdownParser.InlineStyle.CODE),
                InlineMarkdownParser.StyledSegment("Docs", InlineMarkdownParser.InlineStyle.LINK, "https://example.org"),
            ),
            formattedTexts,
        )

        val plainJoined = segments.filter { it.style == null }.joinToString("") { it.text }
        assertFalse(plainJoined.contains("**"))
        assertFalse(plainJoined.contains("`"))
        assertFalse(plainJoined.contains("[Docs]"))
    }

    @Test
    fun inlineMarkdownParserPreservesPlainTextArithmeticAndSnakeCase() {
        assertTrue(InlineMarkdownParser.parseInline("").isEmpty())

        val plain = "Calculate 3 * 5 = 15 for my_variable_name with *unclosed"
        val segments = InlineMarkdownParser.parseInline(plain)

        assertEquals(1, segments.size)
        assertEquals(plain, segments[0].text)
        assertNull(segments[0].style)
        assertNull(segments[0].linkUrl)
    }

    @Test
    fun collapseHelpersRespectLatestFlagAndThresholds() {
        val shortText = "Short answer."
        val exactlyThresholdChars = "a".repeat(COLLAPSE_THRESHOLD_CHARS)
        val overThresholdChars = "a".repeat(COLLAPSE_THRESHOLD_CHARS + 1)
        val exactlyThresholdLines = (1..COLLAPSE_THRESHOLD_LINES).joinToString("\n") { "Line $it" }
        val overThresholdLines = (1..(COLLAPSE_THRESHOLD_LINES + 1)).joinToString("\n") { "Line $it" }

        assertFalse(canOfferCollapseToggle(shortText))
        assertFalse(canOfferCollapseToggle(exactlyThresholdChars))
        assertFalse(canOfferCollapseToggle(exactlyThresholdLines))

        assertTrue(canOfferCollapseToggle(overThresholdChars))
        assertTrue(canOfferCollapseToggle(overThresholdLines))

        // Latest assistant turn never collapses by default
        assertFalse(shouldCollapseByDefault(overThresholdChars, isLatestAssistant = true))
        assertFalse(shouldCollapseByDefault(overThresholdLines, isLatestAssistant = true))

        // Older historical assistant turns collapse only when exceeding thresholds
        assertFalse(shouldCollapseByDefault(shortText, isLatestAssistant = false))
        assertTrue(shouldCollapseByDefault(overThresholdChars, isLatestAssistant = false))
        assertTrue(shouldCollapseByDefault(overThresholdLines, isLatestAssistant = false))
    }

    @Test
    fun collapsePreviewTruncatesCleanlyAtMaxCharsAndManyLines() {
        assertEquals("Short", collapsePreview("Short", maxChars = 480))
        assertEquals("", collapsePreview("Short", maxChars = 0))

        val longProse = List(120) { "word$it" }.joinToString(" ")
        val preview = collapsePreview(longProse, maxChars = 120)
        assertTrue(preview.length <= 120)
        assertTrue(preview.endsWith("…"))

        val manyShortLines = (1..30).joinToString("\n") { "Item $it" }
        val linePreview = collapsePreview(manyShortLines, maxChars = 480)
        assertTrue(linePreview.endsWith("…"))
        assertTrue(linePreview.count { it == '\n' } < COLLAPSE_THRESHOLD_LINES)
    }

    @Test
    fun formatTimestampLabelFormatsTodayAndOlderDates() {
        assertEquals("", formatTimestampLabel(0L))
        assertEquals("", formatTimestampLabel(-10L))

        val now = System.currentTimeMillis()
        val expectedToday = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
        assertEquals(expectedToday, formatTimestampLabel(now))

        val olderCal = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -3)
        }
        val expectedOlder = SimpleDateFormat("MMM d · HH:mm", Locale.getDefault())
            .format(Date(olderCal.timeInMillis))
        assertEquals(expectedOlder, formatTimestampLabel(olderCal.timeInMillis))
    }

    @Test
    fun tableElementsSupportInlineFormattedCellsAndRejectPlainPipes() {
        val markdown = """
            Summary:
            | Item | Details |
            | :--- | :---: |
            | **Model** | `qwen2.5` |
            | Guide | [Read](https://example.org) |
            
            Plain prose with a | pipe and no separator.
        """.trimIndent()

        val elements = MarkdownFormatter.parseElements(markdown)
        assertEquals(3, elements.size)
        assertTrue(elements[0] is MarkdownFormatter.MarkdownElement.Paragraph)
        assertTrue(elements[1] is MarkdownFormatter.MarkdownElement.Table)
        assertTrue(elements[2] is MarkdownFormatter.MarkdownElement.Paragraph)

        val table = elements[1] as MarkdownFormatter.MarkdownElement.Table
        assertEquals(listOf("Item", "Details"), table.headers)
        assertEquals(2, table.rows.size)

        val boldCell = InlineMarkdownParser.parseInline(table.rows[0][0])
        assertEquals(
            listOf(InlineMarkdownParser.StyledSegment("Model", InlineMarkdownParser.InlineStyle.BOLD)),
            boldCell,
        )

        val codeCell = InlineMarkdownParser.parseInline(table.rows[0][1])
        assertEquals(
            listOf(InlineMarkdownParser.StyledSegment("qwen2.5", InlineMarkdownParser.InlineStyle.CODE)),
            codeCell,
        )

        val linkCell = InlineMarkdownParser.parseInline(table.rows[1][1])
        assertEquals(
            listOf(
                InlineMarkdownParser.StyledSegment(
                    "Read",
                    InlineMarkdownParser.InlineStyle.LINK,
                    "https://example.org",
                )
            ),
            linkCell,
        )
    }

    private fun assertValidSortedNonOverlapping(
        spans: List<CodeSyntaxHighlighter.HighlightSpan>,
        length: Int,
    ) {
        var prevEnd = 0
        for (span in spans) {
            assertTrue("Span start must be >= 0: $span", span.start >= 0)
            assertTrue("Span end must be <= $length: $span", span.end <= length)
            assertTrue("Span must be non-empty: $span", span.start < span.end)
            assertTrue("Spans must be sorted and non-overlapping: prevEnd=$prevEnd, span=$span", span.start >= prevEnd)
            prevEnd = span.end
        }
    }
}
