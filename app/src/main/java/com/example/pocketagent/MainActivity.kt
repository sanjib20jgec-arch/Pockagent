package com.example.pocketagent

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.transition.TransitionManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MainActivity : FragmentActivity(), TextToSpeech.OnInitListener {
    companion object {
        internal const val PICK_MODEL = 1
        internal const val PICK_TEXT_FILES = 2
        internal const val REQUEST_MIC = 3
        internal const val PICK_PDF = 4
        internal const val PICK_WHISPER_MODEL = 5
        internal const val PICK_WAV = 6
        internal const val CREATE_ANSWER_FILE = 7
        internal const val REQUEST_RECORD_MIC = 8
        internal const val PICK_IMAGE_OCR = 9
        internal const val CREATE_ARTIFACT_HTML = 10
        internal const val STATE_PENDING_ANSWER_SAVE = "pending_answer_to_save"
        internal const val STATE_ACTIVE_SESSION_ID = "active_session_id"
        internal const val PREFS_SETTINGS = "pocketagent_settings"
        internal const val PREF_BACKEND_MODE = "pref_backend_mode"
        internal const val PREF_MODEL_DISPLAY_NAME = "pref_model_display_name"
        internal const val PREF_GPU_LOADING_GUARD = "pref_gpu_loading_crash_guard"
        internal const val PREF_ACTIVE_PROJECT_ID = "pref_active_project_id"
        internal const val PREF_TEMPERATURE_BITS = "pref_temperature_bits"
        internal const val PREF_MAX_OUTPUT_TOKENS = "pref_max_output_tokens"
        internal const val PREF_MAX_CONTEXT_TOKENS = "pref_max_context_tokens"
        internal const val PREF_AGENT_MODE = "pref_agent_mode"
        internal const val PREF_DEEP_THINK = "pref_deep_think"
        internal const val PREF_VISION_ENABLED = "pref_vision_enabled"
        internal const val MAX_ATTACHMENT_CHARS = MainUiState.MAX_ATTACHMENT_CHARS
        internal const val MAX_RESPONSE_TOKENS = MainUiState.MAX_RESPONSE_TOKENS
        /** Hard ceiling so a stalled engine cannot block the worker thread forever. */
        internal const val GENERATION_TIMEOUT_SECONDS = 360L
        /** Skip eager startup preload above 1.5 GB so multi-GB models (e.g. LFM3-Omni 3.5 GB) don't OOM idle launches. */
        internal const val EAGER_WARMUP_MAX_BYTES = 1_500_000_000L
        /** Reset stateful KV cache before cumulative turns approach the 4096-token EngineConfig cap. */
        internal const val WARM_CONTEXT_RESET_THRESHOLD = 2800
        internal const val IDLE_ENGINE_UNLOAD_MS = 300_000L
        internal val SYSTEM_INSTRUCTION: String get() = AnswerDirective.SYSTEM_INSTRUCTION
        internal const val WHITE = Color.WHITE
    }

    internal lateinit var palette: UiPalette

    /** Serial executor for LiteRT-LM load and inference: engine access must never overlap. */

    internal val inferenceExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PocketAgentInference").apply { isDaemon = true }
    }

    /** Parallel executor for PDF extraction, Whisper, session I/O, and Markdown formatting. */

    internal val ioExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "PocketAgentIO").apply { isDaemon = true }
    }

    /** Throttled plain-text streaming; formats once on completion, off the UI thread. */

    internal val streamingRenderer by lazy {
        StreamingRenderController(
            mainHandler = MainThreadDispatcher(mainHandler),
            formatExecutor = ioExecutor,
        ).apply {
            onPartialText = { text ->
                activeAssistantHandle?.bindStreamingText(text)
                maybeFollowStreamingOutput()
            }
        }
    }

    internal val mainHandler = Handler(Looper.getMainLooper())

    internal val engineManager = InferenceEngineManager<Engine>()

    internal val promptContext = AnswerPromptComposer()

    internal val inAppAudioRecorder = InAppAudioRecorder()

    @Volatile internal var whisperContext: Long = 0L

    internal lateinit var taskInput: EditText

    internal lateinit var statusText: TextView

    internal lateinit var attachmentText: TextView

    internal lateinit var attachmentPreview: TextView

    internal lateinit var attachmentPreviewButton: Button

    internal lateinit var whisperModelText: TextView

    internal lateinit var recordAudioButton: Button

    internal lateinit var transcribeWavButton: Button

    internal lateinit var confirmTranscriptButton: Button

    internal lateinit var suggestButton: Button

    internal lateinit var attachmentCard: LinearLayout
    // Sticky bottom composer, multi-turn chat thread, and collapsible chrome.

    internal lateinit var chatScroll: ScrollView

    internal lateinit var chatCanvas: LinearLayout

    internal lateinit var chatThreadContainer: LinearLayout

    internal lateinit var jumpToBottomButton: Button

    internal lateinit var heroCard: LinearLayout

    internal lateinit var heroModelCtaButton: Button

    internal lateinit var quickChips: View

    internal lateinit var composerDock: LinearLayout

    internal lateinit var composerCard: LinearLayout

    internal lateinit var composerRoot: LinearLayout

    internal lateinit var toolsTray: LinearLayout

    internal lateinit var toolsToggleButton: ImageButton

    internal lateinit var attachmentChipContainer: LinearLayout

    internal lateinit var recordingBanner: LinearLayout

    internal lateinit var recordingDot: View

    internal lateinit var recordingBannerText: TextView

    internal lateinit var recordingStopButton: Button

    internal lateinit var composerMicButton: ImageButton

    internal lateinit var modelStatusPill: ModelStatusPillView

    internal var isToolsTrayOpen = false

    internal var isJumpToBottomVisible = false

    internal var lastAttachmentStripSignature: String? = null
    /** True while the user is reading earlier output; suppresses forced auto-scroll. */

    @Volatile internal var isUserReadingUpwards = false

    @Volatile internal var isActivityInForeground = false

    @Volatile internal var firstTokenElapsedMs: Long? = null

    @Volatile internal var passStartElapsedMs: Long = 0L

    @Volatile internal var streamedCharsCount: Int = 0

    internal var recognizer: SpeechRecognizer? = null

    internal var isDictating = false

    internal var tts: TextToSpeech? = null

    internal var ttsReady = false

    @Volatile internal var attachedContext = ""

    @Volatile internal var attachmentGeneration = 0L

    @Volatile internal var isWhisperTranscribing = false

    @Volatile internal var transcriptReviewPending = false

    internal var attachedNames = mutableListOf<String>()

    internal var attachmentPreviewExpanded = false

    internal var answerIsComplete = false

    internal var latestAssistantText: String = ""

    internal var lastRawPrompt: String = ""

    internal var activeAssistantHandle: ActiveAssistantCardHandle? = null

    internal val thinkingPulseAnimator = PulseAnimator()

    internal val recordingPulseAnimator = PulseAnimator()

    internal var generationStartElapsedMs: Long = 0L

    internal var generationTimerRunnable: Runnable? = null

    internal var idleUnloadRunnable: Runnable? = null

    internal var pendingAnswerToSave: String? = null

    internal val viewModel by lazy {
        MainViewModel(filesDir, cacheDir, mainDispatcher = { mainHandler.post(it) })
    }

    internal val sessionStore get() = viewModel.sessionStore

    internal val userMemoryStore get() = viewModel.userMemoryStore

    internal val notesStore get() = viewModel.notesStore

    internal val projectStore by lazy { ProjectStore(ProjectStore.defaultDir(filesDir)) }

    internal val modelLibraryStore by lazy { ModelLibraryStore(ModelLibraryStore.defaultDir(filesDir)) }

    internal val artifactStore by lazy { ArtifactStore(ArtifactStore.defaultDir(filesDir)) }

    internal val documentVault by lazy { DocumentVault(DocumentVault.defaultDir(filesDir)) }

    internal val slideDrawer by lazy { ClaudeSlideDrawer(this) }

    internal var activeProjectId: String? = null

    internal var isAgentModeEnabled: Boolean = false

    internal var isDeepThinkEnabled: Boolean = false

    internal var isVisionModeEnabled: Boolean = false

    internal var currentTemperature: Float = InferenceEngineManager.DEFAULT_TEMPERATURE

    internal var currentMaxOutputTokens: Int = MAX_RESPONSE_TOKENS

    internal var currentMaxContextTokens: Int = ContextBudgetManager.TokenBudget.DEFAULT_MAX_CONTEXT_TOKENS

    @Volatile internal var attachedImagePath: String? = null

    internal var attachedImageName: String? = null

    internal var pendingHtmlToExport: String? = null

    internal lateinit var workspaceSubtitleView: TextView

    internal lateinit var slashPopupContainer: LinearLayout

    internal lateinit var agentModeButton: Button

    internal lateinit var attachImageButton: Button

    internal lateinit var quickModeSummaryChip: Button

    internal lateinit var quickSlashChip: Button

    internal lateinit var quickTuningChip: Button

    internal lateinit var contextContinuityBanner: LinearLayout

    internal lateinit var contextContinuityText: TextView

    internal val ttsSpeaker by lazy {
        AndroidTtsSpeaker(
            ttsProvider = { tts },
            isReadyProvider = { ttsReady }
        )
    }

    internal val settingsPrefs by lazy {
        getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
    }

    internal val duplexController by lazy {
        DuplexVoiceController(
            audioRecorder = inAppAudioRecorder,
            whisperTranscriber = DefaultWhisperTranscriber(),
            llmRunner = DefaultLlmInferenceRunner(
                filesDir = filesDir,
                cacheDir = cacheDir,
                engineManager = engineManager,
                timeoutSeconds = GENERATION_TIMEOUT_SECONDS,
            ),
            ttsSpeaker = ttsSpeaker,
            workerExecutor = inferenceExecutor,
            mainDispatcher = { mainHandler.post(it) },
        )
    }

    internal val biometricLockManager by lazy {
        BiometricLockManager(
            preferences = getSharedPreferences("pocketagent_security", MODE_PRIVATE),
        )
    }

    internal lateinit var lockOverlay: LinearLayout

    internal var isAuthenticating = false

    internal var isMemoryEnabled = true

    internal var currentSession: ConversationSession? = null

    internal var lastPrompt: String = ""

    @Volatile internal var currentCitations = emptyList<LocalDocumentRetriever.DocumentCitation>()

    internal val isGeneratingCancelled = AtomicBoolean(false)

    /** Set by the runaway guard so the UI can explain why generation stopped. */

    @Volatile internal var runawayFragment: String? = null
    /** True while the primary composer button is acting as Stop. */

    @Volatile internal var isGeneratingUi = false
    /** True after the user tapped Stop while waiting for native cancellation to complete. */

    @Volatile internal var isStoppingUi = false
    /** Set when the model dodged the question, so Refine can be offered instead of auto-retrying. */

    @Volatile internal var maybeShowRefineAffordance = false
    /** Latch of the in-flight stream, so Stop can release a waiter the engine may never notify. */

    @Volatile internal var activeStreamLatch: CountDownLatch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeMode = ThemeMode.fromString(settingsPrefs.getString(UiThemeResolver.PREF_THEME_MODE, null))
        val systemNightActive = UiThemeResolver.isDarkFromConfiguration(resources.configuration.uiMode)
        palette = UiThemeResolver.paletteFor(themeMode, systemNightActive)
        super.onCreate(savedInstanceState)
        pendingAnswerToSave = savedInstanceState?.getString(STATE_PENDING_ANSWER_SAVE)
        applyWindowBars(this, palette)
        tts = TextToSpeech(this, this)

        var savedBackend = BackendPreference.fromString(settingsPrefs.getString(PREF_BACKEND_MODE, null))
        var gpuGuardTriggered = false
        if (settingsPrefs.getBoolean(PREF_GPU_LOADING_GUARD, false)) {
            settingsPrefs.edit()
                .putBoolean(PREF_GPU_LOADING_GUARD, false)
                .putString(PREF_BACKEND_MODE, BackendPreference.CPU_ONLY.name)
                .apply()
            savedBackend = BackendPreference.CPU_ONLY
            gpuGuardTriggered = true
        }
        engineManager.preference = savedBackend
        activeProjectId = settingsPrefs.getString(PREF_ACTIVE_PROJECT_ID, null)
        isAgentModeEnabled = settingsPrefs.getBoolean(PREF_AGENT_MODE, false)
        isDeepThinkEnabled = settingsPrefs.getBoolean(PREF_DEEP_THINK, false)
        isVisionModeEnabled = settingsPrefs.getBoolean(PREF_VISION_ENABLED, false)
        currentTemperature = if (settingsPrefs.contains(PREF_TEMPERATURE_BITS)) {
            java.lang.Float.intBitsToFloat(
                settingsPrefs.getInt(
                    PREF_TEMPERATURE_BITS,
                    java.lang.Float.floatToIntBits(InferenceEngineManager.DEFAULT_TEMPERATURE),
                )
            ).coerceIn(0.1f, 1.5f)
        } else {
            InferenceEngineManager.DEFAULT_TEMPERATURE
        }
        currentMaxOutputTokens = settingsPrefs.getInt(PREF_MAX_OUTPUT_TOKENS, MAX_RESPONSE_TOKENS)
            .takeIf { it in listOf(256, 512, 1024, 2048) } ?: MAX_RESPONSE_TOKENS
        currentMaxContextTokens = settingsPrefs.getInt(
            PREF_MAX_CONTEXT_TOKENS,
            ContextBudgetManager.TokenBudget.DEFAULT_MAX_CONTEXT_TOKENS,
        ).takeIf { it in ContextBudgetManager.TokenBudget.SUPPORTED_CONTEXT_WINDOWS }
            ?: ContextBudgetManager.TokenBudget.DEFAULT_MAX_CONTEXT_TOKENS
        engineManager.activeTemperature = currentTemperature
        engineManager.visionEnabled = isVisionModeEnabled
        engineManager.maxContextTokens = currentMaxContextTokens
        ensureLegacyModelRegistered()
        engineManager.onFallback = { reason ->
            if (engineManager.preference == BackendPreference.CPU_ONLY) {
                settingsPrefs.edit().putString(PREF_BACKEND_MODE, BackendPreference.CPU_ONLY.name).apply()
            }
            if (currentMaxContextTokens != engineManager.maxContextTokens) {
                currentMaxContextTokens = engineManager.maxContextTokens
                settingsPrefs.edit().putInt(PREF_MAX_CONTEXT_TOKENS, currentMaxContextTokens).apply()
            }
            mainHandler.post {
                refreshModelStatus()
                updateStatus(reason)
            }
        }

        cleanupStagedInputFiles()
        buildInterface()
        viewModel.addObserver { state -> applyViewModelState(state) }
        viewModel.initModels(restoreWhisper = false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    slideDrawer.isOpen() -> {
                        slideDrawer.close()
                    }
                    ::slashPopupContainer.isInitialized && slashPopupContainer.visibility == View.VISIBLE -> {
                        slashPopupContainer.visibility = View.GONE
                    }
                    isToolsTrayOpen -> {
                        toggleToolsTray(force = false)
                    }
                    ::taskInput.isInitialized && taskInput.hasFocus() -> {
                        hideKeyboard(clearInputFocus = true)
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
        savedInstanceState?.getString(STATE_ACTIVE_SESSION_ID)?.let { loadSession(it) }
        refreshModelStatus()
        updateWorkspaceSubtitle()
        if (gpuGuardTriggered) {
            updateStatus("Previous GPU model load ended unexpectedly; switched safely to CPU mode.")
        }
        restoreWhisperModel()
        warmUpModelAfterFirstFrame()
        if (savedInstanceState == null) {
            handleSharedIntent(intent)
        }
        handleWidgetLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
        handleWidgetLaunchIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PENDING_ANSWER_SAVE, pendingAnswerToSave)
        outState.putString(STATE_ACTIVE_SESSION_ID, currentSession?.id)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Uses the platform document picker for broad Android compatibility")

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CREATE_ANSWER_FILE) {
            val answer = pendingAnswerToSave
            pendingAnswerToSave = null
            val destination = if (resultCode == RESULT_OK) data?.data else null
            if (destination == null) {
                updateStatus("Save canceled. No file was written.")
            } else if (!answer.isNullOrBlank()) {
                saveAnswerToUri(destination, answer)
            } else {
                updateStatus("The answer is no longer available. Generate it again before saving.")
            }
            return
        }
        if (requestCode == CREATE_ARTIFACT_HTML) {
            val html = pendingHtmlToExport
            pendingHtmlToExport = null
            val destination = if (resultCode == RESULT_OK) data?.data else null
            if (destination != null && !html.isNullOrBlank()) {
                saveAnswerToUri(destination, html)
            }
            return
        }
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == PICK_MODEL) {
            data.data?.let(::importModel)
        } else if (requestCode == PICK_TEXT_FILES) {
            val uris = mutableListOf<Uri>()
            data.data?.let(uris::add)
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
            if (uris.isNotEmpty()) importTextFiles(uris.distinct())
        } else if (requestCode == PICK_PDF) {
            data.data?.let(::importPdf)
        } else if (requestCode == PICK_WHISPER_MODEL) {
            data.data?.let(::importWhisperModel)
        } else if (requestCode == PICK_WAV) {
            data.data?.let(::transcribeWav)
        } else if (requestCode == PICK_IMAGE_OCR) {
            data.data?.let(::stageImageUri)
        }
    }

    @Volatile internal var currentGenerationStageLabel: String = "Thinking"

    internal fun startNewConversation() {
        if (isGeneratingUi || engineManager.isGenerationActive) {
            updateStatus("Stop active generation before starting a new chat.")
            return
        }
        dismissComposerOverlays(hideIme = true)
        val newSession = ConversationSession.createWithInitialPrompt("New Conversation")
        try {
            sessionStore.saveSession(newSession)
        } catch (_: Exception) {}
        currentSession = newSession
        engineManager.invalidateConversation("new chat")
        maybeShowRefineAffordance = false
        currentCitations = emptyList()
        activeAssistantHandle = null
        latestAssistantText = ""
        answerIsComplete = false
        lastPrompt = ""
        lastRawPrompt = ""
        if (::chatThreadContainer.isInitialized) {
            chatThreadContainer.removeAllViews()
        }
        taskInput.setText("")
        ComposerInputGuard.resetInputHeight(taskInput)
        isUserReadingUpwards = false
        syncComposerState()
        updateWorkspaceSubtitle()
        scrollChatToBottom(force = true)
        updateStatus("Started a new conversation.")
    }

    internal fun loadSession(id: String) {
        if (isGeneratingUi || engineManager.isGenerationActive) {
            updateStatus("Stop active generation before switching conversations.")
            return
        }
        val s = sessionStore.getSession(id) ?: return
        currentSession = s
        // A different chat must not inherit the previous chat's KV cache.
        engineManager.invalidateConversation("session switched")
        maybeShowRefineAffordance = false
        currentCitations = emptyList()
        activeAssistantHandle = null
        latestAssistantText = ""
        answerIsComplete = false
        chatThreadContainer.removeAllViews()

        val lastAssistantIndex = s.messages.indexOfLast { it.isAssistant }
        val sessionCallbacks = buildCallbacksForAnswer { latestAssistantText }
        for ((index, msg) in s.messages.withIndex()) {
            when (msg.role) {
                ChatMessage.Role.USER -> {
                    val card = ChatMessageCardBuilder.buildUserCard(this, msg, palette, sessionCallbacks)
                    chatThreadContainer.addView(card, marginBottom(10))
                }
                ChatMessage.Role.ASSISTANT -> {
                    val isLatest = (index == lastAssistantIndex)
                    val handle = ChatMessageCardBuilder.buildAssistantCard(
                        context = this,
                        message = msg,
                        palette = palette,
                        isLatest = isLatest,
                        callbacks = if (isLatest) {
                            sessionCallbacks
                        } else {
                            buildCallbacksForAnswer { msg.content }
                        },
                    )
                    chatThreadContainer.addView(handle.rootCard, marginBottom(12))
                    if (isLatest) {
                        activeAssistantHandle = handle
                        latestAssistantText = msg.content
                        answerIsComplete = msg.content.isNotBlank()
                        if (answerIsComplete) {
                            val lastUser = s.messages.lastOrNull { it.isUser }?.content.orEmpty()
                            handle.bindFollowUpChips(FollowUpSuggestionGenerator.suggestFollowUps(lastUser, msg.content))
                        }
                    }
                }
                ChatMessage.Role.SYSTEM -> Unit
            }
        }

        lastPrompt = s.messages.lastOrNull { it.isUser }?.content.orEmpty()
        lastRawPrompt = lastPrompt
        taskInput.setText("")
        ComposerInputGuard.resetInputHeight(taskInput)
        isUserReadingUpwards = false
        syncComposerState()
        updateWorkspaceSubtitle()
        scrollChatToBottom(force = true)
        updateStatus("Loaded: ${s.title}")
    }

    override fun onResume() {
        super.onResume()
        isActivityInForeground = true
        if (biometricLockManager.shouldLockOnResume()) {
            biometricLockManager.lock()
        }
        updateLockOverlay()
        if (biometricLockManager.isAppLocked) {
            promptBiometricUnlock()
        }
    }

    override fun onStop() {
        isActivityInForeground = false
        if (!isChangingConfigurations && !isAuthenticating) {
            biometricLockManager.markBackgrounded()
        }
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startDictation()
            else updateStatus("Microphone permission not granted. You can still type or attach text.")
        } else if (requestCode == REQUEST_RECORD_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleInAppRecording()
            else updateStatus("Microphone permission not granted for in-app audio recording.")
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.setLanguage(Locale.getDefault())
    }

    internal fun applyViewModelState(state: MainUiState) {
        if (::taskInput.isInitialized && taskInput.text.toString() != state.taskInput) {
            taskInput.setText(state.taskInput)
        }
    }

    internal fun updateStatus(message: String) {
        if (::statusText.isInitialized) statusText.text = message
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            !isGeneratingUi && !engineManager.isGenerationActive) {
            // Under memory pressure, drop warm conversation KV state first while keeping weights loaded.
            engineManager.invalidateConversation("trim memory level $level")
        }
        val shouldUnloadWeights = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
            (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && !isActivityInForeground)
        if (shouldUnloadWeights) {
            if (!isGeneratingUi && !engineManager.isGenerationActive) {
                inferenceExecutor.execute {
                    if (!isGeneratingUi && !engineManager.isGenerationActive) {
                        engineManager.close()
                        mainHandler.post {
                            if (!isDestroyed) {
                                refreshModelStatus()
                                updateStatus("Model unloaded in background to free memory. Will reload on next request.")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (!isGeneratingUi && !engineManager.isGenerationActive) {
            engineManager.invalidateConversation("low memory")
            inferenceExecutor.execute {
                if (!isGeneratingUi && !engineManager.isGenerationActive) {
                    engineManager.close()
                    mainHandler.post {
                        if (!isDestroyed) {
                            refreshModelStatus()
                        }
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::palette.isInitialized) {
            applyWindowBars(this, palette)
        }
        window?.decorView?.requestApplyInsets()
        if (::chatScroll.isInitialized && !isUserReadingUpwards) {
            scrollChatToBottom()
        }
    }

    override fun onDestroy() {
        slideDrawer.destroy()
        thinkingPulseAnimator.stop()
        recordingPulseAnimator.stop()
        generationTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        generationTimerRunnable = null
        idleUnloadRunnable?.let { mainHandler.removeCallbacks(it) }
        idleUnloadRunnable = null
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        duplexController.stopSession()
        inAppAudioRecorder.cancelRecording()
        isGeneratingCancelled.set(true)
        streamingRenderer.cancel()
        engineManager.cancelActiveConversation()
        activeStreamLatch?.countDown()
        // Close native LiteRT-LM and Whisper contexts serially on inferenceExecutor.
        inferenceExecutor.execute {
            engineManager.close()
            val context = whisperContext
            whisperContext = 0L
            if (context != 0L) WhisperNative.release(context)
        }
        inferenceExecutor.shutdown()
        ioExecutor.shutdown()
        super.onDestroy()
    }
}
