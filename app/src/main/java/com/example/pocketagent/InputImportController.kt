package com.example.pocketagent

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.content.Context
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
import com.example.pocketagent.MainActivity.Companion.PICK_MODEL
import com.example.pocketagent.MainActivity.Companion.PICK_TEXT_FILES
import com.example.pocketagent.MainActivity.Companion.REQUEST_MIC
import com.example.pocketagent.MainActivity.Companion.PICK_PDF
import com.example.pocketagent.MainActivity.Companion.PICK_WHISPER_MODEL
import com.example.pocketagent.MainActivity.Companion.PICK_WAV
import com.example.pocketagent.MainActivity.Companion.CREATE_ANSWER_FILE
import com.example.pocketagent.MainActivity.Companion.REQUEST_RECORD_MIC
import com.example.pocketagent.MainActivity.Companion.PICK_IMAGE_OCR
import com.example.pocketagent.MainActivity.Companion.CREATE_ARTIFACT_HTML
import com.example.pocketagent.MainActivity.Companion.STATE_PENDING_ANSWER_SAVE
import com.example.pocketagent.MainActivity.Companion.STATE_ACTIVE_SESSION_ID
import com.example.pocketagent.MainActivity.Companion.PREFS_SETTINGS
import com.example.pocketagent.MainActivity.Companion.PREF_BACKEND_MODE
import com.example.pocketagent.MainActivity.Companion.PREF_MODEL_DISPLAY_NAME
import com.example.pocketagent.MainActivity.Companion.PREF_GPU_LOADING_GUARD
import com.example.pocketagent.MainActivity.Companion.PREF_ACTIVE_PROJECT_ID
import com.example.pocketagent.MainActivity.Companion.PREF_TEMPERATURE_BITS
import com.example.pocketagent.MainActivity.Companion.PREF_MAX_OUTPUT_TOKENS
import com.example.pocketagent.MainActivity.Companion.PREF_MAX_CONTEXT_TOKENS
import com.example.pocketagent.MainActivity.Companion.PREF_AGENT_MODE
import com.example.pocketagent.MainActivity.Companion.PREF_DEEP_THINK
import com.example.pocketagent.MainActivity.Companion.PREF_VISION_ENABLED
import com.example.pocketagent.MainActivity.Companion.MAX_ATTACHMENT_CHARS
import com.example.pocketagent.MainActivity.Companion.MAX_RESPONSE_TOKENS
import com.example.pocketagent.MainActivity.Companion.GENERATION_TIMEOUT_SECONDS
import com.example.pocketagent.MainActivity.Companion.EAGER_WARMUP_MAX_BYTES
import com.example.pocketagent.MainActivity.Companion.WARM_CONTEXT_RESET_THRESHOLD
import com.example.pocketagent.MainActivity.Companion.IDLE_ENGINE_UNLOAD_MS
import com.example.pocketagent.MainActivity.Companion.SYSTEM_INSTRUCTION
import com.example.pocketagent.MainActivity.Companion.WHITE


internal fun MainActivity.handleWidgetLaunchIntent(launchIntent: Intent?) {
        if (launchIntent?.getBooleanExtra(PocketAgentWidget.EXTRA_WIDGET_VOICE, false) == true) {
            launchIntent.removeExtra(PocketAgentWidget.EXTRA_WIDGET_VOICE)
            mainHandler.postDelayed({
                if (!isDestroyed && !isFinishing) startDictation()
            }, 450L)
        }
    }

internal fun MainActivity.handleSharedIntent(shareIntent: Intent?) {
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

internal fun MainActivity.activeModelFile(): File {
        val activeEntry = modelLibraryStore.getActive()
        if (activeEntry != null) {
            val candidate = File(activeEntry.filePath)
            if (candidate.exists() && candidate.length() > 0L) {
                return candidate
            }
        }
        return File(filesDir, "model.litertlm")
    }

internal fun MainActivity.ensureLegacyModelRegistered() {
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

internal fun MainActivity.isDeviceLowMemory(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.lowMemory
    }

    /** Initializes the LiteRT-LM engine with a persistent crash-loop guard around GPU loads. */

internal fun MainActivity.ensureEngineInitialized(modelFile: File): Engine {
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

internal fun MainActivity.warmUpModelAfterFirstFrame() {
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

internal fun MainActivity.chooseModel() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startActivityForResult(picker, PICK_MODEL)
    }

internal fun MainActivity.chooseTextFiles() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/markdown", "text/csv"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(picker, PICK_TEXT_FILES)
    }

internal fun MainActivity.choosePdf() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
        startActivityForResult(picker, PICK_PDF)
    }

internal fun MainActivity.chooseWhisperModel() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/x-ggml", "application/gguf"))
        startActivityForResult(picker, PICK_WHISPER_MODEL)
    }

internal fun MainActivity.chooseWav() {
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

internal fun MainActivity.chooseVisionImage() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
        startActivityForResult(picker, PICK_IMAGE_OCR)
    }

internal fun MainActivity.importModel(uri: Uri) {
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

internal fun MainActivity.importTextFiles(uris: List<Uri>) {
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
                    updateWorkspaceSubtitle()
                    updateStatus("Text attached. Review the preview, then add your question.")
                }
            } catch (e: Exception) {
                mainHandler.post { updateStatus("Could not attach text: ${e.message ?: "unknown error"}") }
            }
        }
    }

internal fun MainActivity.importPdf(uri: Uri) {
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
                    updateWorkspaceSubtitle()
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

internal fun MainActivity.importWhisperModel(uri: Uri) {
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

internal fun MainActivity.transcribeWav(uri: Uri) {
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

internal fun MainActivity.removeWhisperModel() {
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

internal fun MainActivity.queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) { null }

internal fun MainActivity.queryFileSize(uri: Uri): Long? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                cursor.getLong(0).takeIf { it > 0L }
            } else {
                null
            }
        }
    } catch (_: Exception) { null }

internal fun MainActivity.clearAttachments() {
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

internal fun MainActivity.stageImageUri(uri: Uri) {
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

internal fun MainActivity.cleanupStagedInputFiles() {
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

internal fun MainActivity.restoreWhisperModel() {
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

    /** Mirrors only the composer text owned by the ViewModel; generation UI remains local until Task 8. */

internal fun MainActivity.refreshModelStatus() {
        if (!isModelStatusPillInitialized()) return
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
        if (isHeroModelCtaButtonInitialized()) {
            heroModelCtaButton.visibility = if (hasModel) View.GONE else View.VISIBLE
        }
        updateWorkspaceSubtitle()
    }

internal fun MainActivity.showModelManagerDialog() {
        dismissComposerOverlays(hideIme = true)
        ModelManagerDialog.show(
            activity = this,
            palette = palette,
            settingsPrefs = settingsPrefs,
            engineManager = engineManager,
            modelLibraryStore = modelLibraryStore,
            inferenceExecutor = inferenceExecutor,
            mainHandler = mainHandler,
            cacheDir = cacheDir,
            getTemperature = { currentTemperature },
            setTemperature = { currentTemperature = it },
            getMaxContextTokens = { currentMaxContextTokens },
            setMaxContextTokens = { currentMaxContextTokens = it },
            getMaxOutputTokens = { currentMaxOutputTokens },
            setMaxOutputTokens = { currentMaxOutputTokens = it },
            getVisionModeEnabled = { isVisionModeEnabled },
            setVisionModeEnabled = { isVisionModeEnabled = it },
            isGenerating = { engineManager.isGenerationActive || isGeneratingUi },
            activeModelFile = { activeModelFile() },
            formatStorageSize = { formatStorageSize(it) },
            onEnsureLegacyModelRegistered = { ensureLegacyModelRegistered() },
            onEnsureEngineInitialized = { ensureEngineInitialized(it) },
            onChooseModel = { chooseModel() },
            onRefreshStatus = { refreshModelStatus() },
            onUpdateSubtitle = { updateWorkspaceSubtitle() },
            onSyncComposer = { syncComposerState() },
            onUpdateStatus = { updateStatus(it) },
        )
    }
