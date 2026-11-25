package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainUiStateTest {

    @Test
    fun defaultStateHasCorrectInitialValues() {
        val state = MainUiState()

        assertFalse(state.isModelReady)
        assertEquals("No model selected", state.modelText)
        assertEquals(0L, state.modelSizeBytes)
        assertFalse(state.isModelImporting)

        assertFalse(state.isWhisperModelReady)
        assertEquals("No local Whisper model selected.", state.whisperModelText)
        assertFalse(state.isWhisperTranscribing)

        assertTrue(state.attachedNames.isEmpty())
        assertEquals("", state.attachedContext)
        assertFalse(state.hasAttachment)
        assertEquals(0, state.attachmentCharCount)
        assertEquals(MainUiState.MAX_ATTACHMENT_CHARS, state.remainingAttachmentChars)
        assertFalse(state.isAttachmentLimitReached)

        assertEquals("", state.taskInput)
        assertEquals(MainUiState.GenerationStatus.IDLE, state.generationStatus)
        assertEquals("", state.generatedAnswer)
        assertFalse(state.answerIsComplete)
        assertFalse(state.isOutputVisible)

        assertFalse(state.canSuggest)
        assertFalse(state.canTranscribeWav)
        assertFalse(state.canSaveAnswer)
        assertNull(state.pendingAnswerToSave)
    }

    @Test
    fun respectsMaximumAttachmentLimit() {
        assertEquals(6_000, MainUiState.MAX_ATTACHMENT_CHARS)

        val oversizedText = "A".repeat(MainUiState.MAX_ATTACHMENT_CHARS + 3_000)
        val state = MainUiState().withAttachmentsUpdated(
            newContext = oversizedText,
            newNames = listOf("large.txt"),
            status = "Attached",
        )

        assertEquals(MainUiState.MAX_ATTACHMENT_CHARS, state.attachedContext.length)
        assertTrue(state.isAttachmentLimitReached)
        assertEquals(0, state.remainingAttachmentChars)
        assertTrue(state.hasAttachment)
    }

    @Test
    fun computesRemainingAttachmentCharactersAccurately() {
        val state = MainUiState().copy(attachedContext = "12345")

        assertEquals(5, state.attachmentCharCount)
        assertEquals(MainUiState.MAX_ATTACHMENT_CHARS - 5, state.remainingAttachmentChars)
        assertFalse(state.isAttachmentLimitReached)
    }

    @Test
    fun evaluatesCanSuggestAccordingToStrictPreconditions() {
        var state = MainUiState()

        // 1. Initial state: model not ready -> cannot suggest
        assertFalse(state.canSuggest)

        // 2. Model ready, but task input & attachment empty -> cannot suggest
        state = state.withModelReady(1024L * 1024L)
        assertTrue(state.isModelReady)
        assertFalse(state.canSuggest)

        // 3. Task input provided -> can suggest
        state = state.withTaskInput("Explain wifi settings")
        assertTrue(state.canSuggest)

        // 4. While generating (thinking) -> cannot suggest
        val thinkingState = state.withThinking()
        assertFalse(thinkingState.canSuggest)

        // 5. While transcribing audio -> cannot suggest
        val transcribingState = state.withTranscribingWav()
        assertFalse(transcribingState.canSuggest)

        // 6. While transcript review is pending -> cannot suggest until confirmed
        val reviewPendingState = state.copy(transcriptReviewPending = true)
        assertFalse(reviewPendingState.canSuggest)

        // 7. Once transcript is confirmed -> can suggest again
        val confirmedState = reviewPendingState.withTranscriptConfirmed()
        assertTrue(confirmedState.canSuggest)

        // 8. If task input is blank but attachment is present -> can suggest
        val attachmentOnlyState = MainUiState()
            .withModelReady(500L)
            .withAttachmentsUpdated("Device log text", listOf("log.txt"), "Attached")
        assertTrue(attachmentOnlyState.canSuggest)
    }

    @Test
    fun evaluatesCanTranscribeWavProperly() {
        var state = MainUiState()
        assertFalse(state.canTranscribeWav)

        // Whisper model ready -> can transcribe
        state = state.withWhisperReady("ggml-tiny.bin", 75L * 1024 * 1024)
        assertTrue(state.canTranscribeWav)

        // While transcribing -> cannot start another transcription
        val transcribingState = state.withTranscribingWav()
        assertFalse(transcribingState.canTranscribeWav)

        // While generating LLM steps -> cannot transcribe audio simultaneously
        val generatingState = state.withThinking()
        assertFalse(generatingState.canTranscribeWav)
    }

    @Test
    fun evaluatesCanSaveAnswerOnlyForCompleteBoundedAnswers() {
        val state = MainUiState()
        assertFalse(state.canSaveAnswer)

        // Incomplete / streaming answer cannot be saved
        val streamingState = state.withStreamChunk("Step 1...", "Step 1...", "Step 1...", true)
        assertFalse(streamingState.canSaveAnswer)

        // Completed valid answer can be saved
        val completedState = state.withGenerationCompleted(
            rawText = "1. First step\n2. Second step",
            formattedText = "1. First step\n2. Second step",
            plainText = "1. First step\n2. Second step",
        )
        assertTrue(completedState.canSaveAnswer)

        // Completed empty/blank answer cannot be saved
        val emptyCompleted = state.withGenerationCompleted("", "", "")
        assertFalse(emptyCompleted.canSaveAnswer)

        // Answer exceeding bounds cannot be saved
        val oversizedAnswer = "A".repeat(AnswerTextWriter.MAX_CHARS + 10)
        val oversizedState = completedState.copy(generatedAnswer = oversizedAnswer)
        assertFalse(oversizedState.canSaveAnswer)
    }

    @Test
    fun managesTranscriptReviewConfirmationVisibility() {
        // Short transcript (<= 400 chars): button visible immediately
        val shortTranscript = MainUiState().withTranscriptReady(
            displayName = "audio.wav",
            updatedContext = "Short transcript",
            newNames = listOf("audio.wav (local transcript)"),
        )
        assertTrue(shortTranscript.showConfirmTranscriptButton)

        // Long transcript (> 400 chars) requires expanding preview first
        val longTranscript = MainUiState().withTranscriptReady(
            displayName = "long_audio.wav",
            updatedContext = "x".repeat(500),
            newNames = listOf("long_audio.wav (local transcript)"),
        )
        assertFalse(longTranscript.showConfirmTranscriptButton)

        // Once expanded, confirmation button becomes visible
        val expandedState = longTranscript.withAttachmentPreviewToggled()
        assertTrue(expandedState.attachmentPreviewExpanded)
        assertTrue(expandedState.showConfirmTranscriptButton)

        // Once confirmed, confirmation button hides
        val confirmedState = expandedState.withTranscriptConfirmed()
        assertFalse(confirmedState.transcriptReviewPending)
        assertFalse(confirmedState.showConfirmTranscriptButton)
    }

    @Test
    fun clearsAttachmentsAndIncrementsGeneration() {
        val initial = MainUiState()
            .withAttachmentsUpdated("Some notes", listOf("notes.txt"), "Attached")
            .copy(attachmentGeneration = 3L)

        val cleared = initial.withAttachmentsCleared()

        assertEquals("", cleared.attachedContext)
        assertTrue(cleared.attachedNames.isEmpty())
        assertFalse(cleared.hasAttachment)
        assertEquals(4L, cleared.attachmentGeneration)
        assertEquals("Attachment removed.", cleared.statusMessage)
    }

    @Test
    fun verifiesSafetyInvariants() {
        // A real answer needs room: 256 tokens truncated factual answers mid-sentence.
        assertEquals(2048, MainUiState.MAX_RESPONSE_TOKENS)
        assertNotNull(MainUiState.SYSTEM_INSTRUCTION)
        assertTrue(MainUiState.SYSTEM_INSTRUCTION.contains("cannot see or control their device"))
        assertTrue(MainUiState.SYSTEM_INSTRUCTION.contains("untrusted content"))
        assertTrue(MainUiState.SYSTEM_INSTRUCTION.contains("Do not ask for passwords"))
        // The instruction must demand an answer, not a generic "give me 3-5 steps" reply.
        assertTrue(MainUiState.SYSTEM_INSTRUCTION.contains("answer the user's request yourself"))
        assertFalse(MainUiState.SYSTEM_INSTRUCTION.contains("usually 3–5 steps"))
        assertEquals(MainUiState.SYSTEM_INSTRUCTION, AnswerDirective.SYSTEM_INSTRUCTION)
    }
}
