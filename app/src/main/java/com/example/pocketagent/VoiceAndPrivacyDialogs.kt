package com.example.pocketagent

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Extracted Continuous Voice Mode and Privacy & Appearance Center dialogs for PocketAgent.
 */
object VoiceAndPrivacyDialogs {

    private fun column(activity: Activity): LinearLayout =
        LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private fun marginBottom(activity: Activity, valueDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, valueDp) }

    /**
     * Launches the hands-free Continuous Voice Mode dialog backed by [duplexController].
     */
    fun showDuplexVoiceDialog(
        activity: Activity,
        palette: UiPalette,
        duplexController: DuplexVoiceController,
        whisperContext: Long,
        systemInstruction: String,
        isGenerationActive: () -> Boolean,
        hasActiveModel: () -> Boolean,
        onRequestMicPermission: () -> Unit,
        getCurrentSession: () -> ConversationSession?,
        onSaveTurn: (userText: String, assistantText: String) -> Unit,
        onLoadSession: (String) -> Unit,
    ) {
        if (isGenerationActive()) {
            Toast.makeText(activity, "Stop active text generation before starting voice mode.", Toast.LENGTH_SHORT).show()
            return
        }
        if (whisperContext == 0L) {
            Toast.makeText(activity, "Select a local Whisper model first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasActiveModel()) {
            Toast.makeText(activity, "Select a local LiteRT-LM model first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!InAppAudioRecorder.hasMicrophonePermission(activity)) {
            onRequestMicPermission()
            return
        }

        val dialogView = column(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(palette.heroGradientStart, palette.heroGradientEnd),
            ).apply { cornerRadius = dp(activity, 20).toFloat() }
            setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 20))
        }

        val title = styledText(activity, "Continuous Voice Mode", 18f, Color.WHITE, true)
        dialogView.addView(title)

        val stateBadge = styledText(activity, "Initializing…", 13f, palette.heroSubtitleInk, true).apply {
            setPadding(0, dp(activity, 6), 0, dp(activity, 8))
        }
        dialogView.addView(stateBadge)

        val waveformView = VoiceWaveformView(activity)
        dialogView.addView(
            waveformView,
            LinearLayout.LayoutParams(-1, dp(activity, 52)).apply { bottomMargin = dp(activity, 12) },
        )

        val transcriptView = TextView(activity).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setLineSpacing(dp(activity, 3).toFloat(), 1f)
            text = "Say something to start speaking…"
            setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
            background = roundedShape(0x33000000, dp(activity, 12).toFloat())
        }
        val scroll = ScrollView(activity).apply {
            addView(transcriptView)
            layoutParams = LinearLayout.LayoutParams(-1, dp(activity, 220))
        }
        dialogView.addView(scroll, marginBottom(activity, 16))

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val interruptBtn = Button(activity).apply {
            text = "Interrupt / Next"
            contentDescription = "Interrupt and listen again"
            setAllCaps(false)
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minHeight = dp(activity, 48)
            minimumHeight = dp(activity, 48)
            setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8))
            background = rippleDrawable(
                roundedShape(0x2EFFFFFF, dp(activity, 24).toFloat(), maxOf(1, dp(activity, 1)), 0x55FFFFFF),
                palette.rippleOnAccent,
            )
            backgroundTintList = null
            stateListAnimator = null
            setOnClickListener {
                performSubtleHaptic(it)
                duplexController.interrupt()
            }
        }
        btnRow.addView(interruptBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(activity, 10) })

        lateinit var dialog: AlertDialog

        val endBtn = Button(activity).apply {
            text = "End Session"
            contentDescription = "End voice duplex mode"
            setAllCaps(false)
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(palette.heroGradientStart)
            gravity = Gravity.CENTER
            minHeight = dp(activity, 48)
            minimumHeight = dp(activity, 48)
            setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8))
            background = rippleDrawable(
                roundedShape(Color.WHITE, dp(activity, 24).toFloat()),
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

        val messageCountBeforeVoice = getCurrentSession()?.messages?.size ?: 0
        dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.setOnDismissListener {
            waveformView.release()
            if (duplexController.isActive) duplexController.stopSession()
            val updatedSess = getCurrentSession()
            if (updatedSess != null && updatedSess.messages.size > messageCountBeforeVoice) {
                onLoadSession(updatedSess.id)
            }
        }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()

        var duplexUserText = ""
        val duplexAssistantStream = StringBuilder()

        duplexController.startSession(
            whisperContext = whisperContext,
            systemInstruction = systemInstruction,
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
                        onSaveTurn(userText, assistantText)
                    } catch (_: Exception) {}
                }

                override fun onError(error: Throwable) {
                    stateBadge.text = "Error: ${error.message}"
                    waveformView.setVoiceState(DuplexVoiceController.DuplexState.ERROR)
                    waveformView.setAmplitude(0f)
                }
            },
        )
    }

    /**
     * Displays the Privacy & Appearance dialog with theme selection, zero-cloud permission audit,
     * local storage breakdown, biometric lock toggle, and panic data wipe.
     */
    fun showPrivacyCenterDialog(
        activity: Activity,
        palette: UiPalette,
        settingsPrefs: SharedPreferences,
        biometricLockManager: BiometricLockManager,
        isGenerationActive: () -> Boolean,
        onThemeChanged: () -> Unit,
        onLockStateChanged: () -> Unit,
        onPromptBiometricUnlock: () -> Unit,
        onPanicWipeComplete: () -> Unit,
    ) {
        val permAudit = PrivacyAuditCenter.auditPermissions(activity)
        val storageAudit = PrivacyAuditCenter.auditStorage(activity.filesDir, activity.cacheDir)

        val dialogView = styleDialogContainer(activity, palette, paddingDp = 16)
        val bodyCol = column(activity)
        val bodyScroll = ScrollView(activity).apply {
            addView(bodyCol)
        }
        dialogView.addView(bodyScroll, LinearLayout.LayoutParams(-1, -2))

        var privacyDialog: AlertDialog? = null

        // 1. Appearance (Theme: System / Light / Dark)
        val currentThemeMode = ThemeMode.fromString(
            settingsPrefs.getString(UiThemeResolver.PREF_THEME_MODE, null)
        )
        val appearanceCard = cardContainer(activity, palette, cornerDp = 14, elevated = true, paddingDp = 14)
        appearanceCard.addView(styledText(activity, "Appearance", 14f, palette.ink, true))
        appearanceCard.addView(
            styledText(activity, "Choose Light, Dark, or match your device theme", 11.5f, palette.muted).apply {
                setPadding(0, dp(activity, 2), 0, dp(activity, 10))
            }
        )
        val themeSegmentRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val themeModes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
        for ((index, mode) in themeModes.withIndex()) {
            val isSelected = (mode == currentThemeMode)
            val segBtn = Button(activity).apply {
                text = mode.displayLabel
                contentDescription = "Set appearance theme to ${mode.displayLabel}"
                setAllCaps(false)
                textSize = 12.5f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(if (isSelected) palette.teal else palette.ink)
                gravity = Gravity.CENTER
                minHeight = dp(activity, 48)
                minimumHeight = dp(activity, 48)
                setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
                background = rippleDrawable(
                    roundedShape(
                        color = if (isSelected) palette.mint else palette.surface,
                        radiusPx = dp(activity, 14).toFloat(),
                        strokeWidthPx = maxOf(1, dp(activity, if (isSelected) 2 else 1)),
                        strokeColor = if (isSelected) palette.teal else palette.border,
                    ),
                    if (isSelected) palette.rippleOnMint else palette.rippleOnSurface,
                )
                backgroundTintList = null
                stateListAnimator = null
                setOnClickListener {
                    if (isGenerationActive()) {
                        Toast.makeText(activity, "Stop active generation before switching theme.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    settingsPrefs.edit().putString(UiThemeResolver.PREF_THEME_MODE, mode.name).apply()
                    privacyDialog?.dismiss()
                    onThemeChanged()
                }
            }
            themeSegmentRow.addView(
                segBtn,
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index < themeModes.lastIndex) marginEnd = dp(activity, 6)
                },
            )
        }
        appearanceCard.addView(themeSegmentRow)
        bodyCol.addView(appearanceCard, marginBottom(activity, 12))

        // 2. Zero-Cloud Telemetry Guarantee Badge
        val auditCard = column(activity).apply {
            background = roundedShape(
                color = palette.successBg,
                radiusPx = dp(activity, 14).toFloat(),
                strokeWidthPx = maxOf(1, dp(activity, 1)),
                strokeColor = palette.successBorder,
            )
            setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
        }
        auditCard.addView(styledText(activity, "100% Zero-Cloud Guarantee", 14f, palette.successInk, true))
        auditCard.addView(
            styledText(
                activity,
                if (permAudit.isZeroCloudCompliant) {
                    "Verified: Hardware-level zero cloud telemetry. PocketAgent does not request or hold 'android.permission.INTERNET'. All audio, documents, and inference run 100% locally on this device."
                } else {
                    "Warning: Non-standard permissions detected."
                },
                12f,
                palette.successInk,
            ).apply { setPadding(0, dp(activity, 4), 0, 0) }
        )
        bodyCol.addView(auditCard, marginBottom(activity, 12))

        // 3. Storage Audit Card
        val storageCard = cardContainer(activity, palette, cornerDp = 14, elevated = true, paddingDp = 14)
        storageCard.addView(styledText(activity, "Local Storage Breakdown", 14f, palette.ink, true), marginBottom(activity, 8))

        fun addStorageRow(label: String, value: String) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(activity, 3), 0, dp(activity, 3))
            }
            row.addView(styledText(activity, label, 12f, palette.muted), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(styledText(activity, value, 12f, palette.ink, true))
            storageCard.addView(row)
        }

        addStorageRow("AI Models (.litertlm & whisper):", storageAudit.modelDisplay)
        addStorageRow("Saved Conversations:", storageAudit.sessionsDisplay)
        addStorageRow("Long-Term Memories:", storageAudit.memoriesDisplay)
        addStorageRow("Personal Notes:", storageAudit.notesDisplay)
        addStorageRow("Temporary Cache:", storageAudit.cacheDisplay)
        addStorageRow("Total Storage Used:", storageAudit.totalDisplay)
        bodyCol.addView(storageCard, marginBottom(activity, 12))

        // 4. Biometric App Lock Row
        val lockCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedShape(
                color = palette.surfaceElevated,
                radiusPx = dp(activity, 14).toFloat(),
                strokeWidthPx = maxOf(1, dp(activity, 1)),
                strokeColor = palette.border,
            )
            setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
        }
        val lockInfo = column(activity).apply {
            addView(styledText(activity, "Biometric App Lock", 14f, palette.ink, true))
            addView(styledText(activity, "Require device credential or biometric on resume", 11.5f, palette.muted))
        }
        lockCard.addView(lockInfo, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(activity, 8) })

        val lockToggleBtn = pillButton(
            context = activity,
            palette = palette,
            label = if (biometricLockManager.isLockEnabled) "Enabled" else "Disabled",
            accessibilityLabel = "Toggle biometric app lock",
            compact = true,
        ) {}
        lockToggleBtn.setOnClickListener {
            val enabling = !biometricLockManager.isLockEnabled
            if (enabling) {
                if (!biometricLockManager.canUseBiometrics(activity)) {
                    Toast.makeText(activity, "Set a device screen lock or biometric first.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                biometricLockManager.setLockEnabled(true)
                lockToggleBtn.text = "Enabled"
                privacyDialog?.dismiss()
                onLockStateChanged()
                onPromptBiometricUnlock()
                Toast.makeText(activity, "App lock enabled. Authenticate to continue.", Toast.LENGTH_SHORT).show()
            } else {
                biometricLockManager.setLockEnabled(false)
                lockToggleBtn.text = "Disabled"
                onLockStateChanged()
                Toast.makeText(activity, "App lock disabled", Toast.LENGTH_SHORT).show()
            }
        }
        lockCard.addView(lockToggleBtn)
        bodyCol.addView(lockCard, marginBottom(activity, 12))

        // 5. Panic Wipe Button
        val panicBtn = dangerButton(
            context = activity,
            palette = palette,
            label = "Panic Wipe All User Data",
            accessibilityLabel = "Emergency delete all personal data",
        ) {
            val wipeDialog = AlertDialog.Builder(activity)
                .setTitle("Panic Data Wipe")
                .setMessage(
                    "This will permanently delete all conversation history, memories, and personal notes on this device. (AI models will be kept).\n\nProceed?"
                )
                .setPositiveButton("Erase Everything") { _, _ ->
                    PrivacyAuditCenter.wipeAllUserData(activity.filesDir, activity.cacheDir, purgeModels = false)
                    onPanicWipeComplete()
                    Toast.makeText(activity, "All personal data has been erased.", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel", null)
                .create()
            showStyledAlertDialog(wipeDialog, palette)
        }
        bodyCol.addView(panicBtn, LinearLayout.LayoutParams(-1, -2))

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Privacy & Appearance")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()
        privacyDialog = dialog
        showStyledAlertDialog(dialog, palette)
    }
}
