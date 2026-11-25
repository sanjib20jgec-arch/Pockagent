package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownFormatterTest {

    @Test
    fun formatsHeadersWithVisualHierarchy() {
        val input = """
            # Primary Heading
            ## Secondary Section
            ### Detail Subsection
        """.trimIndent()

        val formatted = MarkdownFormatter.formatForDisplay(input)

        assertTrue(formatted.contains("PRIMARY HEADING"))
        assertTrue(formatted.contains("──────"))
        assertTrue(formatted.contains("■ Secondary Section"))
        assertTrue(formatted.contains("◆ Detail Subsection"))
    }

    @Test
    fun formatsBulletPointsWithCleanBulletsAndIndent() {
        val input = """
            * Top level step
              * Sub step A
              * Sub step B
            - Dash bullet
            + Plus bullet
        """.trimIndent()

        val formatted = MarkdownFormatter.formatForDisplay(input)

        assertTrue(formatted.contains("• Top level step"))
        assertTrue(formatted.contains("  • Sub step A"))
        assertTrue(formatted.contains("  • Sub step B"))
        assertTrue(formatted.contains("• Dash bullet"))
        assertTrue(formatted.contains("• Plus bullet"))
    }

    @Test
    fun preservesArithmeticAndDoesNotMisidentifyAsBullet() {
        val input = "Calculate 3 * 5 = 15 or 10 - 2 = 8."
        val formatted = MarkdownFormatter.formatForDisplay(input)

        assertEquals("Calculate 3 * 5 = 15 or 10 - 2 = 8.", formatted)
        assertFalse(formatted.contains("•"))
    }

    @Test
    fun formatsNumberedListsAccurately() {
        val input = """
            1. Open device Settings.
            2. Scroll down to Apps & Notifications.
            3. Tap on App Permissions.
        """.trimIndent()

        val formatted = MarkdownFormatter.formatForDisplay(input)

        assertTrue(formatted.contains("1. Open device Settings."))
        assertTrue(formatted.contains("2. Scroll down to Apps & Notifications."))
        assertTrue(formatted.contains("3. Tap on App Permissions."))
    }

    @Test
    fun formatsCodeBlocksWithFramedBordersAndLanguageTag() {
        val input = """
            ```bash
            pm list packages -f
            dumpsys battery
            ```
        """.trimIndent()

        val formatted = MarkdownFormatter.formatForDisplay(input)

        assertTrue(formatted.contains("┌── bash"))
        assertTrue(formatted.contains("│ pm list packages -f"))
        assertTrue(formatted.contains("│ dumpsys battery"))
        assertTrue(formatted.contains("└──"))
    }

    @Test
    fun formatsCodeBlockWithoutLanguageDefaultingToCode() {
        val input = """
            ```
            echo "offline assistant"
            ```
        """.trimIndent()

        val formatted = MarkdownFormatter.formatForDisplay(input)

        assertTrue(formatted.contains("┌── Code"))
        assertTrue(formatted.contains("│ echo \"offline assistant\""))
        assertTrue(formatted.contains("└──"))
    }

    @Test
    fun handlesStreamingUnclosedCodeBlockGracefully() {
        // Simulates an ongoing LLM stream where the closing ``` has not arrived yet
        val streamingInput = """
            Here is the command:
            ```kotlin
            val state = MainUiState()
        """.trimIndent()

        val formatted = MarkdownFormatter.formatForDisplay(streamingInput)

        assertTrue(formatted.contains("Here is the command:"))
        assertTrue(formatted.contains("┌── kotlin"))
        assertTrue(formatted.contains("│ val state = MainUiState()"))
        assertTrue(formatted.contains("└──"))
    }

    @Test
    fun stripsMarkdownSyntaxCleanlyForSpeechAndExport() {
        val input = """
            # Setup Guide
            1. **Enable offline mode**: Turn off mobile data.
            2. Run `pm clear com.example.app` in terminal.
            
            ```bash
            am start -n com.example/.MainActivity
            ```
            
            *Tip*: Verify [docs](https://example.com) for details.
        """.trimIndent()

        val plain = MarkdownFormatter.stripToPlainText(input)

        // Headers stripped of #
        assertTrue(plain.contains("Setup Guide"))
        assertFalse(plain.contains("#"))

        // Bold stripped of **
        assertTrue(plain.contains("1. Enable offline mode: Turn off mobile data."))
        assertFalse(plain.contains("**"))

        // Inline code backticks stripped
        assertTrue(plain.contains("2. Run pm clear com.example.app in terminal."))
        assertFalse(plain.contains("`"))

        // Code block fences stripped, code body retained
        assertTrue(plain.contains("am start -n com.example/.MainActivity"))
        assertFalse(plain.contains("```"))
        assertFalse(plain.contains("┌──"))

        // Italic stripped and links cleaned
        assertTrue(plain.contains("Tip: Verify docs (https://example.com) for details."))
        assertFalse(plain.contains("*Tip*"))
    }

    @Test
    fun extractsIndividualStepsFromResponse() {
        val response = """
            Here is what to do:
            1. Check your battery percentage.
            2. Turn on Battery Saver mode.
            3. Lower display brightness to 30%.
            
            Additional suggestions:
            * Close background apps.
            * Disable location services.
        """.trimIndent()

        val steps = MarkdownFormatter.extractSteps(response)

        assertEquals(5, steps.size)
        assertEquals("Check your battery percentage.", steps[0])
        assertEquals("Turn on Battery Saver mode.", steps[1])
        assertEquals("Lower display brightness to 30%.", steps[2])
        assertEquals("Close background apps.", steps[3])
        assertEquals("Disable location services.", steps[4])
    }

    @Test
    fun normalizesExcessiveWhitespaceAndNewlines() {
        val input = "Step 1\n\n\n\n\nStep 2   \n\n\n   \n\nStep 3\n\n"
        val normalized = MarkdownFormatter.normalizeSpacing(input)

        assertEquals("Step 1\n\nStep 2\n\nStep 3", normalized)
    }

    @Test
    fun handlesEmptyAndBlankInputsSafely() {
        assertEquals("", MarkdownFormatter.formatForDisplay(""))
        assertEquals("", MarkdownFormatter.formatForDisplay("   \n\t  \n  "))
        assertEquals("", MarkdownFormatter.stripToPlainText(""))
        assertEquals("", MarkdownFormatter.stripToPlainText("   "))
        assertTrue(MarkdownFormatter.extractSteps("").isEmpty())
        assertTrue(MarkdownFormatter.parseElements("").isEmpty())
    }

    @Test
    fun handlesBlockquotesAndHorizontalRules() {
        val input = """
            > Always verify steps manually.
            
            ---
            
            Proceed with caution.
        """.trimIndent()

        val elements = MarkdownFormatter.parseElements(input)
        assertEquals(3, elements.size)
        assertTrue(elements[0] is MarkdownFormatter.MarkdownElement.Blockquote)
        assertTrue(elements[1] is MarkdownFormatter.MarkdownElement.HorizontalRule)
        assertTrue(elements[2] is MarkdownFormatter.MarkdownElement.Paragraph)

        val formatted = MarkdownFormatter.formatForDisplay(input)
        assertTrue(formatted.contains("┃ Always verify steps manually."))
        assertTrue(formatted.contains("────────────────────────────"))
        assertTrue(formatted.contains("Proceed with caution."))
    }

    @Test
    fun parsesGfmTableWithAlignmentsAndNormalizesUnevenRows() {
        val input = """
            Intro paragraph.
            | Name | Role | Score |
            | :--- | :---: | ---: |
            | Alice | Admin | 98 |
            | Bob | User |
            | Carol | Guest | 75 | extra |
            Outro paragraph.
        """.trimIndent()

        val elements = MarkdownFormatter.parseElements(input)
        assertEquals(3, elements.size)
        assertTrue(elements[0] is MarkdownFormatter.MarkdownElement.Paragraph)
        assertTrue(elements[1] is MarkdownFormatter.MarkdownElement.Table)
        assertTrue(elements[2] is MarkdownFormatter.MarkdownElement.Paragraph)

        val table = elements[1] as MarkdownFormatter.MarkdownElement.Table
        assertEquals(listOf("Name", "Role", "Score"), table.headers)
        assertEquals(3, table.rows.size)
        assertEquals(listOf("Alice", "Admin", "98"), table.rows[0])
        assertEquals(listOf("Bob", "User", ""), table.rows[1])
        assertEquals(listOf("Carol", "Guest", "75"), table.rows[2])
    }

    @Test
    fun parsesGfmTableWithoutOuterPipes() {
        val input = """
            Metric | Value
            ---|---
            Latency | 12ms
            Throughput | 45 tok/s
        """.trimIndent()

        val elements = MarkdownFormatter.parseElements(input)
        assertEquals(1, elements.size)
        val table = elements[0] as MarkdownFormatter.MarkdownElement.Table
        assertEquals(listOf("Metric", "Value"), table.headers)
        assertEquals(
            listOf(
                listOf("Latency", "12ms"),
                listOf("Throughput", "45 tok/s"),
            ),
            table.rows,
        )
    }

    @Test
    fun doesNotParsePipeLinesWithoutSeparatorRowAsTable() {
        val input = """
            Use A | B for bitwise OR in Kotlin.
            Another line | with pipes | here.
            
            ```text
            | Col1 | Col2 |
            | --- | --- |
            | Val1 | Val2 |
            ```
        """.trimIndent()

        val elements = MarkdownFormatter.parseElements(input)
        assertEquals(2, elements.size)
        assertTrue(elements[0] is MarkdownFormatter.MarkdownElement.Paragraph)
        assertTrue(elements[1] is MarkdownFormatter.MarkdownElement.CodeBlock)
    }

    @Test
    fun formatsAndStripsTablesCleanlyForDisplayAndTts() {
        val input = """
            | **Feature** | Status |
            | :--- | ---: |
            | `Tables` | Ready |
            | [Docs](https://example.com) | Done |
        """.trimIndent()

        assertTrue(MarkdownFormatter.containsMarkdownSyntax("A | B"))

        val formatted = MarkdownFormatter.formatForDisplay(input)
        assertTrue(formatted.contains("Feature"))
        assertTrue(formatted.contains("Status"))
        assertTrue(formatted.contains("Tables"))
        assertTrue(formatted.contains("Docs (https://example.com)"))
        assertTrue(formatted.contains(" | "))
        assertFalse(formatted.contains("**"))

        val plain = MarkdownFormatter.stripToPlainText(input)
        assertTrue(plain.contains("Feature, Status"))
        assertTrue(plain.contains("Tables, Ready"))
        assertTrue(plain.contains("Docs (https://example.com), Done"))
        assertFalse(plain.contains("|"))
        assertFalse(plain.contains("---"))
        assertFalse(plain.contains("`"))
    }
}
