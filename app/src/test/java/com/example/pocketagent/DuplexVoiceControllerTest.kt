package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor

class DuplexVoiceControllerTest {

    private lateinit var fakeRecorder: InAppAudioRecorder
    private lateinit var fakeWhisper: MainViewModelTest.FakeWhisperTranscriber
    private lateinit var fakeLlm: MainViewModelTest.FakeLlmInferenceRunner
    private lateinit var fakeTts: FakeTtsSpeaker
    private lateinit var controller: DuplexVoiceController

    class FakeAudioSource : AudioInputSource {
        var isStarted = false
        var isStopped = false
        var readCalls = 0

        override fun startRecording() {
            isStarted = true
        }

        override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
            readCalls++
            if (readCalls == 1) {
                // Populate speech samples
                for (i in 0 until minOf(size, 2048)) {
                    buffer[i] = (1000 + i).toShort()
                }
                return minOf(size, 2048)
            } else {
                // Silence (zeros) to trigger cutoff
                buffer.fill(0)
                return minOf(size, 2048)
            }
        }

        override fun stop() {
            isStopped = true
        }

        override fun close() {
            stop()
        }
    }

    class FakeTtsSpeaker : TtsSpeaker {
        var spokenTexts = mutableListOf<String>()
        var completeCallback: (() -> Unit)? = null
        var isStopped = false

        override fun isReady(): Boolean = true

        override fun speak(text: String, utteranceId: String, onComplete: () -> Unit) {
            spokenTexts.add(text)
            completeCallback = onComplete
            // Trigger complete immediately for tests
            onComplete()
        }

        override fun stop() {
            isStopped = true
        }
    }

    @Before
    fun setUp() {
        val fakeSource = FakeAudioSource()
        fakeRecorder = InAppAudioRecorder(
            buffer = AudioRecordingBuffer(maxDurationSeconds = 5),
            sourceFactory = { fakeSource }
        )
        fakeWhisper = MainViewModelTest.FakeWhisperTranscriber()
        fakeWhisper.transcriptToReturn = "What is the weather today?"

        fakeLlm = MainViewModelTest.FakeLlmInferenceRunner()
        fakeLlm.available = true
        fakeLlm.responseToReturn = "The weather is sunny and pleasant."

        fakeTts = FakeTtsSpeaker()

        controller = DuplexVoiceController(
            audioRecorder = fakeRecorder,
            whisperTranscriber = fakeWhisper,
            llmRunner = fakeLlm,
            ttsSpeaker = fakeTts,
            workerExecutor = Executor { it.run() },
            mainDispatcher = { it.run() },
        )
    }

    @Test
    fun `starts in IDLE state and transitions to LISTENING on startSession`() {
        assertEquals(DuplexVoiceController.DuplexState.IDLE, controller.state)
        assertFalse(controller.isActive)

        val states = mutableListOf<DuplexVoiceController.DuplexState>()
        controller.startSession(
            whisperContext = 100L,
            listener = object : DuplexVoiceController.Listener {
                override fun onStateChanged(state: DuplexVoiceController.DuplexState, statusMessage: String) {
                    states.add(state)
                }
                override fun onAmplitude(rms: Float) {}
                override fun onUserTranscribed(text: String) {}
                override fun onAssistantChunk(chunk: String) {}
                override fun onTurnComplete(userText: String, assistantText: String) {}
                override fun onError(error: Throwable) {}
            }
        )

        assertTrue(controller.isActive)
        assertTrue(states.contains(DuplexVoiceController.DuplexState.LISTENING))
    }

    @Test
    fun `stopSession stops recording, halts TTS, and sets state to IDLE`() {
        controller.startSession(
            whisperContext = 100L,
            listener = object : DuplexVoiceController.Listener {
                override fun onStateChanged(state: DuplexVoiceController.DuplexState, statusMessage: String) {}
                override fun onAmplitude(rms: Float) {}
                override fun onUserTranscribed(text: String) {}
                override fun onAssistantChunk(chunk: String) {}
                override fun onTurnComplete(userText: String, assistantText: String) {}
                override fun onError(error: Throwable) {}
            }
        )

        assertTrue(controller.isActive)
        controller.stopSession()

        assertFalse(controller.isActive)
        assertEquals(DuplexVoiceController.DuplexState.IDLE, controller.state)
        assertTrue(fakeTts.isStopped)
    }

    @Test
    fun `interrupt halts active TTS and re-arms listening`() {
        val steadyRecorder = InAppAudioRecorder(
            buffer = AudioRecordingBuffer(maxDurationSeconds = 5),
            sourceFactory = {
                object : AudioInputSource {
                    @Volatile var stopped = false
                    override fun startRecording() { stopped = false }
                    override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
                        if (stopped) return -1
                        Thread.sleep(20)
                        for (i in 0 until minOf(size, 512)) buffer[offset + i] = 1200
                        return minOf(size, 512)
                    }
                    override fun stop() { stopped = true }
                    override fun close() { stop() }
                }
            },
        )
        val steadyController = DuplexVoiceController(
            audioRecorder = steadyRecorder,
            whisperTranscriber = fakeWhisper,
            llmRunner = fakeLlm,
            ttsSpeaker = fakeTts,
            workerExecutor = Executor { it.run() },
            mainDispatcher = { it.run() },
        )
        steadyController.startSession(
            whisperContext = 100L,
            listener = object : DuplexVoiceController.Listener {
                override fun onStateChanged(state: DuplexVoiceController.DuplexState, statusMessage: String) {}
                override fun onAmplitude(rms: Float) {}
                override fun onUserTranscribed(text: String) {}
                override fun onAssistantChunk(chunk: String) {}
                override fun onTurnComplete(userText: String, assistantText: String) {}
                override fun onError(error: Throwable) {}
            }
        )

        steadyController.interrupt()
        assertTrue(fakeTts.isStopped)
        assertTrue(steadyController.isActive)
        // A cancelled recording run must not revive the pipeline after interrupt re-arms listening.
        val deadline = System.currentTimeMillis() + 300
        while (System.currentTimeMillis() < deadline) {
            assertEquals(DuplexVoiceController.DuplexState.LISTENING, steadyController.state)
            Thread.sleep(10)
        }
        steadyController.stopSession()
    }

    /** Builds a controller whose spoken turns use [instructionFor]. */
    private fun controllerWithInstruction(instructionFor: (String, String) -> String): DuplexVoiceController =
        DuplexVoiceController(
            audioRecorder = fakeRecorder,
            whisperTranscriber = fakeWhisper,
            llmRunner = fakeLlm,
            ttsSpeaker = fakeTts,
            workerExecutor = Executor { it.run() },
            mainDispatcher = { it.run() },
            instructionFor = instructionFor,
        )

    private fun runOneVoiceTurn(controller: DuplexVoiceController): String? {
        var spoken: String? = null
        controller.startSession(
            whisperContext = 100L,
            listener = object : DuplexVoiceController.Listener {
                override fun onStateChanged(state: DuplexVoiceController.DuplexState, statusMessage: String) {}
                override fun onAmplitude(rms: Float) {}
                override fun onUserTranscribed(text: String) {}
                override fun onAssistantChunk(chunk: String) {}
                override fun onTurnComplete(userText: String, assistantText: String) { spoken = assistantText }
                override fun onError(error: Throwable) {}
            }
        )
        // Let the recorder thread deliver its synthetic speech turn.
        val deadline = System.currentTimeMillis() + 5_000
        while (spoken == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        controller.stopSession()
        return spoken
    }

    @Test
    fun `spoken turns use the intent-aware instruction from the view model`() {
        var receivedSpokenPrompt: String? = null
        val voiceController = controllerWithInstruction { spokenPrompt, _ ->
            receivedSpokenPrompt = spokenPrompt
            "INTENT-AWARE DIRECTIVE"
        }

        assertEquals("The weather is sunny and pleasant.", runOneVoiceTurn(voiceController))

        // The directive built for the spoken turn is what the model actually sees.
        assertEquals("INTENT-AWARE DIRECTIVE", fakeLlm.lastSystemInstruction)
        assertEquals("What is the weather today?", receivedSpokenPrompt)
    }

    @Test
    fun `spoken answer is clamped once instead of looping forever`() {
        // A degenerate model repeating the same line far past the answer budget: exactly the
        // reported failure mode, where the same two lines are printed forever.
        val loopingLine = "Checking the weather.\n"
        fakeLlm.chunksToStream = List(2_000) { loopingLine }
        val voiceController = controllerWithInstruction { _, _ -> "BASE" }

        var largestPartial = 0
        var spoken: String? = null
        voiceController.startSession(
            whisperContext = 100L,
            listener = object : DuplexVoiceController.Listener {
                override fun onStateChanged(state: DuplexVoiceController.DuplexState, statusMessage: String) {}
                override fun onAmplitude(rms: Float) {}
                override fun onUserTranscribed(text: String) {}
                override fun onAssistantChunk(chunk: String) {
                    largestPartial = maxOf(largestPartial, chunk.length)
                }
                override fun onTurnComplete(userText: String, assistantText: String) { spoken = assistantText }
                override fun onError(error: Throwable) {}
            }
        )
        val deadline = System.currentTimeMillis() + 5_000
        while (spoken == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        voiceController.stopSession()

        // The model produced 44,000 characters of the same line.
        assertEquals(44_000, loopingLine.length * 2_000)

        // The runaway guard stops feeding the UI long before that.
        assertTrue(
            "streamed text must stay under the answer budget, got $largestPartial",
            largestPartial <= RunawayGuard.MAX_ANSWER_CHARS
        )

        assertNotNull(spoken)
        val answer = spoken!!
        assertTrue("spoken answer must not be empty", answer.isNotBlank())
        assertTrue("spoken answer must be clamped, was ${answer.length} chars", answer.length <= 600)
        assertTrue(
            "the looped line must appear a handful of times, not hundreds (count=${answer.split("Checking the weather.").size - 1}, answer=<<<$answer>>>)",
            answer.split("Checking the weather.").size - 1 <= 3
        )
    }

    @Test
    fun `voice turn with no instruction hook still answers`() {
        // Defensive: the default instructionFor must not break normal spoken turns.
        assertEquals("The weather is sunny and pleasant.", runOneVoiceTurn(controller))
    }
}
