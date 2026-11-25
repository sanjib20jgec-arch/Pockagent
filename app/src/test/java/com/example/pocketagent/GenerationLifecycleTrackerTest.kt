package com.example.pocketagent

import org.junit.Assert.*
import org.junit.Test

class GenerationLifecycleTrackerTest {

    @Test
    fun beginBindsGenerationToOriginatingSession() {
        val tracker = GenerationLifecycleTracker()
        val token = tracker.begin("session-a")

        assertTrue(tracker.isCurrent(token.generationId))
        assertTrue(tracker.isCurrent(token))
        assertEquals("session-a", tracker.activeOriginatingSessionId)
        assertTrue(tracker.canPersist(token.generationId, "session-a"))
        assertFalse(tracker.canPersist(token.generationId, "session-b"))
    }

    @Test
    fun newGenerationInvalidatesStaleCallbacks() {
        val tracker = GenerationLifecycleTracker()
        val first = tracker.begin("session-a")
        val second = tracker.begin("session-a")

        assertFalse(tracker.isCurrent(first.generationId))
        assertFalse(tracker.canApplyUi(first.generationId))
        assertFalse(tracker.canPersist(first.generationId, "session-a"))
        assertTrue(tracker.isCurrent(second.generationId))
        assertTrue(tracker.canApplyUi(second.generationId))
        assertTrue(tracker.canPersist(second.generationId, "session-a"))
    }

    @Test
    fun staleCompletionCannotPersistToAnotherSelectedSession() {
        val tracker = GenerationLifecycleTracker()
        val tokenA = tracker.begin("session-a")
        // Simulate a newer generation taking ownership (e.g. active generation slot moved).
        tracker.begin("session-b")

        assertFalse(tracker.canPersist(tokenA.generationId, "session-a"))
        assertFalse(tracker.canPersist(tokenA.generationId, "session-b"))
        assertFalse(tracker.canApplyUi(tokenA.generationId))
    }

    @Test
    fun invalidateActiveGenerationBlocksStaleUiAndPersistence() {
        val tracker = GenerationLifecycleTracker()
        val token = tracker.begin("session-a")
        tracker.invalidateActiveGeneration()

        assertFalse(tracker.isCurrent(token.generationId))
        assertFalse(tracker.canApplyUi(token.generationId))
        assertFalse(tracker.canPersist(token.generationId, "session-a"))
        assertEquals(0L, tracker.activeGenerationId)
        assertNull(tracker.activeOriginatingSessionId)
    }

    @Test
    fun sessionDeletionIsBlockedOnlyForOriginatingActiveSession() {
        val tracker = GenerationLifecycleTracker()
        tracker.begin("session-a")

        assertTrue(tracker.isSessionMutationBlockedDuringGeneration("session-a", isGenerating = true))
        assertFalse(tracker.isSessionMutationBlockedDuringGeneration("session-b", isGenerating = true))
        assertFalse(tracker.isSessionMutationBlockedDuringGeneration("session-a", isGenerating = false))
    }

    @Test
    fun canPersistRequiresMatchingOriginatingSession() {
        val tracker = GenerationLifecycleTracker()
        val token = tracker.begin("session-a")

        assertTrue(tracker.canPersist(token.generationId, "session-a"))
        assertFalse(tracker.canPersist(token.generationId, "session-b"))
        assertFalse(tracker.canPersist(9999L, "session-a"))
    }
}
