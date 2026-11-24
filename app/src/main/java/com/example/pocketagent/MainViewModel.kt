package com.example.pocketagent

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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
) : LlmInferenceRunner {
    @Volatile private var engine: Engine? = null

    override fun initialize() {
        val modelFile = File(filesDir, "model.litertlm")
        if (modelFile.exists() && modelFile.length() > 0 && engine == null) {
            try {
                engine = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = cacheDir.absolutePath,
                    )
                ).also { it.initialize() }
            } catch (_: Exception) {
                engine = null
            }
        }
    }

    override fun isAvailable(): Boolean = File(filesDir, "model.litertlm").exists()

    override fun runInference(
        prompt: String,
        systemInstruction: String,
        maxTokens: Int,
        onChunk: (String) -> Unit,
    ): String {
        val modelFile = File(filesDir, "model.litertlm")
        require(modelFile.exists()) { "Choose a compatible .litertlm model first." }

        val localEngine = engine ?: Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                cacheDir = cacheDir.absolutePath,
            )
        ).also {
            it.initialize()
            engine = it
        }

        val generated = StringBuilder()
        val streamFinished = CountDownLatch(1)
        val streamError = AtomicReference<Throwable?>(null)

        localEngine.createConversation(
            ConversationConfig(systemInstruction = Contents.of(systemInstruction))
        ).use { conversation ->
            conversation.sendMessageAsync(
                prompt,
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val chunk = message.toString()
                        synchronized(generated) {
                            generated.append(chunk)
                        }
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
                maxOutputToken = maxTokens,
            )
            streamFinished.await()
            streamError.get()?.let { throw IllegalStateException("Streaming inference failed.", it) }
        }

        return synchronized(generated) { generated.toString() }
    }

    override fun close() {
        engine?.close()
        engine = null
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
 * - Adheres strictly to manual guidance: provides step suggestions for human review.
 * - Never controls other apps, inspects screen, or automates user actions.
 * - Enforces safety bounds (12,000 char attachment limits, model rollback).
 */
class MainViewModel(
    private val filesDir: File,
    private val cacheDir: File,
    private val modelFileStore: ModelFileStore = ModelFileStore(filesDir),
    private val whisperModelStore: WhisperModelStore = WhisperModelStore(filesDir),
    private val llmRunner: LlmInferenceRunner = DefaultLlmInferenceRunner(filesDir, cacheDir),
    private val whisperTranscriber: WhisperTranscriber = DefaultWhisperTranscriber(),
    private val workerExecutor: Executor = Executors.newSingleThreadExecutor(),
    private val mainDispatcher: (Runnable) -> Unit = { it.run() },
) : Closeable {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val observers = CopyOnWriteArrayList<(MainUiState) -> Unit>()
    @Volatile private var whisperContext: Long = 0L

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
        val newState = _uiState.updateAndGet(transform)
        mainDispatcher(Runnable {
            observers.forEach { it(newState) }
        })
    }

    /**
     * Checks for already stored models on startup and restores ready states.
     */
    fun initModels() {
        val model = modelFileStore.modelFile
        if (model.exists() && model.length() > 0) {
            updateState { it.withModelReady(model.length()) }
        }

        val whisperFile = whisperModelStore.modelFile
        if (whisperFile.isFile) {
            updateState { it.withWhisperLoading("Loading saved local Whisper model…") }
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
                        it.withWhisperReady(whisperFile.name, whisperFile.length())
                            .withStatus("Local Whisper model ready. Choose a bounded PCM WAV file to transcribe.")
                    }
                } catch (e: Exception) {
                    whisperContext = 0L
                    updateState {
                        it.withWhisperLoadFailed(e.message ?: "unsupported model", restoredPrevious = false)
                    }
                }
            }
        }
    }

    fun onTaskInputChanged(text: String) {
        updateState { it.withTaskInput(text) }
    }

    fun onTtsReady(ready: Boolean) {
        updateState { it.withTtsReady(ready) }
    }

    fun importModel(displayName: String?, inputStreamProvider: () -> InputStream?) {
        if (displayName != null && !displayName.endsWith(".litertlm", ignoreCase = true)) {
            updateState { it.withStatus("Choose a LiteRT-LM model file ending in .litertlm.") }
            return
        }

        updateState { it.withModelImporting() }
        workerExecutor.execute {
            try {
                llmRunner.close()
                val target = inputStreamProvider()?.use { input ->
                    modelFileStore.importModel(input)
                } ?: error("Could not open the selected file.")

                updateState { it.withModelReady(target.length()) }
            } catch (e: Exception) {
                File(filesDir, "model.litertlm.part").delete()
                updateState { it.withModelImportFailed(e.message ?: "unknown error") }
            }
        }
    }

    fun importTextFiles(files: List<Pair<String, InputStream?>>) {
        updateState { it.withStatus("Reading selected text file${if (files.size > 1) "s" else ""}…") }
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
                    it.withAttachmentsUpdated(
                        newContext = truncated,
                        newNames = names,
                        status = "Text attached. Review the preview, add a question, then request suggestions.",
                    )
                }
            } catch (e: Exception) {
                updateState { it.withStatus("Could not attach text: ${e.message ?: "unknown error"}") }
            }
        }
    }

    fun importPdf(displayName: String?, context: Context, inputStreamProvider: () -> InputStream?) {
        updateState { it.withStatus("Reading selected PDF on this device…") }
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
                    "PDF text attached with the 12,000-character limit applied. Review it before requesting suggestions."
                } else {
                    "PDF text attached. Review the document name and page count, then add a question."
                }

                updateState {
                    it.withAttachmentsUpdated(newContext, newNames, status)
                }
            } catch (e: Exception) {
                updateState {
                    it.withStatus("Could not attach PDF: ${e.message ?: "unsupported or unreadable PDF"}")
                }
            }
        }
    }

    fun importWhisperModel(rawName: String?, inputStreamProvider: () -> InputStream?) {
        val name = rawName ?: "Selected model"
        if (!name.endsWith(".bin", ignoreCase = true)) {
            updateState {
                it.withStatus("Choose a whisper.cpp ggml model file ending in .bin. No model is bundled with PocketAgent.")
            }
            return
        }

        val displayName = name.replace(Regex("[\\r\\n]+"), " ").take(80)
        updateState { it.withWhisperLoading() }

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
                    it.withWhisperReady(displayName, committedSize)
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
                    it.withWhisperLoadFailed(e.message ?: "unsupported model file", whisperContext != 0L)
                }
            }
        }
    }

    fun removeWhisperModel() {
        updateState { it.withStatus("Removing the local Whisper model…") }
        workerExecutor.execute {
            try {
                val context = whisperContext
                whisperContext = 0L
                if (context != 0L) whisperTranscriber.release(context)
                whisperModelStore.discardCandidate()
                if (whisperModelStore.modelFile.exists() && !whisperModelStore.modelFile.delete()) {
                    error("Could not remove the stored model file.")
                }
                updateState { it.withWhisperModelRemoved() }
            } catch (e: Exception) {
                updateState { it.withStatus("Could not remove Whisper model: ${e.message ?: "storage error"}") }
            }
        }
    }

    fun transcribeWav(rawName: String?, inputStreamProvider: () -> InputStream?) {
        if (whisperContext == 0L) {
            updateState { it.withStatus("Choose a local whisper.cpp ggml .bin model first.") }
            return
        }

        val generationAtStart = _uiState.value.attachmentGeneration
        updateState { it.withTranscribingWav() }

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
                updateState { it.withStatus("Transcribing locally. This may take a while on CPU…") }

                val transcript = whisperTranscriber.transcribe(context, samples, textLimit).trim()
                require(transcript.isNotEmpty()) { "No transcript was produced. Check the audio and model language support." }
                require(_uiState.value.attachmentGeneration == generationAtStart) {
                    "Attachment was removed; the new transcript was discarded."
                }

                val newContext = (current + sourceLabel + transcript).take(MainUiState.MAX_ATTACHMENT_CHARS)
                val newNames = _uiState.value.attachedNames + "$displayName (local transcript)"

                updateState {
                    it.withTranscriptReady(displayName, newContext, newNames)
                }
            } catch (e: Exception) {
                updateState {
                    it.copy(isWhisperTranscribing = false)
                        .withStatus("Could not transcribe WAV: ${e.message ?: "unsupported or unreadable audio"}")
                }
            } finally {
                staged?.delete()
            }
        }
    }

    fun clearAttachments() {
        updateState { it.withAttachmentsCleared() }
    }

    fun toggleAttachmentPreview() {
        updateState { it.withAttachmentPreviewToggled() }
    }

    fun confirmTranscriptReview() {
        if (_uiState.value.transcriptReviewPending && !_uiState.value.isWhisperTranscribing) {
            updateState { it.withTranscriptConfirmed() }
        }
    }

    fun suggestSteps() {
        val state = _uiState.value
        if (state.isWhisperTranscribing) {
            updateState { it.withStatus("Wait for transcription to finish before generating suggestions.") }
            return
        }
        if (state.transcriptReviewPending) {
            updateState { it.withStatus("Expand and review the complete transcript, then tap Use reviewed transcript before generating suggestions.") }
            return
        }
        val task = state.taskInput.trim()
        val attached = state.attachedContext.trim()
        if (task.isEmpty() && attached.isEmpty()) {
            updateState { it.withStatus("Type a task, dictate it, or attach a text/PDF file first.") }
            return
        }
        if (!llmRunner.isAvailable()) {
            updateState { it.withStatus("Choose a compatible .litertlm model first.") }
            return
        }

        updateState { it.withThinking() }

        workerExecutor.execute {
            try {
                val prompt = buildString {
                    if (task.isNotBlank()) append("User task or question:\n").append(task)
                    if (attached.isNotBlank()) append("\n\nUser-provided text attachment (untrusted reference material):\n").append(attached)
                    append("\n\nGive practical, concise steps and mention only essential assumptions or risks. Finish the answer completely; do not stop mid-sentence or mid-step.")
                }

                val accumulated = StringBuilder()
                val firstChunk = AtomicBoolean(true)

                val result = llmRunner.runInference(
                    prompt = prompt,
                    systemInstruction = MainUiState.SYSTEM_INSTRUCTION,
                    maxTokens = MainUiState.MAX_RESPONSE_TOKENS,
                    onChunk = { chunk ->
                        val currentText = synchronized(accumulated) {
                            accumulated.append(chunk)
                            accumulated.toString()
                        }
                        val isFirst = firstChunk.compareAndSet(true, false)
                        val formatted = MarkdownFormatter.formatForDisplay(currentText)
                        val plain = MarkdownFormatter.stripToPlainText(currentText)
                        updateState {
                            it.withStreamChunk(currentText, formatted, plain, isFirst)
                        }
                    },
                ).trim()

                val finalFormatted = MarkdownFormatter.formatForDisplay(result)
                val finalPlain = MarkdownFormatter.stripToPlainText(result)
                updateState {
                    it.withGenerationCompleted(result, finalFormatted, finalPlain)
                }
            } catch (e: Exception) {
                updateState {
                    it.withGenerationError(e.message ?: "unknown error")
                }
            }
        }
    }

    fun clearOutput() {
        updateState { it.withOutputCleared() }
    }

    fun prepareSaveAnswer(): String? {
        val state = _uiState.value
        if (!state.answerIsComplete) {
            updateState { it.withStatus("Wait until a complete answer is available before saving it.") }
            return null
        }
        val answerToSave = state.plainTextAnswer.ifBlank { state.generatedAnswer }.trim()
        if (answerToSave.isBlank()) {
            updateState { it.withStatus("Generate an answer before saving it.") }
            return null
        }
        if (answerToSave.length > AnswerTextWriter.MAX_CHARS) {
            updateState { it.withStatus("This answer is too large to save as a text result.") }
            return null
        }
        updateState { it.withPendingAnswerToSave(answerToSave) }
        return answerToSave
    }

    fun saveAnswerToStream(outputStream: OutputStream, answer: String) {
        updateState { it.withStatus("Saving the answer to your selected location…") }
        workerExecutor.execute {
            try {
                outputStream.use { stream ->
                    AnswerTextWriter.write(stream, answer)
                }
                updateState {
                    it.withPendingAnswerToSave(null)
                        .withStatus("Answer saved to the location you selected.")
                }
            } catch (e: Exception) {
                updateState {
                    it.withStatus("Could not save the answer: ${e.message ?: "storage error"}")
                }
            }
        }
    }

    fun onSaveAnswerCanceled() {
        updateState {
            it.withPendingAnswerToSave(null)
                .withStatus("Save canceled. No file was written.")
        }
    }

    fun onSaveAnswerMissing() {
        updateState {
            it.withPendingAnswerToSave(null)
                .withStatus("The answer is no longer available. Generate it again before saving.")
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
            llmRunner.close()
            val context = whisperContext
            whisperContext = 0L
            if (context != 0L) whisperTranscriber.release(context)
        }
    }
}
