package com.example.pocketagent

import android.Manifest
import android.app.Activity
import androidx.fragment.app.FragmentActivity
import android.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
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
        private const val PICK_MODEL = 1
        private const val PICK_TEXT_FILES = 2
        private const val REQUEST_MIC = 3
        private const val PICK_PDF = 4
        private const val PICK_WHISPER_MODEL = 5
        private const val PICK_WAV = 6
        private const val CREATE_ANSWER_FILE = 7
        private const val REQUEST_RECORD_MIC = 8
        private const val STATE_PENDING_ANSWER_SAVE = "pending_answer_to_save"
        private const val STATE_ACTIVE_SESSION_ID = "active_session_id"
        private const val PREFS_SETTINGS = "pocketagent_settings"
        private const val PREF_BACKEND_MODE = "pref_backend_mode"
        private const val PREF_MODEL_DISPLAY_NAME = "pref_model_display_name"
        private const val PREF_GPU_LOADING_GUARD = "pref_gpu_loading_crash_guard"
        private const val MAX_ATTACHMENT_CHARS = MainUiState.MAX_ATTACHMENT_CHARS
        private const val MAX_RESPONSE_TOKENS = MainUiState.MAX_RESPONSE_TOKENS
        /** Hard ceiling so a stalled engine cannot block the worker thread forever. */
        private const val GENERATION_TIMEOUT_SECONDS = 180L
        /** Skip eager startup preload above 1.5 GB so multi-GB models (e.g. LFM3-Omni 3.5 GB) don't OOM idle launches. */
        private const val EAGER_WARMUP_MAX_BYTES = 1_500_000_000L
        /** Reset stateful KV cache before cumulative turns approach the 4096-token EngineConfig cap. */
        private const val WARM_CONTEXT_RESET_THRESHOLD = 2800
        private const val IDLE_ENGINE_UNLOAD_MS = 120_000L
        private val SYSTEM_INSTRUCTION: String get() = AnswerDirective.SYSTEM_INSTRUCTION
        private var BG = 0xFFF4F7F6.toInt()
        private var INK = 0xFF162D2A.toInt()
        private var MUTED = 0xFF5F6F6A.toInt()
        private var TEAL = 0xFF126B60.toInt()
        private var MINT = 0xFFE2F3ED.toInt()
        private var BORDER = 0xFFE3EBE8.toInt()
        private var SURFACE = Color.WHITE
        private var STOP_CRIMSON = 0xFFB91C1C.toInt()
        private var isDarkTheme = false

        private fun applyTheme(dark: Boolean) {
            isDarkTheme = dark
            if (dark) {
                BG = 0xFF121816.toInt()
                INK = 0xFFE8F0EE.toInt()
                MUTED = 0xFF9FB0AB.toInt()
                TEAL = 0xFF4DB6A6.toInt()
                MINT = 0xFF1E332F.toInt()
                BORDER = 0xFF2A3D39.toInt()
                SURFACE = 0xFF1A2421.toInt()
                STOP_CRIMSON = 0xFFEF5350.toInt()
            } else {
                BG = 0xFFF4F7F6.toInt()
                INK = 0xFF162D2A.toInt()
                MUTED = 0xFF5F6F6A.toInt()
                TEAL = 0xFF126B60.toInt()
                MINT = 0xFFE2F3ED.toInt()
                BORDER = 0xFFE3EBE8.toInt()
                SURFACE = Color.WHITE
                STOP_CRIMSON = 0xFFB91C1C.toInt()
            }
        }
    }

    private lateinit var palette: UiPalette

    /** Serial executor for LiteRT-LM load and inference: engine access must never overlap. */
    private val inferenceExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PocketAgentInference").apply { isDaemon = true }
    }

    /** Parallel executor for PDF extraction, Whisper, session I/O, and Markdown formatting. */
    private val ioExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "PocketAgentIO").apply { isDaemon = true }
    }

    /** Throttled plain-text streaming; formats once on completion, off the UI thread. */
    private val streamingRenderer by lazy {
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineManager = InferenceEngineManager<Engine>()
    private val promptContext = AnswerPromptComposer()
    private val inAppAudioRecorder = InAppAudioRecorder()
    @Volatile private var whisperContext: Long = 0L
    private lateinit var taskInput: EditText
    private lateinit var statusText: TextView
    private lateinit var attachmentText: TextView
    private lateinit var attachmentPreview: TextView
    private lateinit var attachmentPreviewButton: Button
    private lateinit var whisperModelText: TextView
    private lateinit var recordAudioButton: Button
    private lateinit var transcribeWavButton: Button
    private lateinit var confirmTranscriptButton: Button
    private lateinit var suggestButton: Button
    private lateinit var attachmentCard: LinearLayout
    // Sticky bottom composer, multi-turn chat thread, and collapsible chrome.
    private lateinit var chatScroll: ScrollView
    private lateinit var chatCanvas: LinearLayout

    // Legacy output-card views (kept for compatibility with older UI paths)
    private lateinit var outputCard: LinearLayout
    private lateinit var stopGeneratingButton: android.widget.Button
    private lateinit var citationsContainer: LinearLayout
    private lateinit var systemActionContainer: LinearLayout
    private lateinit var refineButton: android.widget.Button
    private lateinit var chatThreadContainer: LinearLayout
    private lateinit var jumpToBottomButton: Button
    private lateinit var heroCard: LinearLayout
    private lateinit var heroModelCtaButton: Button
    private lateinit var quickChips: View
    private lateinit var composerDock: LinearLayout
    private lateinit var composerCard: LinearLayout
    private lateinit var composerRoot: LinearLayout
    private lateinit var toolsTray: LinearLayout
    private lateinit var toolsToggleButton: Button
    private lateinit var attachmentChipContainer: LinearLayout
    private lateinit var recordingBanner: LinearLayout
    private lateinit var recordingDot: View
    private lateinit var recordingBannerText: TextView
    private lateinit var recordingStopButton: Button
    private lateinit var composerMicButton: Button
    private lateinit var modelStatusPill: ModelStatusPillView
    private var isToolsTrayOpen = false
    private var lastAttachmentStripSignature: String? = null
    /** True while the user is reading earlier output; suppresses forced auto-scroll. */
    @Volatile private var isUserReadingUpwards = false
    private var recognizer: SpeechRecognizer? = null
    private var isDictating = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var attachedContext = ""
    @Volatile private var attachmentGeneration = 0L
    @Volatile private var isWhisperTranscribing = false
    @Volatile private var transcriptReviewPending = false
    private var attachedNames = mutableListOf<String>()
    private var attachmentPreviewExpanded = false
    private var answerIsComplete = false
    private var latestAssistantText: String = ""
    private var activeAssistantHandle: ActiveAssistantCardHandle? = null
    private val thinkingPulseAnimator = PulseAnimator()
    private val recordingPulseAnimator = PulseAnimator()
    private var generationStartElapsedMs: Long = 0L
    private var generationTimerRunnable: Runnable? = null
    private var idleUnloadRunnable: Runnable? = null
    private var pendingAnswerToSave: String? = null
    private val sessionStore by lazy { SessionStore(SessionStore.defaultDir(filesDir)) }
    private val userMemoryStore by lazy { UserMemoryStore(UserMemoryStore.defaultDir(filesDir)) }
    private val notesStore by lazy { PersonalNotesStore(PersonalNotesStore.defaultDir(filesDir)) }
    private val ttsSpeaker by lazy {
        AndroidTtsSpeaker(
            ttsProvider = { tts },
            isReadyProvider = { ttsReady }
        )
    }
    private val settingsPrefs by lazy {
        getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
    }
    private val duplexController by lazy {
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
    private val biometricLockManager by lazy {
        BiometricLockManager(
            preferences = getSharedPreferences("pocketagent_security", MODE_PRIVATE),
        )
    }
    private lateinit var lockOverlay: LinearLayout
    private var isAuthenticating = false
    private var isMemoryEnabled = true
    private var currentSession: ConversationSession? = null
    private var lastPrompt: String = ""
    @Volatile private var currentCitations = emptyList<LocalDocumentRetriever.DocumentCitation>()
    private val isGeneratingCancelled = AtomicBoolean(false)

    /** Set by the runaway guard so the UI can explain why generation stopped. */
    @Volatile private var runawayFragment: String? = null
    /** True while the primary composer button is acting as Stop. */
    @Volatile private var isGeneratingUi = false
    /** True after the user tapped Stop while waiting for native cancellation to complete. */
    @Volatile private var isStoppingUi = false
    /** Set when the model dodged the question, so Refine can be offered instead of auto-retrying. */
    @Volatile private var maybeShowRefineAffordance = false
    /** Latch of the in-flight stream, so Stop can release a waiter the engine may never notify. */
    @Volatile private var activeStreamLatch: CountDownLatch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        applyTheme(nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        pendingAnswerToSave = savedInstanceState?.getString(STATE_PENDING_ANSWER_SAVE)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        var flags = window.decorView.systemUiVisibility
        flags = if (isDarkTheme) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
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
        engineManager.onFallback = { reason ->
            settingsPrefs.edit().putString(PREF_BACKEND_MODE, BackendPreference.CPU_ONLY.name).apply()
            mainHandler.post {
                refreshModelStatus()
                updateStatus(reason)
            }
        }

        cleanupStagedInputFiles()
        buildInterface()
        savedInstanceState?.getString(STATE_ACTIVE_SESSION_ID)?.let { loadSession(it) }
        refreshModelStatus()
        if (gpuGuardTriggered) {
            updateStatus("Previous GPU model load ended unexpectedly; switched safely to CPU mode.")
        }
        restoreWhisperModel()
        warmUpModelAfterFirstFrame()
    }

    private fun isDeviceLowMemory(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.lowMemory
    }

    /** Initializes the LiteRT-LM engine with a persistent crash-loop guard around GPU loads. */
    private fun ensureEngineInitialized(modelFile: File): Engine {
        val tryingGpu = engineManager.preference == BackendPreference.GPU_PREFERRED &&
            engineManager.engine == null
        if (tryingGpu) {
            settingsPrefs.edit().putBoolean(PREF_GPU_LOADING_GUARD, true).commit()
        }
        return try {
            engineManager.getOrInitializeEngine(modelFile, cacheDir)
        } finally {
            if (tryingGpu) {
                settingsPrefs.edit().putBoolean(PREF_GPU_LOADING_GUARD, false).apply()
            }
        }
    }

    /**
     * Loads small models in the background as soon as the UI is on screen.
     *
     * Multi-GB models (e.g. LFM3-Omni 3.5 GB) are loaded on demand on the first prompt to avoid
     * idle startup RAM pressure or OOM when the user only wants History, Notes, or Privacy tools.
     */
    private fun warmUpModelAfterFirstFrame() {
        val modelFile = File(filesDir, "model.litertlm")
        if (!modelFile.exists() || modelFile.length() <= 0) return
        if (modelFile.length() > EAGER_WARMUP_MAX_BYTES || isDeviceLowMemory()) {
            val storedName = settingsPrefs.getString(PREF_MODEL_DISPLAY_NAME, null)
            val label = storedName ?: formatStorageSize(modelFile.length())
            updateStatus("Model ready ($label). Loads into RAM on your first question.")
            return
        }
        window.decorView.post {
            inferenceExecutor.execute {
                try {
                    ensureEngineInitialized(modelFile)
                    mainHandler.post { refreshModelStatus() }
                } catch (_: Exception) {
                    // A failed warm-up is not user-visible; the real attempt reports properly.
                }
            }
        }
    }

    private fun buildInterface() {
        // Three zones stacked in a full-height column: a slim pinned header, a scrolling chat
        // canvas, and a sticky bottom composer that never scrolls away from the thumb.
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.bg)
            fitsSystemWindows = true
        }
        applyImeInsets(rootLayout)

        // ---------------------------------------------------------------- Zone A: pinned header
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(8), dp(6))
        }
        val mark = TextView(this).apply {
            text = "P"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = shape(palette.teal, 12)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        }
        brandRow.addView(mark)
        val brand = column().apply {
            setPadding(dp(8), 0, 0, 0)
            addView(text("PocketAgent", 16f, palette.ink, true))
        }
        brandRow.addView(brand, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })

        modelStatusPill = ModelStatusPillView(this, palette) {
            val stored = File(filesDir, "model.litertlm")
            if (stored.exists() && stored.length() > 0L) showModelManagerDialog() else chooseModel()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(6)
            }
        }
        brandRow.addView(modelStatusPill)

        val headerToolsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val headerToolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerSpecs = listOf(
            Triple("+ New", "Start a fresh conversation") { startNewConversation() },
            Triple("☰ Chats", "View saved conversations") { showHistoryDialog() },
            Triple("🎙️ Voice", "Start continuous hands-free voice duplex mode") { showDuplexVoiceDialog() },
            Triple("📝 Notes", "Manage sovereign personal notes") { showNotesManagerDialog() },
            Triple("🧠 Memory", "Manage sovereign long-term memory") { showMemoryCenterDialog() },
            Triple("🛡️ Privacy", "View privacy audit, appearance, and security controls") { showPrivacyCenterDialog() },
        )
        for ((index, spec) in headerSpecs.withIndex()) {
            val (label, a11y, action) = spec
            val btn = com.example.pocketagent.pillButton(
                context = this,
                palette = palette,
                label = label,
                accessibilityLabel = a11y,
                compact = true,
                onClick = action,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (index < headerSpecs.lastIndex) marginEnd = dp(6)
                }
            }
            headerToolsRow.addView(btn)
        }
        headerToolsScroll.addView(headerToolsRow)
        brandRow.addView(headerToolsScroll, LinearLayout.LayoutParams(0, -2, 1f))
        rootLayout.addView(brandRow, LinearLayout.LayoutParams(-1, -2))

        // ------------------------------------------------- Zone B: scrolling chat & workspace
        chatCanvas = column().apply {
            setPadding(dp(14), dp(4), dp(14), dp(12))
        }

        // Modernized welcome block, shown only while the conversation is empty.
        heroCard = column().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(palette.heroGradientStart, palette.heroGradientEnd),
            ).apply { cornerRadius = dp(20).toFloat() }
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val trustBadge = styledText(
            context = this,
            text = "🔒 100% Offline · Zero Cloud",
            sizeSp = 11.5f,
            color = Color.WHITE,
            bold = true,
        ).apply {
            background = roundedShape(0x29FFFFFF, dp(12).toFloat())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                bottomMargin = dp(10)
            }
        }
        heroCard.addView(trustBadge)
        heroCard.addView(text("Your pocket assistant", 21f, Color.WHITE, true))
        heroCard.addView(
            text(
                "Private on-device AI. Ask anything, analyze PDFs, or speak hands-free with zero cloud.",
                13.5f,
                palette.heroSubtitleInk,
            ).apply {
                setPadding(0, dp(6), 0, 0)
                setLineSpacing(dp(3).toFloat(), 1f)
            }
        )
        heroModelCtaButton = Button(this).apply {
            text = "📂 Choose .litertlm Model"
            contentDescription = "Choose a LiteRT-LM model file to get started"
            setAllCaps(false)
            textSize = 13.5f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rippleDrawable(
                roundedShape(
                    color = 0x2EFFFFFF,
                    radiusPx = dp(16).toFloat(),
                    strokeWidthPx = maxOf(1, dp(1)),
                    strokeColor = 0x55FFFFFF,
                ),
                palette.rippleOnAccent,
            )
            backgroundTintList = null
            stateListAnimator = null
            isFocusable = true
            setOnClickListener { chooseModel() }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = dp(12)
            }
        }
        heroCard.addView(heroModelCtaButton)
        chatCanvas.addView(heroCard, marginBottom(12))

        // Quick prompt chips collapse once the conversation has output.
        val quickScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Summarize a PDF", "Explain a concept", "Draft a message", "Plan my day").forEach { label ->
            val chip = secondaryOutlineButton(
                context = this,
                palette = palette,
                label = label,
                accessibilityLabel = "Use prompt starter: $label",
                compact = true,
                textColor = palette.teal,
            ) {
                taskInput.setText(label)
                taskInput.setSelection(label.length)
                taskInput.requestFocus()
                syncComposerState()
            }
            quickRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        quickScroll.addView(quickRow)
        quickChips = quickScroll
        chatCanvas.addView(quickScroll, marginBottom(10))

        outputCard = column().apply {
            visibility = View.GONE
            background = shape(SURFACE, 20, BORDER)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        val outputHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val outputTitle = column()
        outputTitle.addView(text("Answer", 18f, INK, true))
        outputTitle.addView(text("Generated locally · runs offline", 11f, MUTED).apply { setPadding(0, dp(3), 0, 0) })
        outputHeader.addView(outputTitle, LinearLayout.LayoutParams(0, -2, 1f))
        outputHeader.addView(textButton("Clear", accessibilityLabel = "Clear answer") {
            outputCard.visibility = View.GONE
            answerText.text = ""
            answerIsComplete = false
            citationsContainer.removeAllViews()
            citationsContainer.visibility = View.GONE
            currentCitations = emptyList()
            systemActionContainer.removeAllViews()
            systemActionContainer.visibility = View.GONE
            maybeShowRefineAffordance = false
            if (::refineButton.isInitialized) refineButton.visibility = View.GONE
            syncComposerState()
        })
        outputCard.addView(outputHeader)
        stopGeneratingButton = pillButton("■ Stop Generating", accessibilityLabel = "Stop in-progress generation") {
            isGeneratingCancelled.set(true)
            suggestButton.isEnabled = true
            updateStatus("Stopping generation…")
        }.apply {
            visibility = View.GONE
            background = shape(0xFFC62828.toInt(), 18)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(6) }
        }
        outputCard.addView(stopGeneratingButton)

        chatCanvas.addView(
            text(
                "Your selected model and prompts stay in this app. Attached text is included only in the local prompt.",
                11f,
                palette.muted,
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(4))
            }
        )

        chatScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
            addView(chatCanvas)
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                // Track whether the reader is parked at the bottom, so streaming output only
                // auto-scrolls when they have not deliberately scrolled up.
                isUserReadingUpwards = scrollY + height < (chatCanvas.height + paddingTop + paddingBottom - dp(24))
                updateJumpToBottomVisibility()
            }
        }

        jumpToBottomButton = ComposerChromeViews.buildJumpToBottomButton(this, palette) {
            isUserReadingUpwards = false
            scrollChatToBottom(force = true)
            updateJumpToBottomVisibility()
        }

        val chatFrame = FrameLayout(this).apply {
            addView(chatScroll, FrameLayout.LayoutParams(-1, -1))
            addView(
                jumpToBottomButton,
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(0, 0, dp(16), dp(16))
                },
            )
        }
        rootLayout.addView(chatFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        // ------------------------------------------------- Zone C: sticky bottom composer dock
        composerDock = column().apply {
            background = shape(palette.bg, 0)
            setPadding(dp(12), dp(6), dp(12), dp(8))
            // Absorb bottom system-navigation insets when the keyboard is closed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setOnApplyWindowInsetsListener { view, insets ->
                    val bottom = insets.systemWindowInsetBottom
                    view.setPadding(dp(12), dp(6), dp(12), dp(8) + bottom)
                    insets
                }
            }
        }
        composerCard = column().apply {
            background = shape(SURFACE, 20, BORDER)
            elevation = dp(6).toFloat()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        attachmentChipContainer = column().apply {
            visibility = View.GONE
        }
        composerCard.addView(attachmentChipContainer, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        toolsTray = column().apply {
            visibility = View.GONE
            setPadding(0, dp(2), 0, dp(6))
        }
        val trayTextRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        trayTextRow.addView(
            pillButton("＋ Text", accessibilityLabel = "Attach text files", compact = true) { chooseTextFiles() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) },
        )
        trayTextRow.addView(
            pillButton("＋ PDF", accessibilityLabel = "Attach a PDF document", compact = true) { choosePdf() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) },
        )
        toolsTray.addView(trayTextRow)

        val audioActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recordAudioButton = pillButton("🎙  Record audio", accessibilityLabel = "Record audio for local Whisper transcription", compact = true) {
            toggleInAppRecording()
        }.apply { isEnabled = false }
        audioActionsRow.addView(recordAudioButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })

        transcribeWavButton = pillButton("Transcribe WAV", accessibilityLabel = "Choose a WAV file for local transcription", compact = true) {
            chooseWav()
        }.apply { isEnabled = false }
        audioActionsRow.addView(transcribeWavButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
        toolsTray.addView(audioActionsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        // Whisper model management lives here instead of cluttering the chat canvas.
        whisperModelText = text("No local Whisper model selected.", 11f, palette.muted).apply {
            setPadding(dp(4), dp(9), dp(4), dp(2))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        toolsTray.addView(whisperModelText)
        val whisperRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        whisperRow.addView(
            pillButton("Choose Whisper model", accessibilityLabel = "Choose a local whisper.cpp ggml model", compact = true) {
                chooseWhisperModel()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) },
        )
        whisperRow.addView(
            dangerButton(this, palette, "Remove", accessibilityLabel = "Remove the locally stored Whisper model", compact = true) {
                removeWhisperModel()
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5) },
        )
        toolsTray.addView(whisperRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        // Compact attachment strip: summary plus preview and transcript review controls.
        attachmentCard = column().apply {
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        attachmentText = text("", 12f, palette.muted)
        attachmentCard.addView(attachmentText)
        attachmentPreview = text("", 12f, palette.ink).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = shape(palette.bg, 10, palette.border)
            setTextIsSelectable(true)
            maxLines = 8
            ellipsize = TextUtils.TruncateAt.END
        }
        attachmentCard.addView(attachmentPreview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        attachmentPreviewButton = textButton("Show full attachment text", accessibilityLabel = "Expand attachment text preview") {
            toggleAttachmentPreview()
        }
        attachmentCard.addView(attachmentPreviewButton)
        confirmTranscriptButton = textButton("Use reviewed transcript", accessibilityLabel = "Confirm transcript after reviewing it") {
            if (transcriptReviewPending && !isWhisperTranscribing) {
                transcriptReviewPending = false
                confirmTranscriptButton.visibility = View.GONE
                syncComposerState()
                updateStatus("Transcript confirmed. Ask your question when ready.")
            }
        }.apply { visibility = View.GONE }
        attachmentCard.addView(confirmTranscriptButton)
        attachmentCard.addView(textButton("Remove attachment", accessibilityLabel = "Remove attached files and extracted text") { clearAttachments() })
        toolsTray.addView(attachmentCard)

        composerCard.addView(toolsTray)

        // Recording banner shown while in-app audio recording or dictation is active.
        recordingBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = roundedShape(
                color = palette.dangerBg,
                radiusPx = dp(12).toFloat(),
                strokeWidthPx = maxOf(1, dp(1)),
                strokeColor = palette.dangerBorder,
            )
            setPadding(dp(12), dp(6), dp(8), dp(6))
        }
        recordingDot = View(this).apply {
            val dotSize = dp(10)
            background = roundedShape(palette.stopCrimson, dotSize / 2f)
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = dp(8)
            }
        }
        recordingBanner.addView(recordingDot)
        recordingBannerText = styledText(
            context = this,
            text = "● Recording audio… Tap Stop",
            sizeSp = 12.5f,
            color = palette.dangerInk,
            bold = true,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        recordingBanner.addView(recordingBannerText)
        recordingStopButton = dangerButton(
            context = this,
            palette = palette,
            label = "Stop",
            accessibilityLabel = "Stop audio recording",
            compact = true,
        ) {
            if (inAppAudioRecorder.isRecordingActive) {
                recordAudioButton.text = "Transcribing…"
                recordAudioButton.isEnabled = false
                inAppAudioRecorder.stopRecording()
            } else if (isDictating) {
                recognizer?.stopListening()
                isDictating = false
                updateStatus("Stopping dictation…")
                updateRecordingBanner()
            }
        }
        recordingBanner.addView(recordingStopButton)
        composerCard.addView(recordingBanner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Main composer row: always visible, always thumb-reachable.
        composerRoot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        toolsToggleButton = Button(this).apply {
            val touchSize = dp(48)
            text = "＋"
            contentDescription = "Show attachment and audio tools"
            setAllCaps(false)
            textSize = 19f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(palette.teal)
            setPadding(0, 0, 0, 0)
            minWidth = touchSize
            minimumWidth = touchSize
            minHeight = touchSize
            minimumHeight = touchSize
            background = rippleDrawable(roundedShape(palette.mint, dp(24).toFloat()), palette.rippleOnMint)
            backgroundTintList = null
            stateListAnimator = null
            isFocusable = true
            setOnClickListener { toggleToolsTray(force = false) }
        }
        composerRoot.addView(toolsToggleButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginEnd = dp(7)
        })

        taskInput = EditText(this).apply {
            id = View.generateViewId()
            hint = "Ask PocketAgent..."
            textSize = 15f
            gravity = Gravity.TOP or Gravity.START
            styleEditText(this, palette, cornerDp = 18)
            // Line limits, height cap, and the scroll-intercept guard come from ComposerInputGuard.
            // Note: never set isSingleLine here, it resets maxLines to Int.MAX_VALUE.
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
            setOnEditorActionListener { _, actionId, event ->
                val sends = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                    (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                        event.action == android.view.KeyEvent.ACTION_DOWN && !event.isShiftPressed)
                if (sends && !isGeneratingUi && suggestButton.isEnabled) {
                    suggestSteps()
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && isToolsTrayOpen) toggleToolsTray(force = false)
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    isUserReadingUpwards = false
                    syncComposerState()
                }
            })
        }
        composerRoot.addView(taskInput, LinearLayout.LayoutParams(0, -2, 1f))
        ComposerInputGuard.configureBoundedMultilineInput(
            editText = taskInput,
            maxLines = ComposerInputGuard.DEFAULT_MAX_LINES,
            maxHeightPx = dp(ComposerInputGuard.DEFAULT_MAX_HEIGHT_DP),
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET,
        )

        composerMicButton = Button(this).apply {
            val touchSize = dp(48)
            text = "🎙"
            contentDescription = "Dictate your question"
            setAllCaps(false)
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(palette.teal)
            setPadding(0, 0, 0, 0)
            minWidth = touchSize
            minimumWidth = touchSize
            minHeight = touchSize
            minimumHeight = touchSize
            background = rippleDrawable(roundedShape(palette.mint, dp(24).toFloat()), palette.rippleOnMint)
            backgroundTintList = null
            stateListAnimator = null
            isFocusable = true
            setOnClickListener { startDictation() }
        }
        composerRoot.addView(composerMicButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginStart = dp(7)
        })

        suggestButton = actionButton("Ask", accessibilityLabel = "Send your question and get an answer") {
            suggestSteps()
        }.apply {
            textSize = 15f
            minHeight = dp(48)
            minimumHeight = dp(48)
            minWidth = dp(64)
            minimumWidth = dp(64)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        composerRoot.addView(suggestButton, LinearLayout.LayoutParams(-2, dp(48)).apply {
            marginStart = dp(7)
        })

        composerCard.addView(composerRoot)
        composerDock.addView(composerCard, LinearLayout.LayoutParams(-1, -2))

        statusText = text(
            "Choose a compatible .litertlm model to get started.",
            11f,
            palette.muted,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(7), dp(6), dp(0))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        composerDock.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        rootLayout.addView(composerDock, LinearLayout.LayoutParams(-1, -2))

        lockOverlay = buildLockOverlay()
        val container = FrameLayout(this).apply {
            addView(rootLayout, FrameLayout.LayoutParams(-1, -1))
            addView(lockOverlay, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(container)
        updateLockOverlay()
        syncComposerState()
    }

    /**
     * Keeps the pinned composer above the soft keyboard on API 28-35.
     *
     * `adjustResize` shrinks the window by the keyboard height, which already lifts the composer on
     * standard devices. The residual inset is the part of the keyboard that the resize did *not*
     * cover (edge-to-edge and some gesture-navigation configurations), and only that leftover is
     * turned into bottom padding, so the composer is never pushed up twice.
     */
    private fun applyImeInsets(rootLayout: LinearLayout) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        rootLayout.setOnApplyWindowInsetsListener(
            ComposerInputGuard.applySafeComposerInsets(
                imeBottomPx = { rawInsets ->
                    @Suppress("UNCHECKED_CAST")
                    val insets = rawInsets as android.view.WindowInsets
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                    } else {
                        @Suppress("DEPRECATION")
                        insets.systemWindowInsetBottom
                    }
                },
            )
        )
    }

    /** Opens or closes the `+` tools tray. */
    private fun toggleToolsTray(force: Boolean) {
        val open = if (force) true else !isToolsTrayOpen
        isToolsTrayOpen = open
        toolsTray.visibility = if (open) View.VISIBLE else View.GONE
        toolsToggleButton.text = if (open) "×" else "＋"
        toolsToggleButton.contentDescription =
            if (open) "Hide attachment and audio tools" else "Show attachment and audio tools"
        // The tray opens through the attachment chip's Edit action too, which must reveal the
        // preview controls rather than the collapsed state.
        if (open) attachmentCard.visibility = if (attachedContext.isNotBlank()) View.VISIBLE else View.GONE
        syncComposerState()
    }

    /**
     * Synchronises composer chrome with the current conversation and attachment state.
     *
     * Called wherever a view used to be toggled individually, so the welcome block, quick chips,
     * attachment pill, and busy state can never drift out of sync with each other.
     */
    private fun syncComposerState() {
        if (!::heroCard.isInitialized) return
        val hasThreadViews = ::chatThreadContainer.isInitialized && chatThreadContainer.childCount > 0
        val hasConversation = hasThreadViews ||
            taskInput.text.isNotBlank() || attachedContext.isNotBlank() ||
            lastPrompt.isNotBlank() || currentSession?.messages?.isNotEmpty() == true
        val welcomeVisibility = if (hasConversation) View.GONE else View.VISIBLE
        heroCard.visibility = welcomeVisibility
        quickChips.visibility = welcomeVisibility

        if (::heroModelCtaButton.isInitialized) {
            val hasModel = File(filesDir, "model.litertlm").let { it.exists() && it.length() > 0L }
            heroModelCtaButton.visibility = if (hasModel) View.GONE else View.VISIBLE
        }

        val hasAttachment = attachedContext.isNotBlank()
        if (::attachmentChipContainer.isInitialized) {
            if (hasAttachment) {
                val signature = attachedNames.joinToString("|") + ":" + attachedContext.length
                attachmentChipContainer.visibility = View.VISIBLE
                if (signature != lastAttachmentStripSignature) {
                    lastAttachmentStripSignature = signature
                    attachmentChipContainer.removeAllViews()
                    attachmentChipContainer.addView(
                        ComposerChromeViews.buildAttachmentChipsStrip(
                            context = this,
                            palette = palette,
                            names = attachedNames,
                            usedChars = attachedContext.length,
                            maxChars = MAX_ATTACHMENT_CHARS,
                            onInspect = { toggleToolsTray(force = true) },
                            onClear = { clearAttachments() },
                        )
                    )
                }
            } else {
                lastAttachmentStripSignature = null
                attachmentChipContainer.removeAllViews()
                attachmentChipContainer.visibility = View.GONE
            }
        }
        if (!hasAttachment && ::attachmentCard.isInitialized) {
            attachmentCard.visibility = View.GONE
        }

        // Badge the + button whenever there is hidden work sitting behind it.
        toolsToggleButton.contentDescription = when {
            isToolsTrayOpen -> "Hide attachment and audio tools"
            hasAttachment -> "Show attachment and audio tools (attachment active)"
            else -> "Show attachment and audio tools"
        }
        if (::recordAudioButton.isInitialized) {
            recordAudioButton.isEnabled = whisperContext != 0L && !isWhisperTranscribing
        }
        if (::transcribeWavButton.isInitialized) {
            transcribeWavButton.isEnabled = whisperContext != 0L && !isWhisperTranscribing
        }
        if (::suggestButton.isInitialized) {
            val canSend = (taskInput.text.isNotBlank() || attachedContext.isNotBlank()) &&
                !isWhisperTranscribing && !transcriptReviewPending
            suggestButton.isEnabled = (isGeneratingUi && !isStoppingUi) || (!isGeneratingUi && canSend)
            suggestButton.alpha = if (suggestButton.isEnabled) 1f else 0.5f
        }
        updateRecordingBanner()
        updateJumpToBottomVisibility()
    }

    /** Updates the floating Jump-to-Bottom button based on scroll position and thread content. */
    private fun updateJumpToBottomVisibility() {
        if (!::jumpToBottomButton.isInitialized || !::chatScroll.isInitialized) return
        val hasContent = ::chatThreadContainer.isInitialized && chatThreadContainer.childCount > 0
        val show = hasContent && isUserReadingUpwards && chatScroll.canScrollVertically(1)
        jumpToBottomButton.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** Updates the visible recording banner and pulsing indicator inside the composer card. */
    private fun updateRecordingBanner() {
        if (!::recordingBanner.isInitialized) return
        val isRec = inAppAudioRecorder.isRecordingActive
        if (isRec || isDictating) {
            recordingBannerText.text = if (isRec) {
                "● Recording audio… Tap Stop"
            } else {
                "● Listening for dictation… Tap Stop"
            }
            if (recordingBanner.visibility != View.VISIBLE) {
                recordingBanner.visibility = View.VISIBLE
                recordingPulseAnimator.start(recordingDot)
            }
        } else {
            recordingPulseAnimator.stop()
            recordingBanner.visibility = View.GONE
        }
    }

    /** Scrolls the chat canvas to the newest content unless the reader scrolled up. */
    private fun scrollChatToBottom(force: Boolean = false) {
        if (!::chatScroll.isInitialized) return
        if (!force && isUserReadingUpwards) return
        chatScroll.post {
            if (!force && isUserReadingUpwards) return@post
            chatScroll.fullScroll(View.FOCUS_DOWN)
            updateJumpToBottomVisibility()
        }
    }

    private fun chooseModel() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startActivityForResult(picker, PICK_MODEL)
    }

    private fun chooseTextFiles() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/markdown", "text/csv"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(picker, PICK_TEXT_FILES)
    }

    private fun choosePdf() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
        startActivityForResult(picker, PICK_PDF)
    }

    private fun chooseWhisperModel() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/x-ggml", "application/gguf"))
        startActivityForResult(picker, PICK_WHISPER_MODEL)
    }

    private fun chooseWav() {
        if (whisperContext == 0L) {
            updateStatus("Choose a local whisper.cpp ggml .bin model first.")
            return
        }
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/wav", "audio/x-wav", "audio/vnd.wave"))
        startActivityForResult(picker, PICK_WAV)
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
        }
    }

    private fun importModel(uri: Uri) {
        if (engineManager.isGenerationActive || isGeneratingUi) {
            updateStatus("Stop the current generation before switching models.")
            return
        }
        val rawName = queryDisplayName(uri)
        if (rawName != null && !rawName.endsWith(".litertlm", ignoreCase = true)) {
            updateStatus("Choose a LiteRT-LM model file ending in .litertlm.")
            return
        }
        val cleanName = rawName?.replace(Regex("[\\r\\n]+"), " ")?.take(80)
        val expectedBytes = queryFileSize(uri)
        updateStatus("Preparing to import ${cleanName ?: "model"}…")
        suggestButton.isEnabled = false
        maybeShowRefineAffordance = false
        activeAssistantHandle?.refineButton?.visibility = View.GONE
        if (duplexController.isActive) duplexController.stopSession()

        ioExecutor.execute {
            try {
                // Close active engine on the serial inference executor before replacing files on disk.
                val engineClosed = CountDownLatch(1)
                inferenceExecutor.execute {
                    try {
                        engineManager.invalidateConversation("model switched")
                        engineManager.close()
                    } finally {
                        engineClosed.countDown()
                    }
                }
                engineClosed.await(15, TimeUnit.SECONDS)
                ModelFileStore.clearEngineCaches(cacheDir)

                val labelForProgress = cleanName ?: "model"
                mainHandler.post { updateStatus("Importing $labelForProgress into private storage…") }
                val target = contentResolver.openInputStream(uri)?.use { input ->
                    ModelFileStore(filesDir).importModel(input)
                } ?: error("Could not open the selected file.")

                settingsPrefs.edit().apply {
                    if (!cleanName.isNullOrBlank()) putString(PREF_MODEL_DISPLAY_NAME, cleanName)
                    else remove(PREF_MODEL_DISPLAY_NAME)
                    putBoolean(PREF_GPU_LOADING_GUARD, false)
                }.apply()

                mainHandler.post {
                    refreshModelStatus()
                    val backendLabel = if (engineManager.preference == BackendPreference.CPU_ONLY) "CPU" else "GPU (auto fallback)"
                    val readyLabel = cleanName ?: formatStorageSize(target.length())
                    val loadHint = if (target.length() > EAGER_WARMUP_MAX_BYTES) {
                        "Loads on your first question to conserve RAM."
                    } else {
                        "Type, dictate, or attach text/PDF."
                    }
                    updateStatus("Model ready ($readyLabel · $backendLabel). $loadHint")
                    suggestButton.isEnabled = true
                    syncComposerState()
                }
            } catch (e: Exception) {
                File(filesDir, "model.litertlm.part").delete()
                mainHandler.post {
                    refreshModelStatus()
                    updateStatus("Model import failed: ${e.message ?: "unknown error"}")
                    suggestButton.isEnabled = true
                    syncComposerState()
                }
            }
        }
    }

    private fun importTextFiles(uris: List<Uri>) {
        updateStatus("Reading selected text file${if (uris.size > 1) "s" else ""}…")
        ioExecutor.execute {
            try {
                val content = StringBuilder()
                val names = mutableListOf<String>()
                for (uri in uris) {
                    if (content.length >= MAX_ATTACHMENT_CHARS) break
                    val name = queryDisplayName(uri) ?: "Attached text"
                    val remaining = MAX_ATTACHMENT_CHARS - content.length
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader -> BoundedTextReader.read(reader, remaining) }
                        ?: error("Could not read $name")
                    names.add(name)
                    content.append("\n\n--- $name ---\n").append(text)
                }
                require(content.isNotBlank()) { "The selected files are empty." }
                attachedContext = content.toString().take(MAX_ATTACHMENT_CHARS)
                attachedNames = names
                mainHandler.post {
                    attachmentText.text = names.joinToString(" · ") + "\nAttached content limited to ${"%,d".format(MAX_ATTACHMENT_CHARS)} characters."
                    showAttachmentPreview(attachedContext)
                    attachmentCard.visibility = View.VISIBLE
                    syncComposerState()
                    updateStatus("Text attached. Review the preview, then add your question.")
                }
            } catch (e: Exception) {
                mainHandler.post { updateStatus("Could not attach text: ${e.message ?: "unknown error"}") }
            }
        }
    }

    private fun importPdf(uri: Uri) {
        updateStatus("Reading selected PDF on this device…")
        ioExecutor.execute {
            try {
                val remaining = MAX_ATTACHMENT_CHARS - attachedContext.length
                require(remaining > 0) { "Remove the current attachment to make room for PDF text." }
                val displayName = (queryDisplayName(uri) ?: "Selected PDF")
                    .replace(Regex("[\\r\\n]+"), " ")
                    .take(120)
                val sourceLabel = "\n\n--- PDF: $displayName ---\n"
                require(remaining > sourceLabel.length) {
                    "Remove the current attachment to make room for PDF text."
                }
                val input = contentResolver.openInputStream(uri)
                    ?: error("Could not open the selected PDF.")
                val extraction = PdfTextExtractor.extract(this, input, remaining - sourceLabel.length)
                attachedContext = (attachedContext + sourceLabel + extraction.text).take(MAX_ATTACHMENT_CHARS)
                attachedNames.add("$displayName (${extraction.pageCount} pages)")
                mainHandler.post {
                    attachmentText.text = attachedNames.joinToString(" · ") +
                        "\n${"%,d".format(attachedContext.length)}/${"%,d".format(MAX_ATTACHMENT_CHARS)} chars used. PDF text extracted locally."
                    showAttachmentPreview(attachedContext)
                    attachmentCard.visibility = View.VISIBLE
                    syncComposerState()
                    updateStatus(
                        if (extraction.limitReached) "PDF text attached with the ${"%,d".format(MAX_ATTACHMENT_CHARS)}-character limit applied. Review it, then ask your question."
                        else "PDF text attached. Review the document name and page count, then add a question."
                    )
                }
            } catch (e: Exception) {
                mainHandler.post {
                    updateStatus("Could not attach PDF: ${e.message ?: "unsupported or unreadable PDF"}")
                }
            }
        }
    }

    private fun importWhisperModel(uri: Uri) {
        val rawName = queryDisplayName(uri) ?: "Selected model"
        if (!rawName.endsWith(".bin", ignoreCase = true)) {
            updateStatus("Choose a whisper.cpp ggml model file ending in .bin. No model is bundled with PocketAgent.")
            return
        }
        val displayName = rawName.replace(Regex("[\\r\\n]+"), " ").take(80)
        updateWhisperActionButtons(false)
        whisperModelText.text = "Staging and loading the selected local Whisper model…"
        updateStatus("Loading a user-selected ggml model locally. Large models may take time and memory.")
        ioExecutor.execute {
            val store = WhisperModelStore(filesDir)
            var candidateContext = 0L
            var previousContext = whisperContext
            try {
                val input = contentResolver.openInputStream(uri)
                    ?: error("Could not open the selected model file.")
                val candidate = store.stage(input)

                // Avoid holding two model contexts in memory at once.
                if (previousContext != 0L) {
                    WhisperNative.release(previousContext)
                    whisperContext = 0L
                    previousContext = 0L
                }
                candidateContext = WhisperNative.loadModel(candidate.absolutePath)
                require(candidateContext != 0L) { "Could not load the selected Whisper model." }
                store.commitCandidate()
                whisperContext = candidateContext
                candidateContext = 0L
                mainHandler.post {
                    whisperModelText.text = "Whisper model ready: ${displayName.take(80)} (stored locally)."
                    updateStatus("Whisper model loaded. Record audio directly or choose a PCM WAV file to transcribe.")
                    updateWhisperActionButtons(true)
                }
            } catch (e: Exception) {
                if (candidateContext != 0L) WhisperNative.release(candidateContext)
                store.discardCandidate()
                val oldModel = store.modelFile
                if (whisperContext == 0L && oldModel.isFile && oldModel.length() in 1..WhisperModelStore.MAX_MODEL_BYTES) {
                    try {
                        whisperContext = WhisperNative.loadModel(oldModel.absolutePath)
                    } catch (_: Exception) {
                        whisperContext = 0L
                    }
                }
                mainHandler.post {
                    whisperModelText.text = if (whisperContext != 0L) "Previous local Whisper model restored."
                    else "No local Whisper model loaded. Choose a valid ggml .bin file (max 512 MB)."
                    updateStatus("Could not load Whisper model: ${e.message ?: "unsupported model file"}")
                    updateWhisperActionButtons(whisperContext != 0L)
                }
            }
        }
    }

    private fun transcribeWav(uri: Uri) {
        if (whisperContext == 0L) {
            updateStatus("Choose a local whisper.cpp ggml .bin model first.")
            return
        }
        updateWhisperActionButtons(false)
        suggestButton.isEnabled = false
        isWhisperTranscribing = true
        val generationAtStart = attachmentGeneration
        updateStatus("Staging and checking the WAV file locally…")
        ioExecutor.execute {
            var staged: File? = null
            try {
                val availableChars = MAX_ATTACHMENT_CHARS - attachedContext.length
                require(availableChars > 160) { "Remove the current attachment to make room for a transcript." }
                val displayName = (queryDisplayName(uri) ?: "Selected WAV")
                    .replace(Regex("[\\r\\n]+"), " ")
                    .take(120)
                val sourceLabel = "\n\n--- Local audio transcript: $displayName ---\n"
                require(availableChars > sourceLabel.length) {
                    "Remove the current attachment to make room for a transcript."
                }
                val input = contentResolver.openInputStream(uri) ?: error("Could not open the selected WAV file.")
                staged = BoundedAudioFile.stage(input, cacheDir, PcmWavReader.MAX_FILE_BYTES)
                val samples = PcmWavReader.read(staged)
                val context = whisperContext
                require(context != 0L) { "Select a Whisper model before transcribing audio." }
                val textLimit = minOf(MAX_ATTACHMENT_CHARS, availableChars - sourceLabel.length)
                mainHandler.post { updateStatus("Transcribing locally. This may take a while on CPU…") }
                val transcript = WhisperNative.transcribe(context, samples, textLimit).trim()
                require(transcript.isNotEmpty()) { "No transcript was produced. Check the audio and model language support." }
                require(attachmentGeneration == generationAtStart) { "Attachment was removed; the new transcript was discarded." }
                attachedContext = (attachedContext + sourceLabel + transcript).take(MAX_ATTACHMENT_CHARS)
                transcriptReviewPending = true
                attachedNames.add("$displayName (local transcript)")
                mainHandler.post {
                    attachmentText.text = attachedNames.joinToString(" · ") +
                        "\n${"%,d".format(attachedContext.length)}/${"%,d".format(MAX_ATTACHMENT_CHARS)} chars used. Temporary WAV cache deleted after processing."
                    showAttachmentPreview(attachedContext)
                    attachmentCard.visibility = View.VISIBLE
                    isWhisperTranscribing = false
                    syncComposerState()
                    updateStatus("Transcript ready. Expand and review it, then tap Use reviewed transcript.")
                    updateWhisperActionButtons(true)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    isWhisperTranscribing = false
                    syncComposerState()
                    updateStatus("Could not transcribe WAV: ${e.message ?: "unsupported or unreadable audio"}")
                    updateWhisperActionButtons(whisperContext != 0L)
                }
            } finally {
                staged?.delete()
            }
        }
    }

    private fun removeWhisperModel() {
        updateWhisperActionButtons(false)
        updateStatus("Removing the local Whisper model…")
        ioExecutor.execute {
            try {
                val context = whisperContext
                whisperContext = 0L
                if (context != 0L) WhisperNative.release(context)
                val store = WhisperModelStore(filesDir)
                store.discardCandidate()
                if (store.modelFile.exists() && !store.modelFile.delete()) {
                    error("Could not remove the stored model file.")
                }
                mainHandler.post {
                    whisperModelText.text = "No local Whisper model selected."
                    updateStatus("Local Whisper model removed.")
                }
            } catch (e: Exception) {
                mainHandler.post { updateStatus("Could not remove Whisper model: ${e.message ?: "storage error"}") }
            }
        }
    }

    private fun toggleInAppRecording() {
        if (whisperContext == 0L) {
            updateStatus("Choose a local whisper.cpp ggml .bin model first.")
            return
        }
        if (inAppAudioRecorder.isRecordingActive) {
            recordAudioButton.text = "Transcribing…"
            recordAudioButton.isEnabled = false
            inAppAudioRecorder.stopRecording()
            return
        }
        if (!InAppAudioRecorder.hasMicrophonePermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_MIC)
            updateStatus("Allow microphone access, then tap Record audio again.")
            return
        }
        startInAppRecording()
    }

    private fun startInAppRecording() {
        val generationAtStart = attachmentGeneration
        suggestButton.isEnabled = false
        transcribeWavButton.isEnabled = false
        recordAudioButton.text = "■  Stop recording"
        recordAudioButton.contentDescription = "Stop in-app audio recording"
        updateRecordingBanner()
        updateStatus("Recording audio… speak your task (max 60 seconds). Tap Stop when done.")

        inAppAudioRecorder.startRecording(object : AudioRecorderListener {
            override fun onRecordingStarted() {
                mainHandler.post {
                    updateRecordingBanner()
                    updateStatus("Listening… audio is kept in memory (PCM 16-bit 16kHz mono).")
                }
            }

            override fun onSilenceCutoff() {
                mainHandler.post {
                    updateStatus("Silence detected. Finishing recording…")
                }
            }

            override fun onMaxDurationReached() {
                mainHandler.post {
                    updateStatus("60-second limit reached. Finishing recording…")
                }
            }

            override fun onRecordingComplete(samples: FloatArray) {
                mainHandler.post {
                    updateRecordingBanner()
                    recordAudioButton.text = "Transcribing…"
                    recordAudioButton.isEnabled = false
                }
                ioExecutor.execute {
                    transcribeRecordedAudio(samples, generationAtStart)
                }
            }

            override fun onError(error: Throwable) {
                mainHandler.post {
                    resetRecordButton()
                    syncComposerState()
                    updateWhisperActionButtons(whisperContext != 0L)
                    updateStatus("Recording error: ${error.message ?: "could not record audio"}")
                }
            }
        })
    }

    private fun transcribeRecordedAudio(samples: FloatArray, generationAtStart: Long) {
        isWhisperTranscribing = true
        try {
            val availableChars = MAX_ATTACHMENT_CHARS - attachedContext.length
            require(availableChars > 160) { "Remove the current attachment to make room for a transcript." }
            val sourceLabel = "\n\n--- Local microphone transcript ---\n"
            require(availableChars > sourceLabel.length) {
                "Remove the current attachment to make room for a transcript."
            }
            val context = whisperContext
            require(context != 0L) { "Select a Whisper model before transcribing audio." }
            val textLimit = minOf(MAX_ATTACHMENT_CHARS, availableChars - sourceLabel.length)
            mainHandler.post { updateStatus("Transcribing in-app audio locally on CPU…") }
            val transcript = WhisperNative.transcribe(context, samples, textLimit).trim()
            require(transcript.isNotEmpty()) { "No transcript was produced. Check audio and model language support." }
            require(attachmentGeneration == generationAtStart) { "Attachment was removed; the new transcript was discarded." }
            attachedContext = (attachedContext + sourceLabel + transcript).take(MAX_ATTACHMENT_CHARS)
            transcriptReviewPending = true
            attachedNames.add("Microphone recording (local transcript)")
            mainHandler.post {
                attachmentText.text = attachedNames.joinToString(" · ") +
                    "\n${"%,d".format(attachedContext.length)}/${"%,d".format(MAX_ATTACHMENT_CHARS)} chars used. Recording memory wiped after transcription."
                showAttachmentPreview(attachedContext)
                attachmentCard.visibility = View.VISIBLE
                isWhisperTranscribing = false
                syncComposerState()
                updateStatus("Transcript ready. Expand and review it, then tap Use reviewed transcript.")
                resetRecordButton()
            }
        } catch (e: Exception) {
            mainHandler.post {
                isWhisperTranscribing = false
                syncComposerState()
                updateStatus("Could not transcribe in-app audio: ${e.message ?: "unreadable audio"}")
                resetRecordButton()
            }
        } finally {
            // Strict invariant: all audio buffers wiped after transcription
            inAppAudioRecorder.wipe()
            inAppAudioRecorder.wipeSamples(samples)
        }
    }

    private fun resetRecordButton() {
        recordAudioButton.text = "🎙  Record audio"
        recordAudioButton.contentDescription = "Record audio for local Whisper transcription"
        updateWhisperActionButtons(whisperContext != 0L)
    }

    private fun updateWhisperActionButtons(enabled: Boolean) {
        if (::transcribeWavButton.isInitialized) transcribeWavButton.isEnabled = enabled
        if (::recordAudioButton.isInitialized) recordAudioButton.isEnabled = enabled
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) { null }

    private fun queryFileSize(uri: Uri): Long? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                cursor.getLong(0).takeIf { it > 0L }
            } else {
                null
            }
        }
    } catch (_: Exception) { null }

    private fun clearAttachments() {
        attachmentGeneration++
        attachedContext = ""
        attachedNames.clear()
        transcriptReviewPending = false
        confirmTranscriptButton.visibility = View.GONE
        attachmentPreview.text = ""
        attachmentPreviewExpanded = false
        attachmentPreviewButton.text = "Show full attachment text"
        attachmentPreviewButton.contentDescription = "Expand attachment text preview"
        attachmentCard.visibility = View.GONE
        // The prompt context just changed size and content; the cached KV does not match it.
        engineManager.invalidateConversation("attachment removed")
        syncComposerState()
        updateStatus("Attachment removed.")
    }

    private fun showAttachmentPreview(value: String) {
        attachmentPreview.text = value
        attachmentPreviewExpanded = false
        attachmentPreview.maxLines = 8
        attachmentPreview.ellipsize = android.text.TextUtils.TruncateAt.END
        attachmentPreviewButton.text = "Show full attachment text"
        attachmentPreviewButton.contentDescription = "Expand attachment text preview"
        attachmentPreviewButton.visibility = if (value.length > 400) View.VISIBLE else View.GONE
        confirmTranscriptButton.visibility =
            if (transcriptReviewPending && value.length <= 400) View.VISIBLE else View.GONE
    }

    private fun toggleAttachmentPreview() {
        attachmentPreviewExpanded = !attachmentPreviewExpanded
        if (attachmentPreviewExpanded) {
            attachmentPreview.maxLines = Int.MAX_VALUE
            attachmentPreview.ellipsize = null
            attachmentPreviewButton.text = "Hide full attachment text"
            attachmentPreviewButton.contentDescription = "Collapse attachment text preview"
            confirmTranscriptButton.visibility = if (transcriptReviewPending) View.VISIBLE else View.GONE
        } else {
            attachmentPreview.maxLines = 8
            attachmentPreview.ellipsize = android.text.TextUtils.TruncateAt.END
            attachmentPreviewButton.text = "Show full attachment text"
            attachmentPreviewButton.contentDescription = "Expand attachment text preview"
            confirmTranscriptButton.visibility =
                if (transcriptReviewPending && attachmentPreview.text.length <= 400) View.VISIBLE else View.GONE
        }
    }

    /**
     * Builds the prompt for one answer attempt, with real conversation history and an
     * intent-shaped request so factual questions get answered rather than turned into to-dos.
     */
    /** Recent turns for the active session, minus the turn currently being generated. */
    private fun conversationHistoryFor(task: String): List<ChatMessage> =
        promptContext.historyFor(currentSession?.messages.orEmpty(), task)

    /** Composes prompt + token/truncation metadata for one attempt; see [AnswerPromptComposer]. */
    private fun composePrompt(
        task: String,
        attachment: String,
        intent: AnswerDirective.Intent,
        isRetry: Boolean,
        history: List<ChatMessage>,
        calculation: LocalCalculatorTool.CalculationResult? = null,
    ): AnswerPromptComposer.PromptBuildResult = promptContext.answerPrompt(task, attachment, intent, isRetry, history, calculation)

    /**
     * Runs one inference pass with the runaway guard applied.
     *
     * Reuses the session-scoped LiteRT-LM conversation so the KV cache stays warm between turns,
     * streams plain text at a throttled cadence instead of re-parsing Markdown per token, and
     * bounds the wait so a stalled engine cannot wedge the inference thread forever.
     *
     * Any failure invalidates the cached conversation: after a timeout or a streaming error the
     * engine's context is in an unknown state, and reusing it would corrupt the next answer.
     */
    @Volatile private var currentGenerationStageLabel: String = "Thinking"

    private fun buildCallbacksForAnswer(answerProvider: () -> String): AssistantTurnCallbacks =
        AssistantTurnCallbacks(
            onCopyCode = { code, language ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(language ?: "Code", code))
                Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show()
            },
            onCopyAnswer = { copyAnswerText(answerProvider()) },
            onShareAnswer = { shareAnswerText(answerProvider()) },
            onSpeakAnswer = { speakAnswerText(answerProvider()) },
            onSaveAnswer = { saveAnswerToFile() },
            onExportMarkdown = { exportCurrentAsMarkdown() },
            onRegenerate = { regenerateAnswer() },
            onRefine = { refineLastAnswer() },
        )

    private fun startGenerationIndicators(handle: ActiveAssistantCardHandle, initialStage: String) {
        stopGenerationIndicators()
        currentGenerationStageLabel = initialStage
        generationStartElapsedMs = SystemClock.elapsedRealtime()
        thinkingPulseAnimator.start(handle.statusDot)
        handle.setStatusBadge("$initialStage · 0.0s", palette.teal)
        val ticker = object : Runnable {
            override fun run() {
                if (!isGeneratingUi) return
                val elapsed = SystemClock.elapsedRealtime() - generationStartElapsedMs
                handle.statusLabel.text =
                    "$currentGenerationStageLabel · ${ComposerChromeHelper.formatElapsedDuration(elapsed)}"
                mainHandler.postDelayed(this, 250L)
            }
        }
        generationTimerRunnable = ticker
        mainHandler.postDelayed(ticker, 250L)
    }

    private fun stopGenerationIndicators() {
        thinkingPulseAnimator.stop()
        generationTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        generationTimerRunnable = null
    }

    private fun runGuardedGeneration(
        convKey: String,
        prompt: String,
        systemInstruction: String,
    ): String {
        val accumulated = StringBuilder()
        val firstChunk = AtomicBoolean(true)
        val streamFinished = CountDownLatch(1)
        val streamError = AtomicReference<Throwable?>(null)

        val guard = GuardedGeneration { partial ->
            val isFirst = firstChunk.compareAndSet(true, false)
            if (isFirst) {
                mainHandler.post {
                    refreshModelStatus()
                    currentGenerationStageLabel = "Streaming"
                    updateStatus("Response streaming locally…")
                }
            }
            // Plain text at ~10 fps: no Markdown parsing on the UI thread while streaming.
            streamingRenderer.onPartial(partial)
        }

        val conversation = engineManager.getOrCreateConversation(convKey, systemInstruction)
        activeStreamLatch = streamFinished
        engineManager.beginGeneration()
        try {
            conversation.sendMessage(
                prompt,
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (isGeneratingCancelled.get() && !guard.wasCancelled) {
                            guard.cancel()
                        }
                        guard.accept(accumulated, message.toString())
                    }

                    override fun onDone() {
                        streamFinished.countDown()
                    }

                    override fun onError(throwable: Throwable) {
                        streamError.set(throwable)
                        streamFinished.countDown()
                    }
                },
                maxOutputTokens = MAX_RESPONSE_TOKENS,
            )

            // Bounded wait: a stalled engine must not wedge the inference thread, which would
            // make every later request look frozen with the last answer on screen.
            val finished = streamFinished.await(GENERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                guard.cancel()
                conversation.cancel()
                engineManager.invalidateConversation("generation timed out")
                throw IllegalStateException(
                    "Generation timed out after ${GENERATION_TIMEOUT_SECONDS}s. Try a shorter request."
                )
            }
            streamError.get()?.let { error ->
                conversation.cancel()
                engineManager.invalidateConversation("streaming failed")
                throw IllegalStateException("Streaming inference failed.", error)
            }

            // The user pressed Stop: the context holds a half-written turn, so start fresh next time.
            if (isGeneratingCancelled.get() || guard.wasCancelled) {
                conversation.cancel()
                engineManager.invalidateConversation("generation stopped by user")
            }
        } catch (t: Throwable) {
            if (t !is IllegalStateException) {
                conversation.cancel()
                engineManager.invalidateConversation("generation failed")
            }
            throw t
        } finally {
            activeStreamLatch = null
            engineManager.endGeneration()
        }

        runawayFragment = guard.runawayFragment
        val raw = guard.text(accumulated)
        val finalised = AnswerFinalizer.finalize(raw)
        return finalised.text
    }

    // ------------------------------------------------ generation lifecycle & rendering helpers

    /**
     * Binds the completed raw markdown directly into [ActiveAssistantCardHandle] so rich headers,
     * bold spans, and syntax-highlighted fenced code blocks render cleanly.
     */
    private fun renderFinalAnswer(
        handle: ActiveAssistantCardHandle,
        result: String,
        stoppedByUser: Boolean,
        elapsedMs: Long,
    ) {
        streamingRenderer.cancel()
        stopGenerationIndicators()
        if (result.isBlank()) {
            latestAssistantText = ""
            answerIsComplete = false
            handle.setStatusBadge(
                if (stoppedByUser) "Stopped" else "No response",
                if (stoppedByUser) palette.statusStandby else palette.statusMissing,
            )
            handle.bindCompletedMarkdown(
                markdown = if (stoppedByUser) "Generation stopped." else "No answer was returned. Try rephrasing the request.",
                isLatest = true,
                showActions = false,
            )
            maybeFollowStreamingOutput()
            return
        }

        latestAssistantText = result
        answerIsComplete = !stoppedByUser
        val backendTag = when (engineManager.activeBackend) {
            ActiveBackend.GPU -> "GPU"
            ActiveBackend.CPU -> "CPU"
            null -> "Local"
        }
        val dotColor = when {
            stoppedByUser -> palette.statusStandby
            engineManager.activeBackend == ActiveBackend.GPU -> palette.statusLoadedGpu
            engineManager.activeBackend == ActiveBackend.CPU -> palette.statusLoadedCpu
            else -> palette.teal
        }
        val badgeText = if (stoppedByUser) {
            "Stopped · partial"
        } else {
            "PocketAgent · $backendTag · ${ComposerChromeHelper.formatElapsedDuration(elapsedMs)}"
        }
        handle.setStatusBadge(badgeText, dotColor)
        handle.timestampView.text = formatTimestampLabel(System.currentTimeMillis())
        handle.timestampView.visibility = View.VISIBLE
        handle.bindCompletedMarkdown(
            markdown = result,
            isLatest = true,
            showActions = true,
        )
        maybeFollowStreamingOutput()
    }

    /** Switches the primary composer action from Ask to Stop while a generation is running. */
    private fun enterGeneratingState() {
        idleUnloadRunnable?.let { mainHandler.removeCallbacks(it) }
        idleUnloadRunnable = null
        isGeneratingUi = true
        isStoppingUi = false
        suggestButton.isEnabled = true
        suggestButton.text = "Stop"
        suggestButton.contentDescription = "Stop generating answer"
        suggestButton.background = ripple(shape(STOP_CRIMSON, 16), 0x33FFFFFF)
        suggestButton.setTextColor(Color.WHITE)
        suggestButton.setOnClickListener { stopActiveGeneration() }
        syncComposerState()
    }

    /** Restores the primary composer action to Ask. */
    private fun exitGeneratingState() {
        if (!isGeneratingUi) return
        isGeneratingUi = false
        isStoppingUi = false
        val gradient = gradientPillDrawable(
            palette.heroGradientStart,
            palette.heroGradientEnd,
            dp(16).toFloat(),
        )
        suggestButton.text = "Ask"
        suggestButton.contentDescription = "Send your question and get an answer"
        suggestButton.background = ripple(gradient, 0x33FFFFFF)
        suggestButton.setTextColor(Color.WHITE)
        suggestButton.setOnClickListener { suggestSteps() }
        syncComposerState()
        scheduleIdleEngineUnload()
    }

    private fun scheduleIdleEngineUnload() {
        idleUnloadRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (!isGeneratingUi && engineManager.engine != null) {
                engineManager.invalidateConversation("idle unload")
                inferenceExecutor.execute { engineManager.close() }
                updateStatus("Model unloaded after inactivity to free memory. It will reload on next request.")
                refreshModelStatus()
            }
        }
        idleUnloadRunnable = runnable
        mainHandler.postDelayed(runnable, IDLE_ENGINE_UNLOAD_MS)
    }

    /**
     * Stops the running generation immediately: flags it, signals the native engine to abort,
     * unblocks the waiter, drops the streaming preview, and waits for [exitGeneratingState] on
     * completion so a subsequent tap cannot race the closing native conversation.
     */
    private fun stopActiveGeneration() {
        if (!isGeneratingUi) return
        isGeneratingCancelled.set(true)
        isStoppingUi = true
        streamingRenderer.cancel()
        engineManager.cancelActiveConversation()
        suggestButton.isEnabled = false
        suggestButton.alpha = 0.5f
        suggestButton.text = "Stopping…"
        activeStreamLatch?.let { latch ->
            // The engine may never call onDone after a cancel, so release the waiter ourselves.
            latch.countDown()
        }
        updateStatus("Stopping generation…")
    }

    /** Follows streaming output only when the reader has not scrolled up to read earlier text. */
    private fun maybeFollowStreamingOutput() {
        if (isUserReadingUpwards) return
        scrollChatToBottom()
    }

    /**
     * Reveals the manual Refine affordance for a dodged or unsubstantive answer.
     *
     * This replaces the old silent second inference pass: the user now chooses whether the extra
     * generation is worth it, instead of every vague answer costing two full runs.
     */
    private fun updateRefineAffordance(handle: ActiveAssistantCardHandle? = activeAssistantHandle) {
        val btn = handle?.refineButton ?: return
        btn.visibility = if (maybeShowRefineAffordance && lastPrompt.isNotBlank()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    /**
     * Runs the corrective pass on demand on the serial [inferenceExecutor].
     *
     * Forces a fresh conversation because the retry uses a different prompt shape, and reuses the
     * same intent and calculation context as the original attempt.
     */
    private fun refineLastAnswer() {
        val task = lastPrompt
        val handle = activeAssistantHandle ?: return
        if (task.isBlank() || isGeneratingUi || engineManager.isGenerationActive) return
        val modelFile = File(filesDir, "model.litertlm")
        if (!modelFile.exists()) {
            updateStatus("Choose a compatible .litertlm model first.")
            return
        }
        maybeShowRefineAffordance = false
        updateRefineAffordance(handle)
        isGeneratingCancelled.set(false)
        streamingRenderer.reset()
        enterGeneratingState()
        handle.bindStreamingText("Refining answer…")
        startGenerationIndicators(handle, "Refining")
        updateStatus("Refining the answer…")

        inferenceExecutor.execute {
            try {
                ensureEngineInitialized(modelFile)
                val intent = AnswerDirective.classify(task.ifBlank { attachedContext })
                val history = conversationHistoryFor(task)
                val calculation = if (task.isNotBlank()) LocalCalculatorTool.tryEvaluate(task) else null
                val instruction = effectiveSystemInstruction(task, intent)
                val retryBuild = composePrompt(
                    task = task,
                    attachment = attachedContext,
                    intent = intent,
                    isRetry = true,
                    history = history,
                    calculation = calculation,
                )
                // Different prompt shape: start from a clean context.
                engineManager.invalidateConversation("refine")
                runawayFragment = null
                val convKey = conversationKeyFor(currentSession?.id, modelFile, instruction, attachedContext)
                val retried = executeWithGpuFallback(modelFile) {
                    runGuardedGeneration(convKey, retryBuild.prompt, instruction)
                }
                if (!isGeneratingCancelled.get()) {
                    engineManager.recordConversationTurnTokens(
                        retryBuild.estimatedTotalTokens + ChatMessage.estimateTextTokens(retried) + 8
                    )
                }
                val elapsedMs = SystemClock.elapsedRealtime() - generationStartElapsedMs
                mainHandler.post {
                    exitGeneratingState()
                    refreshModelStatus()
                    if (isGeneratingCancelled.get()) {
                        renderFinalAnswer(handle, retried, stoppedByUser = true, elapsedMs = elapsedMs)
                        updateStatus("Generation stopped by user.")
                    } else if (retried.isNotBlank()) {
                        renderFinalAnswer(handle, retried, stoppedByUser = false, elapsedMs = elapsedMs)
                        updateStatus("Answer refined.")
                        try {
                            val sess = currentSession
                            if (sess != null) {
                                val msgs = sess.messages.toMutableList()
                                val lastIdx = msgs.indexOfLast { it.isAssistant }
                                if (lastIdx >= 0) {
                                    msgs[lastIdx] = ChatMessage.assistant(retried)
                                    val updated = sess.copy(messages = msgs, updatedAt = System.currentTimeMillis())
                                    sessionStore.saveSession(updated)
                                    currentSession = updated
                                }
                            }
                        } catch (_: Exception) {}
                    } else {
                        stopGenerationIndicators()
                        handle.setStatusBadge("No response", palette.statusMissing)
                        updateStatus("Refine did not produce a new answer.")
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    exitGeneratingState()
                    stopGenerationIndicators()
                    refreshModelStatus()
                    if (isGeneratingCancelled.get()) {
                        handle.setStatusBadge("Stopped", palette.statusStandby)
                        updateStatus("Generation stopped by user.")
                    } else {
                        handle.setStatusBadge("Error", palette.statusMissing)
                        updateStatus("Could not refine the answer: ${e.message ?: "unknown error"}")
                    }
                }
            }
        }
    }

    private fun conversationKeyFor(
        sessionId: String?,
        modelFile: File,
        systemInstruction: String,
        attachmentContext: String,
    ): String = engineManager.conversationKey(
        sessionId = sessionId,
        modelPath = modelFile.absolutePath,
        systemInstruction = systemInstruction,
        attachmentHash = attachmentContext.hashCode(),
    )

    private fun effectiveSystemInstruction(task: String, intent: AnswerDirective.Intent): String {
        if (!isMemoryEnabled) return AnswerDirective.systemInstructionFor(intent)
        val candidateFacts = MemoryFactExtractor.extractCandidateFacts(task)
        for (cf in candidateFacts) {
            userMemoryStore.addFact(
                content = cf.content,
                category = cf.category,
                confidence = cf.confidence,
                sourceSessionId = currentSession?.id,
            )
        }
        val memoryBlock = userMemoryStore.buildPromptContext(maxTokens = 120)
        return ContextBudgetManager().enrichSystemInstructionWithMemory(
            AnswerDirective.systemInstructionFor(intent),
            memoryBlock,
        )
    }

    private fun executeWithGpuFallback(
        modelFile: File,
        onBeforeCpuRetry: (() -> Unit)? = null,
        attempt: () -> String,
    ): String {
        return try {
            attempt()
        } catch (gpuError: Throwable) {
            if (!isGeneratingCancelled.get() && engineManager.activeBackend == ActiveBackend.GPU) {
                val reason = gpuError.message ?: gpuError.javaClass.simpleName
                if (engineManager.fallbackToCpuAfterRuntimeFailure(reason, modelFile, cacheDir)) {
                    mainHandler.post {
                        refreshModelStatus()
                        currentGenerationStageLabel = "Retrying on CPU"
                        updateStatus("GPU runtime failed; switched to CPU and retrying…")
                        activeAssistantHandle?.setStatusBadge("Retrying on CPU…", palette.statusLoadedCpu)
                        activeAssistantHandle?.bindStreamingText("Retrying on CPU…")
                    }
                    streamingRenderer.reset()
                    onBeforeCpuRetry?.invoke()
                    return attempt()
                }
            }
            throw gpuError
        }
    }

    private fun suggestSteps() {
        if (isWhisperTranscribing) {
            updateStatus("Wait for transcription to finish before asking.")
            return
        }
        if (transcriptReviewPending) {
            updateStatus("Expand and review the complete transcript, then tap Use reviewed transcript.")
            return
        }
        val task = taskInput.text.toString().trim()
        if (task.isEmpty() && attachedContext.isEmpty()) {
            updateStatus("Type a task, dictate it, or attach a text/PDF file first.")
            taskInput.requestFocus()
            return
        }
        val modelFile = File(filesDir, "model.litertlm")
        if (!modelFile.exists()) {
            updateStatus("Choose a compatible .litertlm model first.")
            return
        }
        if (engineManager.isGenerationActive || isGeneratingUi) {
            updateStatus("An answer is already being generated. Tap Stop first.")
            return
        }
        // Assign session ID before building the cache key so Turn 1 and Turn 2 share the warm KV cache.
        val activeSession = currentSession
            ?: ConversationSession.createWithInitialPrompt(task.ifBlank { "Conversation" }).also {
                currentSession = it
            }
        lastPrompt = task
        isGeneratingCancelled.set(false)
        streamingRenderer.reset()

        val attachmentSummary = if (attachedContext.isNotBlank()) {
            attachedNames.joinToString(" · ").ifBlank { "Attachment" }
        } else {
            null
        }
        val attachmentRefs = attachedNames.toList()
        val userMsg = ChatMessage.user(
            content = task,
            attachmentContent = if (attachedContext.isNotBlank()) attachedContext else null,
            attachmentSummary = attachmentSummary,
            attachmentReferences = attachmentRefs,
        )
        val userCard = ChatMessageCardBuilder.buildUserCard(this, userMsg, palette)
        chatThreadContainer.addView(userCard, marginBottom(10))

        activeAssistantHandle?.setLatestTurn(false)
        val handle = ChatMessageCardBuilder.buildAssistantCard(
            context = this,
            message = null,
            palette = palette,
            isLatest = true,
            callbacks = buildCallbacksForAnswer { latestAssistantText },
        )
        chatThreadContainer.addView(handle.rootCard, marginBottom(12))
        activeAssistantHandle = handle

        taskInput.setText("")
        ComposerInputGuard.resetInputHeight(taskInput)
        answerIsComplete = false
        latestAssistantText = ""
        maybeShowRefineAffordance = false

        val initialBackendLabel = if (engineManager.preference == BackendPreference.CPU_ONLY) {
            "CPU"
        } else {
            engineManager.activeBackend?.name ?: "GPU"
        }
        enterGeneratingState()
        handle.bindStreamingText("Working on it…")
        startGenerationIndicators(handle, "Thinking on $initialBackendLabel")
        isUserReadingUpwards = false
        syncComposerState()
        scrollChatToBottom(force = true)
        updateStatus("Answering on this device ($initialBackendLabel)…")

        val attachmentSnapshot = attachedContext
        inferenceExecutor.execute {
            var localCitations = emptyList<LocalDocumentRetriever.DocumentCitation>()
            try {
                ensureEngineInitialized(modelFile)
                mainHandler.post { refreshModelStatus() }
                val intent = AnswerDirective.classify(task.ifBlank { attachmentSnapshot })
                val calculation = if (task.isNotBlank()) LocalCalculatorTool.tryEvaluate(task) else null
                val boundedAttachment = if (attachmentSnapshot.isNotBlank()) {
                    val retrieval = LocalDocumentRetriever.retrieveTextWithCitations(
                        query = task,
                        documentText = attachmentSnapshot,
                        sourceName = attachmentRefs.firstOrNull() ?: "attachment",
                        maxChars = MAX_ATTACHMENT_CHARS,
                    )
                    localCitations = retrieval.citations
                    if (retrieval.promptContext.isNotBlank()) retrieval.promptContext else attachmentSnapshot
                } else {
                    localCitations = emptyList()
                    ""
                }

                val effectiveInstruction = effectiveSystemInstruction(task, intent)
                val convKey = conversationKeyFor(activeSession.id, modelFile, effectiveInstruction, boundedAttachment)

                var isWarm = engineManager.isConversationWarm(convKey)
                val sessionHistory = conversationHistoryFor(task)
                var promptBuild = composePrompt(
                    task = task,
                    attachment = boundedAttachment,
                    intent = intent,
                    isRetry = false,
                    history = if (isWarm) emptyList() else sessionHistory,
                    calculation = calculation,
                )
                if (isWarm &&
                    engineManager.cachedConversationTokens + promptBuild.estimatedTotalTokens + MAX_RESPONSE_TOKENS >
                    WARM_CONTEXT_RESET_THRESHOLD
                ) {
                    engineManager.invalidateConversation("context budget reached")
                    isWarm = false
                    promptBuild = composePrompt(
                        task = task,
                        attachment = boundedAttachment,
                        intent = intent,
                        isRetry = false,
                        history = sessionHistory,
                        calculation = calculation,
                    )
                }
                if (promptBuild.truncatedCount > 0) {
                    mainHandler.post { updateStatus("Older context trimmed to fit model limit.") }
                }

                runawayFragment = null
                var activeBuild = promptBuild
                val result = executeWithGpuFallback(
                    modelFile = modelFile,
                    onBeforeCpuRetry = {
                        activeBuild = composePrompt(
                            task = task,
                            attachment = boundedAttachment,
                            intent = intent,
                            isRetry = false,
                            history = sessionHistory,
                            calculation = calculation,
                        )
                    },
                ) {
                    runGuardedGeneration(convKey, activeBuild.prompt, effectiveInstruction)
                }

                if (!isGeneratingCancelled.get()) {
                    engineManager.recordConversationTurnTokens(
                        activeBuild.estimatedTotalTokens + ChatMessage.estimateTextTokens(result) + 8
                    )
                }
                val needsRefineHint = !isGeneratingCancelled.get() && runawayFragment == null &&
                    (AnswerDirective.looksLikeDelegationRefusal(result, intent) ||
                        AnswerDirective.isUnsubstantive(result))
                maybeShowRefineAffordance = needsRefineHint
                val elapsedMs = SystemClock.elapsedRealtime() - generationStartElapsedMs
                mainHandler.post {
                    exitGeneratingState()
                    refreshModelStatus()
                    syncComposerState()
                    currentCitations = localCitations
                    renderCitations(localCitations, handle.citationsContainer)
                    if (isGeneratingCancelled.get()) {
                        renderFinalAnswer(handle, result, stoppedByUser = true, elapsedMs = elapsedMs)
                        updateStatus("Generation stopped by user.")
                        scrollChatToBottom()
                        return@post
                    }
                    renderFinalAnswer(handle, result, stoppedByUser = false, elapsedMs = elapsedMs)
                    updateRefineAffordance(handle)
                    scrollChatToBottom()
                    val stopped = runawayFragment
                    updateStatus(
                        when {
                            stopped != null -> RunawayGuard.stoppedNotice(stopped)
                            answerIsComplete -> "Answer ready."
                            else -> "No answer was returned. Try rephrasing the request."
                        }
                    )

                    // Auto-save turn to local conversation session
                    if (result.isNotBlank()) {
                        try {
                            val sess = currentSession ?: activeSession
                            val assistantMsg = ChatMessage.assistant(result)
                            val updated = sess.withMessage(userMsg).withMessage(assistantMsg)
                            sessionStore.saveSession(updated)
                            currentSession = updated
                        } catch (_: Exception) {}
                    }

                    // Safe actionable system intents review
                    val parsedActions = SystemActionTools.parseActions(result)
                    handle.systemActionContainer.removeAllViews()
                    if (parsedActions.isNotEmpty()) {
                        handle.systemActionContainer.visibility = View.VISIBLE
                        for (action in parsedActions.take(3)) {
                            val reviewUi = SystemActionTools.getReviewUi(action)
                            handle.systemActionContainer.addView(
                                pillButton(reviewUi.buttonLabel, accessibilityLabel = reviewUi.buttonLabel) {
                                    showActionReviewDialog(reviewUi)
                                }.apply {
                                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
                                }
                            )
                        }
                    } else {
                        handle.systemActionContainer.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    exitGeneratingState()
                    stopGenerationIndicators()
                    refreshModelStatus()
                    syncComposerState()
                    if (isGeneratingCancelled.get()) {
                        handle.setStatusBadge("Stopped", palette.statusStandby)
                        updateStatus("Generation stopped by user.")
                    } else {
                        currentCitations = localCitations
                        renderCitations(localCitations, handle.citationsContainer)
                        answerIsComplete = false
                        latestAssistantText = ""
                        handle.setStatusBadge("Error", palette.statusMissing)
                        handle.bindCompletedMarkdown(
                            markdown = "Could not generate an answer: ${e.message ?: "unknown error"}",
                            isLatest = true,
                            showActions = false,
                        )
                        handle.systemActionContainer.removeAllViews()
                        handle.systemActionContainer.visibility = View.GONE
                        updateStatus("Could not generate an answer: ${e.message ?: "unknown error"}")
                    }
                }
            }
        }
    }

    private fun regenerateAnswer() {
        if (lastPrompt.isNotBlank() && !isGeneratingUi && !engineManager.isGenerationActive) {
            taskInput.setText(lastPrompt)
            suggestSteps()
        }
    }

    private fun startNewConversation() {
        if (isGeneratingUi || engineManager.isGenerationActive) {
            updateStatus("Stop active generation before starting a new chat.")
            return
        }
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
        if (::chatThreadContainer.isInitialized) {
            chatThreadContainer.removeAllViews()
        }
        taskInput.setText("")
        ComposerInputGuard.resetInputHeight(taskInput)
        isUserReadingUpwards = false
        syncComposerState()
        scrollChatToBottom(force = true)
        updateStatus("Started a new conversation.")
    }

    private fun loadSession(id: String) {
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
        for ((index, msg) in s.messages.withIndex()) {
            when (msg.role) {
                ChatMessage.Role.USER -> {
                    val card = ChatMessageCardBuilder.buildUserCard(this, msg, palette)
                    chatThreadContainer.addView(card, marginBottom(10))
                }
                ChatMessage.Role.ASSISTANT -> {
                    val isLatest = (index == lastAssistantIndex)
                    val handle = ChatMessageCardBuilder.buildAssistantCard(
                        context = this,
                        message = msg,
                        palette = palette,
                        isLatest = isLatest,
                        callbacks = buildCallbacksForAnswer(
                            if (isLatest) {
                                { latestAssistantText }
                            } else {
                                { msg.content }
                            }
                        ),
                    )
                    chatThreadContainer.addView(handle.rootCard, marginBottom(12))
                    if (isLatest) {
                        activeAssistantHandle = handle
                        latestAssistantText = msg.content
                        answerIsComplete = msg.content.isNotBlank()
                    }
                }
                ChatMessage.Role.SYSTEM -> Unit
            }
        }

        lastPrompt = s.messages.lastOrNull { it.isUser }?.content.orEmpty()
        taskInput.setText("")
        ComposerInputGuard.resetInputHeight(taskInput)
        isUserReadingUpwards = false
        syncComposerState()
        scrollChatToBottom(force = true)
        updateStatus("Loaded: ${s.title}")
    }

    private fun showHistoryDialog() {
        var allSessions = sessionStore.listSessions().sortedWith(
            compareByDescending<ConversationSession> { it.isPinned }
                .thenByDescending { it.updatedAt }
        )
        val dialogView = styleDialogContainer(this, palette, paddingDp = 16)

        val searchBox = EditText(this).apply {
            hint = "Search conversations…"
            textSize = 14f
            background = shape(SURFACE, 12, BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(INK)
            setHintTextColor(MUTED)
        }
        dialogView.addView(searchBox, marginBottom(12))

        val listContainer = column()
        val scroll = ScrollView(this).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(-1, dp(340))
        }
        dialogView.addView(scroll)

        lateinit var dialog: AlertDialog

        fun populateList(query: String = "", reload: Boolean = false) {
            if (reload) {
                allSessions = sessionStore.listSessions().sortedWith(
                    compareByDescending<ConversationSession> { it.isPinned }
                        .thenByDescending { it.updatedAt }
                )
                dialog.setTitle("Conversations (${allSessions.size})")
            }
            listContainer.removeAllViews()
            val filtered = if (query.isBlank()) {
                allSessions
            } else {
                allSessions.filter { sess ->
                    sess.title.contains(query, ignoreCase = true) ||
                        sess.messages.any { it.content.contains(query, ignoreCase = true) }
                }
            }
            if (filtered.isEmpty()) {
                listContainer.addView(text("No conversations found.", 13f, palette.muted).apply {
                    setPadding(0, dp(24), 0, dp(24))
                    gravity = Gravity.CENTER
                })
                return
            }

            val dateFormatter = java.text.SimpleDateFormat("MMM dd, yyyy · HH:mm", java.util.Locale.getDefault())
            for (s in filtered) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = shape(SURFACE, 12, BORDER)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        loadSession(s.id)
                        dialog.dismiss()
                    }
                }
                val info = column().apply {
                    val titlePrefix = if (s.isPinned) "📌 " else ""
                    addView(text("$titlePrefix${s.title}", 14.5f, palette.ink, true))
                    val previewSnippet = s.messages.lastOrNull()?.content
                        ?.trim()
                        ?.replace(Regex("\\s+"), " ")
                        .orEmpty()
                    if (previewSnippet.isNotEmpty()) {
                        addView(text(previewSnippet, 12.5f, palette.muted).apply {
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                            setPadding(0, dp(3), 0, dp(3))
                        })
                    }
                    val dateStr = dateFormatter.format(java.util.Date(s.updatedAt))
                    addView(text("$dateStr · ${s.messageCount} turns", 11f, palette.teal))
                }
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

                val menuBtn = iconTouchButton(
                    context = this,
                    palette = palette,
                    iconText = "⋮",
                    accessibilityLabel = "Options for ${s.title}",
                    textColor = palette.muted,
                    textSizeSp = 18f,
                ) {
                    showSessionOptionsMenu(s) {
                        populateList(searchBox.text.toString(), reload = true)
                    }
                }
                row.addView(menuBtn)
                listContainer.addView(row, marginBottom(8))
            }
        }

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                populateList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        dialog = AlertDialog.Builder(this)
            .setTitle("Conversations (${allSessions.size})")
            .setView(dialogView)
            .setPositiveButton("+ New Chat") { _, _ ->
                startNewConversation()
            }
            .setNegativeButton("Close", null)
            .create()
        populateList()
        showStyledAlertDialog(dialog, palette)
    }

    private fun showSessionOptionsMenu(s: ConversationSession, onUpdated: () -> Unit) {
        val dialogView = styleDialogContainer(this, palette, paddingDp = 16)
        lateinit var menuDialog: AlertDialog

        val pinLabel = if (s.isPinned) "Unpin conversation" else "📌 Pin to top"
        val actions = listOf(
            pinLabel to false,
            "✏️ Rename" to false,
            "📄 Export as Markdown" to false,
            "📝 Export as Text" to false,
            "🗑️ Delete conversation" to true,
        )
        for ((index, spec) in actions.withIndex()) {
            val (label, isDanger) = spec
            val btn = if (isDanger) {
                dangerButton(this, palette, label, accessibilityLabel = label) {
                    menuDialog.dismiss()
                    sessionStore.deleteSession(s.id)
                    if (currentSession?.id == s.id) {
                        startNewConversation()
                    }
                    onUpdated()
                }
            } else {
                secondaryOutlineButton(this, palette, label, accessibilityLabel = label) {
                    menuDialog.dismiss()
                    when (index) {
                        0 -> {
                            sessionStore.pinSession(s.id, !s.isPinned)
                            onUpdated()
                        }
                        1 -> promptRenameSession(s, onUpdated)
                        2 -> exportSessionToShare(s.id, asMarkdown = true)
                        3 -> exportSessionToShare(s.id, asMarkdown = false)
                    }
                }
            }
            dialogView.addView(
                btn,
                LinearLayout.LayoutParams(-1, -2).apply {
                    if (index < actions.lastIndex) bottomMargin = dp(8)
                },
            )
        }

        menuDialog = AlertDialog.Builder(this)
            .setTitle(s.title)
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()
        showStyledAlertDialog(menuDialog, palette)
    }

    private fun promptRenameSession(s: ConversationSession, onUpdated: () -> Unit) {
        val container = styleDialogContainer(this, palette, paddingDp = 16)
        val input = EditText(this).apply {
            hint = "Conversation title…"
            textSize = 14f
            styleEditText(this, palette)
            setText(s.title)
            setSelection(text.length)
        }
        container.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename Conversation")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotBlank()) {
                    sessionStore.renameSession(s.id, newTitle)
                    if (currentSession?.id == s.id) {
                        currentSession = currentSession?.withTitle(newTitle)
                    }
                    onUpdated()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    private fun exportSessionToShare(id: String, asMarkdown: Boolean) {
        val text = if (asMarkdown) sessionStore.exportSessionAsMarkdown(id) else sessionStore.exportSessionAsText(id)
        if (text.isNullOrBlank()) return
        val mime = if (asMarkdown) "text/markdown" else "text/plain"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "PocketAgent Conversation Export")
        }
        startActivity(Intent.createChooser(shareIntent, "Export Conversation"))
    }

    private fun exportCurrentAsMarkdown() {
        val sess = currentSession
        if (sess != null && sess.messages.isNotEmpty()) {
            exportSessionToShare(sess.id, asMarkdown = true)
        } else {
            val text = latestAssistantText
            if (text.isNotBlank()) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_TEXT, text)
                    putExtra(Intent.EXTRA_SUBJECT, "PocketAgent Answer Export")
                }
                startActivity(Intent.createChooser(shareIntent, "Export Answer"))
            } else {
                updateStatus("No conversation to export yet.")
            }
        }
    }

    private fun showMemoryCenterDialog() {
        val dialogView = styleDialogContainer(this, palette, paddingDp = 16)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val toggleButton = com.example.pocketagent.pillButton(
            context = this,
            palette = palette,
            label = if (isMemoryEnabled) "Memory: ON" else "Memory: OFF",
            accessibilityLabel = "Toggle sovereign long-term memory",
            compact = true,
        ) {}.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
        }
        headerRow.addView(toggleButton)

        val addFactBtn = com.example.pocketagent.pillButton(
            context = this,
            palette = palette,
            label = "➕ Add Fact",
            accessibilityLabel = "Manually record a user preference or fact",
            compact = true,
        ) {}.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
        }
        headerRow.addView(addFactBtn)

        val clearAllBtn = dangerButton(
            context = this,
            palette = palette,
            label = "Clear All",
            accessibilityLabel = "Clear all sovereign memories",
            compact = true,
        ) {}
        headerRow.addView(clearAllBtn)

        val headerScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(headerRow)
        }
        dialogView.addView(headerScroll, marginBottom(10))

        val desc = text(
            "100% on-device memory. PocketAgent remembers key facts and preferences across conversations without sending data anywhere.",
            12f,
            palette.muted,
        )
        dialogView.addView(desc, marginBottom(12))

        val listContainer = column()
        val scroll = ScrollView(this).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(-1, dp(310))
        }
        dialogView.addView(scroll)

        fun refreshList() {
            listContainer.removeAllViews()
            val memories = userMemoryStore.listMemories()
            if (memories.isEmpty()) {
                listContainer.addView(
                    text("No memories recorded yet.\nAs you chat, preferences and facts will be remembered here.", 13f, palette.muted).apply {
                        setPadding(0, dp(30), 0, dp(30))
                        gravity = Gravity.CENTER
                    }
                )
                return
            }

            for (m in memories) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = shape(SURFACE, 12, BORDER)
                }

                val info = column().apply {
                    val catBadge = "[${m.displayLabel}]"
                    addView(text(catBadge, 11f, palette.teal, true))
                    addView(text(m.content, 13.5f, palette.ink).apply {
                        setPadding(0, dp(2), 0, 0)
                    })
                }
                card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

                val delBtn = iconTouchButton(
                    context = this,
                    palette = palette,
                    iconText = "✕",
                    accessibilityLabel = "Delete memory fact",
                    textColor = palette.muted,
                    textSizeSp = 16f,
                ) {
                    userMemoryStore.deleteMemory(m.id)
                    refreshList()
                }
                card.addView(delBtn)

                listContainer.addView(card, marginBottom(8))
            }
        }

        toggleButton.setOnClickListener {
            isMemoryEnabled = !isMemoryEnabled
            toggleButton.text = if (isMemoryEnabled) "Memory: ON" else "Memory: OFF"
            Toast.makeText(this, if (isMemoryEnabled) "Long-term memory enabled" else "Long-term memory paused", Toast.LENGTH_SHORT).show()
        }

        addFactBtn.setOnClickListener {
            promptAddMemoryFact { refreshList() }
        }

        clearAllBtn.setOnClickListener {
            val confirmDialog = AlertDialog.Builder(this)
                .setTitle("Clear All Memories?")
                .setMessage("Are you sure you want to permanently delete all stored memory facts?")
                .setPositiveButton("Clear") { _, _ ->
                    userMemoryStore.clearMemories()
                    refreshList()
                    Toast.makeText(this, "All memories cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .create()
            showStyledAlertDialog(confirmDialog, palette)
        }

        refreshList()

        val dialog = AlertDialog.Builder(this)
            .setTitle("🧠 Sovereign Memory Vault")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    private fun promptAddMemoryFact(onAdded: () -> Unit) {
        val input = EditText(this).apply {
            hint = "e.g., Prefers Kotlin, Speaks Bengali, Offline only…"
            textSize = 14f
            background = shape(SURFACE, 12, BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(INK)
            setHintTextColor(MUTED)
        }
        val categories = arrayOf("Preference", "Profile", "Constraint", "Goal", "Custom")
        var selectedCategoryIndex = 0

        val container = styleDialogContainer(this, palette, paddingDp = 16).apply {
            addView(input, marginBottom(12))
            addView(text("Category", 12.5f, palette.muted, true), marginBottom(6))
        }

        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val radioButtons = categories.mapIndexed { idx, label ->
            RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 14f
                setTextColor(palette.ink)
                buttonTintList = ColorStateList.valueOf(palette.teal)
                minHeight = dp(40)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                isChecked = idx == 0
                setOnClickListener { selectedCategoryIndex = idx }
            }.also { radioGroup.addView(it) }
        }
        if (radioButtons.isNotEmpty()) {
            radioGroup.check(radioButtons[0].id)
        }
        container.addView(radioGroup)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Memory Fact")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val fact = input.text.toString().trim()
                if (fact.isNotBlank()) {
                    val category = when (selectedCategoryIndex) {
                        0 -> UserMemoryStore.MemoryCategory.PREFERENCE
                        1 -> UserMemoryStore.MemoryCategory.PROFILE
                        2 -> UserMemoryStore.MemoryCategory.CONSTRAINT
                        3 -> UserMemoryStore.MemoryCategory.GOAL
                        else -> UserMemoryStore.MemoryCategory.CUSTOM
                    }
                    userMemoryStore.addFact(fact, category)
                    onAdded()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    private fun showNotesManagerDialog() {
        val dialogView = styleDialogContainer(this, palette, paddingDp = 16)

        val searchBox = EditText(this).apply {
            hint = "Search personal notes…"
            textSize = 14f
            background = shape(SURFACE, 12, BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(INK)
            setHintTextColor(MUTED)
        }
        dialogView.addView(searchBox, marginBottom(12))

        val listContainer = column()
        val scroll = ScrollView(this).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(-1, dp(340))
        }
        dialogView.addView(scroll)

        fun refreshList(query: String = "") {
            listContainer.removeAllViews()
            val notes = notesStore.searchNotes(query)
            if (notes.isEmpty()) {
                listContainer.addView(text("No notes found.", 13f, palette.muted).apply {
                    setPadding(0, dp(30), 0, dp(30))
                    gravity = Gravity.CENTER
                })
                return
            }

            val dateFormatter = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
            for (n in notes) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = shape(SURFACE, 12, BORDER)
                }

                val info = column().apply {
                    val pinPrefix = if (n.isPinned) "📌 " else ""
                    addView(text("$pinPrefix${n.title}", 14.5f, palette.ink, true))
                    if (n.content.isNotBlank()) {
                        val preview = n.content.take(90).replace("\n", " ") + (if (n.content.length > 90) "…" else "")
                        addView(text(preview, 12.5f, palette.muted).apply {
                            setPadding(0, dp(2), 0, dp(2))
                        })
                    }
                    val dateStr = dateFormatter.format(java.util.Date(n.updatedAt))
                    addView(text(dateStr, 11f, palette.teal))
                }
                card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

                card.setOnClickListener {
                    promptEditNote(n) { refreshList(searchBox.text.toString()) }
                }

                val pinBtn = iconTouchButton(
                    context = this,
                    palette = palette,
                    iconText = if (n.isPinned) "📌" else "📍",
                    accessibilityLabel = if (n.isPinned) "Unpin note ${n.title}" else "Pin note ${n.title}",
                    textColor = if (n.isPinned) palette.teal else palette.muted,
                    textSizeSp = 15f,
                ) {
                    notesStore.pinNote(n.id, !n.isPinned)
                    refreshList(searchBox.text.toString())
                }
                card.addView(pinBtn)

                val delBtn = iconTouchButton(
                    context = this,
                    palette = palette,
                    iconText = "✕",
                    accessibilityLabel = "Delete note ${n.title}",
                    textColor = palette.muted,
                    textSizeSp = 16f,
                ) {
                    notesStore.deleteNote(n.id)
                    refreshList(searchBox.text.toString())
                }
                card.addView(delBtn)

                listContainer.addView(card, marginBottom(8))
            }
        }

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        refreshList()

        val dialog = AlertDialog.Builder(this)
            .setTitle("📝 Personal Notes (${notesStore.listNotes().size})")
            .setView(dialogView)
            .setPositiveButton("➕ New Note") { _, _ ->
                promptEditNote(null) { refreshList(searchBox.text.toString()) }
            }
            .setNeutralButton("Export All") { _, _ ->
                val md = notesStore.exportNotesAsMarkdown()
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_TEXT, md)
                    putExtra(Intent.EXTRA_SUBJECT, "PocketAgent Notes Export")
                }
                startActivity(Intent.createChooser(shareIntent, "Export Notes"))
            }
            .setNegativeButton("Close", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    private fun promptEditNote(existingNote: PersonalNotesStore.PersonalNote?, onSaved: () -> Unit) {
        val container = styleDialogContainer(this, palette, paddingDp = 16)

        val titleInput = EditText(this).apply {
            hint = "Note Title"
            textSize = 14f
            background = shape(SURFACE, 12, BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(INK)
            setHintTextColor(MUTED)
            if (existingNote != null) setText(existingNote.title)
        }
        container.addView(titleInput, marginBottom(10))

        val contentInput = EditText(this).apply {
            hint = "Note Content…"
            textSize = 13f
            background = shape(SURFACE, 12, BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(INK)
            setHintTextColor(MUTED)
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            if (existingNote != null) setText(existingNote.content)
        }
        container.addView(contentInput, marginBottom(4))

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existingNote == null) "➕ Create Note" else "✏️ Edit Note")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val title = titleInput.text.toString().trim()
                val content = contentInput.text.toString().trim()
                if (title.isNotBlank()) {
                    if (existingNote == null) {
                        notesStore.createNote(title = title, content = content)
                    } else {
                        notesStore.saveNote(existingNote.withUpdatedContent(title, content))
                    }
                    onSaved()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    private fun showDuplexVoiceDialog() {
        if (engineManager.isGenerationActive || isGeneratingUi) {
            Toast.makeText(this, "Stop active text generation before starting voice mode.", Toast.LENGTH_SHORT).show()
            return
        }
        if (whisperContext == 0L) {
            Toast.makeText(this, "Select a local Whisper model first.", Toast.LENGTH_SHORT).show()
            return
        }
        val model = File(filesDir, "model.litertlm")
        if (!model.exists() || model.length() == 0L) {
            Toast.makeText(this, "Select a local LiteRT-LM model first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!InAppAudioRecorder.hasMicrophonePermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_MIC)
            return
        }

        val dialogView = column().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(palette.heroGradientStart, palette.heroGradientEnd),
            ).apply { cornerRadius = dp(20).toFloat() }
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val title = text("🎙️ Continuous Voice Mode", 18f, Color.WHITE, true)
        dialogView.addView(title)

        val stateBadge = text("Initializing…", 13f, palette.heroSubtitleInk, true).apply {
            setPadding(0, dp(6), 0, dp(8))
        }
        dialogView.addView(stateBadge)

        val audioLevelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 4
            progressDrawable = android.graphics.drawable.LayerDrawable(
                arrayOf(
                    roundedShape(0x33FFFFFF, dp(4).toFloat()),
                    android.graphics.drawable.ClipDrawable(
                        roundedShape(Color.WHITE, dp(4).toFloat()),
                        Gravity.START,
                        android.graphics.drawable.ClipDrawable.HORIZONTAL,
                    ),
                )
            ).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            contentDescription = "Microphone input audio level"
        }
        dialogView.addView(audioLevelBar, LinearLayout.LayoutParams(-1, dp(6)).apply { bottomMargin = dp(12) })

        val transcriptView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            text = "Say something to start speaking…"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = shape(0x33000000, 12)
        }
        val scroll = ScrollView(this).apply {
            addView(transcriptView)
            layoutParams = LinearLayout.LayoutParams(-1, dp(220))
        }
        dialogView.addView(scroll, marginBottom(16))

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val interruptBtn = Button(this).apply {
            text = "Interrupt / Next"
            contentDescription = "Interrupt and listen again"
            setAllCaps(false)
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rippleDrawable(
                roundedShape(0x2EFFFFFF, dp(24).toFloat(), maxOf(1, dp(1)), 0x55FFFFFF),
                palette.rippleOnAccent,
            )
            backgroundTintList = null
            stateListAnimator = null
            setOnClickListener { duplexController.interrupt() }
        }
        btnRow.addView(interruptBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })

        lateinit var dialog: AlertDialog

        val endBtn = Button(this).apply {
            text = "End Session"
            contentDescription = "End voice duplex mode"
            setAllCaps(false)
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(palette.heroGradientStart)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rippleDrawable(
                roundedShape(Color.WHITE, dp(24).toFloat()),
                palette.rippleOnMint,
            )
            backgroundTintList = null
            stateListAnimator = null
            setOnClickListener {
                duplexController.stopSession()
                dialog.dismiss()
            }
        }
        btnRow.addView(endBtn, LinearLayout.LayoutParams(0, -2, 1f))
        dialogView.addView(btnRow)

        val messageCountBeforeVoice = currentSession?.messages?.size ?: 0
        dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.setOnDismissListener {
            if (duplexController.isActive) duplexController.stopSession()
            val updatedSess = currentSession
            if (updatedSess != null && updatedSess.messages.size > messageCountBeforeVoice) {
                loadSession(updatedSess.id)
            }
        }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()

        var duplexUserText = ""
        val duplexAssistantStream = StringBuilder()

        duplexController.startSession(
            whisperContext = whisperContext,
            systemInstruction = SYSTEM_INSTRUCTION,
            listener = object : DuplexVoiceController.Listener {
                override fun onStateChanged(state: DuplexVoiceController.DuplexState, statusMessage: String) {
                    val label = when (state) {
                        DuplexVoiceController.DuplexState.LISTENING -> "🎙️ Listening for speech…"
                        DuplexVoiceController.DuplexState.TRANSCRIBING -> "⚡ Transcribing audio locally…"
                        DuplexVoiceController.DuplexState.GENERATING -> "🧠 Thinking on-device…"
                        DuplexVoiceController.DuplexState.SPEAKING -> "🔊 Speaking response…"
                        DuplexVoiceController.DuplexState.ERROR -> "⚠️ $statusMessage"
                        DuplexVoiceController.DuplexState.IDLE -> "Idle"
                    }
                    stateBadge.text = label
                    if (state != DuplexVoiceController.DuplexState.LISTENING) {
                        audioLevelBar.progress = 0
                    }
                }

                override fun onAmplitude(rms: Float) {
                    val scaled = (rms * 320f).toInt().coerceIn(4, 100)
                    audioLevelBar.progress = scaled
                }

                override fun onUserTranscribed(text: String) {
                    duplexUserText = text
                    duplexAssistantStream.clear()
                    transcriptView.text = "You: $text\n\nAssistant: Thinking…"
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }

                override fun onAssistantChunk(chunk: String) {
                    duplexAssistantStream.append(chunk)
                    transcriptView.text = "You: $duplexUserText\n\nAssistant: $duplexAssistantStream"
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }

                override fun onTurnComplete(userText: String, assistantText: String) {
                    duplexUserText = userText
                    duplexAssistantStream.clear()
                    transcriptView.text = "You: $userText\n\nAssistant: $assistantText"
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

                    try {
                        val sess = currentSession ?: ConversationSession.createWithInitialPrompt(userText)
                        val uMsg = ChatMessage.user(userText)
                        val aMsg = ChatMessage.assistant(assistantText)
                        val updated = sess.withMessage(uMsg).withMessage(aMsg)
                        sessionStore.saveSession(updated)
                        currentSession = updated
                    } catch (_: Exception) {}
                }

                override fun onError(error: Throwable) {
                    stateBadge.text = "⚠️ Error: ${error.message}"
                    audioLevelBar.progress = 0
                }
            }
        )
    }

    private fun showPrivacyCenterDialog() {
        val permAudit = PrivacyAuditCenter.auditPermissions(this)
        val storageAudit = PrivacyAuditCenter.auditStorage(filesDir, cacheDir)

        val dialogView = styleDialogContainer(this, palette, paddingDp = 16)
        val bodyCol = column()
        val bodyScroll = ScrollView(this).apply {
            addView(bodyCol)
        }
        dialogView.addView(bodyScroll, LinearLayout.LayoutParams(-1, -2))

        var privacyDialog: AlertDialog? = null

        // 1. Appearance (Theme: System / Light / Dark)
        val currentThemeMode = ThemeMode.fromString(
            settingsPrefs.getString(UiThemeResolver.PREF_THEME_MODE, null)
        )
        val appearanceCard = cardContainer(this, palette, cornerDp = 14, elevated = true, paddingDp = 14)
        appearanceCard.addView(text("🎨 Appearance", 14f, palette.ink, true))
        appearanceCard.addView(
            text("Choose Light, Dark, or match your device theme", 11.5f, palette.muted).apply {
                setPadding(0, dp(2), 0, dp(10))
            }
        )
        val themeSegmentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val themeModes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
        for ((index, mode) in themeModes.withIndex()) {
            val isSelected = (mode == currentThemeMode)
            val segBtn = Button(this).apply {
                text = mode.displayLabel
                contentDescription = "Set appearance theme to ${mode.displayLabel}"
                setAllCaps(false)
                textSize = 12.5f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(if (isSelected) palette.teal else palette.ink)
                gravity = Gravity.CENTER
                minHeight = dp(48)
                minimumHeight = dp(48)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rippleDrawable(
                    roundedShape(
                        color = if (isSelected) palette.mint else palette.surface,
                        radiusPx = dp(14).toFloat(),
                        strokeWidthPx = maxOf(1, dp(if (isSelected) 2 else 1)),
                        strokeColor = if (isSelected) palette.teal else palette.border,
                    ),
                    if (isSelected) palette.rippleOnMint else palette.rippleOnSurface,
                )
                backgroundTintList = null
                stateListAnimator = null
                setOnClickListener {
                    if (engineManager.isGenerationActive || isGeneratingUi) {
                        Toast.makeText(this@MainActivity, "Stop active generation before switching theme.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    settingsPrefs.edit().putString(UiThemeResolver.PREF_THEME_MODE, mode.name).apply()
                    privacyDialog?.dismiss()
                    recreate()
                }
            }
            themeSegmentRow.addView(
                segBtn,
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index < themeModes.lastIndex) marginEnd = dp(6)
                },
            )
        }
        appearanceCard.addView(themeSegmentRow)
        bodyCol.addView(appearanceCard, marginBottom(12))

        // 2. Zero-Cloud Telemetry Guarantee Badge
        val auditCard = column().apply {
            val bg = if (isDarkTheme) 0xFF1B3A2F.toInt() else 0xFFE8F5E9.toInt()
            val border = if (isDarkTheme) 0xFF2E5A4A.toInt() else 0xFFC8E6C9.toInt()
            background = shape(bg, 12, border)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val auditTitle = text("🛡️ 100% Zero-Cloud Guarantee", 14f, if (isDarkTheme) 0xFFB9F6CA.toInt() else 0xFF1B5E20.toInt(), true)
        auditCard.addView(auditTitle)
        val auditDesc = text(
            if (permAudit.isZeroCloudCompliant)
                "Verified: Hardware-level zero cloud telemetry. PocketAgent does not request or hold 'android.permission.INTERNET'. All audio, documents, and inference run 100% locally on this device."
            else
                "Warning: Non-standard permissions detected.",
            12f,
            if (isDarkTheme) 0xFFA5D6A7.toInt() else 0xFF2E7D32.toInt()
        ).apply { setPadding(0, dp(4), 0, 0) }
        auditCard.addView(auditDesc)
        dialogView.addView(auditCard, marginBottom(12))

        // 2. Storage Audit Card
        val storageCard = column().apply {
            background = shape(SURFACE, 12, BORDER)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        storageCard.addView(text("💾 Local Storage Breakdown", 14f, INK, true), marginBottom(8))

        fun addStorageRow(label: String, value: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(text(label, 12f, palette.muted), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(value, 12f, palette.ink, true))
            storageCard.addView(row)
        }

        addStorageRow("AI Models (.litertlm & whisper):", storageAudit.modelDisplay)
        addStorageRow("Saved Conversations:", storageAudit.sessionsDisplay)
        addStorageRow("Long-Term Memories:", storageAudit.memoriesDisplay)
        addStorageRow("Personal Notes:", storageAudit.notesDisplay)
        addStorageRow("Temporary Cache:", storageAudit.cacheDisplay)
        addStorageRow("Total Storage Used:", storageAudit.totalDisplay)
        bodyCol.addView(storageCard, marginBottom(12))

        // 4. Biometric App Lock Row
        val lockCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(SURFACE, 12, BORDER)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val lockInfo = column().apply {
            addView(text("🔒 Biometric App Lock", 14f, INK, true))
            addView(text("Require device credential/biometric on resume", 11f, MUTED))
        }
        lockCard.addView(lockInfo, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })

        val lockToggleBtn = com.example.pocketagent.pillButton(
            context = this,
            palette = palette,
            label = if (biometricLockManager.isLockEnabled) "Enabled" else "Disabled",
            accessibilityLabel = "Toggle biometric app lock",
            compact = true,
        ) {}
        lockToggleBtn.setOnClickListener {
            val enabling = !biometricLockManager.isLockEnabled
            if (enabling) {
                if (!biometricLockManager.canUseBiometrics(this)) {
                    Toast.makeText(this, "Set a device screen lock or biometric first.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                biometricLockManager.setLockEnabled(true)
                lockToggleBtn.text = "Enabled"
                updateLockOverlay()
                Toast.makeText(this, "App lock enabled. You'll be asked to authenticate on resume.", Toast.LENGTH_SHORT).show()
            } else {
                biometricLockManager.setLockEnabled(false)
                lockToggleBtn.text = "Disabled"
                updateLockOverlay()
                Toast.makeText(this, "App lock disabled", Toast.LENGTH_SHORT).show()
            }
        }
        lockCard.addView(lockToggleBtn)
        bodyCol.addView(lockCard, marginBottom(12))

        // 5. Panic Wipe Button
        val panicBtn = dangerButton(
            context = this,
            palette = palette,
            label = "⚠️ Panic Wipe All User Data",
            accessibilityLabel = "Emergency delete all personal data",
        ) {
            val wipeDialog = AlertDialog.Builder(this)
                .setTitle("🚨 Panic Data Wipe")
                .setMessage("This will permanently delete all conversation history, memories, and personal notes on this device. (AI models will be kept).\n\nProceed?")
                .setPositiveButton("Erase Everything") { _, _ ->
                    PrivacyAuditCenter.wipeAllUserData(filesDir, cacheDir, purgeModels = false)
                    startNewConversation()
                    Toast.makeText(this, "All personal data has been erased.", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }.apply {
            val bg = if (isDarkTheme) 0xFF3A1F1F.toInt() else 0xFFFFEBEE.toInt()
            val border = if (isDarkTheme) 0xFF5C2A2A.toInt() else 0xFFFFCDD2.toInt()
            background = shape(bg, 12, border)
            setTextColor(if (isDarkTheme) 0xFFFF8A80.toInt() else 0xFFC62828.toInt())
        }
        bodyCol.addView(panicBtn, LinearLayout.LayoutParams(-1, -2))

        val dialog = AlertDialog.Builder(this)
            .setTitle("🛡️ Privacy & Appearance")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()
        privacyDialog = dialog
        showStyledAlertDialog(dialog, palette)
    }

    override fun onResume() {
        super.onResume()
        if (biometricLockManager.shouldLockOnResume()) {
            biometricLockManager.lock()
        }
        updateLockOverlay()
        if (biometricLockManager.isAppLocked) {
            promptBiometricUnlock()
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations && !isAuthenticating) {
            biometricLockManager.markBackgrounded()
        }
        super.onStop()
    }

    private fun buildLockOverlay(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(palette.scrimOverlay)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setPadding(dp(24), dp(24), dp(24), dp(24))

            val card = cardContainer(context, palette, cornerDp = 20, elevated = true, paddingDp = 24)
            card.addView(text("PocketAgent is locked", 22f, palette.ink, true))
            card.addView(
                text(
                    "Authenticate to access your local conversations, memories, and notes.",
                    14f,
                    palette.muted,
                ).apply {
                    setPadding(0, dp(10), 0, dp(18))
                }
            )
            card.addView(
                pillButton("Unlock", accessibilityLabel = "Unlock PocketAgent") {
                    promptBiometricUnlock()
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            addView(card, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun updateLockOverlay() {
        if (!::lockOverlay.isInitialized) return
        lockOverlay.visibility = if (biometricLockManager.isAppLocked) View.VISIBLE else View.GONE
    }



    private fun promptBiometricUnlock() {
        if (!::lockOverlay.isInitialized) return
        if (!biometricLockManager.isLockEnabled) {
            biometricLockManager.unlock()
            updateLockOverlay()
            return
        }
        if (!biometricLockManager.isAppLocked) {
            updateLockOverlay()
            return
        }
        if (isAuthenticating) return
        if (!biometricLockManager.canUseBiometrics(this)) {
            updateStatus("Set a device screen lock or biometric to use app lock.")
            return
        }
        isAuthenticating = true
        biometricLockManager.authenticate(
            activity = this,
            onSuccess = {
                isAuthenticating = false
                updateLockOverlay()
                updateStatus("PocketAgent unlocked.")
            },
            onError = { msg ->
                isAuthenticating = false
                updateLockOverlay()
                updateStatus("Unlock failed: $msg")
            },
            onCancel = {
                isAuthenticating = false
                updateLockOverlay()
                updateStatus("Authentication required to unlock PocketAgent.")
            },
        )
    }

    private fun showActionReviewDialog(reviewUi: SystemActionTools.ActionReviewUi) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(reviewUi.dialogTitle)
            .setMessage(reviewUi.dialogMessage)
            .setPositiveButton("Open in ${reviewUi.targetAppCategory}") { _, _ ->
                try {
                    startActivity(reviewUi.action.toIntent())
                    updateStatus("Opened ${reviewUi.targetAppCategory}. Confirm and save your action there.")
                } catch (_: ActivityNotFoundException) {
                    updateStatus("No application is available on this device for ${reviewUi.targetAppCategory}.")
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    private fun startDictation() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            updateStatus("Allow microphone access, then tap Dictate again.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateStatus("No speech recognizer is available. Type your task or attach a text file instead.")
            return
        }
        recognizer?.destroy()
        val speech = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = speech
        isDictating = true
        updateRecordingBanner()
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isDictating = true
                updateRecordingBanner()
                updateStatus("Listening… speak your task now.")
            }
            override fun onResults(results: Bundle?) {
                isDictating = false
                updateRecordingBanner()
                val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (transcript != null) {
                    taskInput.setText(transcript)
                    taskInput.setSelection(taskInput.text.length)
                    syncComposerState()
                    updateStatus("Transcript ready. Review it, then confirm.")
                } else updateStatus("No speech was recognized. You can type instead.")
                speech.destroy()
                if (recognizer === speech) recognizer = null
            }
            override fun onError(error: Int) {
                isDictating = false
                updateRecordingBanner()
                updateStatus("Speech recognition failed ($error). You can type instead.")
                speech.destroy()
                if (recognizer === speech) recognizer = null
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isDictating = false
                updateRecordingBanner()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speech.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        )
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

    private fun saveAnswerToFile() {
        if (!answerIsComplete) {
            updateStatus("Wait until a complete answer is available before saving it.")
            return
        }
        val answer = latestAssistantText.trim()
        if (answer.isBlank()) {
            updateStatus("Generate an answer before saving it.")
            return
        }
        if (answer.length > AnswerTextWriter.MAX_CHARS) {
            updateStatus("This answer is too large to save as a text result.")
            return
        }
        pendingAnswerToSave = answer
        val picker = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE, "PocketAgent-answer.txt")
        try {
            updateStatus("Choose where to save the completed answer. PocketAgent writes only to your selection.")
            startActivityForResult(picker, CREATE_ANSWER_FILE)
        } catch (_: ActivityNotFoundException) {
            pendingAnswerToSave = null
            updateStatus("No file-saving app is available. You can still copy or share the answer.")
        }
    }

    private fun saveAnswerToUri(uri: Uri, answer: String) {
        updateStatus("Saving the answer to your selected location…")
        ioExecutor.execute {
            try {
                val output = contentResolver.openOutputStream(uri, "w")
                    ?: error("Could not open the selected destination.")
                output.use { AnswerTextWriter.write(it, answer) }
                mainHandler.post {
                    updateStatus("Answer saved to the location you selected.")
                    Toast.makeText(this, "Answer saved", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    updateStatus("Could not save the answer: ${e.message ?: "storage error"}")
                }
            }
        }
    }

    private fun copyAnswerText(text: String) {
        if (text.isBlank()) return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("PocketAgent answer", text))
        Toast.makeText(this, "Answer copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareAnswerText(text: String) {
        if (text.isBlank()) return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                "Share answer",
            )
        )
    }

    private fun speakAnswerText(raw: String) {
        val text = MarkdownFormatter.stripToPlainText(raw)
        if (!ttsReady || text.isBlank()) {
            Toast.makeText(this, "Speech is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pocketagent-answer")
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.setLanguage(Locale.getDefault())
    }

    private fun refreshModelStatus() {
        if (!::modelStatusPill.isInitialized) return
        val model = File(filesDir, "model.litertlm")
        val hasModel = model.exists() && model.length() > 0L
        val savedName = if (hasModel) settingsPrefs.getString(PREF_MODEL_DISPLAY_NAME, null) else null
        val sizeBytes = if (hasModel) model.length() else 0L
        val formattedSize = if (hasModel) formatStorageSize(sizeBytes) else ""
        val badgeState = ComposerChromeHelper.formatModelBadge(
            displayName = savedName,
            sizeBytes = sizeBytes,
            activeBackend = if (hasModel) engineManager.activeBackend else null,
            preference = engineManager.preference,
            formattedSize = formattedSize,
        )
        modelStatusPill.update(badgeState, palette)
        if (::heroModelCtaButton.isInitialized) {
            heroModelCtaButton.visibility = if (hasModel) View.GONE else View.VISIBLE
        }
    }

    private fun showModelManagerDialog() {
        val model = File(filesDir, "model.litertlm")
        if (!model.exists() || model.length() == 0L) {
            chooseModel()
            return
        }
        val modelName = settingsPrefs.getString(PREF_MODEL_DISPLAY_NAME, null) ?: "model.litertlm"
        val sizeStr = formatStorageSize(model.length())
        val isCpuOnly = engineManager.preference == BackendPreference.CPU_ONLY
        val runtimeState = when (engineManager.activeBackend) {
            ActiveBackend.GPU -> "Loaded in RAM (GPU)"
            ActiveBackend.CPU -> "Loaded in RAM (CPU)"
            null -> "Stored on disk (standby · 0 RAM)"
        }

        val dialogView = styleDialogContainer(this, palette, paddingDp = 16)
        val bodyCol = column()
        val bodyScroll = ScrollView(this).apply { addView(bodyCol) }
        dialogView.addView(bodyScroll, LinearLayout.LayoutParams(-1, -2))

        val infoCard = cardContainer(this, palette, cornerDp = 14, elevated = true, paddingDp = 14)
        infoCard.addView(text(modelName, 15f, palette.ink, true))
        infoCard.addView(text("Size: $sizeStr  ·  $runtimeState", 12.5f, palette.muted).apply {
            setPadding(0, dp(4), 0, dp(8))
        })
        infoCard.addView(
            text(
                "Note: Multimodal (.litertlm Omni) weights occupy storage & memory for their bundle, while PocketAgent runs text inference only (vision/audio towers kept disabled) to save RAM.",
                11.5f,
                palette.muted,
            )
        )
        bodyCol.addView(infoCard, marginBottom(12))

        lateinit var dialog: AlertDialog

        bodyCol.addView(text("Inference Backend", 12.5f, palette.muted, true), marginBottom(6))

        fun switchBackendPreference(next: BackendPreference) {
            if (engineManager.preference == next) return
            if (engineManager.isGenerationActive || isGeneratingUi) {
                Toast.makeText(this, "Stop generation before changing backend.", Toast.LENGTH_SHORT).show()
                return
            }
            settingsPrefs.edit()
                .putString(PREF_BACKEND_MODE, next.name)
                .putBoolean(PREF_GPU_LOADING_GUARD, false)
                .apply()
            dialog.dismiss()
            updateStatus("Switching backend to ${if (next == BackendPreference.CPU_ONLY) "CPU Only" else "GPU (auto fallback)"}…")
            inferenceExecutor.execute {
                engineManager.switchBackend(next)
                mainHandler.post {
                    refreshModelStatus()
                    updateStatus("Backend set to ${if (next == BackendPreference.CPU_ONLY) "CPU Only" else "GPU (auto fallback)"}. Ready for your next question.")
                }
            }
        }

        fun buildBackendCard(
            title: String,
            subtitle: String,
            selected: Boolean,
            onSelect: () -> Unit,
        ): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rippleDrawable(
                roundedShape(
                    color = if (selected) palette.mint else palette.surface,
                    radiusPx = dp(14).toFloat(),
                    strokeWidthPx = maxOf(1, dp(if (selected) 2 else 1)),
                    strokeColor = if (selected) palette.teal else palette.border,
                ),
                if (selected) palette.rippleOnMint else palette.rippleOnSurface,
            )
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $subtitle"
            addView(
                text(
                    if (selected) "✓ $title" else title,
                    13.5f,
                    if (selected) palette.teal else palette.ink,
                    true,
                )
            )
            addView(text(subtitle, 11.5f, palette.muted).apply {
                setPadding(0, dp(2), 0, 0)
            })
            setOnClickListener { onSelect() }
        }

        val backendRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backendRow.addView(
            buildBackendCard(
                title = "GPU + Auto Fallback",
                subtitle = "Fastest when supported",
                selected = !isCpuOnly,
            ) { switchBackendPreference(BackendPreference.GPU_PREFERRED) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) },
        )
        backendRow.addView(
            buildBackendCard(
                title = "CPU Only",
                subtitle = "Maximum compatibility",
                selected = isCpuOnly,
            ) { switchBackendPreference(BackendPreference.CPU_ONLY) },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        bodyCol.addView(backendRow, marginBottom(12))

        bodyCol.addView(
            secondaryOutlineButton(
                context = this,
                palette = palette,
                label = "🧹 Unload Model from RAM (Keep File)",
                accessibilityLabel = "Release model memory from RAM",
            ) {
                if (engineManager.isGenerationActive || isGeneratingUi) {
                    Toast.makeText(this, "Stop generation before unloading model.", Toast.LENGTH_SHORT).show()
                    return@secondaryOutlineButton
                }
                dialog.dismiss()
                inferenceExecutor.execute {
                    engineManager.close()
                    mainHandler.post {
                        refreshModelStatus()
                        updateStatus("Model unloaded from RAM ($sizeStr file kept on disk). Will reload on next prompt.")
                    }
                }
            },
            marginBottom(8),
        )

        bodyCol.addView(
            pillButton("📂 Switch / Choose Different .litertlm File", accessibilityLabel = "Pick a different LiteRT-LM model file") {
                dialog.dismiss()
                chooseModel()
            },
            marginBottom(8),
        )

        bodyCol.addView(
            dangerButton(
                context = this,
                palette = palette,
                label = "🗑️ Remove Stored Model ($sizeStr)",
                accessibilityLabel = "Delete stored model file to free space",
            ) {
                if (engineManager.isGenerationActive || isGeneratingUi) {
                    Toast.makeText(this, "Stop generation before removing model.", Toast.LENGTH_SHORT).show()
                    return@dangerButton
                }
                dialog.dismiss()
                val confirmDialog = AlertDialog.Builder(this)
                    .setTitle("Remove Local Model?")
                    .setMessage("Delete $modelName ($sizeStr) from app private storage to free disk space?")
                    .setPositiveButton("Remove") { _, _ ->
                        inferenceExecutor.execute {
                            engineManager.close()
                            ModelFileStore(filesDir).removeStoredModel()
                            ModelFileStore.clearEngineCaches(cacheDir)
                            settingsPrefs.edit()
                                .remove(PREF_MODEL_DISPLAY_NAME)
                                .putBoolean(PREF_GPU_LOADING_GUARD, false)
                                .apply()
                            mainHandler.post {
                                refreshModelStatus()
                                syncComposerState()
                                updateStatus("Stored model removed. Freed $sizeStr.")
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                showStyledAlertDialog(confirmDialog, palette)
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        dialog = AlertDialog.Builder(this)
            .setTitle("⚙️ Local Model & Engine")
            .setView(dialogView)
            .setNegativeButton("Close", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    private fun cleanupStagedInputFiles() {
        cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("pocketagent-pdf-") || file.name.startsWith("pocketagent-audio-") || file.name.startsWith("pocketagent-mic-")) {
                file.delete()
            }
        }
    }

    private fun restoreWhisperModel() {
        val store = WhisperModelStore(filesDir)
        val model = store.modelFile
        if (!model.isFile) return
        whisperModelText.text = "Loading saved local Whisper model…"
        updateWhisperActionButtons(false)
        inferenceExecutor.execute {
            try {
                store.discardCandidate()
                require(model.length() in 1..WhisperModelStore.MAX_MODEL_BYTES) {
                    "Saved model is empty or exceeds the 512 MB limit."
                }
                val loaded = WhisperNative.loadModel(model.absolutePath)
                require(loaded != 0L) { "Saved ggml model could not be loaded." }
                whisperContext = loaded
                mainHandler.post {
                    whisperModelText.text = "Local Whisper model ready (${formatBytes(model.length())})."
                    updateWhisperActionButtons(true)
                    updateStatus("Local Whisper model ready. Record audio directly or choose a PCM WAV file.")
                }
            } catch (e: Exception) {
                whisperContext = 0L
                mainHandler.post {
                    whisperModelText.text = "Saved Whisper model could not be loaded. Choose another .bin model or remove it."
                    updateWhisperActionButtons(false)
                    updateStatus("Could not load saved Whisper model: ${e.message ?: "unsupported model"}")
                }
            }
        }
    }

    private fun updateStatus(message: String) {
        if (::statusText.isInitialized) statusText.text = message
    }

    private fun formatBytes(bytes: Long): String = if (bytes >= 1024L * 1024) "${bytes / (1024 * 1024)} MB" else "${bytes / 1024} KB"
    private fun formatStorageSize(bytes: Long): String = PrivacyAuditCenter.StorageAudit.formatBytes(bytes)
    private fun dp(value: Int): Int = com.example.pocketagent.dp(this, value)

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun marginBottom(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(value) }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        styledText(this, value, size, color, bold)

    private fun shape(color: Int, radius: Int, stroke: Int? = null) =
        roundedShape(
            color = color,
            radiusPx = dp(radius).toFloat(),
            strokeWidthPx = if (stroke != null) maxOf(1, dp(1)) else 0,
            strokeColor = stroke,
        )

    private fun sectionHeading(title: String, subtitle: String): LinearLayout = column().apply {
        addView(text(title, 18f, palette.ink, true))
        addView(text(subtitle, 12f, palette.muted).apply { setPadding(0, dp(3), 0, 0) })
    }

    private fun pillButton(
        label: String,
        accessibilityLabel: String = label,
        compact: Boolean = false,
        onClick: () -> Unit,
    ): Button = com.example.pocketagent.pillButton(
        context = this,
        palette = palette,
        label = label,
        accessibilityLabel = accessibilityLabel,
        compact = compact,
        onClick = onClick,
    )

    private fun textButton(
        label: String,
        accessibilityLabel: String = label,
        onClick: () -> Unit,
    ): Button = Button(this).apply {
        val touchSize = dp(48)
        text = label
        contentDescription = accessibilityLabel
        setAllCaps(false)
        textSize = 12f
        setTextColor(palette.teal)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        minHeight = touchSize
        minimumHeight = touchSize
        minWidth = touchSize
        minimumWidth = touchSize
        background = rippleDrawable(shape(Color.TRANSPARENT, 8), palette.rippleOnMint)
        backgroundTintList = null
        stateListAnimator = null
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun actionButton(
        label: String,
        accessibilityLabel: String = label,
        onClick: () -> Unit,
    ): Button = Button(this).apply {
        val touchSize = dp(48)
        text = label
        contentDescription = accessibilityLabel
        setAllCaps(false)
        textSize = 16f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        val gradient = gradientPillDrawable(
            palette.heroGradientStart,
            palette.heroGradientEnd,
            dp(16).toFloat(),
        )
        background = rippleDrawable(gradient, palette.rippleOnAccent)
        backgroundTintList = null
        stateListAnimator = null
        minHeight = touchSize
        minimumHeight = touchSize
        minWidth = touchSize
        minimumWidth = touchSize
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun ripple(content: GradientDrawable, color: Int) =
        rippleDrawable(content, color)

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // Under memory pressure, drop warm conversation state; keep engine loaded if possible.
            engineManager.invalidateConversation("trim memory level $level")
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            if (!isGeneratingUi) {
                inferenceExecutor.execute { engineManager.close() }
                updateStatus("Model unloaded to free memory. It will reload on next request.")
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        engineManager.invalidateConversation("low memory")
        if (!isGeneratingUi) {
            inferenceExecutor.execute { engineManager.close() }
        }
    }

    override fun onDestroy() {
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
        streamingRenderer.cancel()
        engineManager.cancelActiveConversation()
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

    private fun renderCitations(
        citations: List<LocalDocumentRetriever.DocumentCitation>,
        container: LinearLayout,
    ) {
        container.removeAllViews()
        if (citations.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        container.addView(text("📌 Document Citations", 12f, palette.teal, true).apply {
            setPadding(0, 0, 0, dp(4))
        })
        val wrapRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (cit in citations) {
            val pill = pillButton(
                label = "📄 ${cit.citationLabel}",
                accessibilityLabel = "View citation from ${cit.citationLabel}",
                compact = true,
            ) {
                showCitationDialog(cit)
            }
            wrapRow.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        val hScroll = HorizontalScrollView(this).apply {
            addView(wrapRow)
            isHorizontalScrollBarEnabled = false
        }
        container.addView(hScroll)
    }

    private fun showCitationDialog(citation: LocalDocumentRetriever.DocumentCitation) {
        val dialogView = styleDialogContainer(this, palette, paddingDp = 16).apply {
            addView(text(citation.citationLabel, 16f, palette.ink, true))
            val snippetText = citation.excerptSnippet.ifBlank { "No excerpt snippet available." }
            addView(text("\"$snippetText…\"", 13.5f, palette.muted).apply {
                setPadding(0, dp(10), 0, dp(4))
                setLineSpacing(dp(3).toFloat(), 1.0f)
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }
}
