package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the reported bug: after a few answers the model printed the same two lines
 * forever and generation never stopped.
 */
class RunawayGuardTest {

    @Test
    fun stopsWhenTwoLinesRepeatForever() {
        // Exactly what the user saw: two lines, repeated endlessly.
        val looping = "The distance is about 1,470 km.\nTap Ask again to continue.\n".repeat(40)

        assertTrue("Runaway loop must be detected", RunawayGuard.shouldStop(looping))
        assertNotNull(RunawayGuard.stuckFragment(looping))
    }

    @Test
    fun stopsWhenOneLineRepeatsForever() {
        val looping = ("I cannot answer that question.\n").repeat(30)
        assertTrue(RunawayGuard.shouldStop(looping))
    }

    @Test
    fun stopsWhenTheLoopHappensInsideOneLine() {
        // Some models degenerate without any newline at all.
        val looping = "Delhi is far away. ".repeat(60)
        assertTrue(RunawayGuard.shouldStop(looping))
    }

    @Test
    fun doesNotStopOnANormalAnswer() {
        val normal = """
            Jalpaiguri to Delhi is about 1,470 km by road, and roughly 1,200 km in a straight line.
            By train the journey takes around 20 hours depending on the route.
            The fastest option is usually a flight to Delhi, which takes about 2 hours.

            Would you like a route breakdown by road?
        """.trimIndent()

        assertFalse("A normal multi-paragraph answer must not be flagged", RunawayGuard.shouldStop(normal))
        assertNull(RunawayGuard.stuckFragment(normal))
    }

    @Test
    fun doesNotStopOnALongLegitimateList() {
        // Length alone is not degeneration: ten real numbered steps must survive.
        val longList = (1..10).joinToString("\n") { step ->
            "$step. This is a genuinely different step number $step explaining a distinct action."
        }

        assertFalse(RunawayGuard.shouldStop(longList))
    }

    @Test
    fun doesNotStopOnARepeatedWordInShortProse() {
        val short = "That is a very very good question. The answer is 42."
        assertFalse(RunawayGuard.shouldStop(short))
    }

    @Test
    fun stopsOnEndlessBlankLines() {
        val padded = "The answer is 42.\n" + "\n".repeat(60)
        assertTrue(RunawayGuard.shouldStop(padded))
    }

    @Test
    fun stopsOnBufferOverflow() {
        val huge = "a distinct sentence that keeps growing. ".repeat(400)
        assertTrue(RunawayGuard.shouldStop(huge))
    }

    @Test
    fun stoppedNoticeNamesTheLoopSoTheUserUnderstands() {
        val notice = RunawayGuard.stoppedNotice("Tap Ask again to continue.")
        assertTrue(notice.contains("Stopping") || notice.contains("Stopped"))
        assertTrue(notice.contains("Tap Ask again to continue."))
    }

    // ---------------------------------------------------------------- finalizer

    @Test
    fun finalizerCollapsesALoopingTailToASingleCopy() {
        val looping = "Jalpaiguri is about 1,470 km from Delhi.\n" +
            "Tap Ask again to continue.\n".repeat(25)

        val result = AnswerFinalizer.finalize(looping)

        assertTrue("Must be reported as truncated", result.wasTruncated)
        assertTrue(result.text.startsWith("Jalpaiguri is about 1,470 km from Delhi."))
        assertEquals(
            "The loop body must appear once, not 25 times",
            1,
            Regex("Tap Ask again to continue").findAll(result.text).count(),
        )
    }

    @Test
    fun finalizerLeavesCleanAnswersUntouched() {
        val clean = "Delhi is about 1,470 km from Jalpaiguri by road."
        val result = AnswerFinalizer.finalize(clean)

        assertEquals(clean, result.text)
        assertFalse(result.wasTruncated)
    }

    @Test
    fun finalizerMarksAnAnswerCutOffMidSentence() {
        val cutOff = "The first point is complete. The second point was cut off mid sen"

        val finished = AnswerFinalizer.finishSentence(cutOff)

        // Cut off too late to rewind to a sentence boundary, so it is marked rather than
        // silently left dangling mid-word.
        assertTrue(finished.endsWith(AnswerFinalizer.SENTENCE_END_MARKER))
        assertTrue(finished.contains("The first point is complete."))
    }

    @Test
    fun finalizerRewindsToASentenceBoundaryWhenClose() {
        val mostlyDone = "The first point is complete. The second point is also complete. And then the final"

        val finished = AnswerFinalizer.finishSentence(mostlyDone)

        assertTrue(finished.endsWith("complete."))
        assertFalse(finished.contains("And then the final"))
    }

    @Test
    fun finalizerHandlesEmptyInput() {
        val result = AnswerFinalizer.finalize("   ")
        assertEquals("", result.text)
        assertFalse(result.wasTruncated)
    }

    // ---------------------------------------------------------------- guarded generation

    @Test
    fun guardedGenerationStopsAcceptingChunksOnceItDetectsALoop() {
        val accumulated = StringBuilder()
        val guard = GuardedGeneration()
        var accepted = 0

        // Feed the same two lines over and over, as the model would stream them.
        var stillGoing = true
        repeat(60) {
            if (stillGoing) {
                stillGoing = guard.accept(accumulated, "Line one.\nLine two.\n")
                if (stillGoing) accepted++
            }
        }

        assertFalse("Guard must stop accepting chunks", stillGoing)
        assertTrue("Must have stopped well before 60 chunks, got $accepted", accepted < 60)
        assertNotNull(guard.runawayFragment)
        assertTrue(guard.isStopped)
    }

    @Test
    fun guardedGenerationKeepsAllChunksForAHealthyAnswer() {
        val accumulated = StringBuilder()
        val guard = GuardedGeneration()

        listOf(
            "Photosynthesis is the process ",
            "by which plants convert light ",
            "into chemical energy. ",
            "It happens in the chloroplasts.",
        ).forEach { chunk ->
            assertTrue("Healthy answer must not be stopped", guard.accept(accumulated, chunk))
        }

        assertNull(guard.runawayFragment)
        assertFalse(guard.isStopped)
        assertEquals(
            "Photosynthesis is the process by which plants convert light into chemical energy. " +
                "It happens in the chloroplasts.",
            guard.text(accumulated),
        )
    }

    @Test
    fun cancellingGuardedGenerationIgnoresLaterChunks() {
        val accumulated = StringBuilder()
        val guard = GuardedGeneration()
        guard.accept(accumulated, "Partial answer. ")

        guard.cancel()

        assertFalse(guard.accept(accumulated, "Should be ignored."))
        assertFalse(guard.text(accumulated).contains("Should be ignored"))
        assertTrue(guard.wasCancelled)
    }

    @Test
    fun oversizedSingleChunkIsBoundedInsteadOfAccumulatedWholesale() {
        val accumulated = StringBuilder()
        val guard = GuardedGeneration()

        // An engine that streams an entire degenerate generation as one chunk.
        guard.accept(accumulated, "Checking the weather.\n".repeat(5_000))

        assertTrue(guard.isStopped)
        assertTrue(
            "accumulated text must never exceed the answer budget, was ${accumulated.length}",
            accumulated.length <= RunawayGuard.MAX_ANSWER_CHARS
        )
        assertFalse(guard.accept(accumulated, "More text after a stop."))
    }

    @Test
    fun collapseTrimsLongLoopThatSlidesPastTheTailWindow() {
        val looped = "Checking the weather.\n".repeat(2_000)
        assertEquals(44_000, looped.length)

        val collapsed = AnswerFinalizer.collapseLoopingTail(looped)

        assertTrue("was <<<$collapsed>>>", collapsed.startsWith("Checking the weather."))
        assertEquals("was <<<$collapsed>>>", 1, collapsed.split("Checking the weather.").size - 1)
    }

    @Test
    fun collapseKeepsGenuineTextBeforeALoop() {
        val answer = "The distance is 42 km.\n" + "Checking the weather.\n".repeat(400)

        val collapsed = AnswerFinalizer.collapseLoopingTail(answer)

        assertTrue(collapsed.startsWith("The distance is 42 km."))
        assertEquals(1, collapsed.split("Checking the weather.").size - 1)
    }

    @Test
    fun collapseKeepsOrdinaryMultiParagraphAnswersIntact() {
        val answer = """
            Kolkata to Jangipur is about 260 km by road.
            It takes roughly 5 hours by car.

            The fastest route is via NH12 and Berhampore.
        """.trimIndent()

        assertEquals(answer, AnswerFinalizer.collapseLoopingTail(answer))

        val result = AnswerFinalizer.finalize(answer)
        assertEquals(answer, result.text)
        assertFalse(result.wasTruncated)
    }

    @Test
    fun collapseKeepsRepeatedStructureThatIsNotALoop() {
        // Three short bullet lines that happen to be equal are legitimate formatting, not a loop.
        val answer = "Done.\n- ok\n- ok\n- ok"
        assertEquals(answer, AnswerFinalizer.collapseLoopingTail(answer))
    }
}
