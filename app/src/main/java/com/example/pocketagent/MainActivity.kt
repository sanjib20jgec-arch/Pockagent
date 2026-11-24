package com.example.pocketagent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
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

class MainActivity : Activity(), TextToSpeech.OnInitListener {
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
        private const val MAX_ATTACHMENT_CHARS = MainUiState.MAX_ATTACHMENT_CHARS
        private const val MAX_RESPONSE_TOKENS = MainUiState.MAX_RESPONSE_TOKENS
        /** Hard ceiling so a stalled engine cannot block the worker thread forever. */
        private const val GENERATION_TIMEOUT_SECONDS = 90L
        private val SYSTEM_INSTRUCTION: String get() = AnswerDirective.SYSTEM_INSTRUCTION
        private const val BG = 0xFFF4F7F6.toInt()
        private const val INK = 0xFF162D2A.toInt()
        private const val MUTED = 0xFF5F6F6A.toInt()
        private const val TEAL = 0xFF126B60.toInt()
        private const val MINT = 0xFFE2F3ED.toInt()
        private const val BORDER = 0xFFE3EBE8.toInt()
        private const val WHITE = Color.WHITE
        private const val STOP_CRIMSON = 0xFFB91C1C.toInt()
    }

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
            mainHandler = mainHandler,
            formatExecutor = ioExecutor,
        ).apply {
            onPartialText = { text ->
                answerText.text = text
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
    private lateinit var answerText: TextView
    private lateinit var attachmentText: TextView
    private lateinit var attachmentPreview: TextView
    private lateinit var attachmentPreviewButton: Button
    private lateinit var whisperModelText: TextView
    private lateinit var recordAudioButton: Button
    private lateinit var transcribeWavButton: Button
    private lateinit var confirmTranscriptButton: Button
    private lateinit var suggestButton: Button
    private lateinit var attachmentCard: LinearLayout
    private lateinit var outputCard: LinearLayout
    // Sticky bottom composer and its collapsible chrome.
    private lateinit var chatScroll: ScrollView
    private lateinit var chatCanvas: LinearLayout
    private lateinit var heroCard: LinearLayout
    private lateinit var quickChips: View
    private lateinit var composerDock: LinearLayout
    private lateinit var composerCard: LinearLayout
    private lateinit var composerRoot: LinearLayout
    private lateinit var toolsTray: LinearLayout
    private lateinit var toolsToggleButton: Button
    private lateinit var attachmentChipBar: LinearLayout
    private lateinit var attachmentChipText: TextView
    private lateinit var composerMicButton: Button
    private lateinit var modelPill: Button
    private var isToolsTrayOpen = false
    /** True while the user is reading earlier output; suppresses forced auto-scroll. */
    @Volatile private var isUserReadingUpwards = false
    private lateinit var citationsContainer: LinearLayout
    private lateinit var systemActionContainer: LinearLayout
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var attachedContext = ""
    @Volatile private var attachmentGeneration = 0L
    @Volatile private var isWhisperTranscribing = false
    @Volatile private var transcriptReviewPending = false
    private var attachedNames = mutableListOf<String>()
    private var attachmentPreviewExpanded = false
    private var answerIsComplete = false
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
    private val duplexController by lazy {
        DuplexVoiceController(
            audioRecorder = inAppAudioRecorder,
            whisperTranscriber = DefaultWhisperTranscriber(),
            llmRunner = DefaultLlmInferenceRunner(filesDir, cacheDir),
            ttsSpeaker = ttsSpeaker,
            workerExecutor = inferenceExecutor,
            mainDispatcher = { mainHandler.post(it) },
        )
    }
    private val biometricLockManager by lazy {
        BiometricLockManager(
            preferences = getSharedPreferences("pocketagent_security", MODE_PRIVATE),
            authenticator = AndroidBiometricAuthenticator(this)
        )
    }
    private var isMemoryEnabled = true
    private var currentSession: ConversationSession? = null
    private var lastPrompt: String = ""
    @Volatile private var currentCitations = emptyList<LocalDocumentRetriever.DocumentCitation>()
    private val isGeneratingCancelled = AtomicBoolean(false)

    /** Set by the runaway guard so the UI can explain why generation stopped. */
    @Volatile private var runawayFragment: String? = null
    private lateinit var stopGeneratingButton: Button
    private lateinit var refineButton: Button
    /** True while the primary composer button is acting as Stop. */
    @Volatile private var isGeneratingUi = false
    /** Set when the model dodged the question, so Refine can be offered instead of auto-retrying. */
    @Volatile private var maybeShowRefineAffordance = false
    /** Latch of the in-flight stream, so Stop can release a waiter the engine may never notify. */
    @Volatile private var activeStreamLatch: CountDownLatch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAnswerToSave = savedInstanceState?.getString(STATE_PENDING_ANSWER_SAVE)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        tts = TextToSpeech(this, this)
        engineManager.onFallback = { reason ->
            mainHandler.post { updateStatus(reason) }
        }
        cleanupStagedInputFiles()
        buildInterface()
        refreshModelStatus()
        restoreWhisperModel()
        warmUpModelAfterFirstFrame()
    }

    /**
     * Loads the selected model in the background as soon as the UI is on screen.
     *
     * Without this, the first question pays both the load and the prefill. Warming up on the
     * inference thread means the cost lands before the user asks anything, and it stays off the
     * UI thread.
     */
    private fun warmUpModelAfterFirstFrame() {
        val modelFile = File(filesDir, "model.litertlm")
        if (!modelFile.exists() || modelFile.length() <= 0) return
        window.decorView.post {
            inferenceExecutor.execute {
                try {
                    engineManager.getOrInitializeEngine(modelFile, cacheDir)
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
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }
        applyImeInsets(rootLayout)

        // ---------------------------------------------------------------- Zone A: pinned header
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(10), dp(8))
        }
        val mark = TextView(this).apply {
            text = "P"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(WHITE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = shape(TEAL, 13)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        }
        brandRow.addView(mark)
        val brand = column().apply {
            setPadding(dp(9), 0, 0, 0)
            addView(text("PocketAgent", 16f, INK, true))
        }
        brandRow.addView(brand, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })

        modelPill = pillButton("Choose model", accessibilityLabel = "Active model. Tap to choose a LiteRT-LM model") {
            chooseModel()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(34))
        }
        brandRow.addView(modelPill)

        val headerToolsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val headerToolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerToolsRow.addView(pillButton("🎙️ Voice", accessibilityLabel = "Start continuous hands-free voice duplex mode") {
            showDuplexVoiceDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) }
        })
        headerToolsRow.addView(pillButton("🧠 Memory", accessibilityLabel = "Manage sovereign long-term memory") {
            showMemoryCenterDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) }
        })
        headerToolsRow.addView(pillButton("📝 Notes", accessibilityLabel = "Manage sovereign personal notes") {
            showNotesManagerDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) }
        })
        headerToolsRow.addView(pillButton("🛡️ Privacy", accessibilityLabel = "View privacy audit and storage controls") {
            showPrivacyCenterDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) }
        })
        headerToolsRow.addView(pillButton("☰ Chats", accessibilityLabel = "View saved conversations") {
            showHistoryDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) }
        })
        headerToolsRow.addView(pillButton("+ New", accessibilityLabel = "Start a fresh conversation") {
            startNewConversation()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(34))
        })
        headerToolsScroll.addView(headerToolsRow)
        brandRow.addView(headerToolsScroll, LinearLayout.LayoutParams(0, -2, 1f))
        rootLayout.addView(brandRow, LinearLayout.LayoutParams(-1, -2))

        // ------------------------------------------------- Zone B: scrolling chat & workspace
        chatCanvas = column().apply {
            setPadding(dp(16), dp(2), dp(16), dp(10))
        }

        // Condensed welcome block, shown only while the conversation is empty.
        heroCard = column().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF153B36.toInt(), 0xFF176B60.toInt())
            ).apply { cornerRadius = dp(20).toFloat() }
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }
        heroCard.addView(text("Your pocket assistant", 20f, WHITE, true))
        heroCard.addView(text("Ask anything. Get the actual answer, computed on this device.", 13f, 0xFFD5EAE5.toInt()).apply {
            setPadding(0, dp(5), 0, 0)
        })
        chatCanvas.addView(heroCard, marginBottom(12))

        // Quick prompt chips collapse once the conversation has output.
        val quickScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Plan my day", "Explain a setting", "Write a message", "Learn something").forEach { label ->
            val chip = pillButton(label, accessibilityLabel = "Use task preset: $label") {
                taskInput.setText(label)
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
            background = shape(WHITE, 20, BORDER)
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
            setTextColor(WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(6) }
        }
        outputCard.addView(stopGeneratingButton)

        answerText = text("", 15f, INK).apply {
            setPadding(0, dp(13), 0, dp(13))
            setTextIsSelectable(true)
            setLineSpacing(dp(4).toFloat(), 1.0f)
        }
        outputCard.addView(answerText)

        citationsContainer = column().apply {
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(10))
        }
        outputCard.addView(citationsContainer)

        // Offered instead of silently running a second full generation.
        refineButton = pillButton(
            "✨ Refine answer",
            accessibilityLabel = "Refine this answer with a corrective second pass",
        ) { refineLastAnswer() }.apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
        }
        outputCard.addView(refineButton)

        val outputActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        outputActions.addView(pillButton("Copy", accessibilityLabel = "Copy answer") { copyAnswer() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        outputActions.addView(pillButton("Share", accessibilityLabel = "Share answer") { shareAnswer() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        outputActions.addView(pillButton("Regenerate", accessibilityLabel = "Regenerate answer") { regenerateAnswer() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        outputActions.addView(pillButton("Read aloud", accessibilityLabel = "Read answer aloud") { speakAnswer() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        outputCard.addView(outputActions)

        val exportActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        exportActions.addView(pillButton("Save text file", accessibilityLabel = "Save the completed answer to a text file") {
            saveAnswerToFile()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        exportActions.addView(pillButton("Export Markdown", accessibilityLabel = "Share or export conversation as Markdown") {
            exportCurrentAsMarkdown()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        outputCard.addView(exportActions)
        systemActionContainer = column().apply {
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        outputCard.addView(systemActionContainer)
        chatCanvas.addView(outputCard, marginBottom(6))

        chatCanvas.addView(text("Your selected model and prompts stay in this app. Attached text is included only in the local prompt.", 11f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(4))
        })

        chatScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(0, 0, 0, 0)
            addView(chatCanvas)
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                // Track whether the reader is parked at the bottom, so streaming output only
                // auto-scrolls when they have not deliberately scrolled up.
                isUserReadingUpwards = scrollY + height < (chatCanvas.height + paddingTop + paddingBottom - dp(24))
            }
        }
        rootLayout.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // ------------------------------------------------- Zone C: sticky bottom composer dock
        composerDock = column().apply {
            background = shape(BG, 0)
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
            background = shape(WHITE, 20, BORDER)
            elevation = dp(6).toFloat()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        attachmentChipBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = shape(MINT, 14)
            setPadding(dp(10), dp(2), dp(2), dp(2))
        }
        attachmentChipText = text("", 12f, TEAL, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        attachmentChipBar.addView(attachmentChipText, LinearLayout.LayoutParams(0, -2, 1f))
        attachmentChipBar.addView(textButton("Edit", accessibilityLabel = "Review attached files and transcripts") {
            toggleToolsTray(force = true)
        }.apply { textSize = 11f; minHeight = dp(36) })
        attachmentChipBar.addView(textButton("×", accessibilityLabel = "Remove attached files and extracted text") {
            clearAttachments()
        }.apply { textSize = 15f; minHeight = dp(36) })
        attachmentChipBar.setOnClickListener { toggleToolsTray(force = true) }
        composerCard.addView(attachmentChipBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        toolsTray = column().apply {
            visibility = View.GONE
            setPadding(0, dp(2), 0, dp(6))
        }
        val trayTextRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        trayTextRow.addView(pillButton("＋ Text", accessibilityLabel = "Attach text files") { chooseTextFiles() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
        trayTextRow.addView(pillButton("＋ PDF", accessibilityLabel = "Attach a PDF document") { choosePdf() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
        toolsTray.addView(trayTextRow)

        val audioActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recordAudioButton = pillButton("🎙  Record audio", accessibilityLabel = "Record audio for local Whisper transcription") {
            toggleInAppRecording()
        }.apply { isEnabled = false }
        audioActionsRow.addView(recordAudioButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })

        transcribeWavButton = pillButton("Transcribe WAV", accessibilityLabel = "Choose a WAV file for local transcription") {
            chooseWav()
        }.apply { isEnabled = false }
        audioActionsRow.addView(transcribeWavButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
        toolsTray.addView(audioActionsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        // Whisper model management lives here instead of cluttering the chat canvas.
        whisperModelText = text("No local Whisper model selected.", 11f, MUTED).apply {
            setPadding(dp(4), dp(9), dp(4), dp(2))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        toolsTray.addView(whisperModelText)
        val whisperRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        whisperRow.addView(pillButton("Choose Whisper model", accessibilityLabel = "Choose a local whisper.cpp ggml model") {
            chooseWhisperModel()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
        whisperRow.addView(pillButton("Remove", accessibilityLabel = "Remove the locally stored Whisper model") {
            removeWhisperModel()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5) })
        toolsTray.addView(whisperRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        // Compact attachment strip: summary plus preview and transcript review controls.
        attachmentCard = column().apply {
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        attachmentText = text("", 12f, MUTED)
        attachmentCard.addView(attachmentText)
        attachmentPreview = text("", 12f, INK).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = shape(BG, 10)
            setTextIsSelectable(true)
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
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
                suggestButton.isEnabled = true
                updateStatus("Transcript confirmed. Ask your question when ready.")
            }
        }.apply { visibility = View.GONE }
        attachmentCard.addView(confirmTranscriptButton)
        attachmentCard.addView(textButton("Remove attachment", accessibilityLabel = "Remove attached files and extracted text") { clearAttachments() })
        toolsTray.addView(attachmentCard)

        composerCard.addView(toolsTray)

        // Main composer row: always visible, always thumb-reachable.
        composerRoot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        toolsToggleButton = Button(this).apply {
            text = "＋"
            contentDescription = "Show attachment and audio tools"
            setAllCaps(false)
            textSize = 19f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(TEAL)
            setPadding(0, 0, 0, 0)
            background = ripple(shape(MINT, 24), 0x33126B60)
            backgroundTintList = null
            isFocusable = true
            setOnClickListener { toggleToolsTray(force = false) }
        }
        composerRoot.addView(toolsToggleButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            marginEnd = dp(7)
            bottomMargin = dp(1)
        })

        taskInput = EditText(this).apply {
            id = View.generateViewId()
            hint = "Ask Pockagent..."
            setHintTextColor(MUTED)
            setTextColor(INK)
            textSize = 15f
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = shape(BG, 18)
            // Line limits, height cap, and the scroll-intercept guard come from ComposerInputGuard.
            // Note: never set isSingleLine here, it resets maxLines to Int.MAX_VALUE.
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
            setOnEditorActionListener { _, actionId, event ->
                val sends = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                    (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                        event.action == android.view.KeyEvent.ACTION_DOWN && !event.isShiftPressed)
                if (sends && suggestButton.isEnabled) {
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
            text = "🎙"
            contentDescription = "Dictate your question"
            setAllCaps(false)
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(TEAL)
            setPadding(0, 0, 0, 0)
            background = ripple(shape(MINT, 24), 0x33126B60)
            backgroundTintList = null
            isFocusable = true
            setOnClickListener { startDictation() }
        }
        composerRoot.addView(composerMicButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            marginStart = dp(7)
            bottomMargin = dp(1)
        })

        suggestButton = actionButton("Ask", accessibilityLabel = "Send your question and get an answer") {
            suggestSteps()
        }.apply {
            textSize = 15f
            minHeight = dp(44)
            minWidth = dp(64)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        composerRoot.addView(suggestButton, LinearLayout.LayoutParams(-2, dp(44)).apply {
            marginStart = dp(7)
        })

        composerCard.addView(composerRoot)
        composerDock.addView(composerCard, LinearLayout.LayoutParams(-1, -2))

        statusText = text(
            "Choose a compatible .litertlm model to get started.",
            11f,
            MUTED
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(7), dp(6), dp(0))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        composerDock.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        rootLayout.addView(composerDock, LinearLayout.LayoutParams(-1, -2))

        setContentView(rootLayout)
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
        val hasConversation = (::outputCard.isInitialized && outputCard.visibility == View.VISIBLE) ||
            taskInput.text.isNotBlank() || attachedContext.isNotBlank() ||
            lastPrompt.isNotBlank() || currentSession?.messages?.isNotEmpty() == true
        val welcomeVisibility = if (hasConversation) View.GONE else View.VISIBLE
        heroCard.visibility = welcomeVisibility
        quickChips.visibility = welcomeVisibility

        val hasAttachment = attachedContext.isNotBlank()
        attachmentChipBar.visibility = if (hasAttachment) View.VISIBLE else View.GONE
        if (hasAttachment) {
            attachmentChipText.text = attachedNames.joinToString(" · ").ifBlank { "Attachment ready" }
            attachmentChipText.contentDescription =
                "Attachments: ${attachedNames.joinToString(", ").ifBlank { "one attachment" }}"
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
            suggestButton.alpha = if (suggestButton.isEnabled) 1f else .55f
        }
    }

    /** Scrolls the chat canvas to the newest content unless the reader scrolled up. */
    private fun scrollChatToBottom(force: Boolean = false) {
        if (!::chatScroll.isInitialized) return
        if (!force && isUserReadingUpwards) return
        chatScroll.post {
            if (!force && isUserReadingUpwards) return@post
            chatScroll.fullScroll(View.FOCUS_DOWN)
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
        val displayName = queryDisplayName(uri)
        if (displayName != null && !displayName.endsWith(".litertlm", ignoreCase = true)) {
            updateStatus("Choose a LiteRT-LM model file ending in .litertlm.")
            return
        }
        updateStatus("Importing model into app storage…")
        suggestButton.isEnabled = false
        // Different weights: nothing cached from the old model may survive.
        engineManager.invalidateConversation("model switched")
        maybeShowRefineAffordance = false
        if (::refineButton.isInitialized) refineButton.visibility = View.GONE
        ioExecutor.execute {
            try {
                engineManager.close()
                val target = contentResolver.openInputStream(uri)?.use { input ->
                    ModelFileStore(filesDir).importModel(input)
                } ?: error("Could not open the selected file.")
                mainHandler.post {
                    refreshModelStatus()
                    updateStatus("Model ready. Type, dictate, or attach text/PDF (selectable text only).")
                    suggestButton.isEnabled = true
                    syncComposerState()
                }
            } catch (e: Exception) {
                File(filesDir, "model.litertlm.part").delete()
                mainHandler.post {
                    updateStatus("Model import failed: ${e.message ?: "unknown error"}")
                    suggestButton.isEnabled = true
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
                    attachmentText.text = names.joinToString(" · ") + "\nAttached content limited to 12,000 characters."
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
                        "\nAttached content limited to 12,000 characters. PDF text is extracted locally."
                    showAttachmentPreview(attachedContext)
                    attachmentCard.visibility = View.VISIBLE
                    syncComposerState()
                    updateStatus(
                        if (extraction.limitReached) "PDF text attached with the 12,000-character limit applied. Review it, then ask your question."
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
                        "\nCombined attachment text limited to 12,000 characters. Temporary WAV cache file is deleted after processing."
                    showAttachmentPreview(attachedContext)
                    attachmentCard.visibility = View.VISIBLE
                    syncComposerState()
                    isWhisperTranscribing = false
                    suggestButton.isEnabled = false
                    updateStatus("Transcript ready. Expand and review it, then tap Use reviewed transcript.")
                    updateWhisperActionButtons(true)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    isWhisperTranscribing = false
                    suggestButton.isEnabled = !transcriptReviewPending
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
        updateStatus("Recording audio… speak your task (max 60 seconds). Tap Stop when done.")

        inAppAudioRecorder.startRecording(object : AudioRecorderListener {
            override fun onRecordingStarted() {
                mainHandler.post {
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
                    suggestButton.isEnabled = !transcriptReviewPending
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
                    "\nCombined attachment text limited to 12,000 characters. All recording memory is wiped after transcription."
                showAttachmentPreview(attachedContext)
                attachmentCard.visibility = View.VISIBLE
                syncComposerState()
                isWhisperTranscribing = false
                suggestButton.isEnabled = false
                updateStatus("Transcript ready. Expand and review it, then tap Use reviewed transcript.")
                resetRecordButton()
            }
        } catch (e: Exception) {
            mainHandler.post {
                isWhisperTranscribing = false
                suggestButton.isEnabled = !transcriptReviewPending
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

    /** Answer-shaped prompt for one attempt; see [AnswerPromptComposer]. */
    private fun answerPrompt(
        task: String,
        attachment: String,
        intent: AnswerDirective.Intent,
        isRetry: Boolean,
        history: List<ChatMessage>,
        calculation: LocalCalculatorTool.CalculationResult? = null,
    ): String = promptContext.answerPrompt(task, attachment, intent, isRetry, history, calculation)

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
    private fun runGuardedGeneration(
        prompt: String,
        systemInstruction: String,
    ): String {
        val accumulated = StringBuilder()
        val firstChunk = AtomicBoolean(true)
        val streamFinished = CountDownLatch(1)
        val streamError = AtomicReference<Throwable?>(null)

        val guard = GuardedGeneration { partial ->
            val isFirst = firstChunk.compareAndSet(true, false)
            if (isFirst) mainHandler.post { updateStatus("Response streaming locally…") }
            // Plain text at ~10 fps: no Markdown parsing on the UI thread while streaming.
            streamingRenderer.onPartial(partial)
        }

        val conversation = engineManager.getOrCreateConversation(conversationKey, systemInstruction)
        activeStreamLatch = streamFinished
        engineManager.beginGeneration()
        try {
            conversation.sendMessageAsync(
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
                // Attachments need more room to summarise; plain questions stay lean.
                maxOutputToken = if (attachedContext.isNotBlank()) {
                    MainUiState.MAX_RESPONSE_TOKENS_WITH_CONTEXT
                } else {
                    MAX_RESPONSE_TOKENS
                },
            )

            // Bounded wait: a stalled engine must not wedge the inference thread, which would
            // make every later request look frozen with the last answer on screen.
            val finished = streamFinished.await(GENERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                guard.cancel()
                engineManager.invalidateConversation("generation timed out")
                throw IllegalStateException(
                    "Generation timed out after ${GENERATION_TIMEOUT_SECONDS}s. Try a shorter request."
                )
            }
            streamError.get()?.let { error ->
                engineManager.invalidateConversation("streaming failed")
                throw IllegalStateException("Streaming inference failed.", error)
            }

            // The user pressed Stop: the context holds a half-written turn, so start fresh next time.
            if (isGeneratingCancelled.get() || guard.wasCancelled) {
                engineManager.invalidateConversation("generation stopped by user")
            }
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
     * Formats and shows the finished answer.
     *
     * Markdown parsing happens once, on the IO executor, so the UI thread never pays for it. While
     * it runs the plain streamed text stays visible, which means a long answer never flashes blank.
     */
    private fun renderFinalAnswer(result: String, stoppedByUser: Boolean) {
        if (result.isBlank()) {
            answerText.text = if (stoppedByUser) "Generation stopped." else
                "No answer was returned. Try rephrasing the request."
            return
        }
        streamingRenderer.onComplete(result) { formatted ->
            answerText.text = formatted
            maybeFollowStreamingOutput()
        }
    }

    /** Switches the primary composer action from Ask to Stop while a generation is running. */
    private fun enterGeneratingState() {
        isGeneratingUi = true
        suggestButton.isEnabled = true
        suggestButton.text = "Stop"
        suggestButton.contentDescription = "Stop generating answer"
        suggestButton.background = ripple(shape(STOP_CRIMSON, 16), 0x33FFFFFF)
        suggestButton.setTextColor(WHITE)
        suggestButton.setOnClickListener { stopActiveGeneration() }
        syncComposerState()
    }

    /** Restores the primary composer action to Ask. */
    private fun exitGeneratingState() {
        if (!isGeneratingUi) return
        isGeneratingUi = false
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF167C6F.toInt(), 0xFF10574F.toInt())
        ).apply { cornerRadius = dp(16).toFloat() }
        suggestButton.text = "Ask"
        suggestButton.contentDescription = "Send your question and get an answer"
        suggestButton.background = ripple(gradient, 0x33FFFFFF)
        suggestButton.setTextColor(WHITE)
        suggestButton.setOnClickListener { suggestSteps() }
        suggestButton.isEnabled = true
        syncComposerState()
    }

    /**
     * Stops the running generation immediately: flags it, unblocks the waiting latch by cancelling
     * the guard, drops the streaming preview, and restores Ask. Whatever text had arrived is kept.
     */
    private fun stopActiveGeneration() {
        if (!isGeneratingUi) return
        isGeneratingCancelled.set(true)
        streamingRenderer.cancel()
        activeStreamLatch?.let { latch ->
            // The engine may never call onDone after a cancel, so release the waiter ourselves.
            latch.countDown()
        }
        exitGeneratingState()
        stopGeneratingButton.visibility = View.GONE
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
    private fun updateRefineAffordance() {
        if (!::refineButton.isInitialized) return
        refineButton.visibility = if (maybeShowRefineAffordance && lastPrompt.isNotBlank()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    /**
     * Runs the corrective pass on demand.
     *
     * Forces a fresh conversation because the retry uses a different prompt shape, and reuses the
     * same intent and calculation context as the original attempt.
     */
    private fun refineLastAnswer() {
        val task = lastPrompt
        if (task.isBlank() || isGeneratingUi) return
        val modelFile = File(filesDir, "model.litertlm")
        if (!modelFile.exists()) {
            updateStatus("Choose a compatible .litertlm model first.")
            return
        }
        maybeShowRefineAffordance = false
        updateRefineAffordance()
        isGeneratingCancelled.set(false)
        streamingRenderer.reset()
        enterGeneratingState()
        updateStatus("Refining the answer…")

        ioExecutor.execute {
            try {
                engineManager.getOrInitializeEngine(modelFile, cacheDir)
                val intent = AnswerDirective.classify(task.ifBlank { attachedContext })
                val history = conversationHistoryFor(task)
                val calculation = if (task.isNotBlank()) LocalCalculatorTool.tryEvaluate(task) else null
                val instruction = AnswerDirective.systemInstructionFor(intent)
                val retryPrompt = answerPrompt(
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
                val retried = runGuardedGeneration(retryPrompt, instruction)
                mainHandler.post {
                    exitGeneratingState()
                    if (retried.isNotBlank()) {
                        answerIsComplete = true
                        renderFinalAnswer(retried, stoppedByUser = false)
                        updateStatus("Answer refined.")
                    } else {
                        updateStatus("Refine did not produce a new answer.")
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    exitGeneratingState()
                    updateStatus("Could not refine the answer: ${e.message ?: "unknown error"}")
                }
            }
        }
    }

    /**
     * Cache key for the reusable conversation.
     *
     * Changing chat, model, instruction, or attachment must all start a fresh KV cache, otherwise
     * stale context leaks into the next answer.
     */
    private val conversationKey: String
        get() {
            val model = File(filesDir, "model.litertlm")
            return engineManager.conversationKey(
                sessionId = currentSession?.id,
                modelPath = model.absolutePath,
                systemInstruction = AnswerDirective.SYSTEM_INSTRUCTION,
                attachmentHash = attachedContext.hashCode(),
            )
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
        lastPrompt = task
        isGeneratingCancelled.set(false)
        streamingRenderer.reset()
        ComposerInputGuard.resetInputHeight(taskInput)
        suggestButton.isEnabled = false
        answerIsComplete = false
        outputCard.visibility = View.VISIBLE
        stopGeneratingButton.visibility = View.GONE
        enterGeneratingState()
        syncComposerState()
        scrollChatToBottom(force = true)
        answerText.text = "Working on it…"
        val backendLabel = engineManager.activeBackend?.name ?: "accelerated"
        updateStatus("Answering on this device ($backendLabel)…")
        inferenceExecutor.execute {
            var localCitations = emptyList<LocalDocumentRetriever.DocumentCitation>()
            try {
                engineManager.getOrInitializeEngine(modelFile, cacheDir)
                val intent = AnswerDirective.classify(task.ifBlank { attachedContext })
                val history = conversationHistoryFor(task)
                val calculation = if (task.isNotBlank()) LocalCalculatorTool.tryEvaluate(task) else null
                val boundedAttachment = if (attachedContext.isNotBlank()) {
                    val retrieval = LocalDocumentRetriever.retrieveTextWithCitations(
                        query = task,
                        documentText = attachedContext,
                        sourceName = attachedNames.firstOrNull() ?: "attachment",
                        maxChars = MAX_ATTACHMENT_CHARS,
                    )
                    localCitations = retrieval.citations
                    if (retrieval.promptContext.isNotBlank()) retrieval.promptContext else attachedContext
                } else {
                    localCitations = emptyList()
                    ""
                }

                // Long-term memory (from main) enriches the intent-aware instruction rather than
                // replacing it, so answer-shaped prompting and the runaway guard still apply.
                val effectiveInstruction = if (isMemoryEnabled) {
                    val candidateFacts = MemoryFactExtractor.extractCandidateFacts(task)
                    for (cf in candidateFacts) {
                        userMemoryStore.addFact(
                            content = cf.content,
                            category = cf.category,
                            confidence = cf.confidence,
                            sourceSessionId = currentSession?.id
                        )
                    }
                    val memoryBlock = userMemoryStore.buildPromptContext(maxTokens = 120)
                    ContextBudgetManager().enrichSystemInstructionWithMemory(
                        AnswerDirective.systemInstructionFor(intent),
                        memoryBlock,
                    )
                } else {
                    AnswerDirective.systemInstructionFor(intent)
                }

                val prompt = answerPrompt(
                    task = task,
                    attachment = boundedAttachment,
                    intent = intent,
                    isRetry = false,
                    history = history,
                    calculation = calculation,
                )
                runawayFragment = null
                // One pass only. A dodged answer is surfaced as-is with a Refine affordance,
                // rather than silently burning a second full generation the user never asked for.
                val result = runGuardedGeneration(prompt, effectiveInstruction)
                val needsRefineHint = !isGeneratingCancelled.get() && runawayFragment == null &&
                    (AnswerDirective.looksLikeDelegationRefusal(result, intent) ||
                        AnswerDirective.isUnsubstantive(result))
                maybeShowRefineAffordance = needsRefineHint
                mainHandler.post {
                    exitGeneratingState()
                    stopGeneratingButton.visibility = View.GONE
                    suggestButton.isEnabled = true
                    syncComposerState()
                    scrollChatToBottom()
                    currentCitations = localCitations
                    renderCitations(localCitations)
                    if (isGeneratingCancelled.get()) {
                        renderFinalAnswer(result, stoppedByUser = true)
                        updateStatus("Generation stopped by user.")
                        return@post
                    }
                    answerIsComplete = result.isNotBlank()
                    renderFinalAnswer(result, stoppedByUser = false)
                    updateRefineAffordance()
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
                            val sess = currentSession ?: ConversationSession.createWithInitialPrompt(task.ifBlank { "Conversation" })
                            val userMsg = ChatMessage.user(
                                content = task,
                                attachmentContent = if (attachedContext.isNotBlank()) attachedContext else null
                            )
                            val assistantMsg = ChatMessage.assistant(result)
                            val updated = sess.withMessage(userMsg).withMessage(assistantMsg)
                            sessionStore.saveSession(updated)
                            currentSession = updated
                        } catch (_: Exception) {}
                    }

                    // Safe actionable system intents review
                    val parsedActions = SystemActionTools.parseActions(result)
                    systemActionContainer.removeAllViews()
                    if (parsedActions.isNotEmpty()) {
                        systemActionContainer.visibility = View.VISIBLE
                        for (action in parsedActions.take(3)) {
                            val reviewUi = SystemActionTools.getReviewUi(action)
                            systemActionContainer.addView(
                                pillButton(reviewUi.buttonLabel, accessibilityLabel = reviewUi.buttonLabel) {
                                    showActionReviewDialog(reviewUi)
                                }.apply {
                                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
                                }
                            )
                        }
                    } else {
                        systemActionContainer.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    stopGeneratingButton.visibility = View.GONE
                    suggestButton.isEnabled = true
                    if (isGeneratingCancelled.get()) {
                        updateStatus("Generation stopped by user.")
                    } else {
                        currentCitations = localCitations
                        renderCitations(localCitations)
                        answerIsComplete = false
                        answerText.text = ""
                        outputCard.visibility = View.GONE
                        systemActionContainer.removeAllViews()
                        systemActionContainer.visibility = View.GONE
                        updateStatus("Could not generate an answer: ${e.message ?: "unknown error"}")
                    }
                }
            }
        }
    }

    private fun regenerateAnswer() {
        if (lastPrompt.isNotBlank() && suggestButton.isEnabled) {
            taskInput.setText(lastPrompt)
            suggestSteps()
        }
    }

    private fun startNewConversation() {
        val newSession = ConversationSession.createWithInitialPrompt("New Conversation")
        try {
            sessionStore.saveSession(newSession)
        } catch (_: Exception) {}
        currentSession = newSession
        engineManager.invalidateConversation("new chat")
        maybeShowRefineAffordance = false
        if (::refineButton.isInitialized) refineButton.visibility = View.GONE
        taskInput.setText("")
        ComposerInputGuard.resetInputHeight(taskInput)
        answerText.text = ""
        outputCard.visibility = View.GONE
        systemActionContainer.removeAllViews()
        systemActionContainer.visibility = View.GONE
        lastPrompt = ""
        syncComposerState()
        scrollChatToBottom(force = true)
        updateStatus("Started a new conversation.")
    }

    private fun loadSession(id: String) {
        val s = sessionStore.getSession(id) ?: return
        currentSession = s
        // A different chat must not inherit the previous chat's KV cache.
        engineManager.invalidateConversation("session switched")
        val lastUserMsg = s.messages.lastOrNull { it.isUser }
        val lastAssistantMsg = s.messages.lastOrNull { it.isAssistant }
        if (lastUserMsg != null) {
            taskInput.setText(lastUserMsg.content)
            ComposerInputGuard.resetInputHeight(taskInput)
            lastPrompt = lastUserMsg.content
        }
        if (lastAssistantMsg != null) {
            outputCard.visibility = View.VISIBLE
            answerText.text = MarkdownFormatter.formatForDisplay(lastAssistantMsg.content)
            answerIsComplete = true
        } else {
            outputCard.visibility = View.GONE
            answerText.text = ""
            answerIsComplete = false
        }
        updateStatus("Loaded: ${s.title}")
    }

    private fun showHistoryDialog() {
        val summaries = sessionStore.listSessionSummaries()
        val dialogView = column().apply {
            setPadding(dp(18), dp(12), dp(18), dp(18))
        }

        val searchBox = EditText(this).apply {
            hint = "Search conversations…"
            textSize = 14f
            background = shape(WHITE, 12, BORDER)
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

        fun populateList(query: String = "") {
            listContainer.removeAllViews()
            val filtered = if (query.isBlank()) summaries else summaries.filter {
                it.title.contains(query, ignoreCase = true)
            }
            if (filtered.isEmpty()) {
                listContainer.addView(text("No conversations found.", 13f, MUTED).apply {
                    setPadding(0, dp(20), 0, dp(20))
                    gravity = Gravity.CENTER
                })
                return
            }

            for (s in filtered) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = shape(WHITE, 12, BORDER)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        loadSession(s.id)
                        dialog.dismiss()
                    }
                }
                val info = column().apply {
                    val titlePrefix = if (s.isPinned) "📌 " else ""
                    addView(text("$titlePrefix${s.title}", 14f, INK, true))
                    val dateStr = java.text.SimpleDateFormat("MMM dd, yyyy · HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s.updatedAt))
                    addView(text("$dateStr · ${s.messageCount} turns", 11f, MUTED))
                }
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

                val menuBtn = text("⋮", 18f, MUTED, true).apply {
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    setOnClickListener {
                        showSessionOptionsMenu(s) {
                            populateList(searchBox.text.toString())
                        }
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

        populateList()

        dialog = AlertDialog.Builder(this)
            .setTitle("Conversations (${summaries.size})")
            .setView(dialogView)
            .setPositiveButton("+ New Chat") { _, _ ->
                startNewConversation()
            }
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
    }

    private fun showSessionOptionsMenu(s: SessionSummary, onUpdated: () -> Unit) {
        val pinLabel = if (s.isPinned) "Unpin" else "Pin to top"
        val options = arrayOf(pinLabel, "Rename", "Export as Markdown", "Export as Text", "Delete")
        AlertDialog.Builder(this)
            .setTitle(s.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        sessionStore.pinSession(s.id, !s.isPinned)
                        onUpdated()
                    }
                    1 -> promptRenameSession(s, onUpdated)
                    2 -> exportSessionToShare(s.id, asMarkdown = true)
                    3 -> exportSessionToShare(s.id, asMarkdown = false)
                    4 -> {
                        sessionStore.deleteSession(s.id)
                        if (currentSession?.id == s.id) {
                            startNewConversation()
                        }
                        onUpdated()
                    }
                }
            }
            .show()
    }

    private fun promptRenameSession(s: SessionSummary, onUpdated: () -> Unit) {
        val input = EditText(this).apply {
            setText(s.title)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Conversation")
            .setView(input)
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
            .show()
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
        if (sess != null) {
            exportSessionToShare(sess.id, asMarkdown = true)
        } else {
            val text = answerText.text.toString()
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
        val dialogView = column().apply {
            setPadding(dp(18), dp(12), dp(18), dp(18))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val toggleButton = pillButton(
            if (isMemoryEnabled) "Memory: ON" else "Memory: OFF",
            accessibilityLabel = "Toggle sovereign long-term memory"
        ) {}.apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(8) }
        }
        headerRow.addView(toggleButton)

        val addFactBtn = pillButton("➕ Add Fact", accessibilityLabel = "Manually record a user preference or fact") {}.apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(8) }
        }
        headerRow.addView(addFactBtn)

        val clearAllBtn = textButton("Clear All", accessibilityLabel = "Clear all sovereign memories") {}
        headerRow.addView(clearAllBtn)
        dialogView.addView(headerRow, marginBottom(12))

        val desc = text("100% on-device memory. PocketAgent remembers key facts and preferences across conversations without sending data anywhere.", 12f, MUTED)
        dialogView.addView(desc, marginBottom(12))

        val listContainer = column()
        val scroll = ScrollView(this).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(-1, dp(320))
        }
        dialogView.addView(scroll)

        fun refreshList() {
            listContainer.removeAllViews()
            val memories = userMemoryStore.listMemories()
            if (memories.isEmpty()) {
                listContainer.addView(text("No memories recorded yet.\nAs you chat, preferences and facts will be remembered here.", 13f, MUTED).apply {
                    setPadding(0, dp(30), 0, dp(30))
                    gravity = Gravity.CENTER
                })
                return
            }

            for (m in memories) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = shape(WHITE, 12, BORDER)
                }

                val info = column().apply {
                    val catBadge = "[${m.displayLabel}]"
                    addView(text(catBadge, 11f, TEAL, true))
                    addView(text(m.content, 13f, INK))
                }
                card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

                val delBtn = text("✕", 16f, MUTED, true).apply {
                    setPadding(dp(12), dp(6), dp(6), dp(6))
                    setOnClickListener {
                        userMemoryStore.deleteMemory(m.id)
                        refreshList()
                    }
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
            AlertDialog.Builder(this)
                .setTitle("Clear All Memories?")
                .setMessage("Are you sure you want to permanently delete all stored memory facts?")
                .setPositiveButton("Clear") { _, _ ->
                    userMemoryStore.clearMemories()
                    refreshList()
                    Toast.makeText(this, "All memories cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        refreshList()

        AlertDialog.Builder(this)
            .setTitle("🧠 Sovereign Memory Vault")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun promptAddMemoryFact(onAdded: () -> Unit) {
        val input = EditText(this).apply {
            hint = "e.g., Prefers Kotlin, Speaks Bengali, Offline only…"
            textSize = 14f
            background = shape(WHITE, 12, BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(INK)
            setHintTextColor(MUTED)
        }
        val categories = arrayOf("Preference", "Profile", "Constraint", "Goal", "Custom")
        var selectedCategoryIndex = 0

        val container = column().apply {
            setPadding(dp(18), dp(12), dp(18), dp(12))
            addView(input, marginBottom(10))
        }

        AlertDialog.Builder(this)
            .setTitle("Add Memory Fact")
            .setView(container)
            .setSingleChoiceItems(categories, 0) { _, which ->
                selectedCategoryIndex = which
            }
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
            .show()
    }

    private fun showNotesManagerDialog() {
        val dialogView = column().apply {
            setPadding(dp(18), dp(12), dp(18), dp(18))
        }

        val searchBox = EditText(this).apply {
            hint = "Search personal notes…"
            textSize = 14f
            background = shape(WHITE, 12, BORDER)
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
                listContainer.addView(text("No notes found.", 13f, MUTED).apply {
                    setPadding(0, dp(30), 0, dp(30))
                    gravity = Gravity.CENTER
                })
                return
            }

            for (n in notes) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = shape(WHITE, 12, BORDER)
                }

                val info = column().apply {
                    val pinPrefix = if (n.isPinned) "📌 " else ""
                    addView(text("$pinPrefix${n.title}", 14f, INK, true))
                    if (n.content.isNotBlank()) {
                        val preview = n.content.take(80).replace("\n", " ") + (if (n.content.length > 80) "…" else "")
                        addView(text(preview, 12f, MUTED))
                    }
                    val dateStr = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(java.util.Date(n.updatedAt))
                    addView(text(dateStr, 10f, TEAL))
                }
                card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

                card.setOnClickListener {
                    promptEditNote(n) { refreshList(searchBox.text.toString()) }
                }

                val pinBtn = text(if (n.isPinned) "Unpin" else "Pin", 11f, TEAL, true).apply {
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setOnClickListener {
                        notesStore.pinNote(n.id, !n.isPinned)
                        refreshList(searchBox.text.toString())
                    }
                }
                card.addView(pinBtn)

                val delBtn = text("✕", 15f, MUTED, true).apply {
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setOnClickListener {
                        notesStore.deleteNote(n.id)
                        refreshList(searchBox.text.toString())
                    }
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

        AlertDialog.Builder(this)
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
            .show()
    }

    private fun promptEditNote(existingNote: PersonalNotesStore.PersonalNote?, onSaved: () -> Unit) {
        val container = column().apply {
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

        val titleInput = EditText(this).apply {
            hint = "Note Title"
            textSize = 14f
            background = shape(WHITE, 12, BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(INK)
            setHintTextColor(MUTED)
            if (existingNote != null) setText(existingNote.title)
        }
        container.addView(titleInput, marginBottom(10))

        val contentInput = EditText(this).apply {
            hint = "Note Content…"
            textSize = 13f
            background = shape(WHITE, 12, BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(INK)
            setHintTextColor(MUTED)
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            if (existingNote != null) setText(existingNote.content)
        }
        container.addView(contentInput, marginBottom(10))

        AlertDialog.Builder(this)
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
            .show()
    }

    private fun showDuplexVoiceDialog() {
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
                intArrayOf(0xFF153B36.toInt(), 0xFF176B60.toInt())
            ).apply { cornerRadius = dp(20).toFloat() }
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val title = text("🎙️ Continuous Voice Mode", 18f, WHITE, true)
        dialogView.addView(title)

        val stateBadge = text("Initializing…", 13f, 0xFFD5EAE5.toInt(), true).apply {
            setPadding(0, dp(6), 0, dp(10))
        }
        dialogView.addView(stateBadge)

        val transcriptView = TextView(this).apply {
            textSize = 14f
            setTextColor(WHITE)
            text = "Say something to start speaking…"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = shape(0x33000000.toInt(), 12)
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
        val interruptBtn = pillButton("Interrupt / Next", accessibilityLabel = "Interrupt and listen again") {
            duplexController.interrupt()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(10) }
        }
        btnRow.addView(interruptBtn)

        lateinit var dialog: AlertDialog

        val endBtn = pillButton("End Session", accessibilityLabel = "End voice duplex mode") {
            duplexController.stopSession()
            dialog.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(38))
        }
        btnRow.addView(endBtn)
        dialogView.addView(btnRow)

        dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.show()

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
                }

                override fun onAmplitude(rms: Float) {}

                override fun onUserTranscribed(text: String) {
                    transcriptView.text = "You: $text\n\nAssistant: Thinking…"
                }

                override fun onAssistantChunk(chunk: String) {}

                override fun onTurnComplete(userText: String, assistantText: String) {
                    transcriptView.text = "You: $userText\n\nAssistant: $assistantText"

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
                }
            }
        )
    }

    private fun showPrivacyCenterDialog() {
        val permAudit = PrivacyAuditCenter.auditPermissions(this)
        val storageAudit = PrivacyAuditCenter.auditStorage(filesDir, cacheDir)

        val dialogView = column().apply {
            setPadding(dp(18), dp(12), dp(18), dp(18))
        }

        // 1. Zero-Cloud Telemetry Guarantee Badge
        val auditCard = column().apply {
            background = shape(0xFFE8F5E9.toInt(), 12, 0xFFC8E6C9.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val auditTitle = text("🛡️ 100% Zero-Cloud Guarantee", 14f, 0xFF1B5E20.toInt(), true)
        auditCard.addView(auditTitle)
        val auditDesc = text(
            if (permAudit.isZeroCloudCompliant)
                "Verified: Hardware-level zero cloud telemetry. PocketAgent does not request or hold 'android.permission.INTERNET'. All audio, documents, and inference run 100% locally on this device."
            else
                "Warning: Non-standard permissions detected.",
            12f,
            0xFF2E7D32.toInt()
        ).apply { setPadding(0, dp(4), 0, 0) }
        auditCard.addView(auditDesc)
        dialogView.addView(auditCard, marginBottom(12))

        // 2. Storage Audit Card
        val storageCard = column().apply {
            background = shape(WHITE, 12, BORDER)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        storageCard.addView(text("💾 Local Storage Breakdown", 14f, INK, true), marginBottom(8))

        fun addStorageRow(label: String, value: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(text(label, 12f, MUTED), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(value, 12f, INK, true))
            storageCard.addView(row)
        }

        addStorageRow("AI Models (.litertlm & whisper):", storageAudit.modelDisplay)
        addStorageRow("Saved Conversations:", storageAudit.sessionsDisplay)
        addStorageRow("Long-Term Memories:", storageAudit.memoriesDisplay)
        addStorageRow("Personal Notes:", storageAudit.notesDisplay)
        addStorageRow("Temporary Cache:", storageAudit.cacheDisplay)
        addStorageRow("Total Storage Used:", storageAudit.totalDisplay)
        dialogView.addView(storageCard, marginBottom(12))

        // 3. Biometric App Lock Row
        val lockCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(WHITE, 12, BORDER)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val lockInfo = column().apply {
            addView(text("🔒 Biometric App Lock", 14f, INK, true))
            addView(text("Require fingerprint or device PIN on launch", 11f, MUTED))
        }
        lockCard.addView(lockInfo, LinearLayout.LayoutParams(0, -2, 1f))

        val lockToggleBtn = pillButton(
            if (biometricLockManager.isLockEnabled) "Enabled" else "Disabled",
            accessibilityLabel = "Toggle biometric app lock"
        ) {}.apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(32))
        }
        lockToggleBtn.setOnClickListener {
            val newState = !biometricLockManager.isLockEnabled
            biometricLockManager.setLockEnabled(newState)
            lockToggleBtn.text = if (newState) "Enabled" else "Disabled"
            Toast.makeText(this, if (newState) "Biometric lock enabled" else "Biometric lock disabled", Toast.LENGTH_SHORT).show()
        }
        lockCard.addView(lockToggleBtn)
        dialogView.addView(lockCard, marginBottom(12))

        // 4. Panic Wipe Button
        val panicBtn = pillButton("⚠️ Panic Wipe All User Data", accessibilityLabel = "Emergency delete all personal data") {
            AlertDialog.Builder(this)
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
            background = shape(0xFFFFEBEE.toInt(), 12, 0xFFFFCDD2.toInt())
            setTextColor(0xFFC62828.toInt())
        }
        dialogView.addView(panicBtn)

        AlertDialog.Builder(this)
            .setTitle("🛡️ Privacy & Sovereign Security")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun promptBiometricUnlock() {
        if (!biometricLockManager.isAppLocked) return
        biometricLockManager.unlock()
    }

    private fun showActionReviewDialog(reviewUi: SystemActionTools.ActionReviewUi) {
        AlertDialog.Builder(this)
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
            .show()
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
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { updateStatus("Listening… speak your task now.") }
            override fun onResults(results: Bundle?) {
                val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (transcript != null) {
                    taskInput.setText(transcript)
                    taskInput.setSelection(taskInput.text.length)
                    updateStatus("Transcript ready. Review it, then confirm.")
                } else updateStatus("No speech was recognized. You can type instead.")
                speech.destroy()
                if (recognizer === speech) recognizer = null
            }
            override fun onError(error: Int) {
                updateStatus("Speech recognition failed ($error). You can type instead.")
                speech.destroy()
                if (recognizer === speech) recognizer = null
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
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
        val answer = answerText.text.toString().trim()
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

    private fun copyAnswer() {
        val text = answerText.text.toString()
        if (text.isBlank()) return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PocketAgent answer", text))
        Toast.makeText(this, "Answer copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareAnswer() {
        val text = answerText.text.toString()
        if (text.isBlank()) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share answer"))
    }

    private fun speakAnswer() {
        val raw = answerText.text.toString()
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
        val model = File(filesDir, "model.litertlm")
        if (!::modelPill.isInitialized) return
        if (model.exists() && model.length() > 0) {
            modelPill.text = "MODEL READY · ${formatBytes(model.length())}"
            modelPill.contentDescription =
                "Active model ready, ${formatBytes(model.length())}. Tap to choose a different LiteRT-LM model"
        } else {
            modelPill.text = "Choose model"
            modelPill.contentDescription = "No active model. Tap to choose a LiteRT-LM model"
        }
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
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun marginBottom(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(value) }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun shape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun sectionHeading(title: String, subtitle: String): LinearLayout = column().apply {
        addView(text(title, 18f, INK, true))
        addView(text(subtitle, 12f, MUTED).apply { setPadding(0, dp(3), 0, 0) })
    }

    private fun pillButton(
        label: String,
        accessibilityLabel: String = label,
        onClick: () -> Unit,
    ): Button = Button(this).apply {
        text = label
        contentDescription = accessibilityLabel
        setAllCaps(false)
        textSize = 13f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(TEAL)
        setPadding(dp(13), dp(8), dp(13), dp(8))
        minHeight = dp(48)
        minWidth = dp(48)
        background = ripple(shape(MINT, 30), 0x33126B60)
        backgroundTintList = null
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun textButton(
        label: String,
        accessibilityLabel: String = label,
        onClick: () -> Unit,
    ): Button = Button(this).apply {
        text = label
        contentDescription = accessibilityLabel
        setAllCaps(false)
        textSize = 12f
        setTextColor(TEAL)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        minHeight = dp(48)
        minWidth = dp(48)
        background = ripple(shape(Color.TRANSPARENT, 8), 0x22126B60)
        backgroundTintList = null
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun actionButton(
        label: String,
        accessibilityLabel: String = label,
        onClick: () -> Unit,
    ): Button = Button(this).apply {
        text = label
        contentDescription = accessibilityLabel
        setAllCaps(false)
        textSize = 16f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(WHITE)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        val gradient = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF167C6F.toInt(), 0xFF10574F.toInt())).apply {
            cornerRadius = dp(16).toFloat()
        }
        background = ripple(gradient, 0x33FFFFFF)
        backgroundTintList = null
        minHeight = dp(56)
        minWidth = dp(48)
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun ripple(content: GradientDrawable, color: Int) =
        RippleDrawable(ColorStateList.valueOf(color), content, null)

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        inAppAudioRecorder.cancelRecording()
        streamingRenderer.cancel()
        // Drop the cached conversation explicitly so a stale KV cache cannot outlive the Activity.
        engineManager.closeConversation()
        ioExecutor.execute {
            engineManager.close()
            val context = whisperContext
            whisperContext = 0L
            if (context != 0L) WhisperNative.release(context)
        }
        inferenceExecutor.shutdown()
        ioExecutor.shutdown()
        super.onDestroy()
    }

    private fun renderCitations(citations: List<LocalDocumentRetriever.DocumentCitation>) {
        citationsContainer.removeAllViews()
        if (citations.isEmpty()) {
            citationsContainer.visibility = View.GONE
            return
        }
        citationsContainer.visibility = View.VISIBLE
        citationsContainer.addView(text("📌 Document Citations", 12f, TEAL, true).apply {
            setPadding(0, 0, 0, dp(4))
        })
        val wrapRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (cit in citations) {
            val pill = textButton("📄 ${cit.citationLabel}", accessibilityLabel = "View citation from ${cit.citationLabel}") {
                showCitationDialog(cit)
            }.apply {
                background = shape(MINT, 12, TEAL)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                textSize = 11f
                setTextColor(TEAL)
            }
            wrapRow.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        val hScroll = HorizontalScrollView(this).apply {
            addView(wrapRow)
            isHorizontalScrollBarEnabled = false
        }
        citationsContainer.addView(hScroll)
    }

    private fun showCitationDialog(citation: LocalDocumentRetriever.DocumentCitation) {
        val dialogView = column().apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(text(citation.citationLabel, 16f, INK, true))
            val snippetText = citation.excerptSnippet.ifBlank { "No excerpt snippet available." }
            addView(text("\"$snippetText…\"", 13f, MUTED).apply {
                setPadding(0, dp(10), 0, dp(12))
                setLineSpacing(dp(3).toFloat(), 1.0f)
            })
        }
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .show()
    }
}
