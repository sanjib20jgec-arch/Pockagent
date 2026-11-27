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
import com.example.pocketagent.MainActivity.Companion.*


fun MainActivity.showVaultChooserDialog() {
        dismissComposerOverlays(hideIme = true)
        val docsCount = runCatching { documentVault.loadAll().size }.getOrDefault(0)
        val artCount = runCatching { artifactStore.loadAll().size }.getOrDefault(0)
        val root = column().apply { setPadding(dp(18), dp(4), dp(18), dp(12)) }
        root.addView(text("Local Knowledge & Code Vaults", 17f, palette.ink, true))
        root.addView(
            text("Everything stays 100% private on this device.", 12.5f, palette.muted).apply {
                setPadding(0, dp(4), 0, dp(12))
            }
        )
        lateinit var sheet: AlertDialog
        val vaultOptions = listOf(
            Triple(R.drawable.ic_notes, "Document Knowledge Vault" to "$docsCount indexed local document${if (docsCount == 1) "" else "s"} for automatic RAG") {
                showDocumentVaultDialog()
            },
            Triple(R.drawable.ic_folder, "HTML & Code Artifact Vault" to "$artCount saved code/HTML snippet${if (artCount == 1) "" else "s"} ready to preview") {
                showArtifactVaultDialog()
            },
        )
        vaultOptions.forEachIndexed { idx, (iconRes, copy, action) ->
            val (title, subtitle) = copy
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
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
                    sheet.dismiss()
                    action()
                }
            }
            val iconView = ImageView(this).apply {
                setImageDrawable(tintedVectorDrawable(this@MainActivity, iconRes, palette.teal))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(iconView, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
            val col = column().apply {
                addView(text(title, 14.5f, palette.ink, true))
                addView(text(subtitle, 12f, palette.muted).apply { setPadding(0, dp(2), 0, 0) })
            }
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                if (idx == 0) bottomMargin = dp(8)
            })
        }
        sheet = showBottomSheetDialog(this, palette, root)
    }

fun MainActivity.showHistoryDialog() {
        WorkspaceDialogs.showHistoryDialog(
            activity = this,
            palette = palette,
            sessionStore = sessionStore,
            getCurrentSession = { currentSession },
            setCurrentSession = { currentSession = it },
            onLoadSession = { loadSession(it) },
            onStartNewConversation = { startNewConversation() },
        )
    }

fun MainActivity.exportSessionToShare(id: String, asMarkdown: Boolean) {
        WorkspaceDialogs.exportSessionToShare(this, sessionStore, id, asMarkdown)
    }

fun MainActivity.exportCurrentAsMarkdown() {
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

fun MainActivity.showMemoryCenterDialog() {
        viewModel.refreshMemories()
        WorkspaceDialogs.showMemoryCenterDialog(
            activity = this,
            palette = palette,
            userMemoryStore = userMemoryStore,
            isMemoryEnabled = { isMemoryEnabled },
            onToggleMemoryEnabled = { isMemoryEnabled = it },
        )
    }

fun MainActivity.showNotesManagerDialog() {
        viewModel.refreshNotes()
        WorkspaceDialogs.showNotesManagerDialog(this, palette, notesStore)
    }

fun MainActivity.showSaveToNotesDialog(answerText: String) {
        WorkspaceDialogs.showSaveToNotesDialog(this, palette, notesStore, answerText, lastPrompt) {
            updateStatus(it)
        }
    }

fun MainActivity.showSaveArtifactDialog(code: String, language: String) {
        WorkspaceDialogs.showSaveArtifactDialog(
            activity = this,
            palette = palette,
            artifactStore = artifactStore,
            code = code,
            language = language,
            lastPrompt = lastPrompt,
            onWorkspaceUpdated = { updateWorkspaceSubtitle() },
            onStatusUpdate = { updateStatus(it) },
        )
    }

fun MainActivity.showProjectsDialog() {
        WorkspaceDialogs.showProjectsDialog(
            activity = this,
            palette = palette,
            projectStore = projectStore,
            getActiveProjectId = { activeProjectId },
            onSelectProject = { nextId ->
                activeProjectId = nextId
                if (nextId == null) settingsPrefs.edit().remove(PREF_ACTIVE_PROJECT_ID).apply()
                else settingsPrefs.edit().putString(PREF_ACTIVE_PROJECT_ID, nextId).apply()
            },
            onInvalidateConversation = { engineManager.invalidateConversation(it) },
            onWorkspaceUpdated = { updateWorkspaceSubtitle() },
            onStatusUpdate = { updateStatus(it) },
            onBeforeShow = { dismissComposerOverlays(hideIme = true) },
        )
    }

fun MainActivity.showDocumentVaultDialog() {
        WorkspaceDialogs.showDocumentVaultDialog(
            activity = this,
            palette = palette,
            documentVault = documentVault,
            onChoosePdf = { choosePdf() },
            onChooseTextFiles = { chooseTextFiles() },
            onWorkspaceUpdated = { updateWorkspaceSubtitle() },
        )
    }

fun MainActivity.showArtifactVaultDialog() {
        WorkspaceDialogs.showArtifactVaultDialog(
            activity = this,
            palette = palette,
            artifactStore = artifactStore,
            onExportHtml = { titleSlug, html ->
                pendingHtmlToExport = html
                val createIntent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/html")
                    .putExtra(Intent.EXTRA_TITLE, "$titleSlug.html")
                try {
                    startActivityForResult(createIntent, CREATE_ARTIFACT_HTML)
                } catch (_: ActivityNotFoundException) {
                    pendingHtmlToExport = null
                    shareAnswerText(html)
                }
            },
            onWorkspaceUpdated = { updateWorkspaceSubtitle() },
        )
    }

fun MainActivity.showDuplexVoiceDialog() {
        VoiceAndPrivacyDialogs.showDuplexVoiceDialog(
            activity = this,
            palette = palette,
            duplexController = duplexController,
            whisperContext = whisperContext,
            systemInstruction = SYSTEM_INSTRUCTION,
            isGenerationActive = { isGeneratingUi || engineManager.isGenerationActive },
            hasActiveModel = { activeModelFile().exists() },
            onRequestMicPermission = { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_MIC) },
            getCurrentSession = { currentSession },
            onSaveTurn = { userText, assistantText ->
                try {
                    val base = currentSession ?: ConversationSession.createWithInitialPrompt(userText)
                    val updated = base.withMessage(ChatMessage.user(userText)).withMessage(ChatMessage.assistant(assistantText))
                    sessionStore.saveSession(updated)
                    currentSession = updated
                } catch (_: Exception) {}
            },
            onLoadSession = { loadSession(it) },
        )
    }

fun MainActivity.showPrivacyCenterDialog() {
        VoiceAndPrivacyDialogs.showPrivacyCenterDialog(
            activity = this,
            palette = palette,
            settingsPrefs = settingsPrefs,
            biometricLockManager = biometricLockManager,
            isGenerationActive = { isGeneratingUi || engineManager.isGenerationActive },
            onThemeChanged = { recreate() },
            onLockStateChanged = { updateLockOverlay() },
            onPromptBiometricUnlock = { promptBiometricUnlock() },
            onPanicWipeComplete = {
                activeProjectId = null
                settingsPrefs.edit().remove(PREF_ACTIVE_PROJECT_ID).apply()
                startNewConversation()
                updateWorkspaceSubtitle()
            },
        )
    }

fun MainActivity.buildLockOverlay(): LinearLayout {
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

fun MainActivity.updateLockOverlay() {
        if (!::lockOverlay.isInitialized) return
        lockOverlay.visibility = if (biometricLockManager.isAppLocked) View.VISIBLE else View.GONE
    }

fun MainActivity.promptBiometricUnlock() {
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

fun MainActivity.showActionReviewDialog(reviewUi: SystemActionTools.ActionReviewUi) {
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

fun MainActivity.saveAnswerToFile() {
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

fun MainActivity.saveAnswerToUri(uri: Uri, answer: String) {
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

fun MainActivity.copyAnswerText(text: String) {
        if (text.isBlank()) return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("PocketAgent answer", text))
        Toast.makeText(this, "Answer copied", Toast.LENGTH_SHORT).show()
    }

fun MainActivity.shareAnswerText(text: String) {
        if (text.isBlank()) return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                "Share answer",
            )
        )
    }

fun MainActivity.speakAnswerText(raw: String) {
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
