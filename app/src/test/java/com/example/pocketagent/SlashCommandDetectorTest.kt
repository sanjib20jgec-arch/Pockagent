package com.example.pocketagent

import org.junit.Assert.*
import org.junit.Test

class SlashCommandDetectorTest {

    @Test fun `no slash returns null`() {
        assertNull(SlashCommandDetector.detectCommand("hello world"))
    }

    @Test fun `detect summarize command`() {
        val cmd = SlashCommandDetector.detectCommand("/summarize this document")
        assertNotNull(cmd)
        assertEquals("/summarize", cmd!!.trigger)
    }

    @Test fun `detect exact command with no trailing space`() {
        assertNotNull(SlashCommandDetector.detectCommand("/code"))
    }

    @Test fun `unknown slash returns null`() {
        assertNull(SlashCommandDetector.detectCommand("/unknown_command"))
    }

    @Test fun `getSuggestions returns matching commands`() {
        assertTrue(SlashCommandDetector.getSuggestions("/su").any { it.trigger == "/summarize" })
    }

    @Test fun `getSuggestions returns empty for non-slash`() {
        assertTrue(SlashCommandDetector.getSuggestions("hello").isEmpty())
    }

    @Test fun `stripCommand removes trigger`() {
        val cmd = SlashCommandDetector.ALL_COMMANDS.first { it.trigger == "/translate" }
        assertEquals("hello", SlashCommandDetector.stripCommand("/translate hello", cmd))
    }

    @Test fun `buildEffectivePrompt enriches instruction`() {
        val (prompt, instruction) = SlashCommandDetector.buildEffectivePrompt(
            "/summarize Tell me about dogs", "Base instruction.")
        assertEquals("Tell me about dogs", prompt)
        assertTrue(instruction.contains("summary"))
        assertTrue(instruction.contains("Base instruction."))
    }

    @Test fun `buildEffectivePrompt passthrough for no command`() {
        val (prompt, instruction) = SlashCommandDetector.buildEffectivePrompt("Just ask", "Base.")
        assertEquals("Just ask", prompt)
        assertEquals("Base.", instruction)
    }

    @Test fun `all commands have non-blank triggers and descriptions`() {
        SlashCommandDetector.ALL_COMMANDS.forEach { cmd ->
            assertTrue(cmd.trigger.isNotBlank())
            assertTrue(cmd.description.isNotBlank())
            assertTrue(cmd.systemPromptPrefix.isNotBlank())
        }
    }
}
