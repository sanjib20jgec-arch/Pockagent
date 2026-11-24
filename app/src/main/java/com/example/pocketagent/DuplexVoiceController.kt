package com.example.pocketagent

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seam abstraction for Text-To-Speech output to allow clean unit testing without Android framework mocks.
 */
interface TtsSpeaker {
    fun isReady(): Boolean
    fun speak(text: String, utteranceId: String, onComplete: () -> Unit)
    fun stop()
}

/**
 * Production implementation of TtsSpeaker using Android's TextToSpeech.
 */
class AndroidTtsSpeaker(
    private val ttsProvider: () -> android.speech.tts.TextToSpeech?,
    private val isReadyProvider: () -> Boolean,
) : TtsSpeaker {
    override fun isReady(): Boolean = isReadyProvider()

    override fun speak(text: String, utteranceId: String, onComplete: () -> Unit) {
        val tts = ttsProvider()
        if (tts == null || !isReady()) {
            onComplete()
            return
        }

        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId) onComplete()
            }
            override fun onError(id: String?) {
                if (id == utteranceId) onComplete()
            }
        })

        tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    override fun stop() {
        ttsProvider()?.stop()
    }
}

/**
 * Coordinates continuous, low-latency hands-free duplex voice conversations for PocketAgent.
 *
 * Invariants:
 * - 100% offline, zero cloud calls, zero external audio streaming.
 * - Hardware bounds: max 60s / 25MiB audio buffer per turn, automatic silence cutoff.
 * - Strict zero-wiping of raw audio buffers immediately after Whisper transcription.
 * - Instant barge-in / interrupt support: user speech or tap immediately stops TTS and model output.
 */
class DuplexVoiceController(
    private val audioRecorder: InAppAudioRecorder,
    private val whisperTranscriber: WhisperTranscriber,
    private val llmRunner: LlmInferenceRunner,
    private val ttsSpeaker: TtsSpeaker,
    private val workerExecutor: Executor,
    private val mainDispatcher: (Runnable) -> Unit = { it.run() },
) {
    enum class DuplexState {
        IDLE,
        LISTENING,
        TRANSCRIBING,
        GENERATING,
        SPEAKING,
        ERROR,
    }

    interface Listener {
        fun onStateChanged(state: DuplexState, statusMessage: String)
        fun onAmplitude(rms: Float)
        fun onUserTranscribed(text: String)
        fun onAssistantChunk(chunk: String)
        fun onTurnComplete(userText: String, assistantText: String)
        fun onError(error: Throwable)
    }

    @Volatile private var currentState: DuplexState = DuplexState.IDLE
    private val isSessionActive = AtomicBoolean(false)
    private val isInterrupted = AtomicBoolean(false)
    private var activeListener: Listener? = null
    private var activeWhisperContext: Long = 0L
    private var activeSystemInstruction: String = ""

    val state: DuplexState get() = currentState
    val isActive: Boolean get() = isSessionActive.get()

    /**
     * Starts continuous hands-free voice duplex mode.
     */
    @Synchronized
    fun startSession(
        listener: Listener,
        whisperContext: Long,
        systemInstruction: String = MainUiState.SYSTEM_INSTRUCTION,
    ) {
        if (isSessionActive.get()) {
            stopSession()
        }

        require(whisperContext != 0L) { "A valid local Whisper context is required for duplex mode." }
        require(llmRunner.isAvailable()) { "A ready LiteRT-LM model is required for duplex mode." }

        activeListener = listener
        activeWhisperContext = whisperContext
        activeSystemInstruction = systemInstruction
        isSessionActive.set(true)
        isInterrupted.set(false)

        startListeningTurn()
    }

    /**
     * Interrupts in-progress TTS speaking or LLM generating and returns to listening.
     */
    @Synchronized
    fun interrupt() {
        if (!isSessionActive.get()) return
        isInterrupted.set(true)
        ttsSpeaker.stop()
        audioRecorder.cancelRecording()

        if (isSessionActive.get()) {
            isInterrupted.set(false)
            startListeningTurn()
        }
    }

    /**
     * Stops the continuous voice duplex session and cleans up resources.
     */
    @Synchronized
    fun stopSession() {
        isSessionActive.set(false)
        isInterrupted.set(true)
        audioRecorder.cancelRecording()
        ttsSpeaker.stop()
        setState(DuplexState.IDLE, "Voice session ended.")
        activeListener = null
    }

    private fun setState(newState: DuplexState, message: String) {
        currentState = newState
        val listener = activeListener
        mainDispatcher(Runnable {
            listener?.onStateChanged(newState, message)
        })
    }

    private fun startListeningTurn() {
        if (!isSessionActive.get()) return
        setState(DuplexState.LISTENING, "Listening for speech…")

        audioRecorder.startRecording(object : AudioRecorderListener {
            override fun onRecordingStarted() {}

            override fun onAmplitude(rms: Float) {
                if (isSessionActive.get() && currentState == DuplexState.LISTENING) {
                    val listener = activeListener
                    mainDispatcher(Runnable { listener?.onAmplitude(rms) })
                }
            }

            override fun onSilenceCutoff() {
                audioRecorder.stopRecording()
            }

            override fun onMaxDurationReached() {
                audioRecorder.stopRecording()
            }

            override fun onRecordingComplete(samples: FloatArray) {
                if (!isSessionActive.get() || isInterrupted.get()) {
                    audioRecorder.wipeSamples(samples)
                    return
                }
                processSpeechSamples(samples)
            }

            override fun onError(error: Throwable) {
                if (isSessionActive.get()) {
                    setState(DuplexState.ERROR, error.message ?: "Microphone error")
                    activeListener?.onError(error)
                }
            }
        })
    }

    private fun processSpeechSamples(samples: FloatArray) {
        setState(DuplexState.TRANSCRIBING, "Transcribing locally…")

        workerExecutor.execute {
            var rawTranscript = ""
            try {
                rawTranscript = whisperTranscriber.transcribe(
                    context = activeWhisperContext,
                    samples = samples,
                    maxChars = 2000
                ).trim()
            } catch (e: Exception) {
                audioRecorder.wipeSamples(samples)
                if (isSessionActive.get()) {
                    setState(DuplexState.ERROR, e.message ?: "Transcription error")
                    activeListener?.onError(e)
                }
                return@execute
            } finally {
                // Invariant: Immediate zero-wiping of raw audio buffers
                audioRecorder.wipeSamples(samples)
            }

            if (!isSessionActive.get() || isInterrupted.get()) return@execute

            if (rawTranscript.isBlank()) {
                // No detectable speech: restart listening
                startListeningTurn()
                return@execute
            }

            mainDispatcher(Runnable {
                activeListener?.onUserTranscribed(rawTranscript)
            })

            generateAssistantResponse(rawTranscript)
        }
    }

    private fun generateAssistantResponse(userPrompt: String) {
        setState(DuplexState.GENERATING, "Thinking…")

        workerExecutor.execute {
            val responseAccumulator = StringBuilder()
            try {
                val fullResult = llmRunner.runInference(
                    prompt = userPrompt,
                    systemInstruction = activeSystemInstruction,
                    maxTokens = MainUiState.MAX_RESPONSE_TOKENS,
                    onChunk = { chunk ->
                        if (!isSessionActive.get() || isInterrupted.get()) return@runInference
                        responseAccumulator.append(chunk)
                        val listener = activeListener
                        mainDispatcher(Runnable {
                            listener?.onAssistantChunk(chunk)
                        })
                    }
                ).trim()

                if (!isSessionActive.get() || isInterrupted.get()) return@execute

                val speakableText = MarkdownFormatter.stripToPlainText(fullResult).ifBlank { fullResult }
                speakAndLoop(userPrompt, speakableText)
            } catch (e: Exception) {
                if (isSessionActive.get()) {
                    setState(DuplexState.ERROR, e.message ?: "Inference error")
                    activeListener?.onError(e)
                }
            }
        }
    }

    private fun speakAndLoop(userPrompt: String, assistantText: String) {
        if (!isSessionActive.get() || isInterrupted.get()) return
        setState(DuplexState.SPEAKING, "Speaking…")

        val utteranceId = "duplex_${System.currentTimeMillis()}"
        ttsSpeaker.speak(assistantText, utteranceId) {
            mainDispatcher(Runnable {
                activeListener?.onTurnComplete(userPrompt, assistantText)
                if (isSessionActive.get() && !isInterrupted.get()) {
                    startListeningTurn()
                }
            })
        }
    }
}
