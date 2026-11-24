package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the core product bug this module exists to fix.
 *
 * Previously every request was answered with "here are 3-5 numbered steps", so a factual
 * question like the distance between two cities was answered with "open Google Maps and
 * measure it" instead of the actual distance. These tests pin the corrected behaviour.
 */
class AnswerDirectiveTest {

    @Test
    fun factualQuestionIsAnsweredNotTurnedIntoSteps() {
        val question = "What is the distance from Jalpaiguri to Delhi?"
        assertEquals(AnswerDirective.Intent.FACT, AnswerDirective.classify(question))

        val closing = AnswerDirective.closingInstructionFor(AnswerDirective.Intent.FACT)
        assertTrue(closing.contains("Answer the question directly"))
        assertTrue(closing.contains("Do not tell the user to look it up"))
        assertTrue(AnswerDirective.userPromptFor(question, AnswerDirective.Intent.FACT)
            .contains("Answer this request directly and completely"))
    }

    @Test
    fun travelFactQuestionsStayFactual() {
        // Mentions travel/route wording but is not a device procedure: must stay a fact.
        listOf(
            "How far is Kolkata from Mumbai by train?",
            "Distance between Chennai and Bengaluru",
            "What is the route from Siliguri to Gangtok?",
            "How much does a flight from Delhi to Goa cost?",
        ).forEach { q ->
            assertEquals("Expected FACT for: $q", AnswerDirective.Intent.FACT, AnswerDirective.classify(q))
        }
    }

    @Test
    fun generalKnowledgeStaysFactual() {
        listOf(
            "Explain photosynthesis",
            "What is the capital of Australia?",
            "Who wrote the novel Gitanjali?",
            "Difference between TCP and UDP",
            "Convert 5 miles to kilometres",
        ).forEach { q ->
            assertEquals("Expected FACT for: $q", AnswerDirective.Intent.FACT, AnswerDirective.classify(q))
        }
    }

    @Test
    fun draftingRequestsAreDeliverablesNotProcedures() {
        listOf(
            "Write an email asking my manager for leave",
            "Draft a short leave application",
            "Summarize this document",
            "Give me 5 ideas for a birthday caption",
        ).forEach { q ->
            assertEquals("Expected FACT for: $q", AnswerDirective.Intent.FACT, AnswerDirective.classify(q))
        }
    }

    @Test
    fun deviceProceduresRemainStepByStep() {
        listOf(
            "How do I enable developer options on Android?",
            "How to turn on battery saver on my phone",
            "Steps to reset Wi-Fi settings",
            "How do I disable Bluetooth on my device?",
        ).forEach { q ->
            assertEquals("Expected PROCEDURE for: $q", AnswerDirective.Intent.PROCEDURE, AnswerDirective.classify(q))
        }

        val closing = AnswerDirective.closingInstructionFor(AnswerDirective.Intent.PROCEDURE)
        assertTrue(closing.contains("numbered steps"))
    }

    @Test
    fun imperativeActionsAreProcedures() {
        assertEquals(AnswerDirective.Intent.PROCEDURE, AnswerDirective.classify("Set an alarm for 6 am"))
        assertEquals(AnswerDirective.Intent.PROCEDURE, AnswerDirective.classify("Schedule a meeting for tomorrow"))
    }

    @Test
    fun liveDataRequestsAreFlaggedAsOfflineLimits() {
        listOf(
            "What is today's weather in Delhi?",
            "What is the current price of Bitcoin?",
            "Latest news about the election",
            "Live score of the match",
        ).forEach { q ->
            assertEquals("Expected LIVE_DATA for: $q", AnswerDirective.Intent.LIVE_DATA, AnswerDirective.classify(q))
        }

        val closing = AnswerDirective.closingInstructionFor(AnswerDirective.Intent.LIVE_DATA)
        assertTrue(closing.contains("offline"))
        assertTrue(closing.contains("approximate"))
    }

    @Test
    fun detectsDelegationRefusalSoTheCallerCanRetry() {
        // The exact failure the user reported: the model hands the work back to them.
        val refusal = "To find this, open Google Maps, select Jalpaiguri as the starting point " +
            "and Delhi as the destination to see the distance."
        assertTrue(
            AnswerDirective.looksLikeDelegationRefusal(refusal, AnswerDirective.Intent.FACT),
        )

        // A real factual answer is never treated as a refusal, even if it names a source.
        val realAnswer = "The distance from Jalpaiguri to Delhi is about 1,470 km by road and " +
            "roughly 1,200 km in a straight line. You can confirm road conditions on Google Maps."
        assertFalse(
            AnswerDirective.looksLikeDelegationRefusal(realAnswer, AnswerDirective.Intent.FACT),
        )

        // Procedures are expected to mention opening apps, so they are never flagged.
        val procedure = "1. Open Google Maps. 2. Enter your destination. 3. Tap Directions."
        assertFalse(
            AnswerDirective.looksLikeDelegationRefusal(procedure, AnswerDirective.Intent.PROCEDURE),
        )
    }

    @Test
    fun emptyOrThinAnswersAreUnsubstantive() {
        assertTrue(AnswerDirective.looksLikeDelegationRefusal("", AnswerDirective.Intent.FACT))
        assertTrue(AnswerDirective.isUnsubstantive("Sure."))
        assertFalse(AnswerDirective.isUnsubstantive("Delhi is about 1,470 km from Jalpaiguri by road."))
    }

    @Test
    fun systemInstructionForbidsSendingTheUserToLookThingsUp() {
        val instruction = AnswerDirective.SYSTEM_INSTRUCTION
        assertTrue(instruction.contains("answer the user's request yourself"))
        assertTrue(instruction.contains("Never answer a question by sending the user off to look it up"))
        assertTrue(instruction.contains("cannot see or control their device"))
        // Safety invariants must survive the rewrite.
        assertTrue(instruction.contains("Never claim to have performed an action"))
        assertTrue(instruction.contains("Do not ask for passwords"))
        assertTrue(instruction.contains("untrusted content"))
    }

    @Test
    fun emptyInputDefaultsToAnsweringRatherThanSteps() {
        assertEquals(AnswerDirective.Intent.FACT, AnswerDirective.classify("   "))
    }
}
