package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActivityTrackerTest {

    @Test
    fun `agent step badge supports both nested and alias references`() {
        val nested = AgentActivityTracker.AgentStepBadge(
            kind = AgentActivityTracker.StepKind.NOTES,
            title = "Notes",
            detail = "2 matched",
        )
        val aliased = AgentStepBadge(
            kind = StepKind.NOTES,
            title = "Notes",
            detail = "2 matched",
            status = StepStatus.DONE,
        )

        assertEquals(nested, aliased)
        assertEquals(StepStatus.DONE, nested.status)
    }

    @Test
    fun `formatDuration formats negative zero sub-minute and minute-plus durations`() {
        assertEquals("0.0s", AgentTelemetryFormatter.formatDuration(-250L))
        assertEquals("0.0s", AgentTelemetryFormatter.formatDuration(0L))
        assertEquals("2.1s", AgentTelemetryFormatter.formatDuration(2_100L))
        assertEquals("12.4s", AgentTelemetryFormatter.formatDuration(12_400L))
        assertEquals("1m 00s", AgentTelemetryFormatter.formatDuration(60_000L))
        assertEquals("2m 05s", AgentTelemetryFormatter.formatDuration(125_000L))
    }

    @Test
    fun `formatStreamingBadge renders stage duration before first token`() {
        assertEquals(
            "Loading model · 12.4s",
            AgentTelemetryFormatter.formatStreamingBadge("Loading model", 12_400L),
        )
        assertEquals(
            "Preparing context · 2.1s",
            AgentTelemetryFormatter.formatStreamingBadge("Preparing context", 2_100L, streamedChars = 0, firstTokenElapsedMs = 500L),
        )
    }

    @Test
    fun `formatStreamingBadge renders tokens and speed once decode threshold met`() {
        // Under 400ms decode window: omits t/s
        assertEquals(
            "Streaming · 1.2s · ~10 tok",
            AgentTelemetryFormatter.formatStreamingBadge("Streaming", 1_200L, streamedChars = 40, firstTokenElapsedMs = 900L),
        )
        // Only 1 estimated token: omits t/s
        assertEquals(
            "Streaming · 2.0s · ~1 tok",
            AgentTelemetryFormatter.formatStreamingBadge("Streaming", 2_000L, streamedChars = 4, firstTokenElapsedMs = 500L),
        )
        // >= 400ms decode and >= 2 tokens: includes t/s
        assertEquals(
            "Streaming · 5.4s · ~50 tok · 10.0 t/s",
            AgentTelemetryFormatter.formatStreamingBadge("Streaming", 5_400L, streamedChars = 200, firstTokenElapsedMs = 400L),
        )
    }

    @Test
    fun `formatCompletedBadge includes throughput only when thresholds are met`() {
        assertEquals(
            "PocketAgent · CPU · 3.5s",
            AgentTelemetryFormatter.formatCompletedBadge("CPU", 3_500L),
        )
        assertEquals(
            "PocketAgent · GPU · 2.0s",
            AgentTelemetryFormatter.formatCompletedBadge("GPU", 2_000L, finalChars = 20, firstTokenElapsedMs = 500L),
        )
        assertEquals(
            "PocketAgent · GPU · 1.2s",
            AgentTelemetryFormatter.formatCompletedBadge("GPU", 1_200L, finalChars = 80, firstTokenElapsedMs = 900L),
        )
        assertEquals(
            "PocketAgent · GPU · 3.0s · 15.0 t/s",
            AgentTelemetryFormatter.formatCompletedBadge("GPU", 3_000L, finalChars = 120, firstTokenElapsedMs = 1_000L),
        )
    }

    @Test
    fun `notesRetriever ranks title and tag hits above content-only hits and skips unrelated notes`() {
        val contentOnly = PersonalNotesStore.PersonalNote(
            id = "n1",
            title = "Weekend Shopping",
            content = "Buy batteries and router cables for the home wifi setup.",
            tags = listOf("shopping"),
            createdAt = 100L,
            updatedAt = 3_000L,
        )
        val titleHit = PersonalNotesStore.PersonalNote(
            id = "n2",
            title = "Home Wifi Credentials",
            content = "Guest access code is cedar-19.",
            tags = listOf("network"),
            createdAt = 100L,
            updatedAt = 2_000L,
        )
        val unrelatedPinned = PersonalNotesStore.PersonalNote(
            id = "n3",
            title = "Grocery List",
            content = "Apples, oats, and coffee beans.",
            tags = listOf("food"),
            isPinned = true,
            createdAt = 100L,
            updatedAt = 5_000L,
        )

        val results = NotesRetriever.findRelevantNotes(
            query = "What is my home wifi password?",
            notes = listOf(contentOnly, titleHit, unrelatedPinned),
            maxNotes = 2,
        )

        assertEquals(2, results.size)
        assertEquals("n2", results[0].id)
        assertEquals("n1", results[1].id)
        assertFalse(results.any { it.id == "n3" })
    }

    @Test
    fun `notesRetriever matches tags and enforces maxTotalChars smoothly`() {
        val tagHit = PersonalNotesStore.PersonalNote(
            id = "tag1",
            title = "Passport Details",
            content = "Stored in top drawer.",
            tags = listOf("travel", "documents"),
            createdAt = 100L,
            updatedAt = 2_000L,
        )
        val longContentHit = PersonalNotesStore.PersonalNote(
            id = "long1",
            title = "Travel Checklist",
            content = "Pack charger adapter itinerary boarding pass " + "item ".repeat(200),
            tags = listOf("checklist"),
            createdAt = 100L,
            updatedAt = 1_000L,
        )

        val singleLong = NotesRetriever.findRelevantNotes(
            query = "travel checklist",
            notes = listOf(longContentHit),
            maxNotes = 2,
            maxTotalChars = 120,
        )
        assertEquals(1, singleLong.size)
        assertTrue(singleLong[0].title.length + singleLong[0].content.length <= 120)

        val multi = NotesRetriever.findRelevantNotes(
            query = "travel documents",
            notes = listOf(longContentHit, tagHit),
            maxNotes = 2,
            maxTotalChars = 150,
        )
        assertTrue(multi.isNotEmpty())
        assertEquals("tag1", multi[0].id)
        assertTrue(multi.sumOf { it.title.length + it.content.length } <= 150)
    }

    @Test
    fun `notesRetriever ignores stopwords and short query tokens`() {
        val note = PersonalNotesStore.PersonalNote(
            id = "n1",
            title = "To Do",
            content = "Go to the store and do it.",
        )

        val results = NotesRetriever.findRelevantNotes(
            query = "is it to do?",
            notes = listOf(note),
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `mainUiState preserves defaults and supports additive telemetry transitions`() {
        val initial = MainUiState()
        assertTrue(initial.agentSteps.isEmpty())
        assertEquals("", initial.liveStageLabel)
        assertEquals("", initial.lastGenerationMetrics)

        val steps = listOf(
            AgentActivityTracker.AgentStepBadge(
                kind = AgentActivityTracker.StepKind.CALCULATOR,
                title = "Calculator",
                detail = "15% of 2400 = 360",
            )
        )
        val updated = initial
            .withAgentSteps(steps)
            .withLiveStage("Streaming · 1.8s · ~24 tok · 13.3 t/s")
            .withGenerationMetrics("PocketAgent · GPU · 2.4s · 16.0 t/s")

        assertEquals(steps, updated.agentSteps)
        assertEquals("Streaming · 1.8s · ~24 tok · 13.3 t/s", updated.liveStageLabel)
        assertEquals("PocketAgent · GPU · 2.4s · 16.0 t/s", updated.lastGenerationMetrics)
    }
}
