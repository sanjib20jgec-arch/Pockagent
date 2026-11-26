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

    @Test fun `superpowers playbooks are registered and detected case-insensitively`() {
        val expected = listOf("/brainstorm", "/plan", "/debug", "/review", "/verify")
        expected.forEach { trigger ->
            val detected = SlashCommandDetector.detectCommand("$trigger my task")
            assertNotNull("Expected $trigger to be detected", detected)
            assertEquals(trigger, detected!!.trigger)

            val upperDetected = SlashCommandDetector.detectCommand("${trigger.uppercase()} my task")
            assertNotNull("Expected uppercase $trigger to be detected", upperDetected)
            assertEquals("my task", SlashCommandDetector.stripCommand("${trigger.uppercase()} my task", upperDetected!!))
        }
        assertTrue(SlashCommandDetector.getSuggestions("/de").any { it.trigger == "/debug" })
        assertTrue(SlashCommandDetector.getSuggestions("/ver").any { it.trigger == "/verify" })
    }
}
