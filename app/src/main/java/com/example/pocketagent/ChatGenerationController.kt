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


fun MainActivity.conversationHistoryFor(task: String): List<ChatMessage> =
        promptContext.historyFor(currentSession?.messages.orEmpty(), task)

    /** Composes prompt + token/truncation metadata for one attempt; see [AnswerPromptComposer]. */

fun MainActivity.composePrompt(
        task: String,
        attachment: String,
        intent: AnswerDirective.Intent,
        isRetry: Boolean,
        history: List<ChatMessage>,
        calculation: LocalCalculatorTool.CalculationResult? = null,
        relevantNotes: List<PersonalNotesStore.PersonalNote> = emptyList(),
    ): AnswerPromptComposer.PromptBuildResult =
        AnswerPromptComposer(
            ContextBudgetManager(
                ContextBudgetManager.TokenBudget.forContextWindow(
                    maxContextTokens = currentMaxContextTokens,
                    requestedResponseTokens = currentMaxOutputTokens,
                )
            )
        ).answerPrompt(task, attachment, intent, isRetry, history, calculation, relevantNotes)

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

fun MainActivity.buildCallbacksForAnswer(answerProvider: () -> String): AssistantTurnCallbacks =
        AssistantTurnCallbacks(
            onCopyCode = { code, language ->
                performSubtleHaptic(chatThreadContainer, isConfirm = true)
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
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
                        showSaveArtifactDialog(savedCode, normalizeArtifactLanguage(savedLang))
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

fun MainActivity.startGenerationIndicators(handle: ActiveAssistantCardHandle, initialStage: String) {
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

fun MainActivity.stopGenerationIndicators() {
        thinkingPulseAnimator.stop()
        generationTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        generationTimerRunnable = null
    }

fun MainActivity.runGuardedGeneration(
        convKey: String,
        prompt: String,
        systemInstruction: String,
        imagePath: String? = null,
    ): String {
        passStartElapsedMs = SystemClock.elapsedRealtime()
        firstTokenElapsedMs = null
        streamedCharsCount = 0
        val accumulated = StringBuilder()
        val firstChunk = AtomicBoolean(true)
        val streamFinished = CountDownLatch(1)
        val streamError = AtomicReference<Throwable?>(null)

        val guard = GuardedGeneration { partial ->
            streamedCharsCount = partial.length
            val isFirst = firstChunk.compareAndSet(true, false)
            if (isFirst) {
                firstTokenElapsedMs = (SystemClock.elapsedRealtime() - passStartElapsedMs).coerceAtLeast(0L)
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
            val effectiveMaxOutputTokens = currentMaxOutputTokens.coerceAtMost(
                (currentMaxContextTokens - engineManager.cachedConversationTokens - 256).coerceAtLeast(256)
            )
            if (!imagePath.isNullOrBlank() && engineManager.visionEnabled) {
                try {
                    conversation.sendImageMessage(
                        imagePath = imagePath,
                        prompt = prompt,
                        callback = callback,
                        maxOutputTokens = effectiveMaxOutputTokens,
                    )
                } catch (_: Throwable) {
                    mainHandler.post {
                        updateStatus("Current model could not read image; falling back to text.")
                    }
                    conversation.sendMessage(
                        prompt,
                        callback,
                        maxOutputTokens = effectiveMaxOutputTokens,
                    )
                }
            } else {
                conversation.sendMessage(
                    prompt,
                    callback,
                    maxOutputTokens = effectiveMaxOutputTokens,
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
                    val partialText = guard.text(accumulated)
                    if (partialText.trim().length >= 80) {
                        // GPU was working and produced real output before hitting a sequence/KV cap;
                        // preserve what was streamed instead of wiping the screen and falling back to CPU.
                        conversation.cancel()
                        engineManager.invalidateConversation("mid-stream limit reached")
                        mainHandler.post {
                            updateStatus("Stopped at sequence/context limit — partial answer kept.")
                        }
                        runawayFragment = guard.runawayFragment
                        return AnswerFinalizer.finalize(partialText).text
                    }
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

fun MainActivity.renderFinalAnswer(
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

fun MainActivity.enterGeneratingState() {
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

fun MainActivity.exitGeneratingState() {
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

fun MainActivity.scheduleIdleEngineUnload() {
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

fun MainActivity.stopActiveGeneration() {
        if (!isGeneratingUi) return
        isGeneratingCancelled.set(true)
        isStoppingUi = true
        viewModel.stopGeneration()
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

fun MainActivity.maybeFollowStreamingOutput() {
        if (isUserReadingUpwards) return
        scrollChatToBottom()
    }

    /**
     * Reveals the manual Refine affordance for a dodged or unsubstantive answer.
     *
     * This replaces the old silent second inference pass: the user now chooses whether the extra
     * generation is worth it, instead of every vague answer costing two full runs.
     */

fun MainActivity.updateRefineAffordance(handle: ActiveAssistantCardHandle? = activeAssistantHandle) {
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

fun MainActivity.refineLastAnswer() {
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

fun MainActivity.conversationKeyFor(
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

fun MainActivity.buildEffectiveInstructionWithTrace(
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

fun MainActivity.effectiveSystemInstruction(task: String, intent: AnswerDirective.Intent): String =
        buildEffectiveInstructionWithTrace(task, intent).first

fun MainActivity.executeWithGpuFallback(
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
                        activeAssistantHandle?.bindStreamingText("GPU could not run this pass; reloading on CPU (first token takes longer)…")
                    }
                    streamingRenderer.reset()
                    onBeforeCpuRetry?.invoke()
                    return attempt()
                }
            }
            throw gpuError
        }
    }

fun MainActivity.suggestSteps() {
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
        dismissComposerOverlays(hideIme = true)
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
        lastRawPrompt = rawTask
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

                val rawVaultExcerpts = if (task.isNotBlank()) documentVault.search(task, topK = 3) else emptyList()
                val vaultExcerpts = ReasoningRouter.deduplicateExcerpts(rawVaultExcerpts)
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
                val routing = ReasoningRouter.route(
                    task = task,
                    hasGroundedContext = boundedAttachment.isNotBlank() || matchedNotes.isNotEmpty(),
                    userTemperature = currentTemperature,
                )
                val effectiveInstruction = if (routing.compactDirective.isNotBlank()) {
                    "${routing.compactDirective}\n\n${memoryEval.first}"
                } else {
                    memoryEval.first
                }
                traceChips += memoryEval.second
                if (routing.profile != ReasoningRouter.TaskProfile.QUICK || routing.languageMode != ReasoningRouter.LanguageMode.ENGLISH) {
                    traceChips += "Router" to "${routing.profile.name.lowercase(Locale.ROOT)} · temp ${String.format(Locale.US, "%.2f", routing.recommendedTemperature)}"
                }
                engineManager.updateTemperature(routing.recommendedTemperature)

                val evidenceParts = buildList {
                    calculation?.let { add("Calculator: ${it.expression} = ${it.displayOutput}") }
                    if (matchedNotes.isNotEmpty()) {
                        add("Notes: " + matchedNotes.joinToString(" | ") { "${it.title}: ${it.content.take(100)}" })
                    }
                    if (vaultExcerpts.isNotEmpty()) {
                        add("Document Vault:\n" + vaultExcerpts.joinToString("\n") { it.take(140) })
                    }
                    if (localCitations.isNotEmpty()) {
                        add("Attachments: " + localCitations.joinToString(" | ") { it.excerptSnippet.take(100) })
                    }
                }.toMutableList()

                if (traceChips.isNotEmpty()) {
                    val snapshot = traceChips.toList()
                    val summarySnapshot = evidenceParts.joinToString("\n\n").ifBlank { null }
                    mainHandler.post {
                        if (!isDestroyed) handle.bindAgentTraceChips(snapshot, summarySnapshot)
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
                    ContextBudgetManager.TokenBudget.warmResetThreshold(currentMaxContextTokens)
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
                            evidenceParts += "Tool ${toolRes.toolName}(${toolRes.input}): ${toolRes.output.take(220)}"
                        }
                        val traceSnapshot = traceChips.toList()
                        val updatedEvidence = evidenceParts.joinToString("\n\n").ifBlank { null }
                        val toolNames = loopRes.toolResults.joinToString(", ") { it.toolName }
                        mainHandler.post {
                            if (!isDestroyed) {
                                handle.bindAgentTraceChips(traceSnapshot, updatedEvidence)
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

                if (isDeepThinkEnabled && routing.shouldAllowDeepThink && !isGeneratingCancelled.get() && runawayFragment == null && result.isNotBlank()) {
                    traceChips += "Deep Think" to "Self-critique pass"
                    evidenceParts += "Deep Think: Verified draft logic, math, and constraints in a second pass."
                    val traceSnapshot = traceChips.toList()
                    val updatedEvidence = evidenceParts.joinToString("\n\n").ifBlank { null }
                    mainHandler.post {
                        if (!isDestroyed) {
                            handle.bindAgentTraceChips(traceSnapshot, updatedEvidence)
                            currentGenerationStageLabel = "Deep Think · Verifying"
                            updateStatus("Deep Think: verifying and refining answer…")
                        }
                    }
                    streamingRenderer.reset()
                    val critiquePrompt = ReasoningRouter.buildDeepThinkCritiquePrompt(task, result)
                    result = runGuardedGeneration(convKey, critiquePrompt, effectiveInstruction)
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
                val nowElapsed = SystemClock.elapsedRealtime()
                val elapsedMs = nowElapsed - generationStartElapsedMs
                val passElapsedMs = nowElapsed - passStartElapsedMs
                val ftMs = firstTokenElapsedMs
                if (!isGeneratingCancelled.get() && ftMs != null && result.length >= 24) {
                    val decodeMs = passElapsedMs - ftMs
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

fun MainActivity.regenerateAnswer() {
        val toRegenerate = lastRawPrompt.ifBlank { lastPrompt }
        if (toRegenerate.isNotBlank() && !isGeneratingUi && !engineManager.isGenerationActive) {
            taskInput.setText(toRegenerate)
            suggestSteps()
        }
    }

fun MainActivity.renderCitations(
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

fun MainActivity.showCitationDialog(citation: LocalDocumentRetriever.DocumentCitation) {
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
