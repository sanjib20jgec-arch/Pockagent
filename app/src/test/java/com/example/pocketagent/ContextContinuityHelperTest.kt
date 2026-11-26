package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextContinuityHelperTest {

    // --------------------------------------------------------- shouldSuggestContinue

    @Test
    fun shouldSuggestContinue_respectsDefaultAndCustomThresholds() {
        assertFalse(ContextContinuityHelper.shouldSuggestContinue(usedTokens = 2000, maxTokens = 4096))
        assertFalse(ContextContinuityHelper.shouldSuggestContinue(usedTokens = 3071, maxTokens = 4096))
        assertTrue(ContextContinuityHelper.shouldSuggestContinue(usedTokens = 3072, maxTokens = 4096))
        assertTrue(ContextContinuityHelper.shouldSuggestContinue(usedTokens = 4096, maxTokens = 4096))

        assertTrue(
            ContextContinuityHelper.shouldSuggestContinue(
                usedTokens = 500,
                maxTokens = 1000,
                thresholdRatio = 0.5f,
            ),
        )
        assertFalse(
            ContextContinuityHelper.shouldSuggestContinue(
                usedTokens = 499,
                maxTokens = 1000,
                thresholdRatio = 0.5f,
            ),
        )
    }

    @Test
    fun shouldSuggestContinue_handlesInvalidAndBoundaryInputsSafely() {
        assertFalse(ContextContinuityHelper.shouldSuggestContinue(usedTokens = 0, maxTokens = 4096))
        assertFalse(ContextContinuityHelper.shouldSuggestContinue(usedTokens = -10, maxTokens = 4096))
        assertFalse(ContextContinuityHelper.shouldSuggestContinue(usedTokens = 100, maxTokens = 0))
        assertFalse(ContextContinuityHelper.shouldSuggestContinue(usedTokens = 100, maxTokens = -512))
        assertFalse(
            ContextContinuityHelper.shouldSuggestContinue(
                usedTokens = 3500,
                maxTokens = 4096,
                thresholdRatio = Float.NaN,
            ),
        )
    }

    // --------------------------------------------------------- formatContextAlert

    @Test
    fun formatContextAlert_formatsExpectedPercentageAndClampsBounds() {
        assertEquals(
            "Context 78% full · Summarize & continue in a fresh chat",
            ContextContinuityHelper.formatContextAlert(usedTokens = 3195, maxTokens = 4096),
        )
        assertEquals(
            "Context 100% full · Summarize & continue in a fresh chat",
            ContextContinuityHelper.formatContextAlert(usedTokens = 5500, maxTokens = 4096),
        )
        assertEquals(
            "Context 0% full · Summarize & continue in a fresh chat",
            ContextContinuityHelper.formatContextAlert(usedTokens = 0, maxTokens = 4096),
        )
        assertEquals(
            "Context 0% full · Summarize & continue in a fresh chat",
            ContextContinuityHelper.formatContextAlert(usedTokens = 100, maxTokens = 0),
        )
    }

    // --------------------------------------------------------- buildCarryOverSeed

    @Test
    fun buildCarryOverSeed_returnsEmptyForNullEmptySystemOnlyOrZeroBudget() {
        assertEquals("", ContextContinuityHelper.buildCarryOverSeed(null))

        val emptySession = ConversationSession(title = "Empty", messages = emptyList())
        assertEquals("", ContextContinuityHelper.buildCarryOverSeed(emptySession))

        val systemOnly = ConversationSession(
            title = "System",
            messages = listOf(ChatMessage.system("Internal system note")),
        )
        assertEquals("", ContextContinuityHelper.buildCarryOverSeed(systemOnly))

        val nonEmpty = ConversationSession(
            title = "Chat",
            messages = listOf(ChatMessage.user("Hello")),
        )
        assertEquals("", ContextContinuityHelper.buildCarryOverSeed(nonEmpty, maxChars = 0))
        assertEquals("", ContextContinuityHelper.buildCarryOverSeed(nonEmpty, maxChars = -50))
    }

    @Test
    fun buildCarryOverSeed_preservesChronologicalTailAndSkipsSystemMessages() {
        val session = ConversationSession(
            title = "  Trip   Planning  ",
            messages = listOf(
                ChatMessage.system("System prompt should be skipped"),
                ChatMessage.user("Turn 1 question"),
                ChatMessage.assistant("Turn 1 answer"),
                ChatMessage.user("Turn 2 question"),
                ChatMessage.assistant("Turn 2 answer"),
                ChatMessage.user("Turn 3 question"),
                ChatMessage.assistant("Turn 3 answer"),
                ChatMessage.user("Turn 4\nquestion   with spaces"),
                ChatMessage.assistant("Turn 4 answer"),
            ),
        )

        val seed = ContextContinuityHelper.buildCarryOverSeed(session, maxChars = 1200)

        assertTrue(seed.startsWith("Continuing from previous chat (\"Trip Planning\"):"))
        assertFalse(seed.contains("System prompt should be skipped"))
        // Only the last 6 turns (Turns 2, 3, 4) are kept.
        assertFalse(seed.contains("Turn 1 question"))
        assertTrue(seed.contains("- User: Turn 2 question"))
        assertTrue(seed.contains("- Assistant: Turn 4 answer"))
        assertTrue(seed.contains("- User: Turn 4 question with spaces"))
        assertTrue(seed.indexOf("Turn 2 question") < seed.indexOf("Turn 4 answer"))
        assertTrue(seed.length <= 1200)
        // Deterministic across repeated calls
        assertEquals(seed, ContextContinuityHelper.buildCarryOverSeed(session, maxChars = 1200))
    }

    @Test
    fun buildCarryOverSeed_strictlyRespectsTightMaxCharsAndIncludesAttachmentFallback() {
        val longReply = "Detailed explanation ".repeat(40)
        val session = ConversationSession(
            title = "Budget Test",
            messages = listOf(
                ChatMessage.user("", attachmentSummary = "receipt_2026.pdf"),
                ChatMessage.assistant(longReply),
            ),
        )

        val fullSeed = ContextContinuityHelper.buildCarryOverSeed(session, maxChars = 1200)
        assertTrue(fullSeed.contains("[Attachment: receipt_2026.pdf]"))
        assertTrue(fullSeed.length <= 1200)

        val tightSeed = ContextContinuityHelper.buildCarryOverSeed(session, maxChars = 140)
        assertTrue(tightSeed.isNotEmpty())
        assertTrue(tightSeed.length <= 140)
    }

    // --------------------------------------------------------- formatBackendDiagnostic

    @Test
    fun formatBackendDiagnostic_reportsGpuActiveState() {
        val diagText = ContextContinuityHelper.formatBackendDiagnostic(
            activeBackend = ActiveBackend.GPU,
            preference = BackendPreference.GPU_PREFERRED,
            lastFallbackReason = null,
            visionEnabled = false,
        )
        assertFalse(diagText.isCpuFallback)
        assertEquals("Running on GPU", diagText.headline)
        assertTrue(diagText.tip.contains("GPU"))

        val diagVision = ContextContinuityHelper.formatBackendDiagnostic(
            activeBackend = ActiveBackend.GPU,
            preference = BackendPreference.GPU_PREFERRED,
            lastFallbackReason = null,
            visionEnabled = true,
        )
        assertFalse(diagVision.isCpuFallback)
        assertTrue(diagVision.tip.contains("image"))
    }

    @Test
    fun formatBackendDiagnostic_distinguishesCpuFallbackFromCpuOnlyPreference() {
        val fallback = ContextContinuityHelper.formatBackendDiagnostic(
            activeBackend = ActiveBackend.CPU,
            preference = BackendPreference.GPU_PREFERRED,
            lastFallbackReason = "OpenCL driver unavailable",
            visionEnabled = false,
        )
        assertTrue(fallback.isCpuFallback)
        assertEquals("Running on CPU (GPU fallback)", fallback.headline)
        assertTrue(fallback.tip.contains("OpenCL driver unavailable"))
        assertTrue(fallback.tip.contains("CPU"))

        val fallbackVision = ContextContinuityHelper.formatBackendDiagnostic(
            activeBackend = ActiveBackend.CPU,
            preference = BackendPreference.GPU_PREFERRED,
            lastFallbackReason = null,
            visionEnabled = true,
        )
        assertTrue(fallbackVision.isCpuFallback)
        assertTrue(fallbackVision.tip.contains("Vision"))

        val cpuOnly = ContextContinuityHelper.formatBackendDiagnostic(
            activeBackend = ActiveBackend.CPU,
            preference = BackendPreference.CPU_ONLY,
            lastFallbackReason = null,
            visionEnabled = false,
        )
        assertFalse(cpuOnly.isCpuFallback)
        assertEquals("Running on CPU", cpuOnly.headline)
        assertTrue(cpuOnly.tip.contains("GPU Preferred"))
    }

    @Test
    fun formatBackendDiagnostic_handlesStandbyState() {
        val standbyGpu = ContextContinuityHelper.formatBackendDiagnostic(
            activeBackend = null,
            preference = BackendPreference.GPU_PREFERRED,
            lastFallbackReason = null,
            visionEnabled = false,
        )
        assertFalse(standbyGpu.isCpuFallback)
        assertEquals("Engine on standby", standbyGpu.headline)
        assertTrue(standbyGpu.tip.contains("GPU"))

        val standbyWithReason = ContextContinuityHelper.formatBackendDiagnostic(
            activeBackend = null,
            preference = BackendPreference.GPU_PREFERRED,
            lastFallbackReason = "delegate init failed",
            visionEnabled = false,
        )
        assertFalse(standbyWithReason.isCpuFallback)
        assertTrue(standbyWithReason.tip.contains("delegate init failed"))
    }
}
