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


internal fun MainActivity.updateRecordingBanner() {
        if (!isRecordingBannerInitialized()) return
        val isRec = inAppAudioRecorder.isRecordingActive
        val targetVisibility = if (isRec || isDictating) View.VISIBLE else View.GONE
        if (recordingBanner.visibility != targetVisibility && isComposerCardInitialized()) {
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

    /** Hides the soft keyboard and optionally clears focus from [taskInput]. */

internal fun MainActivity.toggleInAppRecording() {
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

internal fun MainActivity.startInAppRecording() {
        dismissComposerOverlays(hideIme = true)
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

internal fun MainActivity.transcribeRecordedAudio(samples: FloatArray, generationAtStart: Long) {
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

internal fun MainActivity.resetRecordButton() {
        recordAudioButton.text = "Record audio"
        recordAudioButton.contentDescription = "Record audio for local Whisper transcription"
        updateWhisperActionButtons(whisperContext != 0L)
    }

internal fun MainActivity.updateWhisperActionButtons(enabled: Boolean) {
        if (isTranscribeWavButtonInitialized()) transcribeWavButton.isEnabled = enabled
        if (isRecordAudioButtonInitialized()) recordAudioButton.isEnabled = enabled
    }

internal fun MainActivity.startDictation() {
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
        dismissComposerOverlays(hideIme = true)
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
                if (partial.isNotBlank() && isRecordingBannerTextInitialized()) {
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
