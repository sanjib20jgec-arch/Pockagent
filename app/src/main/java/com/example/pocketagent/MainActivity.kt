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
import android.view.View
import android.view.animation.DecelerateInterpolator
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
        private const val PICK_MODEL = 1
        private const val PICK_TEXT_FILES = 2
        private const val REQUEST_MIC = 3
        private const val PICK_PDF = 4
        private const val PICK_WHISPER_MODEL = 5
        private const val PICK_WAV = 6
        private const val CREATE_ANSWER_FILE = 7
        private const val REQUEST_RECORD_MIC = 8
        private const val PICK_IMAGE_OCR = 9
        private const val CREATE_ARTIFACT_HTML = 10
        private const val STATE_PENDING_ANSWER_SAVE = "pending_answer_to_save"
        private const val STATE_ACTIVE_SESSION_ID = "active_session_id"
        private const val PREFS_SETTINGS = "pocketagent_settings"
        private const val PREF_BACKEND_MODE = "pref_backend_mode"
        private const val PREF_MODEL_DISPLAY_NAME = "pref_model_display_name"
        private const val PREF_GPU_LOADING_GUARD = "pref_gpu_loading_crash_guard"
        private const val PREF_ACTIVE_PROJECT_ID = "pref_active_project_id"
        private const val PREF_TEMPERATURE_BITS = "pref_temperature_bits"
        private const val PREF_MAX_OUTPUT_TOKENS = "pref_max_output_tokens"
        private const val PREF_AGENT_MODE = "pref_agent_mode"
        private const val PREF_VISION_ENABLED = "pref_vision_enabled"
        private const val MAX_ATTACHMENT_CHARS = MainUiState.MAX_ATTACHMENT_CHARS
        private const val MAX_RESPONSE_TOKENS = MainUiState.MAX_RESPONSE_TOKENS
        /** Hard ceiling so a stalled engine cannot block the worker thread forever. */
        private const val GENERATION_TIMEOUT_SECONDS = 360L
        /** Skip eager startup preload above 1.5 GB so multi-GB models (e.g. LFM3-Omni 3.5 GB) don't OOM idle launches. */
        private const val EAGER_WARMUP_MAX_BYTES = 1_500_000_000L
        /** Reset stateful KV cache before cumulative turns approach the 4096-token EngineConfig cap. */
        private const val WARM_CONTEXT_RESET_THRESHOLD = 2800
        private const val IDLE_ENGINE_UNLOAD_MS = 300_000L
        private val SYSTEM_INSTRUCTION: String get() = AnswerDirective.SYSTEM_INSTRUCTION
        private const val WHITE = Color.WHITE
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
    private lateinit var chatThreadContainer: LinearLayout
    private lateinit var jumpToBottomButton: Button
    private lateinit var heroCard: LinearLayout
    private lateinit var heroModelCtaButton: Button
    private lateinit var quickChips: View
    private lateinit var composerDock: LinearLayout
    private lateinit var composerCard: LinearLayout
    private lateinit var composerRoot: LinearLayout
    private lateinit var toolsTray: LinearLayout
    private lateinit var toolsToggleButton: ImageButton
    private lateinit var attachmentChipContainer: LinearLayout
    private lateinit var recordingBanner: LinearLayout
    private lateinit var recordingDot: View
    private lateinit var recordingBannerText: TextView
    private lateinit var recordingStopButton: Button
    private lateinit var composerMicButton: ImageButton
    private lateinit var modelStatusPill: ModelStatusPillView
    private var isToolsTrayOpen = false
    private var isJumpToBottomVisible = false
    private var lastAttachmentStripSignature: String? = null
    /** True while the user is reading earlier output; suppresses forced auto-scroll. */
    @Volatile private var isUserReadingUpwards = false
    @Volatile private var isActivityInForeground = false
    @Volatile private var firstTokenElapsedMs: Long? = null
    @Volatile private var streamedCharsCount: Int = 0
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
    private val projectStore by lazy { ProjectStore(ProjectStore.defaultDir(filesDir)) }
    private val modelLibraryStore by lazy { ModelLibraryStore(ModelLibraryStore.defaultDir(filesDir)) }
    private val artifactStore by lazy { ArtifactStore(ArtifactStore.defaultDir(filesDir)) }
    private val documentVault by lazy { DocumentVault(DocumentVault.defaultDir(filesDir)) }
    private var activeProjectId: String? = null
    private var isAgentModeEnabled: Boolean = false
    private var isVisionModeEnabled: Boolean = false
    private var currentTemperature: Float = InferenceEngineManager.DEFAULT_TEMPERATURE
    private var currentMaxOutputTokens: Int = MAX_RESPONSE_TOKENS
    @Volatile private var attachedImagePath: String? = null
    private var attachedImageName: String? = null
    private var pendingHtmlToExport: String? = null
    private lateinit var workspaceSubtitleView: TextView
    private lateinit var slashPopupContainer: LinearLayout
    private lateinit var agentModeButton: Button
    private lateinit var attachImageButton: Button
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
        engineManager.activeTemperature = currentTemperature
        engineManager.visionEnabled = isVisionModeEnabled
        ensureLegacyModelRegistered()
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

    private fun handleWidgetLaunchIntent(launchIntent: Intent?) {
        if (launchIntent?.getBooleanExtra(PocketAgentWidget.EXTRA_WIDGET_VOICE, false) == true) {
            launchIntent.removeExtra(PocketAgentWidget.EXTRA_WIDGET_VOICE)
            mainHandler.postDelayed({
                if (!isDestroyed && !isFinishing) startDictation()
            }, 450L)
        }
    }

    private fun handleSharedIntent(shareIntent: Intent?) {
        if (shareIntent == null || shareIntent.action != Intent.ACTION_SEND) return
        val mime = shareIntent.type.orEmpty().lowercase(Locale.ROOT)
        val sharedText = shareIntent.getStringExtra(Intent.EXTRA_TEXT)
        val sharedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shareIntent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            shareIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }

        if (!sharedText.isNullOrBlank()) {
            val clean = sharedText.trim().take(MAX_ATTACHMENT_CHARS)
            attachedContext = clean
            attachedNames.clear()
            attachedNames.add("Shared text")
            attachmentGeneration++
            showAttachmentPreview(attachedContext)
            attachmentCard.visibility = View.VISIBLE
            engineManager.invalidateConversation("shared text attached")
            syncComposerState()
            updateStatus("Shared text attached (${clean.length} chars). Ask your question.")
            Toast.makeText(this, "Shared text attached", Toast.LENGTH_SHORT).show()
            shareIntent.action = null
        } else if (sharedUri != null) {
            val displayName = queryDisplayName(sharedUri).orEmpty().lowercase(Locale.ROOT)
            if (mime.startsWith("image/")) {
                if (isVisionModeEnabled) {
                    stageImageUri(sharedUri)
                } else {
                    Toast.makeText(this, "Enable Vision Mode in Model Manager first for images.", Toast.LENGTH_LONG).show()
                }
            } else if (mime == "application/pdf" || displayName.endsWith(".pdf")) {
                importPdf(sharedUri)
            } else {
                importTextFiles(listOf(sharedUri))
            }
            shareIntent.action = null
        }
    }

    private fun activeModelFile(): File {
        val activeEntry = modelLibraryStore.getActive()
        if (activeEntry != null) {
            val candidate = File(activeEntry.filePath)
            if (candidate.exists() && candidate.length() > 0L) {
                return candidate
            }
        }
        return File(filesDir, "model.litertlm")
    }

    private fun ensureLegacyModelRegistered() {
        val legacy = File(filesDir, "model.litertlm")
        if (legacy.exists() && legacy.length() > 0L) {
            val existing = modelLibraryStore.loadAll()
            if (existing.none { it.filePath == legacy.absolutePath }) {
                val label = settingsPrefs.getString(PREF_MODEL_DISPLAY_NAME, null)
                    ?.takeIf { it.isNotBlank() } ?: "Primary Model"
                modelLibraryStore.registerModel(
                    filePath = legacy.absolutePath,
                    displayName = label,
                    makeActive = existing.none { it.isActive },
                )
            }
        }
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
        val modelFile = activeModelFile()
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
            setPadding(dp(10), dp(6), dp(6), dp(6))
        }
        val mark = ImageView(this).apply {
            setImageDrawable(tintedVectorDrawable(this@MainActivity, R.drawable.ic_brand_logo, WHITE))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(7), dp(7), dp(7), dp(7))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = shape(palette.teal, 12)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        }
        brandRow.addView(mark)
        val brand = column().apply {
            setPadding(dp(6), 0, 0, 0)
            addView(text("PocketAgent", 15f, palette.ink, true))
            workspaceSubtitleView = text("", 10f, palette.teal, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                visibility = View.GONE
            }
            addView(workspaceSubtitleView)
        }
        brandRow.addView(brand, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })

        modelStatusPill = ModelStatusPillView(this, palette) {
            val stored = activeModelFile()
            if ((stored.exists() && stored.length() > 0L) || modelLibraryStore.loadAll().isNotEmpty()) {
                showModelManagerDialog()
            } else {
                chooseModel()
            }
        }
        val pillHost = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        pillHost.addView(
            modelStatusPill,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        brandRow.addView(
            pillHost,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            },
        )

        val headerActions = listOf(
            Triple(R.drawable.ic_voice, "Start continuous hands-free voice duplex mode") {
                showDuplexVoiceDialog()
            },
            Triple(R.drawable.ic_add_chat, "Start a fresh conversation") {
                startNewConversation()
            },
            Triple(R.drawable.ic_menu, "Open workspace menu: chats, notes, memory, models, and privacy") {
                showWorkspaceMenuDialog()
            },
        )
        for ((index, spec) in headerActions.withIndex()) {
            val (iconRes, a11y, action) = spec
            val btn = vectorIconButton(
                context = this,
                palette = palette,
                drawableRes = iconRes,
                accessibilityLabel = a11y,
                onClick = action,
            )
            brandRow.addView(
                btn,
                LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    if (index < headerActions.lastIndex) marginEnd = dp(2)
                },
            )
        }
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
            text = "100% Offline · Zero Cloud",
            sizeSp = 11.5f,
            color = WHITE,
            bold = true,
        ).apply {
            background = roundedShape(0x29FFFFFF, dp(12).toFloat())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                bottomMargin = dp(10)
            }
        }
        heroCard.addView(trustBadge)
        heroCard.addView(text("Your pocket assistant", 21f, WHITE, true))
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
            text = "Choose .litertlm Model"
            contentDescription = "Choose a LiteRT-LM model file to get started"
            val folderIcon = tintedVectorDrawable(this@MainActivity, R.drawable.ic_folder, WHITE)?.apply {
                setBounds(0, 0, dp(18), dp(18))
            }
            setCompoundDrawables(folderIcon, null, null, null)
            compoundDrawablePadding = dp(8)
            setAllCaps(false)
            textSize = 13.5f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(WHITE)
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
            lateinit var chip: Button
            chip = secondaryOutlineButton(
                context = this,
                palette = palette,
                label = label,
                accessibilityLabel = "Use prompt starter: $label",
                compact = true,
                textColor = palette.teal,
            ) {
                performSubtleHaptic(chip)
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

        chatThreadContainer = column()
        chatCanvas.addView(chatThreadContainer, LinearLayout.LayoutParams(-1, -2))

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
            setPadding(0, 0, 0, dp(22))
            addView(chatCanvas)
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                // Track whether the reader is parked near the bottom (with 56dp hysteresis so minor
                // height resizes like opening toolsTray or keyboard do not falsely trigger "↓ Latest").
                isUserReadingUpwards = scrollY + height < (chatCanvas.height + paddingTop + paddingBottom - dp(56))
                updateJumpToBottomVisibility()
            }
        }

        jumpToBottomButton = ComposerChromeViews.buildJumpToBottomButton(this, palette) {
            isUserReadingUpwards = false
            scrollChatToBottom(force = true)
            updateJumpToBottomVisibility()
        }.apply {
            alpha = 0.94f
            visibility = View.GONE
        }

        val chatFrame = FrameLayout(this).apply {
            addView(chatScroll, FrameLayout.LayoutParams(-1, -1))
            addView(
                jumpToBottomButton,
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(0, 0, dp(10), dp(10))
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
            background = shape(palette.surface, 20, palette.border)
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
            pillButton("+ Text", accessibilityLabel = "Attach text files", compact = true) { chooseTextFiles() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) },
        )
        trayTextRow.addView(
            pillButton("+ PDF", accessibilityLabel = "Attach a PDF document", compact = true) { choosePdf() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) },
        )
        toolsTray.addView(trayTextRow)

        val audioActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recordAudioButton = pillButton("Record audio", accessibilityLabel = "Record audio for local Whisper transcription", compact = true) {
            toggleInAppRecording()
        }.apply { isEnabled = false }
        audioActionsRow.addView(recordAudioButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })

        transcribeWavButton = pillButton("Transcribe WAV", accessibilityLabel = "Choose a WAV file for local transcription", compact = true) {
            chooseWav()
        }.apply { isEnabled = false }
        audioActionsRow.addView(transcribeWavButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
        toolsTray.addView(audioActionsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val agentToolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        agentModeButton = pillButton(
            label = if (isAgentModeEnabled) "Agent Tools: ON" else "Agent Tools: OFF",
            accessibilityLabel = "Toggle offline multi-step agent tool loop",
            compact = true,
        ) {
            isAgentModeEnabled = !isAgentModeEnabled
            settingsPrefs.edit().putBoolean(PREF_AGENT_MODE, isAgentModeEnabled).apply()
            agentModeButton.text = if (isAgentModeEnabled) "Agent Tools: ON" else "Agent Tools: OFF"
            updateWorkspaceSubtitle()
            Toast.makeText(
                this,
                if (isAgentModeEnabled) "Agent Tool Loop enabled (calc, notes, vault)"
                else "Agent Tool Loop disabled",
                Toast.LENGTH_SHORT,
            ).show()
        }
        agentToolsRow.addView(agentModeButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })

        attachImageButton = pillButton(
            label = "+ Image (Omni)",
            accessibilityLabel = "Attach an image for local Omni vision inference",
            compact = true,
        ) {
            chooseVisionImage()
        }.apply {
            visibility = if (isVisionModeEnabled) View.VISIBLE else View.GONE
        }
        agentToolsRow.addView(attachImageButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
        toolsTray.addView(agentToolsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

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

        slashPopupContainer = column().apply {
            visibility = View.GONE
            background = roundedShape(
                color = palette.surfaceElevated,
                radiusPx = dp(12).toFloat(),
                strokeWidthPx = maxOf(1, dp(1)),
                strokeColor = palette.border,
            )
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        composerCard.addView(slashPopupContainer, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

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

        toolsToggleButton = vectorIconButton(
            context = this,
            palette = palette,
            drawableRes = R.drawable.ic_plus,
            accessibilityLabel = "Show attachment and audio tools",
            tintColor = palette.teal,
            bgColor = palette.mint,
            borderColor = null,
            rippleColor = palette.rippleOnMint,
            onClick = {
                performSubtleHaptic(toolsToggleButton)
                toggleToolsTray(force = false)
            },
        )
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
                    updateSlashSuggestions(s?.toString().orEmpty())
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

        composerMicButton = vectorIconButton(
            context = this,
            palette = palette,
            drawableRes = R.drawable.ic_mic,
            accessibilityLabel = "Dictate your question",
            tintColor = palette.teal,
            bgColor = palette.mint,
            borderColor = null,
            rippleColor = palette.rippleOnMint,
            onClick = {
                performSubtleHaptic(composerMicButton)
                startDictation()
            },
        )
        composerRoot.addView(composerMicButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginStart = dp(7)
        })

        suggestButton = actionButton("Ask", accessibilityLabel = "Send your question and get an answer") {
            performSubtleHaptic(suggestButton, isConfirm = true)
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
        if (::composerCard.isInitialized && isToolsTrayOpen != open) {
            TransitionManager.beginDelayedTransition(composerCard)
        }
        isToolsTrayOpen = open
        toolsTray.visibility = if (open) View.VISIBLE else View.GONE
        toolsToggleButton.setImageDrawable(
            tintedVectorDrawable(
                this,
                if (open) R.drawable.ic_close else R.drawable.ic_plus,
                palette.teal,
            )
        )
        toolsToggleButton.contentDescription =
            if (open) "Hide attachment and audio tools" else "Show attachment and audio tools"
        // The tray opens through the attachment chip's Edit action too, which must reveal the
        // preview controls rather than the collapsed state.
        if (open) attachmentCard.visibility = if (attachedContext.isNotBlank()) View.VISIBLE else View.GONE
        syncComposerState()
        if (!isUserReadingUpwards) scrollChatToBottom()
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
        val hasImage = !attachedImagePath.isNullOrBlank()
        val hasConversation = hasThreadViews ||
            taskInput.text.isNotBlank() || attachedContext.isNotBlank() || hasImage ||
            lastPrompt.isNotBlank() || currentSession?.messages?.isNotEmpty() == true
        val welcomeVisibility = if (hasConversation) View.GONE else View.VISIBLE
        if (heroCard.visibility != welcomeVisibility && ::chatCanvas.isInitialized) {
            TransitionManager.beginDelayedTransition(chatCanvas)
        }
        heroCard.visibility = welcomeVisibility
        quickChips.visibility = welcomeVisibility

        if (::heroModelCtaButton.isInitialized) {
            val hasModel = activeModelFile().let { it.exists() && it.length() > 0L }
            heroModelCtaButton.visibility = if (hasModel) View.GONE else View.VISIBLE
        }
        if (::attachImageButton.isInitialized) {
            attachImageButton.visibility = if (isVisionModeEnabled) View.VISIBLE else View.GONE
        }

        val effectiveNames = buildList {
            addAll(attachedNames)
            attachedImageName?.let { add("Image: $it") }
        }
        val hasAttachment = attachedContext.isNotBlank() || hasImage
        if (::attachmentChipContainer.isInitialized) {
            val nextChipVisibility = if (hasAttachment) View.VISIBLE else View.GONE
            if (attachmentChipContainer.visibility != nextChipVisibility && ::composerCard.isInitialized) {
                TransitionManager.beginDelayedTransition(composerCard)
            }
            if (hasAttachment) {
                val signature = effectiveNames.joinToString("|") + ":" + attachedContext.length
                attachmentChipContainer.visibility = View.VISIBLE
                if (signature != lastAttachmentStripSignature) {
                    lastAttachmentStripSignature = signature
                    attachmentChipContainer.removeAllViews()
                    attachmentChipContainer.addView(
                        ComposerChromeViews.buildAttachmentChipsStrip(
                            context = this,
                            palette = palette,
                            names = effectiveNames,
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
            val canSend = (taskInput.text.isNotBlank() || attachedContext.isNotBlank() || !attachedImagePath.isNullOrBlank()) &&
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
        if (show == isJumpToBottomVisible) return
        isJumpToBottomVisible = show
        jumpToBottomButton.animate().cancel()
        if (show) {
            if (jumpToBottomButton.visibility != View.VISIBLE) {
                jumpToBottomButton.alpha = 0f
                jumpToBottomButton.scaleX = 0.85f
                jumpToBottomButton.scaleY = 0.85f
                jumpToBottomButton.visibility = View.VISIBLE
            }
            jumpToBottomButton.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180L)
                .withEndAction(null)
                .start()
        } else {
            jumpToBottomButton.animate()
                .alpha(0f)
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(180L)
                .withEndAction {
                    if (!isJumpToBottomVisible) {
                        jumpToBottomButton.visibility = View.GONE
                    }
                }
                .start()
        }
    }

    /** Animates a newly added chat card into view with a subtle fade and upward slide. */
    private fun animateCardEntrance(view: View) {
        view.alpha = 0f
        view.translationY = dp(12).toFloat()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Updates the visible recording banner and pulsing indicator inside the composer card. */
    private fun updateRecordingBanner() {
        if (!::recordingBanner.isInitialized) return
        val isRec = inAppAudioRecorder.isRecordingActive
        val targetVisibility = if (isRec || isDictating) View.VISIBLE else View.GONE
        if (recordingBanner.visibility != targetVisibility && ::composerCard.isInitialized) {
            TransitionManager.beginDelayedTransition(composerCard)
        }
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

    private fun chooseVisionImage() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
        startActivityForResult(picker, PICK_IMAGE_OCR)
    }

    private fun updateSlashSuggestions(currentText: String) {
        if (!::slashPopupContainer.isInitialized) return
        val trimmed = currentText.trimStart()
        val exactMatch = SlashCommandDetector.detectCommand(trimmed)
        val hasTrailingArgs = exactMatch != null && trimmed.length > exactMatch.trigger.length
        if (!trimmed.startsWith("/") || trimmed.contains('\n') || hasTrailingArgs) {
            slashPopupContainer.removeAllViews()
            slashPopupContainer.visibility = View.GONE
            return
        }
        val matches = SlashCommandDetector.getSuggestions(trimmed)
        if (matches.isEmpty()) {
            slashPopupContainer.removeAllViews()
            slashPopupContainer.visibility = View.GONE
            return
        }
        slashPopupContainer.removeAllViews()
        for ((index, cmd) in matches.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = rippleDrawable(
                    roundedShape(Color.TRANSPARENT, dp(10).toFloat()),
                    palette.rippleOnSurface,
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    performSubtleHaptic(this)
                    val filled = "${cmd.trigger} "
                    taskInput.setText(filled)
                    taskInput.setSelection(filled.length)
                    taskInput.requestFocus()
                    slashPopupContainer.visibility = View.GONE
                    syncComposerState()
                }
            }
            row.addView(
                text(cmd.trigger, 13f, palette.teal, true).apply {
                    setPadding(0, 0, dp(10), 0)
                }
            )
            row.addView(
                text(cmd.description, 12.5f, palette.muted).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            slashPopupContainer.addView(row)
            if (index < matches.lastIndex) {
                slashPopupContainer.addView(dividerView(this, palette))
            }
        }
        slashPopupContainer.visibility = View.VISIBLE
    }

    private fun updateWorkspaceSubtitle() {
        if (!::workspaceSubtitleView.isInitialized) return
        val proj = activeProjectId?.let { projectStore.getProject(it) }
        val ctxUsed = engineManager.cachedConversationTokens
        val parts = buildList {
            proj?.let { add(it.name) }
            if (isAgentModeEnabled) add("Agent")
            if (ctxUsed > 0) {
                val pct = ((ctxUsed * 100) / ContextBudgetManager.TokenBudget.DEFAULT_MAX_CONTEXT_TOKENS).coerceIn(1, 100)
                add("Ctx $pct%")
            }
        }
        if (parts.isEmpty()) {
            workspaceSubtitleView.visibility = View.GONE
            workspaceSubtitleView.text = ""
        } else {
            workspaceSubtitleView.text = parts.joinToString(" · ")
            workspaceSubtitleView.visibility = View.VISIBLE
        }
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
                val existingLegacy = File(filesDir, "model.litertlm")
                val targetStoreDir = if (!existingLegacy.exists() || existingLegacy.length() == 0L) {
                    filesDir
                } else {
                    val baseSlug = (cleanName ?: "model")
                        .replace(".litertlm", "", ignoreCase = true)
                        .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                        .take(32)
                        .ifBlank { "model" }
                    File(ModelLibraryStore.defaultDir(filesDir), "${baseSlug}_${System.currentTimeMillis()}")
                }
                val target = contentResolver.openInputStream(uri)?.use { input ->
                    ModelFileStore(targetStoreDir).importModel(
                        input = input,
                        expectedBytes = expectedBytes,
                    ) { copied, total ->
                        val progressMsg = if (total != null && total > 0L) {
                            val pct = ((copied * 100L) / total).coerceIn(0L, 100L)
                            "Importing $labelForProgress… ${formatStorageSize(copied)} / ${formatStorageSize(total)} ($pct%)"
                        } else {
                            "Importing $labelForProgress… ${formatStorageSize(copied)}"
                        }
                        mainHandler.post { updateStatus(progressMsg) }
                    }
                } ?: error("Could not open the selected file.")

                modelLibraryStore.registerModel(
                    filePath = target.absolutePath,
                    displayName = cleanName ?: target.name,
                    makeActive = true,
                )

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
                    if (text.isNotBlank()) {
                        try {
                            documentVault.addDocument(id = name, name = name, text = text)
                        } catch (_: Exception) {}
                    }
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
                if (extraction.text.isNotBlank()) {
                    try {
                        documentVault.addDocument(
                            id = displayName,
                            name = displayName,
                            text = extraction.text,
                        )
                    } catch (_: Exception) {}
                }
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
        recordAudioButton.text = "Record audio"
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
        attachedImagePath?.let { path -> try { File(path).delete() } catch (_: Exception) {} }
        attachedImagePath = null
        attachedImageName = null
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

    private fun stageImageUri(uri: Uri) {
        updateStatus("Staging image locally for Omni vision…")
        ioExecutor.execute {
            try {
                val displayName = (queryDisplayName(uri) ?: "Image")
                    .replace(Regex("[\\r\\n]+"), " ")
                    .take(60)
                val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, optsBounds) }
                    ?: error("Could not read image.")
                var sample = 1
                val maxDim = 768
                while (optsBounds.outWidth / (sample * 2) >= maxDim || optsBounds.outHeight / (sample * 2) >= maxDim) {
                    sample *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
                    ?: error("Unsupported image format.")
                val outFile = File(cacheDir, "pocketagent-vision-${System.currentTimeMillis()}.jpg")
                java.io.FileOutputStream(outFile).use { fos ->
                    decoded.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                }
                decoded.recycle()
                attachedImagePath?.let { old -> try { File(old).delete() } catch (_: Exception) {} }
                attachedImagePath = outFile.absolutePath
                attachedImageName = displayName
                mainHandler.post {
                    syncComposerState()
                    updateStatus("Image attached ($displayName). Ask your question using an Omni model.")
                }
            } catch (e: Exception) {
                mainHandler.post {
                    updateStatus("Could not attach image: ${e.message ?: "unsupported image"}")
                }
            }
        }
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
        relevantNotes: List<PersonalNotesStore.PersonalNote> = emptyList(),
    ): AnswerPromptComposer.PromptBuildResult =
        promptContext.answerPrompt(task, attachment, intent, isRetry, history, calculation, relevantNotes)

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
                performSubtleHaptic(chatThreadContainer, isConfirm = true)
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(language ?: "Code", code))
                Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show()
            },
            onCopyAnswer = {
                performSubtleHaptic(chatThreadContainer, isConfirm = true)
                copyAnswerText(answerProvider())
            },
            onShareAnswer = { shareAnswerText(answerProvider()) },
            onSpeakAnswer = { speakAnswerText(answerProvider()) },
            onSaveAnswer = { saveAnswerToFile() },
            onExportMarkdown = { exportCurrentAsMarkdown() },
            onRegenerate = { regenerateAnswer() },
            onRefine = { refineLastAnswer() },
            onPreviewCode = { code, language ->
                performSubtleHaptic(chatThreadContainer, isConfirm = true)
                HtmlArtifactSandbox.showPreviewDialog(
                    context = this,
                    code = code,
                    language = language,
                    palette = palette,
                    onSaveArtifact = { savedCode, savedLang ->
                        showSaveArtifactDialog(savedCode, savedLang)
                    },
                )
            },
            onSaveToNotes = { text ->
                val toSave = text.ifBlank { answerProvider() }
                if (toSave.isNotBlank()) showSaveToNotesDialog(toSave)
            },
            onEditPrompt = { promptText ->
                if (promptText.isNotBlank()) {
                    performSubtleHaptic(taskInput)
                    taskInput.setText(promptText)
                    taskInput.setSelection(promptText.length)
                    taskInput.requestFocus()
                    syncComposerState()
                    updateStatus("Editing previous prompt — adjust and tap Ask.")
                }
            },
            onFollowUpChip = { chipPrompt ->
                taskInput.setText(chipPrompt)
                taskInput.setSelection(chipPrompt.length)
                taskInput.requestFocus()
                syncComposerState()
                scrollChatToBottom()
            },
        )

    private fun startGenerationIndicators(handle: ActiveAssistantCardHandle, initialStage: String) {
        stopGenerationIndicators()
        currentGenerationStageLabel = initialStage
        firstTokenElapsedMs = null
        streamedCharsCount = 0
        generationStartElapsedMs = SystemClock.elapsedRealtime()
        thinkingPulseAnimator.start(handle.statusDot)
        handle.setStatusBadge(
            AgentTelemetryFormatter.formatStreamingBadge(initialStage, 0L, 0, null),
            palette.teal,
        )
        val ticker = object : Runnable {
            override fun run() {
                if (!isGeneratingUi) return
                val elapsed = SystemClock.elapsedRealtime() - generationStartElapsedMs
                handle.statusLabel.text = AgentTelemetryFormatter.formatStreamingBadge(
                    stageLabel = currentGenerationStageLabel,
                    elapsedMs = elapsed,
                    streamedChars = streamedCharsCount,
                    firstTokenElapsedMs = firstTokenElapsedMs,
                )
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
        imagePath: String? = null,
    ): String {
        val accumulated = StringBuilder()
        val firstChunk = AtomicBoolean(true)
        val streamFinished = CountDownLatch(1)
        val streamError = AtomicReference<Throwable?>(null)

        val guard = GuardedGeneration { partial ->
            streamedCharsCount = partial.length
            val isFirst = firstChunk.compareAndSet(true, false)
            if (isFirst) {
                firstTokenElapsedMs = (SystemClock.elapsedRealtime() - generationStartElapsedMs).coerceAtLeast(0L)
                mainHandler.post {
                    refreshModelStatus()
                    currentGenerationStageLabel = "Streaming"
                    updateStatus("Response streaming locally…")
                }
            }
            // Live throttled output at ~10 fps: keeps Markdown off the hot token loop until complete.
            streamingRenderer.onPartial(partial)
        }

        val conversation = engineManager.getOrCreateConversation(convKey, systemInstruction)
        activeStreamLatch = streamFinished
        engineManager.beginGeneration()
        try {
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    if (isGeneratingCancelled.get() && !guard.wasCancelled) {
                        guard.cancel()
                    }
                    if (!guard.accept(accumulated, message.toString())) {
                        conversation.cancel()
                        streamFinished.countDown()
                    }
                }

                override fun onDone() {
                    streamFinished.countDown()
                }

                override fun onError(throwable: Throwable) {
                    streamError.set(throwable)
                    streamFinished.countDown()
                }
            }
            if (!imagePath.isNullOrBlank() && engineManager.visionEnabled) {
                try {
                    conversation.sendImageMessage(
                        imagePath = imagePath,
                        prompt = prompt,
                        callback = callback,
                        maxOutputTokens = currentMaxOutputTokens,
                    )
                } catch (_: Throwable) {
                    mainHandler.post {
                        updateStatus("Current model could not read image; falling back to text.")
                    }
                    conversation.sendMessage(
                        prompt,
                        callback,
                        maxOutputTokens = currentMaxOutputTokens,
                    )
                }
            } else {
                conversation.sendMessage(
                    prompt,
                    callback,
                    maxOutputTokens = currentMaxOutputTokens,
                )
            }

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
            if (!isGeneratingCancelled.get() && !guard.isStopped) {
                streamError.get()?.let { error ->
                    conversation.cancel()
                    engineManager.invalidateConversation("streaming failed")
                    throw IllegalStateException("Streaming inference failed.", error)
                }
            }

            // The user pressed Stop: the context holds a half-written turn, so start fresh next time.
            if (isGeneratingCancelled.get() || guard.wasCancelled) {
                conversation.cancel()
                engineManager.invalidateConversation("generation stopped by user")
            } else if (guard.runawayFragment != null) {
                // Runaway guard stopped the turn mid-stream; never reuse a cancelled KV context.
                engineManager.invalidateConversation("runaway guard stopped")
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
            AgentTelemetryFormatter.formatCompletedBadge(
                backendTag = backendTag,
                elapsedMs = elapsedMs,
                finalChars = result.length,
                firstTokenElapsedMs = firstTokenElapsedMs,
            )
        }
        handle.setStatusBadge(badgeText, dotColor)
        handle.timestampView.text = formatTimestampLabel(System.currentTimeMillis())
        handle.timestampView.visibility = View.VISIBLE
        handle.bindCompletedMarkdown(
            markdown = result,
            isLatest = true,
            showActions = true,
        )
        if (answerIsComplete && !stoppedByUser) {
            handle.bindFollowUpChips(FollowUpSuggestionGenerator.suggestFollowUps(lastPrompt, result))
        }
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
        suggestButton.background = ripple(shape(palette.stopCrimson, 16), palette.rippleOnAccent)
        suggestButton.setTextColor(WHITE)
        suggestButton.setOnClickListener {
            performSubtleHaptic(suggestButton)
            stopActiveGeneration()
        }
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
        suggestButton.background = rippleDrawable(gradient, palette.rippleOnAccent)
        suggestButton.setTextColor(WHITE)
        suggestButton.setOnClickListener {
            performSubtleHaptic(suggestButton, isConfirm = true)
            suggestSteps()
        }
        syncComposerState()
        scheduleIdleEngineUnload()
    }

    private fun scheduleIdleEngineUnload() {
        idleUnloadRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (!isGeneratingUi && !engineManager.isGenerationActive && engineManager.engine != null) {
                inferenceExecutor.execute {
                    if (!isGeneratingUi && !engineManager.isGenerationActive && engineManager.engine != null) {
                        engineManager.invalidateConversation("idle unload")
                        engineManager.close()
                        mainHandler.post {
                            if (!isDestroyed) {
                                updateStatus("Model unloaded after inactivity to free memory. It will reload on next request.")
                                refreshModelStatus()
                            }
                        }
                    }
                }
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
        val modelFile = activeModelFile()
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
                val matchedNotes = if (task.isNotBlank()) {
                    NotesRetriever.findRelevantNotes(task, notesStore.listNotes())
                } else {
                    emptyList()
                }
                val memoryEval = buildEffectiveInstructionWithTrace(task, intent)
                val instruction = memoryEval.first
                val refineTrace = buildList {
                    if (calculation != null) add("Calculator" to "${calculation.expression} = ${calculation.displayOutput}")
                    if (matchedNotes.isNotEmpty()) add("Notes" to matchedNotes.joinToString(", ") { it.title })
                    addAll(memoryEval.second)
                }
                if (refineTrace.isNotEmpty()) {
                    mainHandler.post { if (!isDestroyed) handle.bindAgentTraceChips(refineTrace) }
                }
                val retryBuild = composePrompt(
                    task = task,
                    attachment = attachedContext,
                    intent = intent,
                    isRetry = true,
                    history = history,
                    calculation = calculation,
                    relevantNotes = matchedNotes,
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

    private fun buildEffectiveInstructionWithTrace(
        task: String,
        intent: AnswerDirective.Intent,
        slashCommand: SlashCommandDetector.SlashCommand? = null,
    ): Pair<String, List<Pair<String, String>>> {
        val traces = mutableListOf<Pair<String, String>>()
        val activeProj = activeProjectId?.let { projectStore.getProject(it) }
        val baseDirective = buildString {
            if (slashCommand != null) {
                append(slashCommand.systemPromptPrefix).append("\n\n")
                traces += "Command" to slashCommand.trigger
            }
            if (activeProj != null) {
                if (activeProj.systemInstructions.isNotBlank()) {
                    append("[Active Project: ").append(activeProj.name).append("]\n")
                    append(activeProj.systemInstructions).append("\n\n")
                }
                traces += "Project" to activeProj.name
            }
            if (isAgentModeEnabled) {
                append(AgentToolLoop.AGENT_MODE_INSTRUCTION).append("\n\n")
                traces += "Agent Mode" to "Tools enabled"
            }
            append(AnswerDirective.systemInstructionFor(intent))
        }
        if (!isMemoryEnabled) return baseDirective to traces
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
        val enriched = ContextBudgetManager().enrichSystemInstructionWithMemory(
            baseDirective,
            memoryBlock,
        )
        when {
            candidateFacts.isNotEmpty() -> traces += "Memory Saved" to candidateFacts.first().content
            memoryBlock.isNotBlank() -> traces += "Memory" to "Applied personal context"
        }
        return enriched to traces
    }

    private fun effectiveSystemInstruction(task: String, intent: AnswerDirective.Intent): String =
        buildEffectiveInstructionWithTrace(task, intent).first

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
        val rawTask = taskInput.text.toString().trim()
        if (rawTask.isEmpty() && attachedContext.isEmpty() && attachedImagePath.isNullOrBlank()) {
            updateStatus("Type a task, dictate it, or attach a text/PDF file first.")
            taskInput.requestFocus()
            return
        }
        val modelFile = activeModelFile()
        if (!modelFile.exists()) {
            updateStatus("Choose a compatible .litertlm model first.")
            return
        }
        if (engineManager.isGenerationActive || isGeneratingUi) {
            updateStatus("An answer is already being generated. Tap Stop first.")
            return
        }
        val detectedSlash = SlashCommandDetector.detectCommand(rawTask)
        val task = if (detectedSlash != null) {
            SlashCommandDetector.stripCommand(rawTask, detectedSlash)
                .ifBlank { "Apply ${detectedSlash.displayName}: ${detectedSlash.description}" }
        } else if (rawTask.isBlank() && !attachedImagePath.isNullOrBlank()) {
            "Describe and analyze this attached image in detail."
        } else {
            rawTask
        }
        // Assign session ID before building the cache key so Turn 1 and Turn 2 share the warm KV cache.
        val existingSession = currentSession
        val activeSession = when {
            existingSession == null ->
                ConversationSession.createWithInitialPrompt(task.ifBlank { "Conversation" }).also {
                    currentSession = it
                }
            existingSession.messages.isEmpty() && existingSession.title == "New Conversation" -> {
                val derivedTitle = ConversationSession.deriveTitle(task)
                if (derivedTitle != existingSession.title) {
                    existingSession.withTitle(derivedTitle).also {
                        currentSession = it
                    }
                } else {
                    existingSession
                }
            }
            else -> existingSession
        }
        lastPrompt = task
        isGeneratingCancelled.set(false)
        streamingRenderer.reset()

        val attachmentSummary = if (attachedContext.isNotBlank()) {
            attachedNames.joinToString(" · ").ifBlank { "Attachment" }
        } else {
            null
        }
        val attachmentRefs = buildList {
            addAll(attachedNames)
            attachedImageName?.let { add("Image: $it") }
        }
        val userMsg = ChatMessage.user(
            content = rawTask.ifBlank { task },
            attachmentContent = if (attachedContext.isNotBlank()) attachedContext else null,
            attachmentSummary = attachmentSummary,
            attachmentReferences = attachmentRefs,
        )
        val cardCallbacks = buildCallbacksForAnswer { latestAssistantText }
        val userCard = ChatMessageCardBuilder.buildUserCard(this, userMsg, palette, cardCallbacks)
        chatThreadContainer.addView(userCard, marginBottom(10))
        animateCardEntrance(userCard)

        activeAssistantHandle?.setLatestTurn(false)
        val handle = ChatMessageCardBuilder.buildAssistantCard(
            context = this,
            message = null,
            palette = palette,
            isLatest = true,
            callbacks = cardCallbacks,
        )
        chatThreadContainer.addView(handle.rootCard, marginBottom(12))
        animateCardEntrance(handle.rootCard)
        activeAssistantHandle = handle

        taskInput.setText("")
        ComposerInputGuard.resetInputHeight(taskInput)
        answerIsComplete = false
        latestAssistantText = ""
        maybeShowRefineAffordance = false

        val modelWasLoaded = engineManager.engine != null
        val initialBackendLabel = if (engineManager.preference == BackendPreference.CPU_ONLY) {
            "CPU"
        } else {
            engineManager.activeBackend?.name ?: "GPU"
        }
        val initialStage = if (modelWasLoaded) {
            "Checking tools · $initialBackendLabel"
        } else {
            "Loading ${formatStorageSize(modelFile.length())} model"
        }
        enterGeneratingState()
        handle.bindStreamingText(if (modelWasLoaded) "Preparing local tools and context…" else "Loading model weights into RAM…")
        startGenerationIndicators(handle, initialStage)
        isUserReadingUpwards = false
        syncComposerState()
        scrollChatToBottom(force = true)
        updateStatus(
            if (modelWasLoaded) "Preparing answer on this device ($initialBackendLabel)…"
            else "Loading model into device RAM (first turn takes longer)…"
        )

        val attachmentSnapshot = attachedContext
        val imageSnapshot = attachedImagePath
        inferenceExecutor.execute {
            var localCitations = emptyList<LocalDocumentRetriever.DocumentCitation>()
            val traceChips = mutableListOf<Pair<String, String>>()
            try {
                if (!imageSnapshot.isNullOrBlank()) {
                    traceChips += "Vision" to (attachedImageName ?: "Image")
                }
                ensureEngineInitialized(modelFile)
                val activeBackendLabel = engineManager.activeBackend?.name ?: initialBackendLabel
                if (!modelWasLoaded) {
                    traceChips += "Model" to "Loaded ${formatStorageSize(modelFile.length())} ($activeBackendLabel)"
                }
                currentGenerationStageLabel = "Preparing $activeBackendLabel context"
                mainHandler.post {
                    refreshModelStatus()
                    if (!isGeneratingCancelled.get()) {
                        updateStatus("Preparing local context & tools ($activeBackendLabel)…")
                    }
                }

                val intent = AnswerDirective.classify(task.ifBlank { attachmentSnapshot })
                val calculation = if (task.isNotBlank()) LocalCalculatorTool.tryEvaluate(task) else null
                if (calculation != null) {
                    traceChips += "Calculator" to "${calculation.expression} = ${calculation.displayOutput}"
                }

                val matchedNotes = if (task.isNotBlank()) {
                    NotesRetriever.findRelevantNotes(task, notesStore.listNotes())
                } else {
                    emptyList()
                }
                if (matchedNotes.isNotEmpty()) {
                    traceChips += "Notes" to matchedNotes.joinToString(", ") { it.title }
                }

                val vaultExcerpts = if (task.isNotBlank()) documentVault.search(task, topK = 3) else emptyList()
                if (vaultExcerpts.isNotEmpty()) {
                    traceChips += "Vault" to "${vaultExcerpts.size} excerpt${if (vaultExcerpts.size > 1) "s" else ""}"
                }

                val boundedAttachment = if (attachmentSnapshot.isNotBlank()) {
                    val retrieval = LocalDocumentRetriever.retrieveTextWithCitations(
                        query = task,
                        documentText = attachmentSnapshot,
                        sourceName = attachmentRefs.firstOrNull() ?: "attachment",
                        maxChars = MAX_ATTACHMENT_CHARS,
                    )
                    localCitations = retrieval.citations
                    if (localCitations.isNotEmpty()) {
                        traceChips += "Documents" to "${localCitations.size} citation${if (localCitations.size > 1) "s" else ""}"
                    }
                    val baseAtt = if (retrieval.promptContext.isNotBlank()) retrieval.promptContext else attachmentSnapshot
                    if (vaultExcerpts.isNotEmpty() && baseAtt.length < MAX_ATTACHMENT_CHARS - 200) {
                        val remaining = MAX_ATTACHMENT_CHARS - baseAtt.length
                        (baseAtt + "\n\nKnowledge Vault:\n" + vaultExcerpts.joinToString("\n\n")).take(MAX_ATTACHMENT_CHARS)
                    } else {
                        baseAtt
                    }
                } else if (vaultExcerpts.isNotEmpty()) {
                    localCitations = emptyList()
                    ("Knowledge Vault Excerpts:\n" + vaultExcerpts.joinToString("\n\n")).take(MAX_ATTACHMENT_CHARS)
                } else {
                    localCitations = emptyList()
                    ""
                }

                val memoryEval = buildEffectiveInstructionWithTrace(task, intent, detectedSlash)
                val effectiveInstruction = memoryEval.first
                traceChips += memoryEval.second

                if (traceChips.isNotEmpty()) {
                    val snapshot = traceChips.toList()
                    mainHandler.post {
                        if (!isDestroyed) handle.bindAgentTraceChips(snapshot)
                    }
                }

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
                    relevantNotes = matchedNotes,
                )
                if (isWarm &&
                    engineManager.cachedConversationTokens + promptBuild.estimatedTotalTokens + currentMaxOutputTokens >
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
                        relevantNotes = matchedNotes,
                    )
                }
                if (promptBuild.truncatedCount > 0) {
                    mainHandler.post { updateStatus("Older context trimmed to fit model limit.") }
                }

                runawayFragment = null
                var activeBuild = promptBuild
                var result = executeWithGpuFallback(
                    modelFile = modelFile,
                    onBeforeCpuRetry = {
                        activeBuild = composePrompt(
                            task = task,
                            attachment = boundedAttachment,
                            intent = intent,
                            isRetry = false,
                            history = sessionHistory,
                            calculation = calculation,
                            relevantNotes = matchedNotes,
                        )
                    },
                ) {
                    runGuardedGeneration(convKey, activeBuild.prompt, effectiveInstruction, imageSnapshot)
                }

                if (isAgentModeEnabled && !isGeneratingCancelled.get() && runawayFragment == null) {
                    val agentLoop = AgentToolLoop(
                        notesStore = notesStore,
                        documentVault = documentVault,
                        maxIterations = 3,
                    )
                    var loopPrompt = activeBuild.prompt
                    var loopIter = 0
                    while (loopIter < 3 && !isGeneratingCancelled.get() && runawayFragment == null) {
                        val (hasTools, loopRes) = agentLoop.processOutput(result, loopIter)
                        if (!hasTools || loopRes.toolResults.isEmpty()) break
                        for (toolRes in loopRes.toolResults) {
                            traceChips += "Tool: ${toolRes.toolName}" to toolRes.output.take(40)
                        }
                        val traceSnapshot = traceChips.toList()
                        val toolNames = loopRes.toolResults.joinToString(", ") { it.toolName }
                        mainHandler.post {
                            if (!isDestroyed) {
                                handle.bindAgentTraceChips(traceSnapshot)
                                currentGenerationStageLabel = "Tool: $toolNames"
                                updateStatus("Executed offline tool ($toolNames); continuing…")
                            }
                        }
                        streamingRenderer.reset()
                        loopPrompt = agentLoop.buildEnrichedPrompt(loopPrompt, loopRes.toolResults)
                        result = runGuardedGeneration(convKey, loopPrompt, effectiveInstruction)
                        loopIter++
                    }
                    result = agentLoop.stripToolCallLines(result)
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
                val ftMs = firstTokenElapsedMs
                if (!isGeneratingCancelled.get() && ftMs != null && result.length >= 24) {
                    val decodeMs = elapsedMs - ftMs
                    if (decodeMs >= 400L) {
                        val estTokens = (result.length / 4.0f).coerceAtLeast(1f)
                        val tokPerSec = (estTokens * 1000.0f) / decodeMs
                        try {
                            modelLibraryStore.updateActiveBenchmark(tokPerSec)
                        } catch (_: Exception) {}
                    }
                }
                mainHandler.post {
                    exitGeneratingState()
                    refreshModelStatus()
                    updateWorkspaceSubtitle()
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
                            answerIsComplete -> "Answer ready · ${AgentTelemetryFormatter.formatDuration(elapsedMs)}."
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
            styleEditText(this, palette)
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
                    setPadding(dp(12), dp(10), dp(6), dp(10))
                    background = rippleDrawable(
                        roundedShape(
                            color = palette.surfaceElevated,
                            radiusPx = dp(14).toFloat(),
                            strokeWidthPx = maxOf(1, dp(1)),
                            strokeColor = palette.border,
                        ),
                        palette.rippleOnSurface,
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        loadSession(s.id)
                        dialog.dismiss()
                    }
                }
                val info = column().apply {
                    val titlePrefix = if (s.isPinned) "[Pinned] " else ""
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

        val pinLabel = if (s.isPinned) "Unpin conversation" else "Pin to top"
        val actions = listOf(
            pinLabel to false,
            "Rename" to false,
            "Export as Markdown" to false,
            "Export as Text" to false,
            "Delete conversation" to true,
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
            label = "+ Add Fact",
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
                    setPadding(dp(12), dp(10), dp(6), dp(10))
                    background = roundedShape(
                        color = palette.surfaceElevated,
                        radiusPx = dp(14).toFloat(),
                        strokeWidthPx = maxOf(1, dp(1)),
                        strokeColor = palette.border,
                    )
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
            .setTitle("Sovereign Memory Vault")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    private fun promptAddMemoryFact(onAdded: () -> Unit) {
        val input = EditText(this).apply {
            hint = "e.g., Prefers Kotlin, Speaks Bengali, Offline only…"
            textSize = 14f
            styleEditText(this, palette)
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
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
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
            styleEditText(this, palette)
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
                    setPadding(dp(12), dp(10), dp(6), dp(10))
                    background = rippleDrawable(
                        roundedShape(
                            color = palette.surfaceElevated,
                            radiusPx = dp(14).toFloat(),
                            strokeWidthPx = maxOf(1, dp(1)),
                            strokeColor = palette.border,
                        ),
                        palette.rippleOnSurface,
                    )
                    isClickable = true
                    isFocusable = true
                }

                val info = column().apply {
                    val pinPrefix = if (n.isPinned) "[Pinned] " else ""
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
                    iconText = if (n.isPinned) "★" else "☆",
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
            .setTitle("Personal Notes (${notesStore.listNotes().size})")
            .setView(dialogView)
            .setPositiveButton("+ New Note") { _, _ ->
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
            styleEditText(this, palette)
            if (existingNote != null) setText(existingNote.title)
        }
        container.addView(titleInput, marginBottom(10))

        val contentInput = EditText(this).apply {
            hint = "Note Content…"
            textSize = 13.5f
            styleEditText(this, palette)
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            if (existingNote != null) setText(existingNote.content)
        }
        container.addView(contentInput, marginBottom(4))

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existingNote == null) "Create Note" else "Edit Note")
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

    /** Opens the workspace menu dialog grouping chats, notes, memory, models, and privacy. */
    private fun showWorkspaceMenuDialog() {
        val root = styleDialogContainer(this, palette, paddingDp = 18)
        root.addView(text("Workspace", 18f, palette.ink, true))
        root.addView(
            text("Manage chats, local knowledge, models, and privacy", 12.5f, palette.muted).apply {
                setPadding(0, dp(4), 0, dp(14))
            }
        )

        lateinit var dialog: AlertDialog
        val activeProjName = activeProjectId?.let { projectStore.getProject(it)?.name } ?: "Default Workspace"
        val docCount = documentVault.loadAll().size
        val artifactCount = artifactStore.loadAll().size
        val items = listOf(
            Triple(R.drawable.ic_folder, "Projects & Workspaces" to "Active: $activeProjName") {
                showProjectsDialog()
            },
            Triple(R.drawable.ic_history, "Saved Conversations" to "Browse, search, or resume past chats") {
                showHistoryDialog()
            },
            Triple(R.drawable.ic_notes, "Personal Notes" to "Create and organize sovereign notes") {
                showNotesManagerDialog()
            },
            Triple(R.drawable.ic_notes, "Document Vault" to "$docCount indexed local document${if (docCount == 1) "" else "s"}") {
                showDocumentVaultDialog()
            },
            Triple(R.drawable.ic_folder, "Artifact Vault" to "$artifactCount saved code & HTML snippet${if (artifactCount == 1) "" else "s"}") {
                showArtifactVaultDialog()
            },
            Triple(R.drawable.ic_memory, "Long-Term Memory" to "Inspect and curate remembered facts") {
                showMemoryCenterDialog()
            },
            Triple(R.drawable.ic_folder, "Model & Tuning" to "Switch models, temperature, context, vision") {
                val stored = activeModelFile()
                if ((stored.exists() && stored.length() > 0L) || modelLibraryStore.loadAll().isNotEmpty()) {
                    showModelManagerDialog()
                } else {
                    chooseModel()
                }
            },
            Triple(R.drawable.ic_shield, "Privacy & Appearance" to "Privacy audit, theme, and security lock") {
                showPrivacyCenterDialog()
            },
        )

        val listCol = column()
        for ((index, item) in items.withIndex()) {
            val (iconRes, copy, action) = item
            val (itemTitle, itemSubtitle) = copy
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                elevation = dp(1).toFloat()
                background = rippleDrawable(
                    roundedShape(
                        color = palette.surfaceElevated,
                        radiusPx = dp(14).toFloat(),
                        strokeWidthPx = maxOf(1, dp(1)),
                        strokeColor = palette.border,
                    ),
                    palette.rippleOnSurface,
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    performSubtleHaptic(it)
                    dialog.dismiss()
                    action()
                }
            }
            val iconView = ImageView(this).apply {
                setImageDrawable(tintedVectorDrawable(this@MainActivity, iconRes, palette.teal))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(iconView, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
            val textCol = column().apply {
                addView(text(itemTitle, 14.5f, palette.ink, true))
                addView(text(itemSubtitle, 12f, palette.muted).apply { setPadding(0, dp(2), 0, 0) })
            }
            row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
            listCol.addView(
                row,
                LinearLayout.LayoutParams(-1, -2).apply {
                    if (index < items.lastIndex) bottomMargin = dp(8)
                },
            )
        }
        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(listCol)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        dialog = AlertDialog.Builder(this)
            .setView(root)
            .setNegativeButton("Close", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    private fun showSaveToNotesDialog(answerText: String) {
        val container = styleDialogContainer(this, palette, paddingDp = 16)
        val suggested = lastPrompt.trim()
            .replace(Regex("\\s+"), " ")
            .take(56)
            .ifBlank { "PocketAgent Answer" }
        val titleInput = EditText(this).apply {
            hint = "Note Title"
            textSize = 14f
            styleEditText(this, palette)
            setText(suggested)
            setSelection(text.length)
        }
        container.addView(titleInput, marginBottom(6))
        container.addView(
            text("${answerText.length} chars will be saved locally.", 12f, palette.muted)
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle("Save Answer to Notes")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val title = titleInput.text.toString().trim().ifBlank { "PocketAgent Answer" }
                notesStore.createNote(title = title, content = answerText, tags = listOf("saved-answer"))
                updateStatus("Saved answer to Personal Notes ($title).")
                Toast.makeText(this, "Saved to Notes", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    private fun showSaveArtifactDialog(code: String, language: String?) {
        val container = styleDialogContainer(this, palette, paddingDp = 16)
        val langLabel = language?.trim()?.takeIf { it.isNotEmpty() } ?: "html"
        val suggestedTitle = lastPrompt.trim().replace(Regex("\\s+"), " ").take(48)
            .ifBlank { "${langLabel.uppercase(Locale.ROOT)} Artifact" }
        val titleInput = EditText(this).apply {
            hint = "Artifact Title"
            textSize = 14f
            styleEditText(this, palette)
            setText(suggestedTitle)
            setSelection(text.length)
        }
        container.addView(titleInput)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Save to Artifact Vault")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val title = titleInput.text.toString().trim().ifBlank { "Artifact" }
                artifactStore.save(
                    ArtifactStore.Artifact(
                        title = title,
                        language = langLabel,
                        code = code,
                    )
                )
                updateStatus("Artifact saved to vault ($title).")
                Toast.makeText(this, "Saved to Artifact Vault", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    private fun showProjectsDialog() {
        val root = styleDialogContainer(this, palette, paddingDp = 16)
        root.addView(
            text("Switch project workspace or customize its system prompt:", 12.5f, palette.muted),
            marginBottom(10),
        )
        val listCol = column()
        val scroll = ScrollView(this).apply {
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(-1, dp(320))
        }
        root.addView(scroll)

        lateinit var dialog: AlertDialog

        fun refreshProjectsList() {
            listCol.removeAllViews()
            val isDefaultSelected = activeProjectId == null
            val defaultRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rippleDrawable(
                    roundedShape(
                        color = if (isDefaultSelected) palette.mint else palette.surfaceElevated,
                        radiusPx = dp(14).toFloat(),
                        strokeWidthPx = maxOf(1, dp(if (isDefaultSelected) 2 else 1)),
                        strokeColor = if (isDefaultSelected) palette.teal else palette.border,
                    ),
                    palette.rippleOnSurface,
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (activeProjectId != null) {
                        activeProjectId = null
                        settingsPrefs.edit().remove(PREF_ACTIVE_PROJECT_ID).apply()
                        engineManager.invalidateConversation("project switched to default")
                        updateWorkspaceSubtitle()
                    }
                    dialog.dismiss()
                    updateStatus("Active workspace: Default")
                }
            }
            val defaultText = column().apply {
                addView(text(if (isDefaultSelected) "✓ Default Workspace" else "Default Workspace", 14f, if (isDefaultSelected) palette.teal else palette.ink, true))
                addView(text("Standard general-purpose offline assistant", 12f, palette.muted))
            }
            defaultRow.addView(defaultText, LinearLayout.LayoutParams(0, -2, 1f))
            listCol.addView(defaultRow, marginBottom(8))

            val projects = projectStore.loadAll()
            for (proj in projects) {
                val selected = proj.id == activeProjectId
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48)
                    setPadding(dp(12), dp(10), dp(6), dp(10))
                    background = rippleDrawable(
                        roundedShape(
                            color = if (selected) palette.mint else palette.surfaceElevated,
                            radiusPx = dp(14).toFloat(),
                            strokeWidthPx = maxOf(1, dp(if (selected) 2 else 1)),
                            strokeColor = if (selected) palette.teal else palette.border,
                        ),
                        palette.rippleOnSurface,
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        activeProjectId = proj.id
                        settingsPrefs.edit().putString(PREF_ACTIVE_PROJECT_ID, proj.id).apply()
                        engineManager.invalidateConversation("project switched")
                        updateWorkspaceSubtitle()
                        dialog.dismiss()
                        updateStatus("Switched to project: ${proj.name}")
                    }
                }
                val info = column().apply {
                    addView(text(if (selected) "✓ ${proj.name}" else proj.name, 14f, if (selected) palette.teal else palette.ink, true))
                    val preview = proj.systemInstructions.ifBlank { "No custom instructions" }
                    addView(text(preview, 12f, palette.muted).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    })
                }
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                val editBtn = iconTouchButton(this, palette, "✎", "Edit project ${proj.name}", palette.teal, 15f) {
                    promptProjectEditor(proj) { refreshProjectsList() }
                }
                row.addView(editBtn)
                val delBtn = iconTouchButton(this, palette, "✕", "Delete project ${proj.name}", palette.muted, 16f) {
                    projectStore.delete(proj.id)
                    if (activeProjectId == proj.id) {
                        activeProjectId = null
                        settingsPrefs.edit().remove(PREF_ACTIVE_PROJECT_ID).apply()
                        engineManager.invalidateConversation("active project deleted")
                        updateWorkspaceSubtitle()
                    }
                    refreshProjectsList()
                }
                row.addView(delBtn)
                listCol.addView(row, marginBottom(8))
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Projects & Workspaces")
            .setView(root)
            .setPositiveButton("+ New Project") { _, _ ->
                promptProjectEditor(null) {
                    updateWorkspaceSubtitle()
                    showProjectsDialog()
                }
            }
            .setNegativeButton("Close", null)
            .create()
        refreshProjectsList()
        showStyledAlertDialog(dialog, palette)
    }

    private fun promptProjectEditor(existing: ProjectStore.Project?, onSaved: () -> Unit) {
        val container = styleDialogContainer(this, palette, paddingDp = 16)
        val nameInput = EditText(this).apply {
            hint = "Project Name (e.g. Android Coach)"
            textSize = 14f
            styleEditText(this, palette)
            if (existing != null) setText(existing.name)
        }
        container.addView(nameInput, marginBottom(10))
        val instrInput = EditText(this).apply {
            hint = "Custom System Instructions (role, rules, output format)…"
            textSize = 13.5f
            styleEditText(this, palette)
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            if (existing != null) setText(existing.systemInstructions)
        }
        container.addView(instrInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Create Project" else "Edit Project")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val instr = instrInput.text.toString().trim()
                if (name.isNotBlank()) {
                    val toSave = if (existing == null) {
                        ProjectStore.Project(name = name, systemInstructions = instr)
                    } else {
                        existing.copy(name = name, systemInstructions = instr, updatedAt = System.currentTimeMillis())
                    }
                    if (projectStore.save(toSave)) {
                        activeProjectId = toSave.id
                        settingsPrefs.edit().putString(PREF_ACTIVE_PROJECT_ID, toSave.id).apply()
                        engineManager.invalidateConversation("project updated")
                        updateWorkspaceSubtitle()
                    }
                    onSaved()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    private fun showDocumentVaultDialog() {
        val root = styleDialogContainer(this, palette, paddingDp = 16)
        root.addView(
            text("Attached PDFs & text files are indexed here offline and searched automatically across chats.", 12f, palette.muted),
            marginBottom(10),
        )
        val listCol = column()
        val scroll = ScrollView(this).apply {
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(-1, dp(320))
        }
        root.addView(scroll)

        fun refreshVault() {
            listCol.removeAllViews()
            val docs = documentVault.loadAll()
            if (docs.isEmpty()) {
                listCol.addView(
                    text("No documents indexed yet.\nUse + PDF or + Text in the composer to index files.", 13f, palette.muted).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(28), 0, dp(28))
                    }
                )
                return
            }
            for (doc in docs) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(6), dp(10))
                    background = roundedShape(
                        color = palette.surfaceElevated,
                        radiusPx = dp(14).toFloat(),
                        strokeWidthPx = maxOf(1, dp(1)),
                        strokeColor = palette.border,
                    )
                }
                val info = column().apply {
                    addView(text(doc.name, 14f, palette.ink, true))
                    addView(text("${doc.chunkCount} searchable chunks · ${doc.extractedText.length} chars", 11.5f, palette.teal))
                }
                card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                val delBtn = iconTouchButton(this, palette, "✕", "Remove ${doc.name}", palette.muted, 16f) {
                    documentVault.removeDocument(doc.id)
                    refreshVault()
                }
                card.addView(delBtn)
                listCol.addView(card, marginBottom(8))
            }
        }
        refreshVault()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Document Knowledge Vault")
            .setView(root)
            .setPositiveButton("+ Add PDF") { _, _ -> choosePdf() }
            .setNeutralButton("+ Add Text") { _, _ -> chooseTextFiles() }
            .setNegativeButton("Close", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    private fun showArtifactVaultDialog() {
        val root = styleDialogContainer(this, palette, paddingDp = 16)
        root.addView(
            text("Saved HTML games, visualizations, and code blocks stored offline.", 12f, palette.muted),
            marginBottom(10),
        )
        val listCol = column()
        val scroll = ScrollView(this).apply {
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(-1, dp(330))
        }
        root.addView(scroll)

        fun refreshArtifacts() {
            listCol.removeAllViews()
            val artifacts = artifactStore.loadAll()
            if (artifacts.isEmpty()) {
                listCol.addView(
                    text("No saved artifacts yet.\nOpen any ▶ Preview and tap Save to store it here.", 13f, palette.muted).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(28), 0, dp(28))
                    }
                )
                return
            }
            for (art in artifacts) {
                val card = cardContainer(this, palette, cornerDp = 14, elevated = true, paddingDp = 12)
                card.addView(text(art.title, 14f, palette.ink, true))
                card.addView(
                    text("${art.language.uppercase(Locale.ROOT).ifBlank { "TEXT" }} · ${art.code.length} chars", 11.5f, palette.muted).apply {
                        setPadding(0, dp(2), 0, dp(8))
                    }
                )
                val btnRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                if (HtmlArtifactSandbox.isPreviewableCode(art.language, art.code)) {
                    btnRow.addView(
                        com.example.pocketagent.pillButton(this, palette, "Preview", compact = true) {
                            HtmlArtifactSandbox.showPreviewDialog(this, art.code, art.language, palette)
                        }.apply {
                            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
                        }
                    )
                }
                btnRow.addView(
                    secondaryOutlineButton(this, palette, "Export .html", compact = true, textColor = palette.teal) {
                        val html = artifactStore.exportHtml(art)
                        pendingHtmlToExport = html
                        val safeSlug = art.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32).ifBlank { "artifact" }
                        val createIntent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("text/html")
                            .putExtra(Intent.EXTRA_TITLE, "$safeSlug.html")
                        try {
                            startActivityForResult(createIntent, CREATE_ARTIFACT_HTML)
                        } catch (_: ActivityNotFoundException) {
                            pendingHtmlToExport = null
                            shareAnswerText(html)
                        }
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
                    }
                )
                btnRow.addView(
                    dangerButton(this, palette, "Delete", compact = true) {
                        artifactStore.delete(art.id)
                        refreshArtifacts()
                    }
                )
                card.addView(btnRow)
                listCol.addView(card, marginBottom(8))
            }
        }
        refreshArtifacts()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Artifact Vault")
            .setView(root)
            .setNegativeButton("Close", null)
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
        val model = activeModelFile()
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

        val title = text("Continuous Voice Mode", 18f, WHITE, true)
        dialogView.addView(title)

        val stateBadge = text("Initializing…", 13f, palette.heroSubtitleInk, true).apply {
            setPadding(0, dp(6), 0, dp(8))
        }
        dialogView.addView(stateBadge)

        val waveformView = VoiceWaveformView(this)
        dialogView.addView(waveformView, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(12) })

        val transcriptView = TextView(this).apply {
            textSize = 14f
            setTextColor(WHITE)
            setLineSpacing(dp(3).toFloat(), 1f)
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
            setTextColor(WHITE)
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
            setOnClickListener {
                performSubtleHaptic(it)
                duplexController.interrupt()
            }
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
                roundedShape(WHITE, dp(24).toFloat()),
                palette.rippleOnMint,
            )
            backgroundTintList = null
            stateListAnimator = null
            setOnClickListener {
                performSubtleHaptic(it, isConfirm = true)
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
            waveformView.release()
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
                        DuplexVoiceController.DuplexState.LISTENING -> "Listening for speech…"
                        DuplexVoiceController.DuplexState.TRANSCRIBING -> "Transcribing audio locally…"
                        DuplexVoiceController.DuplexState.GENERATING -> "Thinking on-device…"
                        DuplexVoiceController.DuplexState.SPEAKING -> "Speaking response…"
                        DuplexVoiceController.DuplexState.ERROR -> "Error: $statusMessage"
                        DuplexVoiceController.DuplexState.IDLE -> "Idle"
                    }
                    stateBadge.text = label
                    waveformView.setVoiceState(state)
                }

                override fun onAmplitude(rms: Float) {
                    waveformView.setAmplitude(rms)
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
                    stateBadge.text = "Error: ${error.message}"
                    waveformView.setVoiceState(DuplexVoiceController.DuplexState.ERROR)
                    waveformView.setAmplitude(0f)
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
        appearanceCard.addView(text("Appearance", 14f, palette.ink, true))
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
            background = roundedShape(
                color = palette.successBg,
                radiusPx = dp(14).toFloat(),
                strokeWidthPx = maxOf(1, dp(1)),
                strokeColor = palette.successBorder,
            )
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        auditCard.addView(text("100% Zero-Cloud Guarantee", 14f, palette.successInk, true))
        auditCard.addView(
            text(
                if (permAudit.isZeroCloudCompliant)
                    "Verified: Hardware-level zero cloud telemetry. PocketAgent does not request or hold 'android.permission.INTERNET'. All audio, documents, and inference run 100% locally on this device."
                else
                    "Warning: Non-standard permissions detected.",
                12f,
                palette.successInk,
            ).apply { setPadding(0, dp(4), 0, 0) }
        )
        bodyCol.addView(auditCard, marginBottom(12))

        // 3. Storage Audit Card
        val storageCard = cardContainer(this, palette, cornerDp = 14, elevated = true, paddingDp = 14)
        storageCard.addView(text("Local Storage Breakdown", 14f, palette.ink, true), marginBottom(8))

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
            background = roundedShape(
                color = palette.surfaceElevated,
                radiusPx = dp(14).toFloat(),
                strokeWidthPx = maxOf(1, dp(1)),
                strokeColor = palette.border,
            )
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val lockInfo = column().apply {
            addView(text("Biometric App Lock", 14f, palette.ink, true))
            addView(text("Require device credential or biometric on resume", 11.5f, palette.muted))
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
                privacyDialog?.dismiss()
                updateLockOverlay()
                promptBiometricUnlock()
                Toast.makeText(this, "App lock enabled. Authenticate to continue.", Toast.LENGTH_SHORT).show()
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
            label = "Panic Wipe All User Data",
            accessibilityLabel = "Emergency delete all personal data",
        ) {
            val wipeDialog = AlertDialog.Builder(this)
                .setTitle("Panic Data Wipe")
                .setMessage("This will permanently delete all conversation history, memories, and personal notes on this device. (AI models will be kept).\n\nProceed?")
                .setPositiveButton("Erase Everything") { _, _ ->
                    activeProjectId = null
                    settingsPrefs.edit().remove(PREF_ACTIVE_PROJECT_ID).apply()
                    PrivacyAuditCenter.wipeAllUserData(filesDir, cacheDir, purgeModels = false)
                    startNewConversation()
                    updateWorkspaceSubtitle()
                    Toast.makeText(this, "All personal data has been erased.", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel", null)
                .create()
            showStyledAlertDialog(wipeDialog, palette)
        }
        bodyCol.addView(panicBtn, LinearLayout.LayoutParams(-1, -2))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Privacy & Appearance")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()
        privacyDialog = dialog
        showStyledAlertDialog(dialog, palette)
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
                if (!transcript.isNullOrBlank()) {
                    val merged = ComposerChromeHelper.mergeDictationTranscript(taskInput.text.toString(), transcript)
                    ComposerInputGuard.applyTranscriptForReview(
                        editText = taskInput,
                        mergedText = merged,
                        maxLinesForReview = ComposerInputGuard.DICTATION_REVIEW_MAX_LINES,
                    )
                    syncComposerState()
                    updateStatus("Transcript ready. Review or edit, then tap Ask.")
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
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (partial.isNotBlank() && ::recordingBannerText.isInitialized) {
                    recordingBannerText.text = ComposerChromeHelper.formatDictationLivePreview(partial)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speech.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
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
        val targetLocale = if (FollowUpSuggestionGenerator.containsBengaliScript(text)) {
            Locale("bn", "IN")
        } else {
            Locale.getDefault()
        }
        val langResult = tts?.setLanguage(targetLocale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.getDefault())
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pocketagent-answer")
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.setLanguage(Locale.getDefault())
    }

    private fun refreshModelStatus() {
        if (!::modelStatusPill.isInitialized) return
        val model = activeModelFile()
        val hasModel = model.exists() && model.length() > 0L
        val activeEntry = modelLibraryStore.getActive()
        val savedName = if (hasModel) {
            activeEntry?.displayName ?: settingsPrefs.getString(PREF_MODEL_DISPLAY_NAME, null)
        } else null
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
        updateWorkspaceSubtitle()
    }

    private fun showModelManagerDialog() {
        ensureLegacyModelRegistered()
        val model = activeModelFile()
        val allModels = modelLibraryStore.loadAll()
        if ((!model.exists() || model.length() == 0L) && allModels.isEmpty()) {
            chooseModel()
            return
        }
        val activeEntry = modelLibraryStore.getActive()
        val modelName = activeEntry?.displayName
            ?: settingsPrefs.getString(PREF_MODEL_DISPLAY_NAME, null)
            ?: model.name
        val sizeStr = if (model.exists()) formatStorageSize(model.length()) else "0 B"
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
            setPadding(0, dp(4), 0, dp(6))
        })
        if (activeEntry != null && activeEntry.tokensPerSecond > 0f) {
            infoCard.addView(
                text("Measured Speed: ${activeEntry.tokensPerSecondLabel}", 12f, palette.teal, true).apply {
                    setPadding(0, 0, 0, dp(6))
                }
            )
        }
        infoCard.addView(
            text(
                "Vision towers remain off by default to save RAM unless Omni Vision Mode is enabled below.",
                11.5f,
                palette.muted,
            )
        )
        bodyCol.addView(infoCard, marginBottom(12))

        lateinit var dialog: AlertDialog

        // Context Window Meter Card
        val maxContext = ContextBudgetManager.TokenBudget.DEFAULT_MAX_CONTEXT_TOKENS
        val usedContext = engineManager.cachedConversationTokens.coerceIn(0, maxContext)
        val ctxCard = cardContainer(this, palette, cornerDp = 14, elevated = true, paddingDp = 14)
        val ctxHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ctxTitleView = text("Context Window: $usedContext / $maxContext tok", 13f, palette.ink, true)
        ctxHeaderRow.addView(ctxTitleView, LinearLayout.LayoutParams(0, -2, 1f))
        val ctxProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = maxContext
            progress = usedContext
            progressTintList = ColorStateList.valueOf(
                if (usedContext >= WARM_CONTEXT_RESET_THRESHOLD) palette.stopCrimson else palette.teal
            )
        }
        val resetCtxBtn = secondaryOutlineButton(this, palette, "Reset", compact = true, textColor = palette.teal) {
            engineManager.invalidateConversation("manual context reset")
            ctxTitleView.text = "Context Window: 0 / $maxContext tok"
            ctxProgress.progress = 0
            updateWorkspaceSubtitle()
            Toast.makeText(this, "Conversation KV cache reset", Toast.LENGTH_SHORT).show()
        }
        ctxHeaderRow.addView(resetCtxBtn)
        ctxCard.addView(ctxHeaderRow, marginBottom(8))
        ctxCard.addView(ctxProgress, LinearLayout.LayoutParams(-1, dp(8)))
        bodyCol.addView(ctxCard, marginBottom(12))

        // Generation Settings Card (Temperature, Max Tokens, Vision Mode)
        val settingsCard = cardContainer(this, palette, cornerDp = 14, elevated = true, paddingDp = 14)
        settingsCard.addView(text("Generation & Capabilities", 14f, palette.ink, true), marginBottom(8))

        val tempLabel = text(
            String.format(Locale.US, "Temperature: %.1f (lower = precise, higher = creative)", currentTemperature),
            12.5f,
            palette.muted,
        )
        settingsCard.addView(tempLabel, marginBottom(4))
        val tempSeek = SeekBar(this).apply {
            max = 14 // maps 0..14 -> 0.1..1.5
            progress = (((currentTemperature - 0.1f) * 10f).toInt()).coerceIn(0, 14)
            progressTintList = ColorStateList.valueOf(palette.teal)
            thumbTintList = ColorStateList.valueOf(palette.teal)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val temp = (progress + 1) / 10f
                    tempLabel.text = String.format(Locale.US, "Temperature: %.1f (lower = precise, higher = creative)", temp)
                    if (fromUser) {
                        currentTemperature = temp
                        settingsPrefs.edit().putInt(PREF_TEMPERATURE_BITS, java.lang.Float.floatToIntBits(temp)).apply()
                        engineManager.updateTemperature(temp)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        settingsCard.addView(tempSeek, marginBottom(10))

        settingsCard.addView(text("Max Output Tokens", 12.5f, palette.muted, true), marginBottom(6))
        val tokenOptions = listOf(256, 512, 1024, 2048)
        val tokenRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tokenButtons = mutableListOf<Button>()
        fun refreshTokenButtons() {
            tokenButtons.forEachIndexed { i, btn ->
                val tokVal = tokenOptions[i]
                val sel = tokVal == currentMaxOutputTokens
                btn.setTextColor(if (sel) palette.teal else palette.ink)
                btn.background = rippleDrawable(
                    roundedShape(
                        color = if (sel) palette.mint else palette.surface,
                        radiusPx = dp(12).toFloat(),
                        strokeWidthPx = maxOf(1, dp(if (sel) 2 else 1)),
                        strokeColor = if (sel) palette.teal else palette.border,
                    ),
                    if (sel) palette.rippleOnMint else palette.rippleOnSurface,
                )
            }
        }
        for ((index, tok) in tokenOptions.withIndex()) {
            val btn = Button(this).apply {
                text = tok.toString()
                setAllCaps(false)
                textSize = 12f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                minHeight = dp(48)
                minimumHeight = dp(48)
                backgroundTintList = null
                stateListAnimator = null
                setOnClickListener {
                    currentMaxOutputTokens = tok
                    settingsPrefs.edit().putInt(PREF_MAX_OUTPUT_TOKENS, tok).apply()
                    refreshTokenButtons()
                }
            }
            tokenButtons += btn
            tokenRow.addView(
                btn,
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index < tokenOptions.lastIndex) marginEnd = dp(6)
                },
            )
        }
        refreshTokenButtons()
        settingsCard.addView(tokenRow, marginBottom(10))

        val visionBtn = secondaryOutlineButton(
            context = this,
            palette = palette,
            label = if (isVisionModeEnabled) "Omni Vision Mode: ON (+ Image)" else "Omni Vision Mode: OFF",
            accessibilityLabel = "Toggle Omni Vision image input support",
            textColor = if (isVisionModeEnabled) palette.teal else palette.ink,
        ) {}
        visionBtn.setOnClickListener {
            if (engineManager.isGenerationActive || isGeneratingUi) {
                Toast.makeText(this, "Stop generation first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isVisionModeEnabled = !isVisionModeEnabled
            settingsPrefs.edit().putBoolean(PREF_VISION_ENABLED, isVisionModeEnabled).apply()
            inferenceExecutor.execute { engineManager.setVisionMode(isVisionModeEnabled) }
            visionBtn.text = if (isVisionModeEnabled) "Omni Vision Mode: ON (+ Image)" else "Omni Vision Mode: OFF"
            syncComposerState()
            Toast.makeText(
                this,
                if (isVisionModeEnabled) "Vision enabled (Omni models) — + Image available in + tray"
                else "Vision disabled (text-only saves RAM)",
                Toast.LENGTH_LONG,
            ).show()
        }
        settingsCard.addView(visionBtn, LinearLayout.LayoutParams(-1, -2))
        bodyCol.addView(settingsCard, marginBottom(12))

        // Saved Model Library Switcher
        if (allModels.isNotEmpty()) {
            bodyCol.addView(text("Model Library (${allModels.size})", 12.5f, palette.muted, true), marginBottom(6))
            for (entry in allModels) {
                val isAct = entry.isActive
                val mCard = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(8), dp(10))
                    background = roundedShape(
                        color = if (isAct) palette.mint else palette.surfaceElevated,
                        radiusPx = dp(14).toFloat(),
                        strokeWidthPx = maxOf(1, dp(if (isAct) 2 else 1)),
                        strokeColor = if (isAct) palette.teal else palette.border,
                    )
                }
                val mInfo = column().apply {
                    addView(text(if (isAct) "✓ ${entry.displayName}" else entry.displayName, 13.5f, if (isAct) palette.teal else palette.ink, true))
                    addView(text("${entry.sizeLabel}  ·  ${entry.tokensPerSecondLabel}", 11.5f, palette.muted))
                }
                mCard.addView(mInfo, LinearLayout.LayoutParams(0, -2, 1f))
                if (isAct) {
                    val benchBtn = secondaryOutlineButton(this, palette, "Benchmark", compact = true, textColor = palette.teal) {
                        if (engineManager.isGenerationActive || isGeneratingUi) {
                            Toast.makeText(this, "Stop active generation first.", Toast.LENGTH_SHORT).show()
                            return@secondaryOutlineButton
                        }
                        dialog.dismiss()
                        updateStatus("Benchmarking ${entry.displayName} locally…")
                        inferenceExecutor.execute {
                            try {
                                val benchFile = File(entry.filePath)
                                ensureEngineInitialized(benchFile)
                                val conv = engineManager.getOrCreateConversation(
                                    "benchmark|${benchFile.absolutePath}",
                                    "Respond concisely.",
                                )
                                val startMs = SystemClock.elapsedRealtime()
                                val latch = CountDownLatch(1)
                                val sb = StringBuilder()
                                val benchErr = AtomicReference<Throwable?>(null)
                                var firstMs: Long? = null
                                conv.sendMessage(
                                    "Count cleanly from 1 to 20 separated by commas.",
                                    object : MessageCallback {
                                        override fun onMessage(message: Message) {
                                            if (firstMs == null) firstMs = SystemClock.elapsedRealtime()
                                            sb.append(message.toString())
                                        }
                                        override fun onDone() { latch.countDown() }
                                        override fun onError(throwable: Throwable) {
                                            benchErr.set(throwable)
                                            latch.countDown()
                                        }
                                    },
                                    maxOutputTokens = 64,
                                )
                                latch.await(60, TimeUnit.SECONDS)
                                val endMs = SystemClock.elapsedRealtime()
                                val err = benchErr.get()
                                if (err != null) throw err
                                val decodeMs = (endMs - (firstMs ?: startMs)).coerceAtLeast(250L)
                                val tokens = (sb.length / 4.0f).coerceAtLeast(1f)
                                val tokPerSec = (tokens * 1000.0f) / decodeMs
                                modelLibraryStore.updateBenchmark(entry.id, tokPerSec)
                                engineManager.invalidateConversation("post-benchmark")
                                mainHandler.post {
                                    updateStatus(
                                        String.format(
                                            Locale.US,
                                            "Benchmark complete: ${entry.displayName} · %.1f tok/s",
                                            tokPerSec,
                                        )
                                    )
                                    Toast.makeText(
                                        this,
                                        String.format(Locale.US, "%.1f tok/s", tokPerSec),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            } catch (e: Exception) {
                                mainHandler.post {
                                    updateStatus("Benchmark failed: ${e.message ?: "unknown error"}")
                                }
                            }
                        }
                    }
                    mCard.addView(benchBtn)
                } else {
                    val useBtn = com.example.pocketagent.pillButton(this, palette, "Use", compact = true) {
                        if (engineManager.isGenerationActive || isGeneratingUi) {
                            Toast.makeText(this, "Stop generation before switching models.", Toast.LENGTH_SHORT).show()
                            return@pillButton
                        }
                        modelLibraryStore.setActive(entry.id)
                        settingsPrefs.edit().putString(PREF_MODEL_DISPLAY_NAME, entry.displayName).apply()
                        dialog.dismiss()
                        inferenceExecutor.execute {
                            engineManager.close()
                            mainHandler.post {
                                refreshModelStatus()
                                updateStatus("Switched active model to ${entry.displayName}.")
                            }
                        }
                    }
                    mCard.addView(useBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
                    val delEntryBtn = iconTouchButton(this, palette, "✕", "Delete ${entry.displayName}", palette.muted, 15f) {
                        dialog.dismiss()
                        val targetFile = File(entry.filePath)
                        if (targetFile.parentFile?.parentFile == ModelLibraryStore.defaultDir(filesDir)) {
                            targetFile.parentFile?.deleteRecursively()
                        } else if (targetFile.name != ModelFileStore.MODEL_FILE_NAME) {
                            targetFile.delete()
                        }
                        modelLibraryStore.remove(entry.id)
                        refreshModelStatus()
                        showModelManagerDialog()
                    }
                    mCard.addView(delEntryBtn)
                }
                bodyCol.addView(mCard, marginBottom(8))
            }
        }

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
                label = "Unload Model from RAM (Keep File)",
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
            pillButton("+ Import Another .litertlm Model", accessibilityLabel = "Pick a LiteRT-LM model file to add to library") {
                dialog.dismiss()
                chooseModel()
            },
            marginBottom(8),
        )

        bodyCol.addView(
            dangerButton(
                context = this,
                palette = palette,
                label = "Remove Stored Model ($sizeStr)",
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
                            val activeFile = activeModelFile()
                            if (activeFile.parentFile?.parentFile == ModelLibraryStore.defaultDir(filesDir)) {
                                activeFile.parentFile?.deleteRecursively()
                            } else {
                                ModelFileStore(filesDir).removeStoredModel()
                            }
                            activeEntry?.let { modelLibraryStore.remove(it.id) }
                            val fallbackNext = modelLibraryStore.loadAll().firstOrNull()
                            if (fallbackNext != null) {
                                modelLibraryStore.setActive(fallbackNext.id)
                            }
                            ModelFileStore.clearEngineCaches(cacheDir)
                            settingsPrefs.edit()
                                .apply {
                                    if (fallbackNext != null) putString(PREF_MODEL_DISPLAY_NAME, fallbackNext.displayName)
                                    else remove(PREF_MODEL_DISPLAY_NAME)
                                }
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
            .setTitle("Local Model & Engine")
            .setView(dialogView)
            .setNegativeButton("Close", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }

    private fun cleanupStagedInputFiles() {
        cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("pocketagent-pdf-") ||
                file.name.startsWith("pocketagent-audio-") ||
                file.name.startsWith("pocketagent-mic-") ||
                file.name.startsWith("pocketagent-vision-")
            ) {
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
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
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
        setTextColor(WHITE)
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
        container.addView(text("Document Citations", 12f, palette.teal, true).apply {
            setPadding(0, 0, 0, dp(4))
        })
        val wrapRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (cit in citations) {
            val pill = pillButton(
                label = cit.citationLabel,
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
