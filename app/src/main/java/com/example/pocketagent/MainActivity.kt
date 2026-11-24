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
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
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
        private const val MAX_ATTACHMENT_CHARS = 12_000
        private const val MAX_RESPONSE_TOKENS = MainUiState.MAX_RESPONSE_TOKENS
        /** Hard ceiling so a stalled engine cannot block the worker thread forever. */
        private const val GENERATION_TIMEOUT_SECONDS = 120L
        private val SYSTEM_INSTRUCTION: String get() = AnswerDirective.SYSTEM_INSTRUCTION
        private const val BG = 0xFFF4F7F6.toInt()
        private const val INK = 0xFF162D2A.toInt()
        private const val MUTED = 0xFF5F6F6A.toInt()
        private const val TEAL = 0xFF126B60.toInt()
        private const val MINT = 0xFFE2F3ED.toInt()
        private const val BORDER = 0xFFE3EBE8.toInt()
        private const val WHITE = Color.WHITE
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineManager = InferenceEngineManager<Engine>()
    private val promptContext = AnswerPromptComposer()
    private val inAppAudioRecorder = InAppAudioRecorder()
    @Volatile private var whisperContext: Long = 0L
    private lateinit var taskInput: EditText
    private lateinit var statusText: TextView
    private lateinit var answerText: TextView
    private lateinit var modelText: TextView
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
    private var currentSession: ConversationSession? = null
    private var lastPrompt: String = ""
    @Volatile private var currentCitations = emptyList<LocalDocumentRetriever.DocumentCitation>()
    private val isGeneratingCancelled = AtomicBoolean(false)

    /** Set by the runaway guard so the UI can explain why generation stopped. */
    @Volatile private var runawayFragment: String? = null
    private lateinit var stopGeneratingButton: Button

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
    }

    private fun buildInterface() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(26))
            setBackgroundColor(BG)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(page)
        }
        setContentView(scroll)

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(16))
        }
        val mark = TextView(this).apply {
            text = "P"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(WHITE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = shape(TEAL, 15)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        }
        brandRow.addView(mark)
        val brand = column().apply {
            setPadding(dp(11), 0, 0, 0)
            addView(text("PocketAgent", 18f, INK, true))
            addView(text("LOCAL MODEL INFERENCE", 10f, MUTED, true).apply { letterSpacing = .12f })
        }
        brandRow.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        brandRow.addView(pillButton("☰ Chats", accessibilityLabel = "View saved conversations") {
            showHistoryDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) }
        })
        brandRow.addView(pillButton("+ New", accessibilityLabel = "Start a fresh conversation") {
            startNewConversation()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(34))
        })
        page.addView(brandRow)

        val hero = column().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF153B36.toInt(), 0xFF176B60.toInt())).apply { cornerRadius = dp(24).toFloat() }
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        hero.addView(text("Your pocket assistant", 25f, WHITE, true))
        hero.addView(text("Ask anything. Get the actual answer, computed on this device.", 14f, 0xFFD5EAE5.toInt()).apply {
            setPadding(0, dp(7), 0, dp(14))
        })
        val modelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        modelText = text("No model selected", 12f, WHITE, true).apply {
            setPadding(dp(12), dp(10), dp(10), dp(10))
            maxLines = 2
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        modelRow.addView(modelText, LinearLayout.LayoutParams(0, -2, 1f))
        modelRow.addView(pillButton("Choose model", accessibilityLabel = "Choose a LiteRT-LM model") { chooseModel() })
        hero.addView(modelRow)
        page.addView(hero, marginBottom(18))

        val promptHeading = sectionHeading("What can I help with?", "Type, dictate, or attach text or a selectable-text PDF")
        val promptTitle = promptHeading.getChildAt(0) as TextView
        page.addView(promptHeading, marginBottom(9))
        val promptCard = column().apply {
            background = shape(WHITE, 20, BORDER)
            setPadding(dp(15), dp(9), dp(15), dp(12))
        }
        taskInput = EditText(this).apply {
            id = View.generateViewId()
            promptTitle.labelFor = id
            hint = "e.g. Help me plan a study schedule…"
            setHintTextColor(MUTED)
            setTextColor(INK)
            textSize = 16f
            minLines = 4
            maxLines = 9
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(2), dp(5), dp(2), dp(8))
            background = null
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NONE
        }
        promptCard.addView(taskInput, LinearLayout.LayoutParams(-1, -2))

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actionRow.addView(pillButton("＋  Add text", accessibilityLabel = "Attach text files") { chooseTextFiles() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(7) })
        actionRow.addView(pillButton("◉  Dictate", accessibilityLabel = "Dictate a task") { startDictation() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(7) })
        promptCard.addView(actionRow)
        promptCard.addView(pillButton("＋  Add PDF", accessibilityLabel = "Attach a PDF document") { choosePdf() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) }
        })
        whisperModelText = text("No local Whisper model selected.", 11f, MUTED).apply {
            setPadding(dp(2), dp(9), dp(2), dp(1))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        promptCard.addView(whisperModelText)
        promptCard.addView(pillButton("Choose Whisper model", accessibilityLabel = "Choose a local whisper.cpp ggml model") {
            chooseWhisperModel()
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) } })
        promptCard.addView(textButton("Remove Whisper model", accessibilityLabel = "Remove the locally stored Whisper model") {
            removeWhisperModel()
        })
        val audioActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recordAudioButton = pillButton("🎙  Record audio", accessibilityLabel = "Record audio for local Whisper transcription") {
            toggleInAppRecording()
        }.apply { isEnabled = false }
        audioActionsRow.addView(recordAudioButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })

        transcribeWavButton = pillButton("Transcribe WAV", accessibilityLabel = "Choose a WAV file for local transcription") {
            chooseWav()
        }.apply { isEnabled = false }
        audioActionsRow.addView(transcribeWavButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        promptCard.addView(audioActionsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })

        promptCard.addView(text("WAV and in-app recording run locally with a Whisper model you choose; no weights are bundled. In-app audio is PCM16 16kHz mono (max 60s, 25 MB) with automatic memory wipe. Scanned PDFs are not OCR'd; dictation still depends on your device's speech service.", 11f, MUTED).apply {
            setPadding(dp(2), dp(8), dp(2), dp(2))
        })
        attachmentCard = column().apply { visibility = View.GONE; setPadding(0, dp(9), 0, 0) }
        attachmentText = text("", 12f, MUTED)
        attachmentCard.addView(attachmentText)
        attachmentPreview = text("", 12f, INK).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = shape(BG, 10)
            setTextIsSelectable(true)
            maxLines = 8
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        attachmentCard.addView(attachmentPreview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
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
        promptCard.addView(attachmentCard)
        page.addView(promptCard, marginBottom(12))

        val quickScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Plan my day", "Explain a setting", "Write a message", "Learn something").forEach { label ->
            val chip = pillButton(label, accessibilityLabel = "Use task preset: $label") { taskInput.setText(label) }
            quickRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        quickScroll.addView(quickRow)
        page.addView(quickScroll, marginBottom(14))

        suggestButton = actionButton("Ask", accessibilityLabel = "Send your question and get an answer") { suggestSteps() }
        page.addView(suggestButton, marginBottom(9))
        statusText = text("Choose a compatible .litertlm model to get started.", 12f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(15))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        page.addView(statusText)

        val disclaimer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(0xFFEAF2F0.toInt(), 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        disclaimer.addView(text("ⓘ", 18f, TEAL, true).apply { setPadding(0, 0, dp(9), 0) })
        disclaimer.addView(text("Answers and steps only. PocketAgent never controls your phone or acts for you.", 12f, 0xFF46615B.toInt()))
        page.addView(disclaimer, marginBottom(18))

        outputCard = column().apply {
            visibility = View.GONE
            background = shape(WHITE, 20, BORDER)
            setPadding(dp(16), dp(15), dp(16), dp(16))
        }
        val outputHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val outputTitle = column()
        outputTitle.addView(text("Answer", 19f, INK, true))
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
            setPadding(0, dp(15), 0, dp(15))
            setTextIsSelectable(true)
            setLineSpacing(dp(4).toFloat(), 1.0f)
        }
        outputCard.addView(answerText)

        citationsContainer = column().apply {
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(10))
        }
        outputCard.addView(citationsContainer)

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
        page.addView(outputCard)

        page.addView(text("Your selected model and prompts stay in this app. Attached text is included only in the local prompt.", 11f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(18), dp(10), 0)
        })
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
        worker.execute {
            try {
                engineManager.close()
                val target = contentResolver.openInputStream(uri)?.use { input ->
                    ModelFileStore(filesDir).importModel(input)
                } ?: error("Could not open the selected file.")
                mainHandler.post {
                    modelText.text = "MODEL READY  ·  ${formatBytes(target.length())}"
                    updateStatus("Model ready. Type, dictate, or attach text/PDF (selectable text only).")
                    suggestButton.isEnabled = true
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
        worker.execute {
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
                    updateStatus("Text attached. Review the preview, then add your question.")
                }
            } catch (e: Exception) {
                mainHandler.post { updateStatus("Could not attach text: ${e.message ?: "unknown error"}") }
            }
        }
    }

    private fun importPdf(uri: Uri) {
        updateStatus("Reading selected PDF on this device…")
        worker.execute {
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
        worker.execute {
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
        worker.execute {
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
        worker.execute {
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
                worker.execute {
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
    ): String = promptContext.answerPrompt(task, attachment, intent, isRetry, history)

    /**
     * Runs one inference pass with the runaway guard applied.
     *
     * Stops as soon as the model begins repeating itself, and never blocks the worker thread
     * forever if the engine never signals completion.
     */
    private fun runGuardedGeneration(
        engine: Engine,
        prompt: String,
        intent: AnswerDirective.Intent,
    ): String {
        val accumulated = StringBuilder()
        val firstChunk = AtomicBoolean(true)
        val streamFinished = CountDownLatch(1)
        val streamError = AtomicReference<Throwable?>(null)

        val guard = GuardedGeneration { partial ->
            val isFirst = firstChunk.compareAndSet(true, false)
            mainHandler.post {
                answerText.text = MarkdownFormatter.formatForDisplay(partial)
                if (isFirst) updateStatus("Response streaming locally…")
            }
        }

        engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(AnswerDirective.systemInstructionFor(intent))
            )
        ).use { conversation ->
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
                maxOutputToken = MAX_RESPONSE_TOKENS,
            )

            // Bounded wait: a stalled engine must not wedge the single worker thread, which
            // would make every later request look frozen with the last answer on screen.
            val finished = streamFinished.await(GENERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                guard.cancel()
                throw IllegalStateException(
                    "Generation timed out after ${GENERATION_TIMEOUT_SECONDS}s. Try a shorter request."
                )
            }
            streamError.get()?.let { throw IllegalStateException("Streaming inference failed.", it) }
        }

        runawayFragment = guard.runawayFragment
        val raw = guard.text(accumulated)
        val finalised = AnswerFinalizer.finalize(raw)
        return finalised.text
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
        lastPrompt = task
        isGeneratingCancelled.set(false)
        suggestButton.isEnabled = false
        answerIsComplete = false
        outputCard.visibility = View.VISIBLE
        stopGeneratingButton.visibility = View.VISIBLE
        answerText.text = "Working on it…"
        val backendLabel = engineManager.activeBackend?.name ?: "accelerated"
        updateStatus("Answering on this device ($backendLabel)…")
        worker.execute {
            var localCitations = emptyList<LocalDocumentRetriever.DocumentCitation>()
            try {
                val localEngine = engineManager.getOrInitializeEngine(modelFile, cacheDir)
                val intent = AnswerDirective.classify(task.ifBlank { attachedContext })
                val history = conversationHistoryFor(task)
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
                val prompt = answerPrompt(task = task, attachment = boundedAttachment, intent = intent, isRetry = false, history = history)
                runawayFragment = null
                var result = runGuardedGeneration(localEngine, prompt, intent)
                // If the model dodged the request ("open Google Maps to measure it"), retry once
                // with a corrective instruction instead of showing the user a non-answer.
                if (!isGeneratingCancelled.get() && runawayFragment == null &&
                    (AnswerDirective.looksLikeDelegationRefusal(result, intent) ||
                        AnswerDirective.isUnsubstantive(result))
                ) {
                    val retryPrompt = answerPrompt(task = task, attachment = boundedAttachment, intent = intent, isRetry = true, history = history)
                    mainHandler.post { answerText.text = "Refining the answer…" }
                    val retryEngine = engineManager.getOrInitializeEngine(modelFile, cacheDir)
                    val retried = runGuardedGeneration(retryEngine, retryPrompt, intent)
                    if (retried.isNotBlank()) result = retried
                }
                mainHandler.post {
                    stopGeneratingButton.visibility = View.GONE
                    suggestButton.isEnabled = true
                    currentCitations = localCitations
                    renderCitations(localCitations)
                    if (isGeneratingCancelled.get()) {
                        answerIsComplete = result.isNotBlank()
                        val formatted = MarkdownFormatter.formatForDisplay(result)
                        answerText.text = if (formatted.isNotBlank()) formatted else "Generation stopped."
                        updateStatus("Generation stopped by user.")
                        return@post
                    }
                    answerIsComplete = result.isNotBlank()
                    val formatted = MarkdownFormatter.formatForDisplay(result)
                    answerText.text = if (formatted.isNotBlank()) formatted else "No answer was returned. Try rephrasing the request."
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
        taskInput.setText("")
        answerText.text = ""
        outputCard.visibility = View.GONE
        systemActionContainer.removeAllViews()
        systemActionContainer.visibility = View.GONE
        updateStatus("Started a new conversation.")
    }

    private fun loadSession(id: String) {
        val s = sessionStore.getSession(id) ?: return
        currentSession = s
        val lastUserMsg = s.messages.lastOrNull { it.isUser }
        val lastAssistantMsg = s.messages.lastOrNull { it.isAssistant }
        if (lastUserMsg != null) {
            taskInput.setText(lastUserMsg.content)
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
        worker.execute {
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
        if (model.exists() && model.length() > 0) modelText.text = "MODEL READY  ·  ${formatBytes(model.length())}"
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
        worker.execute {
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
        worker.execute {
            engineManager.close()
            val context = whisperContext
            whisperContext = 0L
            if (context != 0L) WhisperNative.release(context)
        }
        worker.shutdown()
        super.onDestroy()
    }

    private fun renderCitations(citations: List<LocalDocumentRetriever.DocumentCitation>) {
        citationsContainer.removeAllViews()
        if (citations.isEmpty()) {
            citationsContainer.visibility = View.GONE
            return
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

}
}
