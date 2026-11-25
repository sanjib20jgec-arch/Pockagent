package com.example.pocketagent

import android.content.Context
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Interface decoupling LiteRT-LM inference execution for pure unit testability.
 */
interface LlmInferenceRunner : Closeable {
    fun initialize()
    fun isAvailable(): Boolean
    fun runInference(
        prompt: String,
        systemInstruction: String,
        maxTokens: Int,
        onChunk: (String) -> Unit,
    ): String
}

/**
 * Production implementation of LlmInferenceRunner using Google's LiteRT-LM.
 */
class DefaultLlmInferenceRunner(
    private val filesDir: File,
    private val cacheDir: File,
    /** Shared engine manager, so voice mode reuses the one GPU-capable engine instead of a
     *  second CPU-only copy of the same weights in RAM. */
    private val engineManager: InferenceEngineManager<Engine> = InferenceEngineManager(),
    /** Upper bound for one turn's streaming, so a stalled engine cannot wedge the caller. */
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : LlmInferenceRunner {

    private companion object {
        /**
         * Hard ceiling for a single voice turn. Short enough that a wedged engine fails visibly
         * instead of hanging the worker thread, long enough for larger 2B INT8 models on CPU.
         */
        const val DEFAULT_TIMEOUT_SECONDS = 180L
        const val WARM_CONTEXT_RESET_THRESHOLD = 2800
    }

    @Volatile private var isClosed = false

    override fun initialize() {
        val modelFile = modelFile()
        if (modelFile.exists() && modelFile.length() > 0) {
            // GPU-first with automatic CPU fallback, handled inside the manager.
            try {
                engineManager.getOrInitializeEngine(modelFile, cacheDir)
            } catch (_: Exception) {
                // Availability is reported separately; a failed warm-up is not fatal.
            }
        }
    }

    override fun isAvailable(): Boolean = modelFile().exists()

    override fun runInference(
        prompt: String,
        systemInstruction: String,
        maxTokens: Int,
        onChunk: (String) -> Unit,
    ): String {
        val modelFile = modelFile()
        require(modelFile.exists()) { "Choose a compatible .litertlm model first." }
        check(!isClosed) { "Inference runner is closed." }

        return try {
            runInferencePass(modelFile, prompt, systemInstruction, maxTokens, onChunk)
        } catch (gpuFailure: Throwable) {
            if (!isClosed && engineManager.activeBackend == ActiveBackend.GPU) {
                val reason = gpuFailure.message ?: gpuFailure.javaClass.simpleName
                if (engineManager.fallbackToCpuAfterRuntimeFailure(reason, modelFile, cacheDir)) {
                    return runInferencePass(modelFile, prompt, systemInstruction, maxTokens, onChunk)
                }
            }
            throw gpuFailure
        }
    }

    private fun runInferencePass(
        modelFile: File,
        prompt: String,
        systemInstruction: String,
        maxTokens: Int,
        onChunk: (String) -> Unit,
    ): String {
        val generated = StringBuilder()
        val streamFinished = CountDownLatch(1)
        val streamError = AtomicReference<Throwable?>(null)

        val manager = engineManager
        manager.getOrInitializeEngine(modelFile, cacheDir)
        val key = manager.conversationKey(
            sessionId = "voice",
            modelPath = modelFile.absolutePath,
            systemInstruction = systemInstruction,
            attachmentHash = 0,
        )
        if (manager.isConversationWarm(key) &&
            manager.cachedConversationTokens + ChatMessage.estimateTextTokens(prompt) + maxTokens > WARM_CONTEXT_RESET_THRESHOLD
        ) {
            manager.invalidateConversation("voice context budget reached")
        }
        val conversation = manager.getOrCreateConversation(key, systemInstruction)

        manager.beginGeneration()
        try {
            conversation.sendMessage(
                prompt,
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val chunk = message.toString()
                        synchronized(generated) { generated.append(chunk) }
                        onChunk(chunk)
                    }

                    override fun onDone() {
                        streamFinished.countDown()
                    }

                    override fun onError(throwable: Throwable) {
                        streamError.set(throwable)
                        streamFinished.countDown()
                    }
                },
                maxOutputTokens = maxTokens,
            )

            // Bounded: never block a worker thread forever on a stalled engine.
            val finished = streamFinished.await(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                conversation.cancel()
                throw IllegalStateException(
                    "Voice inference timed out after ${timeoutSeconds}s. Try a shorter request."
                )
            }
            streamError.get()?.let { throw IllegalStateException("Streaming inference failed.", it) }
        } catch (e: Exception) {
            // Any failure leaves the cached conversation in an unknown state; drop it.
            conversation.cancel()
            manager.invalidateConversation(e.message ?: "voice inference failed")
            throw e
        } finally {
            manager.endGeneration()
        }

        val finalText = synchronized(generated) { generated.toString() }
        manager.recordConversationTurnTokens(
            ChatMessage.estimateTextTokens(prompt) + ChatMessage.estimateTextTokens(finalText) + 8
        )
        return finalText
    }

    private fun modelFile(): File = File(filesDir, "model.litertlm")

    override fun close() {
        isClosed = true
        engineManager.close()
    }
}

/**
 * Interface decoupling native Whisper audio transcription for pure unit testability.
 */
interface WhisperTranscriber {
    fun loadModel(modelPath: String): Long
    fun transcribe(context: Long, samples: FloatArray, maxChars: Int): String
    fun release(context: Long)
}

/**
 * Production implementation of WhisperTranscriber using whisper.cpp JNI.
 */
class DefaultWhisperTranscriber : WhisperTranscriber {
    override fun loadModel(modelPath: String): Long =
        WhisperNative.loadModel(modelPath)

    override fun transcribe(context: Long, samples: FloatArray, maxChars: Int): String =
        WhisperNative.transcribe(context, samples, maxChars)

    override fun release(context: Long) {
        if (context != 0L) WhisperNative.release(context)
    }
}

/**
 * Main presentation and inference coordinator for PocketAgent.
 *
 * Encapsulates state transitions, attachment processing, local model lifecycle,
 * and LiteRT-LM / Whisper coordination.
 *
 * Invariants:
 * - 100% offline, zero network access, zero analytics.
 * - Answers the user's request directly, using numbered steps only for genuine procedures.
 * - Never controls other apps, inspects screen, or automates user actions.
 * - Enforces safety bounds (32,000 char attachment limits, model rollback).
 */
class MainViewModel internal constructor(
    private val filesDir: File,
    private val cacheDir: File,
    private val modelFileStore: ModelFileStore = ModelFileStore(filesDir),
    private val whisperModelStore: WhisperModelStore = WhisperModelStore(filesDir),
    private val sessionStore: SessionStore = SessionStore(SessionStore.defaultDir(filesDir)),
    private val userMemoryStore: UserMemoryStore = UserMemoryStore(UserMemoryStore.defaultDir(filesDir)),
    private val notesStore: PersonalNotesStore = PersonalNotesStore(PersonalNotesStore.defaultDir(filesDir)),
    private val llmRunner: LlmInferenceRunner = DefaultLlmInferenceRunner(filesDir, cacheDir),
    private val whisperTranscriber: WhisperTranscriber = DefaultWhisperTranscriber(),
    private val ttsSpeaker: TtsSpeaker = AndroidTtsSpeaker({ null }, { false }),
    private val workerExecutor: Executor = Executors.newSingleThreadExecutor(),
    private val mainDispatcher: (Runnable) -> Unit = { it.run() },
) : Closeable {

    constructor(filesDir: File, cacheDir: File) : this(
        filesDir = filesDir,
        cacheDir = cacheDir,
        modelFileStore = ModelFileStore(filesDir),
        whisperModelStore = WhisperModelStore(filesDir),
        sessionStore = SessionStore(SessionStore.defaultDir(filesDir)),
        userMemoryStore = UserMemoryStore(UserMemoryStore.defaultDir(filesDir)),
        notesStore = PersonalNotesStore(PersonalNotesStore.defaultDir(filesDir)),
    )

    private val promptContext = AnswerPromptComposer()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private companion object {
        /** One normal answer plus one corrective retry if the model refuses to answer. */
        const val MAX_ANSWER_ATTEMPTS = 2
    }

    private val observers = CopyOnWriteArrayList<(MainUiState) -> Unit>()
    @Volatile private var whisperContext: Long = 0L
    private val isGenerationCancelled = AtomicBoolean(false)
    @Volatile private var activeSession: ConversationSession? = null
    private val audioRecorder by lazy { InAppAudioRecorder() }
    val duplexVoiceController by lazy {
        DuplexVoiceController(
            audioRecorder = audioRecorder,
            whisperTranscriber = whisperTranscriber,
            llmRunner = llmRunner,
            ttsSpeaker = ttsSpeaker,
            workerExecutor = workerExecutor,
            mainDispatcher = mainDispatcher,
            instructionFor = { spokenPrompt, base -> instructionForSpokenTurn(spokenPrompt, base) },
        )
    }

    /**
     * Voice answers must follow the same rules as typed ones: classify the request, then use the
     * intent-aware directive (enriched with memory) so the assistant answers instead of pointing
     * the user at another app.
     */
    private fun instructionForSpokenTurn(spokenPrompt: String, base: String): String {
        val intent = AnswerDirective.classify(spokenPrompt)
        val directive = AnswerDirective.systemInstructionFor(intent)
        if (!uiState.value.isMemoryEnabled) return directive
        val memoryBlock = userMemoryStore.buildPromptContext(maxTokens = 120)
        if (memoryBlock.isBlank()) return directive
        return ContextBudgetManager().enrichSystemInstructionWithMemory(directive, memoryBlock)
    }

    /** Set by the runaway guard so the UI can explain why generation stopped. */
    @Volatile private var runawayFragment: String? = null

    /**
     * Subscribes a listener to UI state changes. Dispatches the current state immediately.
     * Returns an unsubscribe function.
     */
    fun addObserver(observer: (MainUiState) -> Unit): () -> Unit {
        observers.add(observer)
        observer(_uiState.value)
        return { observers.remove(observer) }
    }

    private fun updateState(transform: (MainUiState) -> MainUiState) {
        _uiState.update(transform)
        val newState = _uiState.value
        mainDispatcher(Runnable {
            observers.forEach { it(newState) }
        })
    }

    /**
     * Checks for already stored models on startup and restores ready states.
     */
    fun initModels() {
        refreshSessions()
        refreshMemories()
        refreshNotes()
        val model = modelFileStore.modelFile
        if (model.exists() && model.length() > 0) {
            updateState { it.copy(
                isModelReady = true,
                isModelImporting = false,
                modelSizeBytes = model.length(),
                modelText = "MODEL READY  ·  ${MainUiState.formatBytes(model.length())}",
                statusMessage = "Model ready. Ask anything, dictate, or attach text/PDF (selectable text only).",
                errorMessage = null,
            ) }
        }

        val whisperFile = whisperModelStore.modelFile
        if (whisperFile.isFile) {
            updateState { it.copy(
            isWhisperLoading = true,
            whisperModelText = "Loading saved local Whisper model…",
            statusMessage = "Loading a user-selected ggml model locally. Large models may take time and memory.",
            errorMessage = null,
        ) }
            workerExecutor.execute {
                try {
                    whisperModelStore.discardCandidate()
                    require(whisperFile.length() in 1..WhisperModelStore.MAX_MODEL_BYTES) {
                        "Saved model is empty or exceeds the 512 MB limit."
                    }
                    val loaded = whisperTranscriber.loadModel(whisperFile.absolutePath)
                    require(loaded != 0L) { "Saved ggml model could not be loaded." }
                    whisperContext = loaded
                    updateState {
                        it.copy(
                            isWhisperModelReady = true,
                            isWhisperLoading = false,
                            whisperModelSizeBytes = whisperFile.length(),
                            whisperModelText = "Whisper model ready: ${(whisperFile.name).take(80)} (stored locally).",
                            statusMessage = "Whisper model loaded. Choose a bounded PCM WAV file to transcribe locally.",
                            errorMessage = null,
                        )
                            .copy(statusMessage = "Local Whisper model ready. Choose a bounded PCM WAV file to transcribe.")
                    }
                } catch (e: Exception) {
                    whisperContext = 0L
                    updateState {
                        it.copy(
                            isWhisperLoading = false,
                            isWhisperModelReady = false,
                            whisperModelText = if (false) "Previous local Whisper model restored."
                            else "No local Whisper model loaded. Choose a valid ggml .bin file (max 512 MB).",
                            statusMessage = "Could not load Whisper model: ${e.message ?: "unsupported model"}",
                            errorMessage = e.message ?: "unsupported model",
                        )
                    }
                }
            }
        }
    }

    fun onTaskInputChanged(text: String) {
        updateState { it.copy(taskInput = text) }
    }

    /** Alias used when restoring a prompt from history or a regenerate request. */
    fun updateTaskInput(text: String) {
        onTaskInputChanged(text)
    }

    fun onTtsReady(ready: Boolean) {
        updateState { it.copy(isTtsReady = ready) }
    }

    fun importModel(displayName: String?, inputStreamProvider: () -> InputStream?) {
        if (displayName != null && !displayName.endsWith(".litertlm", ignoreCase = true)) {
            updateState { it.copy(statusMessage = "Choose a LiteRT-LM model file ending in .litertlm.") }
            return
        }

        updateState { it.copy(isModelImporting = true, statusMessage = "Staging selected model on this device…") }
        workerExecutor.execute {
            try {
                llmRunner.close()
                val target = inputStreamProvider()?.use { input ->
                    modelFileStore.importModel(input)
                } ?: error("Could not open the selected file.")

                updateState { it.copy(
                isModelReady = true,
                isModelImporting = false,
                modelSizeBytes = target.length(),
                modelText = "MODEL READY  ·  ${MainUiState.formatBytes(target.length())}",
                statusMessage = "Model ready. Ask anything, dictate, or attach text/PDF (selectable text only).",
                errorMessage = null,
            ) }
            } catch (e: Exception) {
                File(filesDir, "model.litertlm.part").delete()
                updateState { it.copy(
                isModelImporting = false,
                statusMessage = "Model import failed: ${e.message ?: "unknown error"}",
                errorMessage = e.message ?: "unknown error",
            ) }
            }
        }
    }

    fun importTextFiles(files: List<Pair<String, InputStream?>>) {
        updateState { it.copy(statusMessage = "Reading selected text file${if (files.size > 1) "s" else ""}…") }
        workerExecutor.execute {
            try {
                val content = StringBuilder()
                val names = mutableListOf<String>()
                for ((displayName, stream) in files) {
                    if (content.length >= MainUiState.MAX_ATTACHMENT_CHARS) break
                    val name = displayName.ifBlank { "Attached text" }
                    val remaining = MainUiState.MAX_ATTACHMENT_CHARS - content.length
                    val text = stream?.bufferedReader()?.use { reader ->
                        BoundedTextReader.read(reader, remaining)
                    } ?: error("Could not read $name")
                    names.add(name)
                    content.append("\n\n--- $name ---\n").append(text)
                }
                require(content.isNotBlank()) { "The selected files are empty." }
                val truncated = content.toString().take(MainUiState.MAX_ATTACHMENT_CHARS)
                updateState {
                    it.copy(
                        attachedContext = (newContext = truncated).take(MainUiState.MAX_ATTACHMENT_CHARS),
                        attachedNames = newNames = names,
                        attachmentPreviewExpanded = false,
                        transcriptReviewPending = false,
                        statusMessage = status = "Text attached. Review the preview, then add your question.",
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                updateState { it.copy(statusMessage = "Could not attach text: ${e.message ?: "unknown error"}") }
            }
        }
    }

    fun importPdf(displayName: String?, context: Context, inputStreamProvider: () -> InputStream?) {
        updateState { it.copy(statusMessage = "Reading selected PDF on this device…") }
        workerExecutor.execute {
            try {
                val current = _uiState.value.attachedContext
                val remaining = MainUiState.MAX_ATTACHMENT_CHARS - current.length
                require(remaining > 0) { "Remove the current attachment to make room for PDF text." }

                val name = (displayName ?: "Selected PDF")
                    .replace(Regex("[\\r\\n]+"), " ")
                    .take(120)
                val sourceLabel = "\n\n--- PDF: $name ---\n"
                require(remaining > sourceLabel.length) {
                    "Remove the current attachment to make room for PDF text."
                }

                val input = inputStreamProvider() ?: error("Could not open the selected PDF.")
                val extraction = PdfTextExtractor.extract(context, input, remaining - sourceLabel.length)
                val newContext = (current + sourceLabel + extraction.text).take(MainUiState.MAX_ATTACHMENT_CHARS)
                val newNames = _uiState.value.attachedNames + "$name (${extraction.pageCount} pages)"

                val status = if (extraction.limitReached) {
                    "PDF text attached with the ${"%,d".format(MainUiState.MAX_ATTACHMENT_CHARS)}-character limit applied. Review it, then ask your question."
                } else {
                    "PDF text attached. Review the document name and page count, then add a question."
                }

                updateState {
                    it.copy(
                        attachedContext = (newContext).take(MainUiState.MAX_ATTACHMENT_CHARS),
                        attachedNames = newNames,
                        attachmentPreviewExpanded = false,
                        transcriptReviewPending = false,
                        statusMessage = status,
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                updateState {
                    it.copy(statusMessage = "Could not attach PDF: ${e.message ?: "unsupported or unreadable PDF"}")
                }
            }
        }
    }

    fun importWhisperModel(rawName: String?, inputStreamProvider: () -> InputStream?) {
        val name = rawName ?: "Selected model"
        if (!name.endsWith(".bin", ignoreCase = true)) {
            updateState {
                it.copy(statusMessage = "Choose a whisper.cpp ggml model file ending in .bin. No model is bundled with PocketAgent.")
            }
            return
        }

        val displayName = name.replace(Regex("[\\r\\n]+"), " ").take(80)
        updateState { it.copy(
            isWhisperLoading = true,
            whisperModelText = "Staging and loading the selected local Whisper model…",
            statusMessage = "Loading a user-selected ggml model locally. Large models may take time and memory.",
            errorMessage = null,
        ) }

        workerExecutor.execute {
            var candidateContext = 0L
            var previousContext = whisperContext
            try {
                val input = inputStreamProvider() ?: error("Could not open the selected model file.")
                val candidate = whisperModelStore.stage(input)

                // Avoid holding two model contexts in memory at once
                if (previousContext != 0L) {
                    whisperTranscriber.release(previousContext)
                    whisperContext = 0L
                    previousContext = 0L
                }

                candidateContext = whisperTranscriber.loadModel(candidate.absolutePath)
                require(candidateContext != 0L) { "Could not load the selected Whisper model." }
                whisperModelStore.commitCandidate()
                whisperContext = candidateContext
                candidateContext = 0L

                val committedSize = whisperModelStore.modelFile.length()
                updateState {
                    it.copy(
                            isWhisperModelReady = true,
                            isWhisperLoading = false,
                            whisperModelSizeBytes = committedSize,
                            whisperModelText = "Whisper model ready: ${(displayName).take(80)} (stored locally).",
                            statusMessage = "Whisper model loaded. Choose a bounded PCM WAV file to transcribe locally.",
                            errorMessage = null,
                        )
                }
            } catch (e: Exception) {
                if (candidateContext != 0L) whisperTranscriber.release(candidateContext)
                whisperModelStore.discardCandidate()

                val oldModel = whisperModelStore.modelFile
                if (whisperContext == 0L && oldModel.isFile && oldModel.length() in 1..WhisperModelStore.MAX_MODEL_BYTES) {
                    try {
                        whisperContext = whisperTranscriber.loadModel(oldModel.absolutePath)
                    } catch (_: Exception) {
                        whisperContext = 0L
                    }
                }

                updateState {
                    it.copy(
                            isWhisperLoading = false,
                            isWhisperModelReady = false,
                            whisperModelText = if (false) "Previous local Whisper model restored."
                            else "No local Whisper model loaded. Choose a valid ggml .bin file (max 512 MB).",
                            statusMessage = "Could not load Whisper model: ${e.message ?: "unsupported model file"}",
                            errorMessage = e.message ?: "unsupported model file",
                        )
                }
            }
        }
    }

    fun removeWhisperModel() {
        updateState { it.copy(statusMessage = "Removing the local Whisper model…") }
        workerExecutor.execute {
            try {
                val context = whisperContext
                whisperContext = 0L
                if (context != 0L) whisperTranscriber.release(context)
                whisperModelStore.discardCandidate()
                if (whisperModelStore.modelFile.exists() && !whisperModelStore.modelFile.delete()) {
                    error("Could not remove the stored model file.")
                }
                updateState { it.copy(
                isWhisperModelReady = false,
                isWhisperLoading = false,
                whisperModelSizeBytes = 0L,
                whisperModelText = "No local Whisper model selected.",
                statusMessage = "Local Whisper model removed.",
                errorMessage = null,
            ) }
            } catch (e: Exception) {
                updateState { it.copy(statusMessage = "Could not remove Whisper model: ${e.message ?: "storage error"}") }
            }
        }
    }

    fun transcribeWav(rawName: String?, inputStreamProvider: () -> InputStream?) {
        if (whisperContext == 0L) {
            updateState { it.copy(statusMessage = "Choose a local whisper.cpp ggml .bin model first.") }
            return
        }

        val generationAtStart = _uiState.value.attachmentGeneration
        updateState { it.copy(
            isWhisperTranscribing = true,
            statusMessage = "Staging and checking the WAV file locally…",
            errorMessage = null,
        ) }

        workerExecutor.execute {
            var staged: File? = null
            try {
                val current = _uiState.value.attachedContext
                val availableChars = MainUiState.MAX_ATTACHMENT_CHARS - current.length
                require(availableChars > 160) { "Remove the current attachment to make room for a transcript." }

                val displayName = (rawName ?: "Selected WAV")
                    .replace(Regex("[\\r\\n]+"), " ")
                    .take(120)
                val sourceLabel = "\n\n--- Local audio transcript: $displayName ---\n"
                require(availableChars > sourceLabel.length) {
                    "Remove the current attachment to make room for a transcript."
                }

                val input = inputStreamProvider() ?: error("Could not open the selected WAV file.")
                staged = BoundedAudioFile.stage(input, cacheDir, PcmWavReader.MAX_FILE_BYTES)
                val samples = PcmWavReader.read(staged)
                val context = whisperContext
                require(context != 0L) { "Select a Whisper model before transcribing audio." }

                val textLimit = minOf(MainUiState.MAX_ATTACHMENT_CHARS, availableChars - sourceLabel.length)
                updateState { it.copy(statusMessage = "Transcribing locally. This may take a while on CPU…") }

                val transcript = whisperTranscriber.transcribe(context, samples, textLimit).trim()
                require(transcript.isNotEmpty()) { "No transcript was produced. Check the audio and model language support." }
                require(_uiState.value.attachmentGeneration == generationAtStart) {
                    "Attachment was removed; the new transcript was discarded."
                }

                val newContext = (current + sourceLabel + transcript).take(MainUiState.MAX_ATTACHMENT_CHARS)
                val newNames = _uiState.value.attachedNames + "$displayName (local transcript)"

                updateState {
                    it.copy(
                        isWhisperTranscribing = false,
                        attachedContext = (newContext).take(MainUiState.MAX_ATTACHMENT_CHARS),
                        attachedNames = newNames,
                        transcriptReviewPending = true,
                        attachmentPreviewExpanded = false,
                        statusMessage = "Transcript ready. Expand and review it, then tap Use reviewed transcript.",
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                updateState {
                    it.copy(isWhisperTranscribing = false)
                        .copy(statusMessage = "Could not transcribe WAV: ${e.message ?: "unsupported or unreadable audio"}")
                }
            } finally {
                staged?.delete()
            }
        }
    }

    fun clearAttachments() {
        updateState { it.copy(attachedFileNames = emptyList(), attachedContext = "", statusMessage = "Attached files cleared.") }
    }

    fun toggleAttachmentPreview() {
        updateState { it.copy(isAttachmentPreviewExpanded = !it.isAttachmentPreviewExpanded) }
    }

    fun confirmTranscriptReview() {
        if (_uiState.value.transcriptReviewPending && !_uiState.value.isWhisperTranscribing) {
            updateState { it.copy(
                transcriptReviewPending = false,
                statusMessage = "Transcript confirmed. Ask your question when ready.",
            ) }
        }
    }

    fun suggestSteps() {
        val state = _uiState.value
        if (state.isWhisperTranscribing) {
            updateState { it.copy(statusMessage = "Wait for transcription to finish before asking.") }
            return
        }
        if (state.transcriptReviewPending) {
            updateState { it.copy(statusMessage = "Expand and review the complete transcript, then tap Use reviewed transcript.") }
            return
        }
        val task = state.taskInput.trim()
        val attached = state.attachedContext.trim()
        if (task.isEmpty() && attached.isEmpty()) {
            updateState { it.copy(statusMessage = "Type a task, dictate it, or attach a text/PDF file first.") }
            return
        }
        if (!llmRunner.isAvailable()) {
            updateState { it.copy(statusMessage = "Choose a compatible .litertlm model first.") }
            return
        }

        isGenerationCancelled.set(false)
        val calculation = if (task.isNotBlank()) LocalCalculatorTool.tryEvaluate(task) else null
        updateState {
            it.copy(
                lastUserPrompt = task,
                canRegenerate = (task).isNotBlank(),
            ).copy(generationStatus = MainUiState.GenerationStatus.THINKING, isOutputVisible = true, answerIsComplete = false, generatedAnswer = "Working on it…", formattedAnswer = "Working on it…", plainTextAnswer = "Working on it…", statusMessage = "Answering on this device…", errorMessage = null, activeCalculation = calculation)
        }

        workerExecutor.execute {
            val accumulated = StringBuilder()
            val firstChunk = AtomicBoolean(true)
            var retrievedCitations = emptyList<LocalDocumentRetriever.DocumentCitation>()
            try {
                val intent = AnswerDirective.classify(task.ifBlank { attached })
                val history = conversationHistoryFor(task)
                val calculation = if (task.isNotBlank()) LocalCalculatorTool.tryEvaluate(task) else null
                val contextToInject = if (attached.isNotBlank()) {
                    val retrieval = LocalDocumentRetriever.retrieveTextWithCitations(
                        query = task,
                        documentText = attached,
                        sourceName = state.attachedNames.firstOrNull() ?: "attachment",
                        maxChars = MainUiState.MAX_ATTACHMENT_CHARS,
                    )
                    retrievedCitations = retrieval.citations
                    if (retrieval.promptContext.isNotBlank()) retrieval.promptContext else attached
                } else {
                    ""
                }

                // Long-term memory (from main) enriches the intent-aware instruction rather than
                // replacing it, so the runaway guard and answer-shaped prompting still apply.
                val effectiveInstruction = if (state.isMemoryEnabled) {
                    val candidateFacts = MemoryFactExtractor.extractCandidateFacts(task)
                    for (cf in candidateFacts) {
                        userMemoryStore.addFact(
                            content = cf.content,
                            category = cf.category,
                            confidence = cf.confidence,
                            sourceSessionId = activeSession?.id
                        )
                    }
                    if (candidateFacts.isNotEmpty()) {
                        refreshMemories()
                    }
                    val memoryBlock = userMemoryStore.buildPromptContext(maxTokens = 120)
                    ContextBudgetManager().enrichSystemInstructionWithMemory(
                        AnswerDirective.systemInstructionFor(intent),
                        memoryBlock,
                    )
                } else {
                    AnswerDirective.systemInstructionFor(intent)
                }

                var attempt = 0
                var result = ""
                // Two attempts at most: one normal answer, plus one corrective retry if the model
                // dodges the question with "go search for it yourself" instead of answering.
                while (attempt < MAX_ANSWER_ATTEMPTS) {
                    val isRetry = attempt > 0
                    if (isRetry) {
                        synchronized(accumulated) { accumulated.setLength(0) }
                        firstChunk.set(true)
                        updateState { it.copy(statusMessage = "Answer needs more detail. Refining on this device…") }
                    }

                    val promptBuild = promptContext.composePrompt(
                        task = task,
                        attachment = contextToInject,
                        intent = intent,
                        isRetry = isRetry,
                        history = history,
                        calculation = calculation,
                    )
                    if (promptBuild.truncatedCount > 0 && !isRetry) {
                        updateState { it.copy(statusMessage = "Older context trimmed to fit model limit.") }
                    }
                    val prompt = promptBuild.prompt
                    // The guard stops a model that locks onto a phrase and repeats it forever.
                    val guard = GuardedGeneration { currentText ->
                        val isFirst = firstChunk.compareAndSet(true, false)
                        val formatted = MarkdownFormatter.formatForDisplay(currentText)
                        val plain = MarkdownFormatter.stripToPlainText(currentText)
                        updateState {
                            it.copy(
                                generationStatus = MainUiState.GenerationStatus.STREAMING,
                                isOutputVisible = true,
                                generatedAnswer = currentText,
                                formattedAnswer = formatted,
                                plainTextAnswer = plain,
                                statusMessage = if (isFirst) "Response streaming locally…" else it.statusMessage,
                            )
                        }
                    }
                    val raw = llmRunner.runInference(
                        prompt = prompt,
                        // Memory-enriched, intent-aware instruction.
                        systemInstruction = effectiveInstruction,
                        maxTokens = MainUiState.MAX_RESPONSE_TOKENS,
                        onChunk = { chunk ->
                            if (isGenerationCancelled.get()) {
                                guard.cancel()
                            }
                            guard.accept(accumulated, chunk)
                        },
                    )
                    runawayFragment = guard.runawayFragment
                    result = AnswerFinalizer.finalize(
                        if (guard.runawayFragment != null) guard.text(accumulated) else raw
                    ).text

                    if (isGenerationCancelled.get()) break
                    // A runaway answer is already the best we can get from this attempt.
                    if (guard.runawayFragment != null) break

                    val dodged = AnswerDirective.looksLikeDelegationRefusal(result, intent) ||
                        AnswerDirective.isUnsubstantive(result)
                    if (!dodged || isRetry) break
                    attempt++
                }

                if (isGenerationCancelled.get()) {
                    val currentText = synchronized(accumulated) { accumulated.toString().trim() }
                    val formatted = MarkdownFormatter.formatForDisplay(currentText)
                    val plain = MarkdownFormatter.stripToPlainText(currentText)
                    updateState { it.copy(
                        generationStatus = MainUiState.GenerationStatus.COMPLETED,
                        isOutputVisible = true,
                        answerIsComplete = (currentText).isNotBlank(),
                        generatedAnswer = currentText,
                        formattedAnswer = formatted,
                        plainTextAnswer = plain,
                        statusMessage = "Generation stopped by user.",
                        errorMessage = null,
                        canRegenerate = it.lastUserPrompt.isNotBlank(),
                    ).copy(documentCitations = retrievedCitations) }
                    return@execute
                }

                val finalFormatted = MarkdownFormatter.formatForDisplay(result)
                val finalPlain = MarkdownFormatter.stripToPlainText(result)
                updateState {
                    run {
                        val complete = (result).isNotBlank()
                        it.copy(
                            generationStatus = MainUiState.GenerationStatus.COMPLETED,
                            isOutputVisible = true,
                            answerIsComplete = complete,
                            generatedAnswer = if (complete) result else "No answer was returned. Try rephrasing the request.",
                            formattedAnswer = if (complete) finalFormatted else "No answer was returned. Try rephrasing the request.",
                            plainTextAnswer = if (complete) finalPlain else "No answer was returned. Try rephrasing the request.",
                            statusMessage = if (complete) "Answer ready." else "No answer was returned. Try rephrasing the request.",
                            errorMessage = null,
                        )
                    }
                        .copy(documentCitations = retrievedCitations)
                        .let { done ->
                            val stopped = runawayFragment
                            if (stopped != null && result.isNotBlank()) {
                                done.copy(statusMessage = RunawayGuard.stoppedNotice(stopped))
                            } else {
                                done
                            }
                        }
                }

                // Persist turns to the active session
                saveTurnToSession(task, attached, result)
            } catch (e: Exception) {
                if (isGenerationCancelled.get()) {
                    val currentText = synchronized(accumulated) { accumulated.toString().trim() }
                    val formatted = MarkdownFormatter.formatForDisplay(currentText)
                    val plain = MarkdownFormatter.stripToPlainText(currentText)
                    updateState {
                        it.copy(
                            generationStatus = MainUiState.GenerationStatus.COMPLETED,
                            isOutputVisible = true,
                            answerIsComplete = currentText.isNotBlank(),
                            generatedAnswer = currentText,
                            formattedAnswer = formatted,
                            plainTextAnswer = plain,
                            statusMessage = "Generation stopped by user.",
                            errorMessage = null,
                            canRegenerate = it.lastUserPrompt.isNotBlank(),
                        )
                    }
                } else {
                    updateState {
                        it.copy(
                            generationStatus = MainUiState.GenerationStatus.ERROR,
                            isOutputVisible = false,
                            answerIsComplete = false,
                            generatedAnswer = "",
                            formattedAnswer = "",
                            plainTextAnswer = "",
                            statusMessage = "Could not generate an answer: ${e.message ?: "unknown error"}",
                            errorMessage = e.message ?: "unknown error",
                        )
                    }
                }
            }
        }
    }

    /**
     * Builds the prompt for one answer attempt, carrying real conversation history so follow-up
     * questions ("and by train?") have context, and shaping the request by intent so a factual
     * question is answered instead of being turned into a to-do list for the user.
     */
    /** Recent turns for the active session, minus the turn currently being generated. */
    private fun conversationHistoryFor(task: String): List<ChatMessage> =
        promptContext.historyFor(activeSession?.messages.orEmpty(), task)

    /** Answer-shaped prompt for one attempt; see [AnswerPromptComposer]. */
    private fun answerPrompt(
        task: String,
        attachment: String,
        intent: AnswerDirective.Intent,
        isRetry: Boolean,
        history: List<ChatMessage>,
        calculation: LocalCalculatorTool.CalculationResult? = null,
    ): AnswerPromptComposer.PromptBuildResult = promptContext.answerPrompt(task, attachment, intent, isRetry, history, calculation)

    /**
     * Halts an in-progress token generation loop cleanly.
     */
    fun stopGeneration() {
        if (_uiState.value.isGenerating) {
            isGenerationCancelled.set(true)
        }
    }

    /**
     * Re-runs inference using the previous user prompt.
     */
    fun regenerateLastResponse() {
        val lastPrompt = _uiState.value.lastUserPrompt
        if (lastPrompt.isNotBlank() && !_uiState.value.isGenerating) {
            updateTaskInput(lastPrompt)
            suggestSteps()
        }
    }

    /**
     * Refreshes the cached session summaries from local storage.
     */
    fun refreshSessions() {
        try {
            val summaries = sessionStore.listSessionSummaries()
            val currentId = _uiState.value.currentSessionId
            val active = if (currentId != null) sessionStore.getSession(currentId) else null
            activeSession = active
            updateState {
                it.copy(
                    sessionSummaries = summaries,
                    currentSessionId = active?.id,
                    currentSessionTitle = active?.title ?: "New Conversation",
                    isSessionPinned = active?.isPinned ?: false,
                )
            }
        } catch (_: Exception) {}
    }

    /**
     * Resets current active conversation and starts a clean session.
     */
    fun createNewSession() {
        stopGeneration()
        val session = ConversationSession.createWithInitialPrompt("New Conversation")
        try {
            sessionStore.saveSession(session)
        } catch (_: Exception) {}
        activeSession = session
        updateState {
            it.copy(
                isOutputVisible = false,
                answerIsComplete = false,
                generatedAnswer = "",
                formattedAnswer = "",
                plainTextAnswer = "",
                generationStatus = MainUiState.GenerationStatus.IDLE,
                documentCitations = emptyList(),
                taskInput = "",
                sessionSummaries = sessionStore.listSessionSummaries(),
                currentSessionId = session.id,
                currentSessionTitle = session.title,
                isSessionPinned = false,
            )
        }
    }

    /**
     * Switches the active UI state to an existing saved conversation session.
     */
    fun switchSession(sessionId: String) {
        stopGeneration()
        val session = sessionStore.getSession(sessionId) ?: return
        activeSession = session
        val lastAssistantMsg = session.messages.lastOrNull { it.isAssistant }
        val lastUserMsg = session.messages.lastOrNull { it.isUser }
        val answer = lastAssistantMsg?.content ?: ""
        val formatted = if (answer.isNotBlank()) MarkdownFormatter.formatForDisplay(answer) else ""
        val plain = if (answer.isNotBlank()) MarkdownFormatter.stripToPlainText(answer) else ""

        updateState {
            it.copy(
                taskInput = lastUserMsg?.content ?: "",
                generatedAnswer = answer,
                formattedAnswer = formatted,
                plainTextAnswer = plain,
                isOutputVisible = answer.isNotBlank(),
                answerIsComplete = answer.isNotBlank(),
                generationStatus = MainUiState.GenerationStatus.IDLE,
                lastUserPrompt = lastUserMsg?.content ?: "",
                canRegenerate = !lastUserMsg?.content.isNullOrBlank(),
                sessionSummaries = sessionStore.listSessionSummaries(),
                currentSessionId = session.id,
                currentSessionTitle = session.title,
                isSessionPinned = session.isPinned,
            )
        }
    }

    /**
     * Renames an existing session title.
     */
    fun renameSession(sessionId: String, newTitle: String) {
        val updated = sessionStore.renameSession(sessionId, newTitle) ?: return
        if (activeSession?.id == sessionId) {
            activeSession = updated
        }
        refreshSessions()
    }

    /**
     * Toggles pin status for a conversation session.
     */
    fun pinSession(sessionId: String, pinned: Boolean) {
        val updated = sessionStore.pinSession(sessionId, pinned) ?: return
        if (activeSession?.id == sessionId) {
            activeSession = updated
        }
        refreshSessions()
    }

    /**
     * Deletes a conversation session from private storage.
     */
    fun deleteSession(sessionId: String) {
        sessionStore.deleteSession(sessionId)
        if (activeSession?.id == sessionId) {
            createNewSession()
        } else {
            refreshSessions()
        }
    }

    /**
     * Filters session summaries by search query.
     */
    fun searchSessions(query: String) {
        updateState { it.copy(sessionSearchQuery = query) }
    }

    /**
     * Exports a session as Markdown or plain text.
     */
    fun exportSession(sessionId: String, asMarkdown: Boolean = true): String? {
        return if (asMarkdown) {
            sessionStore.exportSessionAsMarkdown(sessionId)
        } else {
            sessionStore.exportSessionAsText(sessionId)
        }
    }

    /**
     * Refreshes user long-term memories from sovereign local storage into UI state.
     */
    fun refreshMemories() {
        try {
            val memories = userMemoryStore.listMemories()
            updateState { it.copy(userMemories = memories) }
        } catch (_: Exception) {}
    }

    /**
     * Manually adds a user fact or preference to long-term memory.
     */
    fun addMemory(
        content: String,
        category: UserMemoryStore.MemoryCategory = UserMemoryStore.MemoryCategory.PREFERENCE,
    ) {
        userMemoryStore.addFact(content, category)
        refreshMemories()
    }

    /**
     * Deletes a specific memory entry by its UUID.
     */
    fun deleteMemory(id: String) {
        userMemoryStore.deleteMemory(id)
        refreshMemories()
    }

    /**
     * Clears all stored long-term memories.
     */
    fun clearAllMemories() {
        userMemoryStore.clearMemories()
        refreshMemories()
    }

    /**
     * Enables or disables long-term memory injection and extraction.
     */
    fun setMemoryEnabled(enabled: Boolean) {
        updateState { it.copy(isMemoryEnabled = enabled) }
    }

    /**
     * Refreshes personal notes from sovereign local storage into UI state.
     */
    fun refreshNotes() {
        try {
            val notes = notesStore.listNotes()
            updateState { it.copy(personalNotes = notes) }
        } catch (_: Exception) {}
    }

    /**
     * Creates or updates a personal note locally.
     */
    fun saveNote(
        title: String,
        content: String,
        tags: List<String> = emptyList(),
        isPinned: Boolean = false,
    ): PersonalNotesStore.PersonalNote {
        val note = notesStore.createNote(title, content, tags, isPinned)
        refreshNotes()
        return note
    }

    /**
     * Deletes a personal note by ID.
     */
    fun deleteNote(id: String): Boolean {
        val result = notesStore.deleteNote(id)
        refreshNotes()
        return result
    }

    /**
     * Toggles pin status for a personal note.
     */
    fun pinNote(id: String, pinned: Boolean): PersonalNotesStore.PersonalNote? {
        val result = notesStore.pinNote(id, pinned)
        refreshNotes()
        return result
    }

    /**
     * Filters stored notes by search query.
     */
    fun searchNotes(query: String): List<PersonalNotesStore.PersonalNote> {
        val results = notesStore.searchNotes(query)
        updateState { it.copy(personalNotes = results) }
        return results
    }

    /**
     * Exports all personal notes as Markdown text.
     */
    fun exportNotesAsMarkdown(): String = notesStore.exportNotesAsMarkdown()

    /**
     * Clears active calculation result.
     */
    fun clearActiveCalculation() {
        updateState { it.copy(activeCalculation = null) }
    }

    /**
     * Starts continuous hands-free voice duplex mode.
     */
    fun startDuplexVoiceSession(
        onStateChanged: ((DuplexVoiceController.DuplexState, String) -> Unit)? = null,
        onAmplitude: ((Float) -> Unit)? = null,
    ) {
        val context = whisperContext
        if (context == 0L) {
            updateState { it.copy(statusMessage = "Select a local Whisper model first.") }
            return
        }
        if (!llmRunner.isAvailable()) {
            updateState { it.copy(statusMessage = "Select a local LiteRT-LM model first.") }
            return
        }

        updateState {
            it.copy(
                isDuplexActive = true,
                duplexState = DuplexVoiceController.DuplexState.LISTENING,
                duplexStatusMessage = "Listening for speech…",
            )
        }

        duplexVoiceController.startSession(
            whisperContext = context,
            systemInstruction = MainUiState.SYSTEM_INSTRUCTION,
            listener = object : DuplexVoiceController.Listener {
                override fun onStateChanged(state: DuplexVoiceController.DuplexState, statusMessage: String) {
                    updateState { it.copy(duplexState = state, duplexStatusMessage = statusMessage) }
                    onStateChanged?.invoke(state, statusMessage)
                }

                override fun onAmplitude(rms: Float) {
                    onAmplitude?.invoke(rms)
                }

                override fun onUserTranscribed(text: String) {
                    updateState { it.copy(taskInput = text) }
                }

                override fun onAssistantChunk(chunk: String) {}

                override fun onTurnComplete(userText: String, assistantText: String) {
                    saveTurnToSession(userText, "", assistantText)
                    val formatted = MarkdownFormatter.formatForDisplay(assistantText)
                    val plain = MarkdownFormatter.stripToPlainText(assistantText)
                    val complete = assistantText.isNotBlank()
                    updateState {
                        it.copy(
                            generationStatus = MainUiState.GenerationStatus.COMPLETED,
                            isOutputVisible = true,
                            answerIsComplete = complete,
                            generatedAnswer = if (complete) assistantText else "No answer was returned. Try rephrasing the request.",
                            formattedAnswer = if (complete) formatted else "No answer was returned. Try rephrasing the request.",
                            plainTextAnswer = if (complete) plain else "No answer was returned. Try rephrasing the request.",
                            statusMessage = if (complete) "Answer ready." else "No answer was returned. Try rephrasing the request.",
                            errorMessage = null,
                        )
                    }
                }

                override fun onError(error: Throwable) {
                    updateState {
                        it.copy(
                            duplexState = DuplexVoiceController.DuplexState.ERROR,
                            duplexStatusMessage = error.message ?: "Voice error",
                        )
                    }
                }
            }
        )
    }

    /**
     * Interrupts active voice playback or thinking in duplex mode.
     */
    fun interruptDuplex() {
        duplexVoiceController.interrupt()
    }

    /**
     * Stops continuous voice duplex mode.
     */
    fun stopDuplexVoiceSession() {
        duplexVoiceController.stopSession()
        updateState {
            it.copy(
                isDuplexActive = false,
                duplexState = DuplexVoiceController.DuplexState.IDLE,
                duplexStatusMessage = "",
            )
        }
    }

    private fun saveTurnToSession(task: String, attachedContext: String, answer: String) {
        try {
            val current = activeSession ?: ConversationSession.createWithInitialPrompt(task.ifBlank { "Conversation" })
            val userMsg = ChatMessage.user(
                content = task,
                attachmentContent = if (attachedContext.isNotBlank()) attachedContext else null
            )
            val assistantMsg = ChatMessage.assistant(answer)
            val updated = current.withMessage(userMsg).withMessage(assistantMsg)
            sessionStore.saveSession(updated)
            activeSession = updated
            // Point the UI state at this session explicitly. refreshSessions() would read the
            // previous currentSessionId (still null on the first turn) and null out activeSession,
            // dropping the history that was just saved.
            updateState {
                it.copy(
                    sessionSummaries = sessionStore.listSessionSummaries(),
                    currentSessionId = updated.id,
                    currentSessionTitle = updated.title,
                    isSessionPinned = updated.isPinned,
                )
            }
        } catch (_: Exception) {}
    }

    fun clearOutput() {
        updateState {
            it.copy(
                isOutputVisible = false,
                answerIsComplete = false,
                generatedAnswer = "",
                formattedAnswer = "",
                plainTextAnswer = "",
                generationStatus = MainUiState.GenerationStatus.IDLE,
                documentCitations = emptyList(),
            )
        }
    }

    fun prepareSaveAnswer(): String? {
        val state = _uiState.value
        if (!state.answerIsComplete) {
            updateState { it.copy(statusMessage = "Wait until a complete answer is available before saving it.") }
            return null
        }
        val answerToSave = state.plainTextAnswer.ifBlank { state.generatedAnswer }.trim()
        if (answerToSave.isBlank()) {
            updateState { it.copy(statusMessage = "Generate an answer before saving it.") }
            return null
        }
        if (answerToSave.length > AnswerTextWriter.MAX_CHARS) {
            updateState { it.copy(statusMessage = "This answer is too large to save as a text result.") }
            return null
        }
        updateState { it.copy(pendingAnswerToSave = answerToSave) }
        return answerToSave
    }

    fun saveAnswerToStream(outputStream: OutputStream, answer: String) {
        updateState { it.copy(statusMessage = "Saving the answer to your selected location…") }
        workerExecutor.execute {
            try {
                outputStream.use { stream ->
                    AnswerTextWriter.write(stream, answer)
                }
                updateState {
                    it.copy(
                        pendingAnswerToSave = null,
                        statusMessage = "Answer saved to the location you selected.",
                    )
                }
            } catch (e: Exception) {
                updateState {
                    it.copy(statusMessage = "Could not save the answer: ${e.message ?: "storage error"}")
                }
            }
        }
    }

    fun onSaveAnswerCanceled() {
        updateState {
            it.copy(
                pendingAnswerToSave = null,
                statusMessage = "Save canceled. No file was written.",
            )
        }
    }

    fun onSaveAnswerMissing() {
        updateState {
            it.copy(
                pendingAnswerToSave = null,
                statusMessage = "The answer is no longer available. Generate it again before saving.",
            )
        }
    }

    fun getCopyText(): String {
        val state = _uiState.value
        return state.plainTextAnswer.ifBlank { state.formattedAnswer.ifBlank { state.generatedAnswer } }
    }

    fun getSpeechText(): String {
        val state = _uiState.value
        return state.plainTextAnswer.ifBlank { state.generatedAnswer }
    }

    fun cleanupStagedInputFiles() {
        cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("pocketagent-pdf-") || file.name.startsWith("pocketagent-audio-")) {
                file.delete()
            }
        }
    }

    override fun close() {
        workerExecutor.execute {
            duplexVoiceController.stopSession()
            llmRunner.close()
            val context = whisperContext
            whisperContext = 0L
            if (context != 0L) whisperTranscriber.release(context)
        }
    }
}
