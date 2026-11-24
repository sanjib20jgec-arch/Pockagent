package com.example.pocketagent

/**
 * Immutable snapshot of the UI and inference state for PocketAgent.
 *
 * Invariants:
 * - 100% offline, zero cloud, zero telemetry.
 * - Bounded inputs: attachment character limit of 12,000 characters.
 * - Safe rollback: previous model retained on failure.
 * - Manual-guidance only: no automated phone/screen control.
 */
data class MainUiState(
    // LiteRT-LM Model State
    val isModelReady: Boolean = false,
    val modelText: String = "No model selected",
    val modelSizeBytes: Long = 0L,
    val isModelImporting: Boolean = false,

    // Whisper Model State
    val isWhisperModelReady: Boolean = false,
    val whisperModelText: String = "No local Whisper model selected.",
    val whisperModelSizeBytes: Long = 0L,
    val isWhisperLoading: Boolean = false,
    val isWhisperTranscribing: Boolean = false,

    // Attachments
    val attachedNames: List<String> = emptyList(),
    val attachedContext: String = "",
    val attachmentPreviewExpanded: Boolean = false,
    val transcriptReviewPending: Boolean = false,
    val attachmentGeneration: Long = 0L,

    // Task & Generation
    val taskInput: String = "",
    val generationStatus: GenerationStatus = GenerationStatus.IDLE,
    val generatedAnswer: String = "",
    val formattedAnswer: String = "",
    val plainTextAnswer: String = "",
    val answerIsComplete: Boolean = false,
    val isOutputVisible: Boolean = false,

    // General Status & TTS
    val statusMessage: String = "Choose a compatible .litertlm model to get started.",
    val isTtsReady: Boolean = false,
    val pendingAnswerToSave: String? = null,
    val errorMessage: String? = null,

    // Multi-Session & History State
    val currentSessionId: String? = null,
    val currentSessionTitle: String = "New Conversation",
    val isSessionPinned: Boolean = false,
    val sessionSummaries: List<SessionSummary> = emptyList(),
    val sessionSearchQuery: String = "",
    val lastUserPrompt: String = "",
    val canRegenerate: Boolean = false,
    val documentCitations: List<LocalDocumentRetriever.DocumentCitation> = emptyList(),
    val userMemories: List<UserMemoryStore.UserMemory> = emptyList(),
    val isMemoryEnabled: Boolean = true,
) {
    companion object {
        const val MAX_ATTACHMENT_CHARS = 12_000
        const val MAX_RESPONSE_TOKENS = 1024
        /** Intent-aware instruction (answers the request instead of returning steps to the user). */
        val SYSTEM_INSTRUCTION: String get() = AnswerDirective.SYSTEM_INSTRUCTION

        fun formatBytes(bytes: Long): String =
            if (bytes >= 1024L * 1024) "${bytes / (1024 * 1024)} MB" else "${bytes / 1024} KB"
    }

    enum class GenerationStatus {
        IDLE,
        THINKING,
        STREAMING,
        COMPLETED,
        ERROR,
    }

    // Attachment Helpers
    val hasAttachment: Boolean
        get() = attachedContext.isNotEmpty()

    val attachmentCharCount: Int
        get() = attachedContext.length

    val remainingAttachmentChars: Int
        get() = maxOf(0, MAX_ATTACHMENT_CHARS - attachedContext.length)

    val isAttachmentLimitReached: Boolean
        get() = attachedContext.length >= MAX_ATTACHMENT_CHARS

    val showAttachmentPreviewToggle: Boolean
        get() = hasAttachment && attachedContext.length > 400

    val showConfirmTranscriptButton: Boolean
        get() = transcriptReviewPending && !isWhisperTranscribing &&
                (attachmentPreviewExpanded || attachedContext.length <= 400)

    val isGenerating: Boolean
        get() = generationStatus == GenerationStatus.THINKING || generationStatus == GenerationStatus.STREAMING

    // Action Enablement
    val canSuggest: Boolean
        get() = isModelReady &&
                !isGenerating &&
                !isWhisperTranscribing &&
                !transcriptReviewPending &&
                (taskInput.isNotBlank() || attachedContext.isNotBlank())

    val canTranscribeWav: Boolean
        get() = isWhisperModelReady &&
                !isWhisperTranscribing &&
                !isGenerating

    val canSaveAnswer: Boolean
        get() = answerIsComplete &&
                generatedAnswer.isNotBlank() &&
                generatedAnswer.length <= AnswerTextWriter.MAX_CHARS

    val attachmentSummaryText: String
        get() = if (attachedNames.isEmpty()) {
            ""
        } else {
            attachedNames.joinToString(" · ") +
                    "\nAttached content limited to 12,000 characters."
        }

    // State Transition Reducers
    fun withTaskInput(newInput: String): MainUiState =
        copy(taskInput = newInput)

    fun withStatus(message: String): MainUiState =
        copy(statusMessage = message)

    fun withModelReady(sizeBytes: Long): MainUiState = copy(
        isModelReady = true,
        isModelImporting = false,
        modelSizeBytes = sizeBytes,
        modelText = "MODEL READY  ·  ${formatBytes(sizeBytes)}",
        statusMessage = "Model ready. Ask anything, dictate, or attach text/PDF (selectable text only).",
        errorMessage = null,
    )

    fun withModelImporting(): MainUiState = copy(
        isModelImporting = true,
        statusMessage = "Importing model into app storage…",
        errorMessage = null,
    )

    fun withModelImportFailed(error: String): MainUiState = copy(
        isModelImporting = false,
        statusMessage = "Model import failed: $error",
        errorMessage = error,
    )

    fun withWhisperReady(displayName: String, sizeBytes: Long): MainUiState = copy(
        isWhisperModelReady = true,
        isWhisperLoading = false,
        whisperModelSizeBytes = sizeBytes,
        whisperModelText = "Whisper model ready: ${displayName.take(80)} (stored locally).",
        statusMessage = "Whisper model loaded. Choose a bounded PCM WAV file to transcribe locally.",
        errorMessage = null,
    )

    fun withWhisperModelRemoved(): MainUiState = copy(
        isWhisperModelReady = false,
        isWhisperLoading = false,
        whisperModelSizeBytes = 0L,
        whisperModelText = "No local Whisper model selected.",
        statusMessage = "Local Whisper model removed.",
        errorMessage = null,
    )

    fun withWhisperLoading(message: String = "Staging and loading the selected local Whisper model…"): MainUiState = copy(
        isWhisperLoading = true,
        whisperModelText = message,
        statusMessage = "Loading a user-selected ggml model locally. Large models may take time and memory.",
        errorMessage = null,
    )

    fun withWhisperLoadFailed(error: String, restoredPrevious: Boolean): MainUiState = copy(
        isWhisperLoading = false,
        isWhisperModelReady = restoredPrevious,
        whisperModelText = if (restoredPrevious) "Previous local Whisper model restored."
        else "No local Whisper model loaded. Choose a valid ggml .bin file (max 512 MB).",
        statusMessage = "Could not load Whisper model: $error",
        errorMessage = error,
    )

    fun withTranscribingWav(): MainUiState = copy(
        isWhisperTranscribing = true,
        statusMessage = "Staging and checking the WAV file locally…",
        errorMessage = null,
    )

    fun withTranscriptReady(
        displayName: String,
        updatedContext: String,
        newNames: List<String>,
    ): MainUiState = copy(
        isWhisperTranscribing = false,
        attachedContext = updatedContext.take(MAX_ATTACHMENT_CHARS),
        attachedNames = newNames,
        transcriptReviewPending = true,
        attachmentPreviewExpanded = false,
        statusMessage = "Transcript ready. Expand and review it, then tap Use reviewed transcript.",
        errorMessage = null,
    )

    fun withTranscriptConfirmed(): MainUiState = copy(
        transcriptReviewPending = false,
        statusMessage = "Transcript confirmed. Ask your question when ready.",
    )

    fun withAttachmentsUpdated(
        newContext: String,
        newNames: List<String>,
        status: String,
    ): MainUiState = copy(
        attachedContext = newContext.take(MAX_ATTACHMENT_CHARS),
        attachedNames = newNames,
        attachmentPreviewExpanded = false,
        transcriptReviewPending = false,
        statusMessage = status,
        errorMessage = null,
    )

    fun withAttachmentsCleared(): MainUiState = copy(
        attachedContext = "",
        attachedNames = emptyList(),
        attachmentPreviewExpanded = false,
        transcriptReviewPending = false,
        attachmentGeneration = attachmentGeneration + 1,
        statusMessage = "Attachment removed.",
    )

    fun withAttachmentPreviewToggled(): MainUiState = copy(
        attachmentPreviewExpanded = !attachmentPreviewExpanded,
    )

    fun withThinking(): MainUiState = copy(
        generationStatus = GenerationStatus.THINKING,
        isOutputVisible = true,
        answerIsComplete = false,
        generatedAnswer = "Working on it…",
        formattedAnswer = "Working on it…",
        plainTextAnswer = "Working on it…",
        statusMessage = "Answering on this device…",
        errorMessage = null,
    )

    fun withStreamChunk(accumulatedRawText: String, formattedText: String, plainText: String, isFirstChunk: Boolean): MainUiState = copy(
        generationStatus = GenerationStatus.STREAMING,
        isOutputVisible = true,
        generatedAnswer = accumulatedRawText,
        formattedAnswer = formattedText,
        plainTextAnswer = plainText,
        statusMessage = if (isFirstChunk) "Response streaming locally…" else statusMessage,
    )

    fun withGenerationCompleted(rawText: String, formattedText: String, plainText: String): MainUiState {
        val complete = rawText.isNotBlank()
        return copy(
            generationStatus = GenerationStatus.COMPLETED,
            isOutputVisible = true,
            answerIsComplete = complete,
            generatedAnswer = if (complete) rawText else "No answer was returned. Try rephrasing the request.",
            formattedAnswer = if (complete) formattedText else "No answer was returned. Try rephrasing the request.",
            plainTextAnswer = if (complete) plainText else "No answer was returned. Try rephrasing the request.",
            statusMessage = if (complete) "Answer ready."
            else "No answer was returned. Try rephrasing the request.",
            errorMessage = null,
        )
    }

    fun withGenerationError(error: String): MainUiState = copy(
        generationStatus = GenerationStatus.ERROR,
        isOutputVisible = false,
        answerIsComplete = false,
        generatedAnswer = "",
        formattedAnswer = "",
        plainTextAnswer = "",
        statusMessage = "Could not generate an answer: $error",
        errorMessage = error,
    )

    fun withOutputCleared(): MainUiState = copy(
        isOutputVisible = false,
        answerIsComplete = false,
        generatedAnswer = "",
        formattedAnswer = "",
        plainTextAnswer = "",
        generationStatus = GenerationStatus.IDLE,
        documentCitations = emptyList(),
    )

    fun withPendingAnswerToSave(answer: String?): MainUiState = copy(
        pendingAnswerToSave = answer,
    )

    fun withTtsReady(ready: Boolean): MainUiState = copy(
        isTtsReady = ready,
    )

    fun withSessions(
        summaries: List<SessionSummary>,
        activeId: String? = currentSessionId,
        activeTitle: String = currentSessionTitle,
        isPinned: Boolean = isSessionPinned,
    ): MainUiState = copy(
        sessionSummaries = summaries,
        currentSessionId = activeId,
        currentSessionTitle = activeTitle,
        isSessionPinned = isPinned,
    )

    fun withSessionSearch(query: String): MainUiState = copy(
        sessionSearchQuery = query,
    )

    fun withLastUserPrompt(prompt: String): MainUiState = copy(
        lastUserPrompt = prompt,
        canRegenerate = prompt.isNotBlank(),
    )

    fun withGenerationStopped(rawText: String, formattedText: String, plainText: String): MainUiState = copy(
        generationStatus = GenerationStatus.COMPLETED,
        isOutputVisible = true,
        answerIsComplete = rawText.isNotBlank(),
        generatedAnswer = rawText,
        formattedAnswer = formattedText,
        plainTextAnswer = plainText,
        statusMessage = "Generation stopped by user.",
        errorMessage = null,
        canRegenerate = lastUserPrompt.isNotBlank(),
    )

    fun withDocumentCitations(citations: List<LocalDocumentRetriever.DocumentCitation>): MainUiState = copy(
        documentCitations = citations,
    )

    fun withUserMemories(memories: List<UserMemoryStore.UserMemory>): MainUiState = copy(
        userMemories = memories,
    )

    fun withMemoryEnabled(enabled: Boolean): MainUiState = copy(
        isMemoryEnabled = enabled,
    )
}
