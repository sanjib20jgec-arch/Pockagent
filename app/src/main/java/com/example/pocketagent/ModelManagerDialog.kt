package com.example.pocketagent

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Handler
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Toast
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Displays and manages the Local Model & Engine dialog, including runtime status,
 * context window controls, generation parameters, Omni Vision toggle, saved model
 * library switching/benchmarking, hardware backend selection, and model removal.
 */
object ModelManagerDialog {
    private const val PREF_BACKEND_MODE = "pref_backend_mode"
    private const val PREF_MODEL_DISPLAY_NAME = "pref_model_display_name"
    private const val PREF_GPU_LOADING_GUARD = "pref_gpu_loading_crash_guard"
    private const val PREF_TEMPERATURE_BITS = "pref_temperature_bits"
    private const val PREF_MAX_OUTPUT_TOKENS = "pref_max_output_tokens"
    private const val PREF_MAX_CONTEXT_TOKENS = "pref_max_context_tokens"
    private const val PREF_VISION_ENABLED = "pref_vision_enabled"

    /**
     * Builds and shows the Local Model & Engine management dialog.
     *
     * @param activity Host activity used for UI inflation, resources, and dialogs.
     * @param palette Active [UiPalette] theme tokens.
     * @param settingsPrefs SharedPreferences storing persisted engine and generation settings.
     * @param engineManager Active [InferenceEngineManager] managing model/conversation lifecycle.
     * @param modelLibraryStore Store tracking imported `.litertlm` models and benchmarks.
     * @param inferenceExecutor Background executor for engine operations and benchmarking.
     * @param mainHandler Main-thread handler for UI callbacks after background work.
     * @param cacheDir App cache directory cleared when removing stored models.
     * @param getTemperature Returns current sampling temperature.
     * @param setTemperature Updates in-memory sampling temperature.
     * @param getMaxContextTokens Returns current max context window size in tokens.
     * @param setMaxContextTokens Updates in-memory max context window size in tokens.
     * @param getMaxOutputTokens Returns current max output tokens per response.
     * @param setMaxOutputTokens Updates in-memory max output tokens per response.
     * @param getVisionModeEnabled Returns whether Omni Vision Mode is enabled.
     * @param setVisionModeEnabled Updates in-memory Omni Vision Mode flag.
     * @param isGenerating Returns true if UI generation is currently active.
     * @param activeModelFile Returns the currently active model [File].
     * @param formatStorageSize Formats byte counts into human-readable storage strings.
     * @param onEnsureLegacyModelRegistered Ensures legacy single-file models are indexed.
     * @param onEnsureEngineInitialized Initializes the engine for a given model file before benchmarking.
     * @param onChooseModel Launches the model picker to import a `.litertlm` file.
     * @param onRefreshStatus Refreshes model status badges and hero CTA visibility.
     * @param onUpdateSubtitle Updates workspace subtitle with current model/context info.
     * @param onSyncComposer Synchronizes composer state (e.g. vision attachment availability).
     * @param onUpdateStatus Posts a user-visible status message.
     */
    fun show(
        activity: Activity,
        palette: UiPalette,
        settingsPrefs: SharedPreferences,
        engineManager: InferenceEngineManager<*>,
        modelLibraryStore: ModelLibraryStore,
        inferenceExecutor: Executor,
        mainHandler: Handler,
        cacheDir: File,
        getTemperature: () -> Float,
        setTemperature: (Float) -> Unit,
        getMaxContextTokens: () -> Int,
        setMaxContextTokens: (Int) -> Unit,
        getMaxOutputTokens: () -> Int,
        setMaxOutputTokens: (Int) -> Unit,
        getVisionModeEnabled: () -> Boolean,
        setVisionModeEnabled: (Boolean) -> Unit,
        isGenerating: () -> Boolean,
        activeModelFile: () -> File,
        formatStorageSize: (Long) -> String,
        onEnsureLegacyModelRegistered: () -> Unit = {},
        onEnsureEngineInitialized: (File) -> Unit,
        onChooseModel: () -> Unit,
        onRefreshStatus: () -> Unit,
        onUpdateSubtitle: () -> Unit,
        onSyncComposer: () -> Unit,
        onUpdateStatus: (String) -> Unit,
    ) {
        onEnsureLegacyModelRegistered()
        val model = activeModelFile()
        val allModels = modelLibraryStore.loadAll()
        if ((!model.exists() || model.length() == 0L) && allModels.isEmpty()) {
            onChooseModel()
            return
        }

        val activeEntry = modelLibraryStore.getActive()
        val modelName = activeEntry?.displayName
            ?: settingsPrefs.getString(PREF_MODEL_DISPLAY_NAME, null)
            ?: model.name
        val sizeStr = if (model.exists()) formatStorageSize(model.length()) else "0 B"
        val isCpuOnly = engineManager.preference == BackendPreference.CPU_ONLY
        val isNpuPreferred = engineManager.preference == BackendPreference.NPU_PREFERRED
        val runtimeState = when (engineManager.activeBackend) {
            ActiveBackend.NPU -> "Loaded in RAM (NPU)"
            ActiveBackend.GPU -> "Loaded in RAM (GPU)"
            ActiveBackend.CPU -> "Loaded in RAM (CPU)"
            null -> "Stored on disk (standby · 0 RAM)"
        }

        fun marginBottom(dpValue: Int) = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(activity, dpValue)
        }

        val dialogView = styleDialogContainer(activity, palette, paddingDp = 16)
        val bodyCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val bodyScroll = ScrollView(activity).apply { addView(bodyCol) }
        dialogView.addView(bodyScroll, LinearLayout.LayoutParams(-1, -2))

        // Info & Backend Diagnostic Card
        val infoCard = cardContainer(activity, palette, cornerDp = 14, elevated = true, paddingDp = 14)
        infoCard.addView(styledText(activity, modelName, 15f, palette.ink, true))
        infoCard.addView(
            styledText(activity, "Size: $sizeStr  ·  $runtimeState", 12.5f, palette.muted).apply {
                setPadding(0, dp(activity, 4), 0, dp(activity, 6))
            },
        )
        if (activeEntry != null && activeEntry.tokensPerSecond > 0f) {
            infoCard.addView(
                styledText(
                    activity,
                    "Measured Speed: ${activeEntry.tokensPerSecondLabel}",
                    12f,
                    palette.teal,
                    true,
                ).apply {
                    setPadding(0, 0, 0, dp(activity, 6))
                },
            )
        }
        val diag = ContextContinuityHelper.formatBackendDiagnostic(
            activeBackend = engineManager.activeBackend,
            preference = engineManager.preference,
            lastFallbackReason = engineManager.lastFallbackReason,
            visionEnabled = getVisionModeEnabled(),
        )
        infoCard.addView(
            styledText(
                activity,
                "${diag.headline}\n${diag.tip}",
                11.5f,
                if (diag.isCpuFallback) palette.warningInk else palette.muted,
            ).apply {
                setPadding(0, 0, 0, dp(activity, 6))
            },
        )
        infoCard.addView(
            styledText(
                activity,
                "Vision towers remain off by default to save RAM unless Omni Vision Mode is enabled below.",
                11.5f,
                palette.muted,
            ),
        )
        bodyCol.addView(infoCard, marginBottom(12))

        lateinit var dialog: AlertDialog

        // Context Window Meter Card
        var maxContext = getMaxContextTokens()
        val usedContext = engineManager.cachedConversationTokens.coerceIn(0, maxContext)
        val ctxCard = cardContainer(activity, palette, cornerDp = 14, elevated = true, paddingDp = 14)
        val ctxHeaderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ctxTitleView = styledText(
            activity,
            "Context Window: $usedContext / $maxContext tok",
            13f,
            palette.ink,
            true,
        )
        ctxHeaderRow.addView(ctxTitleView, LinearLayout.LayoutParams(0, -2, 1f))
        val ctxProgress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = maxContext
            progress = usedContext
            progressTintList = ColorStateList.valueOf(
                if (usedContext >= ContextBudgetManager.TokenBudget.warmResetThreshold(maxContext)) {
                    palette.stopCrimson
                } else {
                    palette.teal
                },
            )
        }
        val resetCtxBtn = secondaryOutlineButton(
            activity,
            palette,
            "Reset",
            compact = true,
            textColor = palette.teal,
        ) {
            engineManager.invalidateConversation("manual context reset")
            ctxTitleView.text = "Context Window: 0 / $maxContext tok"
            ctxProgress.progress = 0
            onUpdateSubtitle()
            Toast.makeText(activity, "Conversation KV cache reset", Toast.LENGTH_SHORT).show()
        }
        ctxHeaderRow.addView(resetCtxBtn)
        ctxCard.addView(ctxHeaderRow, marginBottom(8))
        ctxCard.addView(ctxProgress, LinearLayout.LayoutParams(-1, dp(activity, 8)))
        bodyCol.addView(ctxCard, marginBottom(12))

        // Generation Settings Card (Temperature, Context Window, Max Tokens, Vision Mode)
        val settingsCard = cardContainer(activity, palette, cornerDp = 14, elevated = true, paddingDp = 14)
        settingsCard.addView(
            styledText(activity, "Generation & Capabilities", 14f, palette.ink, true),
            marginBottom(8),
        )

        val currentTemp = getTemperature()
        val tempLabel = styledText(
            activity,
            String.format(Locale.US, "Temperature: %.1f (lower = precise, higher = creative)", currentTemp),
            12.5f,
            palette.muted,
        )
        settingsCard.addView(tempLabel, marginBottom(4))
        val tempSeek = SeekBar(activity).apply {
            max = 14 // maps 0..14 -> 0.1..1.5
            progress = (((currentTemp - 0.1f) * 10f).toInt()).coerceIn(0, 14)
            progressTintList = ColorStateList.valueOf(palette.teal)
            thumbTintList = ColorStateList.valueOf(palette.teal)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val temp = (progress + 1) / 10f
                    tempLabel.text = String.format(
                        Locale.US,
                        "Temperature: %.1f (lower = precise, higher = creative)",
                        temp,
                    )
                    if (fromUser) {
                        setTemperature(temp)
                        settingsPrefs.edit()
                            .putInt(PREF_TEMPERATURE_BITS, java.lang.Float.floatToIntBits(temp))
                            .apply()
                        engineManager.updateTemperature(temp)
                        onUpdateSubtitle()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        settingsCard.addView(tempSeek, marginBottom(10))

        fun <T> buildSelectableRow(
            options: List<Pair<T, String>>,
            selectedProvider: () -> T,
            onSelect: (T) -> Unit,
        ): LinearLayout {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val buttons = mutableListOf<Button>()
            fun refresh() {
                val selected = selectedProvider()
                buttons.forEachIndexed { i, btn ->
                    val sel = options[i].first == selected
                    btn.setTextColor(if (sel) palette.teal else palette.ink)
                    btn.background = rippleDrawable(
                        roundedShape(
                            color = if (sel) palette.mint else palette.surface,
                            radiusPx = dp(activity, 12).toFloat(),
                            strokeWidthPx = maxOf(1, dp(activity, if (sel) 2 else 1)),
                            strokeColor = if (sel) palette.teal else palette.border,
                        ),
                        if (sel) palette.rippleOnMint else palette.rippleOnSurface,
                    )
                }
            }
            options.forEachIndexed { index, (value, label) ->
                val btn = Button(activity).apply {
                    text = label
                    setAllCaps(false)
                    textSize = 12f
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    minHeight = dp(activity, 48)
                    minimumHeight = dp(activity, 48)
                    backgroundTintList = null
                    stateListAnimator = null
                    setOnClickListener {
                        onSelect(value)
                        refresh()
                    }
                }
                buttons += btn
                row.addView(
                    btn,
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                        if (index < options.lastIndex) marginEnd = dp(activity, 6)
                    },
                )
            }
            refresh()
            return row
        }

        settingsCard.addView(
            styledText(
                activity,
                "Context Window (8K requires 8K model & 12GB+ RAM)",
                12.5f,
                palette.muted,
                true,
            ),
            marginBottom(6),
        )
        val contextOptions = listOf(
            2048 to "2048 (Fast)",
            4096 to "4096",
            8192 to "8192 (8K)",
        )
        val contextRow = buildSelectableRow(
            options = contextOptions,
            selectedProvider = getMaxContextTokens,
        ) { nextCtx ->
            if (engineManager.isGenerationActive || isGenerating()) {
                Toast.makeText(activity, "Stop generation first.", Toast.LENGTH_SHORT).show()
                return@buildSelectableRow
            }
            setMaxContextTokens(nextCtx)
            maxContext = nextCtx
            settingsPrefs.edit().putInt(PREF_MAX_CONTEXT_TOKENS, nextCtx).apply()
            inferenceExecutor.execute { engineManager.updateMaxContextTokens(nextCtx) }
            val clampedUsed = engineManager.cachedConversationTokens.coerceIn(0, nextCtx)
            ctxProgress.max = nextCtx
            ctxProgress.progress = clampedUsed
            ctxTitleView.text = "Context Window: $clampedUsed / $nextCtx tok"
            onUpdateSubtitle()
            Toast.makeText(
                activity,
                if (nextCtx == 8192) "8K context enabled (auto-falls back to 4096 if model limits it)"
                else "Context window set to $nextCtx tokens",
                Toast.LENGTH_SHORT,
            ).show()
        }
        settingsCard.addView(contextRow, marginBottom(10))

        settingsCard.addView(
            styledText(activity, "Max Output Tokens", 12.5f, palette.muted, true),
            marginBottom(6),
        )
        val tokenOptions = listOf(256, 512, 1024, 2048).map { it to it.toString() }
        val tokenRow = buildSelectableRow(
            options = tokenOptions,
            selectedProvider = getMaxOutputTokens,
        ) { tok ->
            setMaxOutputTokens(tok)
            settingsPrefs.edit().putInt(PREF_MAX_OUTPUT_TOKENS, tok).apply()
        }
        settingsCard.addView(tokenRow, marginBottom(10))

        val initialVision = getVisionModeEnabled()
        val visionBtn = secondaryOutlineButton(
            context = activity,
            palette = palette,
            label = if (initialVision) "Omni Vision Mode: ON (+ Image)" else "Omni Vision Mode: OFF",
            accessibilityLabel = "Toggle Omni Vision image input support",
            textColor = if (initialVision) palette.teal else palette.ink,
        ) {}
        visionBtn.setOnClickListener {
            if (engineManager.isGenerationActive || isGenerating()) {
                Toast.makeText(activity, "Stop generation first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val nextVision = !getVisionModeEnabled()
            setVisionModeEnabled(nextVision)
            settingsPrefs.edit().putBoolean(PREF_VISION_ENABLED, nextVision).apply()
            inferenceExecutor.execute { engineManager.setVisionMode(nextVision) }
            visionBtn.text = if (nextVision) "Omni Vision Mode: ON (+ Image)" else "Omni Vision Mode: OFF"
            onSyncComposer()
            onUpdateSubtitle()
            Toast.makeText(
                activity,
                if (nextVision) "Vision enabled (Omni models) — + Image available in + tray"
                else "Vision disabled (text-only saves RAM)",
                Toast.LENGTH_LONG,
            ).show()
        }
        settingsCard.addView(visionBtn, LinearLayout.LayoutParams(-1, -2))
        bodyCol.addView(settingsCard, marginBottom(12))

        // Saved Model Library Switcher
        if (allModels.isNotEmpty()) {
            bodyCol.addView(
                styledText(activity, "Model Library (${allModels.size})", 12.5f, palette.muted, true),
                marginBottom(6),
            )
            for (entry in allModels) {
                val isAct = entry.isActive
                val mCard = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 8), dp(activity, 10))
                    background = roundedShape(
                        color = if (isAct) palette.mint else palette.surfaceElevated,
                        radiusPx = dp(activity, 14).toFloat(),
                        strokeWidthPx = maxOf(1, dp(activity, if (isAct) 2 else 1)),
                        strokeColor = if (isAct) palette.teal else palette.border,
                    )
                }
                val mInfo = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        styledText(
                            activity,
                            if (isAct) "✓ ${entry.displayName}" else entry.displayName,
                            13.5f,
                            if (isAct) palette.teal else palette.ink,
                            true,
                        ),
                    )
                    addView(
                        styledText(
                            activity,
                            "${entry.sizeLabel}  ·  ${entry.tokensPerSecondLabel}",
                            11.5f,
                            palette.muted,
                        ),
                    )
                }
                mCard.addView(mInfo, LinearLayout.LayoutParams(0, -2, 1f))
                if (isAct) {
                    val benchBtn = secondaryOutlineButton(
                        activity,
                        palette,
                        "Benchmark",
                        compact = true,
                        textColor = palette.teal,
                    ) {
                        if (engineManager.isGenerationActive || isGenerating()) {
                            Toast.makeText(activity, "Stop active generation first.", Toast.LENGTH_SHORT).show()
                            return@secondaryOutlineButton
                        }
                        dialog.dismiss()
                        onUpdateStatus("Benchmarking ${entry.displayName} locally…")
                        inferenceExecutor.execute {
                            try {
                                val benchFile = File(entry.filePath)
                                onEnsureEngineInitialized(benchFile)
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

                                        override fun onDone() {
                                            latch.countDown()
                                        }

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
                                    onUpdateStatus(
                                        String.format(
                                            Locale.US,
                                            "Benchmark complete: ${entry.displayName} · %.1f tok/s",
                                            tokPerSec,
                                        ),
                                    )
                                    Toast.makeText(
                                        activity,
                                        String.format(Locale.US, "%.1f tok/s", tokPerSec),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            } catch (e: Exception) {
                                mainHandler.post {
                                    onUpdateStatus("Benchmark failed: ${e.message ?: "unknown error"}")
                                }
                            }
                        }
                    }
                    mCard.addView(benchBtn)
                } else {
                    val useBtn = pillButton(activity, palette, "Use", compact = true) {
                        if (engineManager.isGenerationActive || isGenerating()) {
                            Toast.makeText(
                                activity,
                                "Stop generation before switching models.",
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@pillButton
                        }
                        modelLibraryStore.setActive(entry.id)
                        settingsPrefs.edit().putString(PREF_MODEL_DISPLAY_NAME, entry.displayName).apply()
                        dialog.dismiss()
                        inferenceExecutor.execute {
                            engineManager.close()
                            mainHandler.post {
                                onRefreshStatus()
                                onUpdateStatus("Switched active model to ${entry.displayName}.")
                            }
                        }
                    }
                    mCard.addView(
                        useBtn,
                        LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(activity, 4) },
                    )
                    val delEntryBtn = iconTouchButton(
                        activity,
                        palette,
                        "✕",
                        "Delete ${entry.displayName}",
                        palette.muted,
                        15f,
                    ) {
                        dialog.dismiss()
                        val targetFile = File(entry.filePath)
                        if (targetFile.parentFile?.parentFile == ModelLibraryStore.defaultDir(activity.filesDir)) {
                            targetFile.parentFile?.deleteRecursively()
                        } else if (targetFile.name != ModelFileStore.MODEL_FILE_NAME) {
                            targetFile.delete()
                        }
                        modelLibraryStore.remove(entry.id)
                        onRefreshStatus()
                        show(
                            activity = activity,
                            palette = palette,
                            settingsPrefs = settingsPrefs,
                            engineManager = engineManager,
                            modelLibraryStore = modelLibraryStore,
                            inferenceExecutor = inferenceExecutor,
                            mainHandler = mainHandler,
                            cacheDir = cacheDir,
                            getTemperature = getTemperature,
                            setTemperature = setTemperature,
                            getMaxContextTokens = getMaxContextTokens,
                            setMaxContextTokens = setMaxContextTokens,
                            getMaxOutputTokens = getMaxOutputTokens,
                            setMaxOutputTokens = setMaxOutputTokens,
                            getVisionModeEnabled = getVisionModeEnabled,
                            setVisionModeEnabled = setVisionModeEnabled,
                            isGenerating = isGenerating,
                            activeModelFile = activeModelFile,
                            formatStorageSize = formatStorageSize,
                            onEnsureLegacyModelRegistered = onEnsureLegacyModelRegistered,
                            onEnsureEngineInitialized = onEnsureEngineInitialized,
                            onChooseModel = onChooseModel,
                            onRefreshStatus = onRefreshStatus,
                            onUpdateSubtitle = onUpdateSubtitle,
                            onSyncComposer = onSyncComposer,
                            onUpdateStatus = onUpdateStatus,
                        )
                    }
                    mCard.addView(delEntryBtn)
                }
                bodyCol.addView(mCard, marginBottom(8))
            }
        }

        // Hardware Backend Selection
        bodyCol.addView(
            styledText(activity, "Inference Backend", 12.5f, palette.muted, true),
            marginBottom(6),
        )

        fun switchBackendPreference(next: BackendPreference) {
            if (engineManager.preference == next) return
            if (engineManager.isGenerationActive || isGenerating()) {
                Toast.makeText(activity, "Stop generation before changing backend.", Toast.LENGTH_SHORT).show()
                return
            }
            settingsPrefs.edit()
                .putString(PREF_BACKEND_MODE, next.name)
                .putBoolean(PREF_GPU_LOADING_GUARD, false)
                .apply()
            dialog.dismiss()
            val targetLabel = when (next) {
                BackendPreference.NPU_PREFERRED -> "NPU (auto fallback)"
                BackendPreference.GPU_PREFERRED -> "GPU (auto fallback)"
                BackendPreference.CPU_ONLY -> "CPU Only"
            }
            onUpdateStatus("Switching backend to $targetLabel…")
            inferenceExecutor.execute {
                engineManager.switchBackend(next)
                mainHandler.post {
                    onRefreshStatus()
                    onUpdateStatus("Backend set to $targetLabel. Ready for your next question.")
                }
            }
        }

        fun buildBackendCard(
            title: String,
            subtitle: String,
            selected: Boolean,
            onSelect: () -> Unit,
        ): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(activity, 48)
            setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10))
            background = rippleDrawable(
                roundedShape(
                    color = if (selected) palette.mint else palette.surface,
                    radiusPx = dp(activity, 14).toFloat(),
                    strokeWidthPx = maxOf(1, dp(activity, if (selected) 2 else 1)),
                    strokeColor = if (selected) palette.teal else palette.border,
                ),
                if (selected) palette.rippleOnMint else palette.rippleOnSurface,
            )
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $subtitle"
            addView(
                styledText(
                    activity,
                    if (selected) "✓ $title" else title,
                    13.5f,
                    if (selected) palette.teal else palette.ink,
                    true,
                ),
            )
            addView(
                styledText(activity, subtitle, 11.5f, palette.muted).apply {
                    setPadding(0, dp(activity, 2), 0, 0)
                },
            )
            setOnClickListener { onSelect() }
        }

        val backendRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backendRow.addView(
            buildBackendCard(
                title = "NPU",
                subtitle = "Auto fallback",
                selected = isNpuPreferred,
            ) { switchBackendPreference(BackendPreference.NPU_PREFERRED) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(activity, 6) },
        )
        backendRow.addView(
            buildBackendCard(
                title = "GPU",
                subtitle = "Fastest fallback",
                selected = !isCpuOnly && !isNpuPreferred,
            ) { switchBackendPreference(BackendPreference.GPU_PREFERRED) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(activity, 6) },
        )
        backendRow.addView(
            buildBackendCard(
                title = "CPU Only",
                subtitle = "Max stability",
                selected = isCpuOnly,
            ) { switchBackendPreference(BackendPreference.CPU_ONLY) },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        bodyCol.addView(backendRow, marginBottom(12))

        // Unload RAM, Import Model, Remove Model Actions
        bodyCol.addView(
            secondaryOutlineButton(
                context = activity,
                palette = palette,
                label = "Unload Model from RAM (Keep File)",
                accessibilityLabel = "Release model memory from RAM",
            ) {
                if (engineManager.isGenerationActive || isGenerating()) {
                    Toast.makeText(activity, "Stop generation before unloading model.", Toast.LENGTH_SHORT).show()
                    return@secondaryOutlineButton
                }
                dialog.dismiss()
                inferenceExecutor.execute {
                    engineManager.close()
                    mainHandler.post {
                        onRefreshStatus()
                        onUpdateStatus("Model unloaded from RAM ($sizeStr file kept on disk). Will reload on next prompt.")
                    }
                }
            },
            marginBottom(8),
        )

        bodyCol.addView(
            pillButton(
                context = activity,
                palette = palette,
                label = "+ Import Another .litertlm Model",
                accessibilityLabel = "Pick a LiteRT-LM model file to add to library",
            ) {
                dialog.dismiss()
                onChooseModel()
            },
            marginBottom(8),
        )

        bodyCol.addView(
            dangerButton(
                context = activity,
                palette = palette,
                label = "Remove Stored Model ($sizeStr)",
                accessibilityLabel = "Delete stored model file to free space",
            ) {
                if (engineManager.isGenerationActive || isGenerating()) {
                    Toast.makeText(activity, "Stop generation before removing model.", Toast.LENGTH_SHORT).show()
                    return@dangerButton
                }
                dialog.dismiss()
                val confirmDialog = AlertDialog.Builder(activity)
                    .setTitle("Remove Local Model?")
                    .setMessage("Delete $modelName ($sizeStr) from app private storage to free disk space?")
                    .setPositiveButton("Remove") { _, _ ->
                        inferenceExecutor.execute {
                            engineManager.close()
                            val activeFile = activeModelFile()
                            if (activeFile.parentFile?.parentFile == ModelLibraryStore.defaultDir(activity.filesDir)) {
                                activeFile.parentFile?.deleteRecursively()
                            } else {
                                ModelFileStore(activity.filesDir).removeStoredModel()
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
                                onRefreshStatus()
                                onSyncComposer()
                                onUpdateStatus("Stored model removed. Freed $sizeStr.")
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                showStyledAlertDialog(confirmDialog, palette)
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        dialog = AlertDialog.Builder(activity)
            .setTitle("Local Model & Engine")
            .setView(dialogView)
            .setNegativeButton("Close", null)
            .create()
        showStyledAlertDialog(dialog, palette)
    }
}
