package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningRouterTest {

    @Test
    fun `detects English, Bengali script, and Banglish accurately`() {
        assertEquals(ReasoningRouter.LanguageMode.ENGLISH, ReasoningRouter.detectLanguageMode("Explain quicksort in Kotlin"))
        assertEquals(ReasoningRouter.LanguageMode.BENGALI_SCRIPT, ReasoningRouter.detectLanguageMode("আমার নোট থেকে সারাংশ বলো"))
        assertEquals(ReasoningRouter.LanguageMode.BANGLISH, ReasoningRouter.detectLanguageMode("ami kivabe eta fix korbo"))
    }

    @Test
    fun `routes coding and grounded prompts to lower temperature and disables deep think for greetings`() {
        val greeting = ReasoningRouter.route("Hi!", hasGroundedContext = false, userTemperature = 0.8f)
        assertEquals(ReasoningRouter.TaskProfile.QUICK, greeting.profile)
        assertEquals(0.8f, greeting.recommendedTemperature, 0.001f)
        assertFalse(greeting.shouldAllowDeepThink)

        val coding = ReasoningRouter.route("Fix this Kotlin SQL regex bug", hasGroundedContext = false, userTemperature = 0.8f)
        assertEquals(ReasoningRouter.TaskProfile.LOGIC_CODE, coding.profile)
        assertEquals(0.25f, coding.recommendedTemperature, 0.001f)
        assertTrue(coding.shouldAllowDeepThink)

        val docs = ReasoningRouter.route("অর্থনীতি নিয়ে কী লেখা আছে?", hasGroundedContext = true, userTemperature = 0.9f)
        assertEquals(ReasoningRouter.TaskProfile.GROUNDED_DOCS, docs.profile)
        assertEquals(ReasoningRouter.LanguageMode.BENGALI_SCRIPT, docs.languageMode)
        assertEquals(0.35f, docs.recommendedTemperature, 0.001f)
        assertTrue(docs.compactDirective.contains("বাংলা"))
        assertTrue(docs.shouldAllowDeepThink)
    }

    @Test
    fun `deduplicateExcerpts removes duplicates preserving order`() {
        val deduped = ReasoningRouter.deduplicateExcerpts(
            listOf("First excerpt\nline 2", "first excerpt line 2", "Second excerpt", "   ")
        )
        assertEquals(2, deduped.size)
        assertTrue(deduped[0].startsWith("First excerpt"))
        assertEquals("Second excerpt", deduped[1])
    }
}
