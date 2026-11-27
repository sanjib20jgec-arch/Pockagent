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


fun MainActivity.buildInterface() {
        // Three zones stacked in a full-height column: a slim pinned header, a scrolling chat
        // canvas, and a sticky bottom composer that never scrolls away from the thumb.
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.bg)
            fitsSystemWindows = true
            isFocusableInTouchMode = true
        }
        applyImeInsets(rootLayout)

        // ---------------------------------------------------------------- Zone A: quiet Claude-style header
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val drawerBtn = vectorIconButton(
            context = this,
            palette = palette,
            drawableRes = R.drawable.ic_menu,
            accessibilityLabel = "Open sidebar: chats, projects, vaults, and settings",
            onClick = { openClaudeDrawer() },
        )
        brandRow.addView(drawerBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(4) })

        val brand = column().apply {
            setPadding(dp(2), 0, 0, 0)
            addView(text("PocketAgent", 15.5f, palette.ink, true))
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

        // Calm, editorial welcome card, shown only while the conversation is empty.
        heroCard = column().apply {
            background = roundedShape(
                color = palette.surfaceElevated,
                radiusPx = dp(20).toFloat(),
                strokeWidthPx = maxOf(1, dp(1)),
                strokeColor = palette.border,
            )
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        val trustBadge = styledText(
            context = this,
            text = "v1.6 · 100% Offline",
            sizeSp = 11.5f,
            color = palette.teal,
            bold = true,
        ).apply {
            background = roundedShape(palette.mint, dp(12).toFloat())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                bottomMargin = dp(12)
            }
        }
        heroCard.addView(trustBadge)
        heroCard.addView(text("How can I help you today?", 22f, palette.ink, true))
        heroCard.addView(
            text(
                "Private on-device AI · Projects, Agent Tools, Deep Think & Local Vaults.",
                13.5f,
                palette.muted,
            ).apply {
                setPadding(0, dp(6), 0, 0)
                setLineSpacing(dp(3).toFloat(), 1f)
            }
        )
        heroModelCtaButton = pillButton(
            label = "Choose .litertlm Model",
            accessibilityLabel = "Choose a LiteRT-LM model file to get started",
        ) {
            chooseModel()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = dp(14)
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
        listOf(
            "/summarize Summarize key points",
            "Calculate (1480 * 1.18) - 250",
            "/code Build an HTML Pomodoro timer",
            "/translate Translate to Bengali: Privacy matters",
            "/eli5 Explain how offline LLMs work",
        ).forEach { label ->
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
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                    val hasOpenControls = (isTaskInputInitialized() && taskInput.hasFocus()) ||
                        isToolsTrayOpen ||
                        (isSlashPopupContainerInitialized() && slashPopupContainer.visibility == View.VISIBLE)
                    if (hasOpenControls) {
                        dismissComposerOverlays(hideIme = true)
                    }
                }
                false
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
        val quickModeScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val quickModeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun createQuickModeChip(
            label: String,
            a11y: String,
            active: Boolean,
            action: () -> Unit,
        ): Button = Button(this).apply {
            text = label
            contentDescription = a11y
            isFocusable = true
            ComposerChromeViews.styleQuickModePill(this, palette, active)
            setOnClickListener {
                performSubtleHaptic(this)
                action()
            }
        }
        quickModeSummaryChip = createQuickModeChip(
            label = "Mode: Default ▾",
            a11y = "Open quick mode sheet: Project, Agent Tools, Deep Think, and Vaults",
            active = activeProjectId != null || isAgentModeEnabled || isDeepThinkEnabled,
        ) { showModeQuickSheet() }
        quickSlashChip = createQuickModeChip(
            label = "/ Commands",
            a11y = "Open slash command picker",
            active = false,
        ) { openSlashCommandPicker() }
        quickTuningChip = createQuickModeChip(
            label = "Temp 0.8 · 0% ctx",
            a11y = "Open model switcher, temperature, and context window settings",
            active = false,
        ) {
            val stored = activeModelFile()
            if ((stored.exists() && stored.length() > 0L) || modelLibraryStore.loadAll().isNotEmpty()) {
                showModelManagerDialog()
            } else {
                chooseModel()
            }
        }
        val modeChips = listOf(quickModeSummaryChip, quickSlashChip, quickTuningChip)
        modeChips.forEachIndexed { idx, chip ->
            quickModeRow.addView(
                chip,
                LinearLayout.LayoutParams(-2, -2).apply {
                    if (idx < modeChips.lastIndex) marginEnd = dp(6)
                },
            )
        }
        quickModeScroll.addView(quickModeRow)
        composerDock.addView(quickModeScroll, marginBottom(4))

        contextContinuityBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = roundedShape(
                color = palette.warningBg,
                radiusPx = dp(12).toFloat(),
                strokeWidthPx = maxOf(1, dp(1)),
                strokeColor = palette.warningBorder,
            )
            setPadding(dp(12), dp(6), dp(8), dp(6))
        }
        contextContinuityText = styledText(
            context = this,
            text = "",
            sizeSp = 12f,
            color = palette.warningInk,
            bold = true,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        contextContinuityBanner.addView(contextContinuityText)
        val continueBtn = secondaryOutlineButton(
            context = this,
            palette = palette,
            label = "Continue Fresh",
            accessibilityLabel = "Summarize context and continue in a fresh conversation",
            compact = true,
            textColor = palette.teal,
        ) {
            val seed = ContextContinuityHelper.buildCarryOverSeed(currentSession)
            startNewConversation()
            if (seed.isNotBlank()) {
                taskInput.setText("Continue from previous context:\n$seed\n\nNext question: ")
                taskInput.setSelection(taskInput.text.length)
                taskInput.requestFocus()
                syncComposerState()
            }
        }
        contextContinuityBanner.addView(continueBtn)
        composerDock.addView(contextContinuityBanner, marginBottom(6))

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
            toggleAgentMode(showToast = true)
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
                if (hasFocus && isToolsTrayOpen) {
                    toggleToolsTray(force = false)
                } else if (!hasFocus && isSlashPopupContainerInitialized() && slashPopupContainer.visibility == View.VISIBLE) {
                    slashPopupContainer.visibility = View.GONE
                }
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    isUserReadingUpwards = false
                    val input = s?.toString().orEmpty()
                    viewModel.onTaskInputChanged(input)
                    updateSlashSuggestions(input)
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

fun MainActivity.applyImeInsets(rootLayout: LinearLayout) {
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

fun MainActivity.toggleToolsTray(force: Boolean) {
        val open = if (force) true else !isToolsTrayOpen
        if (isComposerCardInitialized() && isToolsTrayOpen != open) {
            TransitionManager.beginDelayedTransition(composerCard)
        }
        isToolsTrayOpen = open
        if (open) {
            if (isSlashPopupContainerInitialized()) slashPopupContainer.visibility = View.GONE
            hideKeyboard(clearInputFocus = true)
        }
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

fun MainActivity.syncComposerState() {
        if (!isHeroCardInitialized()) return
        val hasThreadViews = isChatThreadContainerInitialized() && chatThreadContainer.childCount > 0
        val hasImage = !attachedImagePath.isNullOrBlank()
        val hasConversation = hasThreadViews ||
            taskInput.text.isNotBlank() || attachedContext.isNotBlank() || hasImage ||
            lastPrompt.isNotBlank() || currentSession?.messages?.isNotEmpty() == true
        val welcomeVisibility = if (hasConversation) View.GONE else View.VISIBLE
        if (heroCard.visibility != welcomeVisibility && isChatCanvasInitialized()) {
            TransitionManager.beginDelayedTransition(chatCanvas)
        }
        heroCard.visibility = welcomeVisibility
        quickChips.visibility = welcomeVisibility

        if (isHeroModelCtaButtonInitialized()) {
            val hasModel = activeModelFile().let { it.exists() && it.length() > 0L }
            heroModelCtaButton.visibility = if (hasModel) View.GONE else View.VISIBLE
        }
        if (isAttachImageButtonInitialized()) {
            attachImageButton.visibility = if (isVisionModeEnabled) View.VISIBLE else View.GONE
        }

        val effectiveNames = buildList {
            addAll(attachedNames)
            attachedImageName?.let { add("Image: $it") }
        }
        val hasAttachment = attachedContext.isNotBlank() || hasImage
        if (isAttachmentChipContainerInitialized()) {
            val nextChipVisibility = if (hasAttachment) View.VISIBLE else View.GONE
            if (attachmentChipContainer.visibility != nextChipVisibility && isComposerCardInitialized()) {
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
        if (!hasAttachment && isAttachmentCardInitialized()) {
            attachmentCard.visibility = View.GONE
        }

        // Badge the + button whenever there is hidden work sitting behind it.
        toolsToggleButton.contentDescription = when {
            isToolsTrayOpen -> "Hide attachment and audio tools"
            hasAttachment -> "Show attachment and audio tools (attachment active)"
            else -> "Show attachment and audio tools"
        }
        if (isRecordAudioButtonInitialized()) {
            recordAudioButton.isEnabled = whisperContext != 0L && !isWhisperTranscribing
        }
        if (isTranscribeWavButtonInitialized()) {
            transcribeWavButton.isEnabled = whisperContext != 0L && !isWhisperTranscribing
        }
        if (isSuggestButtonInitialized()) {
            val canSend = (taskInput.text.isNotBlank() || attachedContext.isNotBlank() || !attachedImagePath.isNullOrBlank()) &&
                !isWhisperTranscribing && !transcriptReviewPending
            suggestButton.isEnabled = (isGeneratingUi && !isStoppingUi) || (!isGeneratingUi && canSend)
            suggestButton.alpha = if (suggestButton.isEnabled) 1f else 0.5f
        }
        updateRecordingBanner()
        updateJumpToBottomVisibility()
    }

    /** Updates the floating Jump-to-Bottom button based on scroll position and thread content. */

fun MainActivity.updateJumpToBottomVisibility() {
        if (!isJumpToBottomButtonInitialized() || !isChatScrollInitialized()) return
        val hasContent = isChatThreadContainerInitialized() && chatThreadContainer.childCount > 0
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

fun MainActivity.animateCardEntrance(view: View) {
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

fun MainActivity.hideKeyboard(clearInputFocus: Boolean = true) {
        if (isTaskInputInitialized() && clearInputFocus) {
            taskInput.clearFocus()
        }
        val target = currentFocus ?: if (isTaskInputInitialized()) taskInput else window?.decorView
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (target != null) {
            imm?.hideSoftInputFromWindow(target.windowToken, 0)
        }
    }

    /** Dismisses open composer popups (slash menu, tools tray) and optionally hides the keyboard. */

fun MainActivity.dismissComposerOverlays(hideIme: Boolean = false) {
        if (isSlashPopupContainerInitialized() && slashPopupContainer.visibility == View.VISIBLE) {
            slashPopupContainer.visibility = View.GONE
        }
        if (isToolsTrayOpen) {
            toggleToolsTray(force = false)
        }
        if (hideIme) {
            hideKeyboard(clearInputFocus = true)
        }
    }

    /** Scrolls the chat canvas to the newest content unless the reader scrolled up, without stealing focus. */

fun MainActivity.scrollChatToBottom(force: Boolean = false) {
        if (!isChatScrollInitialized() || !isChatCanvasInitialized()) return
        if (!force && isUserReadingUpwards) return
        chatScroll.post {
            if (!force && isUserReadingUpwards) return@post
            val bottom = (chatCanvas.height + chatScroll.paddingTop + chatScroll.paddingBottom - chatScroll.height)
                .coerceAtLeast(0)
            if (force) {
                chatScroll.smoothScrollTo(0, bottom)
            } else {
                chatScroll.scrollTo(0, bottom)
            }
            updateJumpToBottomVisibility()
        }
    }

fun MainActivity.updateSlashSuggestions(currentText: String) {
        if (!isSlashPopupContainerInitialized()) return
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
        val listHost = column()
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
            listHost.addView(row)
            if (index < matches.lastIndex) {
                listHost.addView(dividerView(this, palette))
            }
        }
        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(listHost)
        }
        val maxHeightPx = if (matches.size > 4) dp(220) else LinearLayout.LayoutParams.WRAP_CONTENT
        slashPopupContainer.addView(scroll, LinearLayout.LayoutParams(-1, maxHeightPx))
        slashPopupContainer.visibility = View.VISIBLE
    }

fun MainActivity.updateWorkspaceSubtitle() {
        val proj = activeProjectId?.let { projectStore.getProject(it) }
        val ctxUsed = engineManager.cachedConversationTokens
        val ctxMax = currentMaxContextTokens
        val docCount = runCatching { documentVault.loadAll().size }.getOrDefault(0)
        val artifactCount = runCatching { artifactStore.loadAll().size }.getOrDefault(0)

        if (isWorkspaceSubtitleViewInitialized()) {
            val parts = buildList {
                proj?.let { add(it.name) }
                if (isAgentModeEnabled) add("Agent")
                if (ctxUsed > 0) {
                    val pct = ((ctxUsed * 100) / ctxMax).coerceIn(1, 100)
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
        if (isAgentModeButtonInitialized()) {
            agentModeButton.text = if (isAgentModeEnabled) "Agent Tools: ON" else "Agent Tools: OFF"
        }
        if (isQuickModeSummaryChipInitialized()) {
            val labels = ComposerChromeHelper.formatQuickModeLabels(
                projectName = proj?.name,
                isAgentEnabled = isAgentModeEnabled,
                isDeepThinkEnabled = isDeepThinkEnabled,
                temperature = currentTemperature,
                contextUsedTokens = ctxUsed,
                contextMaxTokens = ctxMax,
                docCount = docCount,
                artifactCount = artifactCount,
            )
            val activeFlags = buildList {
                if (proj != null) add(proj.name.take(12))
                if (isAgentModeEnabled) add("Agent")
                if (isDeepThinkEnabled) add("Deep Think")
            }
            quickModeSummaryChip.text = if (activeFlags.isEmpty()) {
                "Mode: Standard ▾"
            } else {
                "● ${activeFlags.joinToString(" · ")} ▾"
            }
            ComposerChromeViews.styleQuickModePill(quickModeSummaryChip, palette, activeFlags.isNotEmpty())

            quickSlashChip.text = labels.slashChipLabel
            quickTuningChip.text = labels.tuningChipLabel
            ComposerChromeViews.styleQuickModePill(quickTuningChip, palette, isVisionModeEnabled)
        }
        if (isContextContinuityBannerInitialized()) {
            if (ContextContinuityHelper.shouldSuggestContinue(ctxUsed, ctxMax)) {
                contextContinuityText.text = ContextContinuityHelper.formatContextAlert(ctxUsed, ctxMax)
                contextContinuityBanner.visibility = View.VISIBLE
            } else {
                contextContinuityBanner.visibility = View.GONE
            }
        }
    }

fun MainActivity.openClaudeDrawer() {
        dismissComposerOverlays(hideIme = true)
        val model = activeModelFile()
        val hasModel = model.exists() && model.length() > 0L
        val activeEntry = modelLibraryStore.getActive()
        val modelLabel = if (hasModel) {
            activeEntry?.displayName ?: settingsPrefs.getString(PREF_MODEL_DISPLAY_NAME, null) ?: model.name
        } else {
            "No model selected"
        }
        val backendLabel = when {
            !hasModel -> "Tap to import .litertlm"
            engineManager.activeBackend == ActiveBackend.GPU -> "GPU Active"
            engineManager.activeBackend == ActiveBackend.CPU -> "CPU Mode"
            else -> if (engineManager.preference == BackendPreference.CPU_ONLY) "Standby · CPU" else "Standby · GPU"
        }
        val activeProjName = activeProjectId?.let { projectStore.getProject(it)?.name } ?: "Default Workspace"
        viewModel.refreshSessions()
        val sessions = runCatching { sessionStore.listSessions() }.getOrDefault(emptyList())
        val docCount = runCatching { documentVault.loadAll().size }.getOrDefault(0)
        val artCount = runCatching { artifactStore.loadAll().size }.getOrDefault(0)

        slideDrawer.open(
            data = DrawerData(
                sessions = sessions,
                activeSessionId = currentSession?.id,
                activeProjectName = activeProjName,
                documentCount = docCount,
                artifactCount = artCount,
                activeModelLabel = modelLabel,
                activeBackendLabel = backendLabel,
                appVersionLabel = "v1.6 · Offline",
            ),
            palette = palette,
            callbacks = DrawerCallbacks(
                onNewChat = { startNewConversation() },
                onSelectSession = { sess -> loadSession(sess.id) },
                onSessionLongPress = { _ -> showHistoryDialog() },
                onOpenProjects = { showProjectsDialog() },
                onOpenDocumentVault = { showDocumentVaultDialog() },
                onOpenArtifactVault = { showArtifactVaultDialog() },
                onOpenNotesMemory = { showNotesManagerDialog() },
                onOpenModelTuning = {
                    if (hasModel || modelLibraryStore.loadAll().isNotEmpty()) {
                        showModelManagerDialog()
                    } else {
                        chooseModel()
                    }
                },
                onOpenPrivacyTheme = { showPrivacyCenterDialog() },
            ),
        )
    }

fun MainActivity.toggleAgentMode(showToast: Boolean) {
        isAgentModeEnabled = !isAgentModeEnabled
        settingsPrefs.edit().putBoolean(PREF_AGENT_MODE, isAgentModeEnabled).apply()
        updateWorkspaceSubtitle()
        if (showToast) {
            Toast.makeText(
                this,
                if (isAgentModeEnabled) "Agent Tool Loop ON (calc, notes, doc search)"
                else "Agent Tool Loop OFF",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

fun MainActivity.toggleDeepThinkMode() {
        isDeepThinkEnabled = !isDeepThinkEnabled
        settingsPrefs.edit().putBoolean(PREF_DEEP_THINK, isDeepThinkEnabled).apply()
        updateWorkspaceSubtitle()
        Toast.makeText(
            this,
            if (isDeepThinkEnabled) "Deep Think ON (2-pass verify on complex tasks)"
            else "Deep Think OFF (fast 1-pass)",
            Toast.LENGTH_SHORT,
        ).show()
    }

fun MainActivity.showModeQuickSheet() {
        dismissComposerOverlays(hideIme = true)
        val root = column().apply { setPadding(dp(18), dp(4), dp(18), dp(12)) }
        root.addView(text("Assistant Mode & Capabilities", 17f, palette.ink, true))
        root.addView(
            text("Configure reasoning depth, tools, project persona, and vaults.", 12.5f, palette.muted).apply {
                setPadding(0, dp(4), 0, dp(12))
            }
        )
        lateinit var sheet: AlertDialog
        val activeProjName = activeProjectId?.let { projectStore.getProject(it)?.name } ?: "Default Workspace"
        val vaultCount = runCatching { documentVault.loadAll().size + artifactStore.loadAll().size }.getOrDefault(0)
        data class ModeOption(
            val iconRes: Int,
            val title: String,
            val subtitle: String,
            val active: Boolean,
            val action: () -> Unit,
        )
        val rows = listOf(
            ModeOption(
                iconRes = R.drawable.ic_memory,
                title = if (isAgentModeEnabled) "Agent Tool Loop: ON" else "Agent Tool Loop: OFF",
                subtitle = "Offline calc, notes & vault tool calls",
                active = isAgentModeEnabled,
                action = { toggleAgentMode(showToast = true) },
            ),
            ModeOption(
                iconRes = R.drawable.ic_sparkle,
                title = if (isDeepThinkEnabled) "Deep Think Mode: ON" else "Deep Think Mode: OFF",
                subtitle = "Two-pass self-critique for coding & logic",
                active = isDeepThinkEnabled,
                action = { toggleDeepThinkMode() },
            ),
            ModeOption(
                iconRes = R.drawable.ic_folder,
                title = "Project: $activeProjName",
                subtitle = "Switch custom system persona & rules",
                active = activeProjectId != null,
                action = { showProjectsDialog() },
            ),
            ModeOption(
                iconRes = R.drawable.ic_notes,
                title = if (vaultCount > 0) "Knowledge & Code Vaults ($vaultCount)" else "Knowledge & Code Vaults",
                subtitle = "Manage indexed PDFs & HTML artifacts",
                active = vaultCount > 0,
                action = { showVaultChooserDialog() },
            ),
        )
        rows.forEachIndexed { idx, opt ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rippleDrawable(
                    roundedShape(
                        color = if (opt.active) palette.mint else palette.surfaceElevated,
                        radiusPx = dp(14).toFloat(),
                        strokeWidthPx = maxOf(1, dp(if (opt.active) 2 else 1)),
                        strokeColor = if (opt.active) palette.teal else palette.border,
                    ),
                    if (opt.active) palette.rippleOnMint else palette.rippleOnSurface,
                )
                isClickable = true
                isFocusable = true
                val iconView = ImageView(this@showModeQuickSheet).apply {
                    setImageDrawable(tintedVectorDrawable(this@showModeQuickSheet, opt.iconRes, if (opt.active) palette.teal else palette.muted))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                addView(iconView, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
                val col = column().apply {
                    addView(text(opt.title, 14f, if (opt.active) palette.teal else palette.ink, true))
                    addView(text(opt.subtitle, 12f, palette.muted).apply { setPadding(0, dp(2), 0, 0) })
                }
                addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                if (opt.active) {
                    val checkView = ImageView(this@showModeQuickSheet).apply {
                        setImageDrawable(tintedVectorDrawable(this@showModeQuickSheet, R.drawable.ic_check, palette.teal))
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    addView(checkView, LinearLayout.LayoutParams(dp(18), dp(18)))
                }
                setOnClickListener {
                    performSubtleHaptic(this)
                    sheet.dismiss()
                    opt.action()
                }
            }
            root.addView(
                row,
                LinearLayout.LayoutParams(-1, -2).apply {
                    if (idx < rows.lastIndex) bottomMargin = dp(8)
                },
            )
        }
        sheet = showBottomSheetDialog(this, palette, root)
    }

fun MainActivity.openSlashCommandPicker() {
        if (!isSlashPopupContainerInitialized()) return
        if (slashPopupContainer.visibility == View.VISIBLE) {
            slashPopupContainer.visibility = View.GONE
            return
        }
        val current = taskInput.text.toString()
        if (!current.trimStart().startsWith("/")) {
            val updated = if (current.isBlank()) "/" else "/$current"
            taskInput.setText(updated)
            taskInput.setSelection(1)
        }
        taskInput.requestFocus()
        updateSlashSuggestions(taskInput.text.toString())
    }

fun MainActivity.showAttachmentPreview(value: String) {
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

fun MainActivity.toggleAttachmentPreview() {
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

fun MainActivity.formatBytes(bytes: Long): String = if (bytes >= 1024L * 1024) "${bytes / (1024 * 1024)} MB" else "${bytes / 1024} KB"

fun MainActivity.formatStorageSize(bytes: Long): String = PrivacyAuditCenter.StorageAudit.formatBytes(bytes)

fun MainActivity.dp(value: Int): Int = com.example.pocketagent.dp(this, value)

fun MainActivity.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun MainActivity.marginBottom(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(value) }

fun MainActivity.text(value: String, size: Float, color: Int, bold: Boolean = false) =
        styledText(this, value, size, color, bold)

fun MainActivity.shape(color: Int, radius: Int, stroke: Int? = null) =
        roundedShape(
            color = color,
            radiusPx = dp(radius).toFloat(),
            strokeWidthPx = if (stroke != null) maxOf(1, dp(1)) else 0,
            strokeColor = stroke,
        )

fun MainActivity.sectionHeading(title: String, subtitle: String): LinearLayout = column().apply {
        addView(text(title, 18f, palette.ink, true))
        addView(text(subtitle, 12f, palette.muted).apply { setPadding(0, dp(3), 0, 0) })
    }

fun MainActivity.pillButton(
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

fun MainActivity.textButton(
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

fun MainActivity.actionButton(
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

fun MainActivity.ripple(content: GradientDrawable, color: Int) =
        rippleDrawable(content, color)
