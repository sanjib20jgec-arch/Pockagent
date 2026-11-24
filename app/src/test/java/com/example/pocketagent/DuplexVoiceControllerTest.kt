package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        controller.interrupt()
        assertTrue(fakeTts.isStopped)
        assertTrue(controller.isActive)
        assertEquals(DuplexVoiceController.DuplexState.LISTENING, controller.state)
    }
}
