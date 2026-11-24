package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor

class MainViewModelTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var fakeLlmRunner: FakeLlmInferenceRunner
    private lateinit var fakeWhisperTranscriber: FakeWhisperTranscriber
    private lateinit var viewModel: MainViewModel

    class FakeLlmInferenceRunner : LlmInferenceRunner {
        var available: Boolean = false
        var chunksToStream: List<String> = listOf("1. Step one\n", "2. Step two")
        var responseToReturn: String
            get() = chunksToStream.joinToString("")
            set(value) { chunksToStream = listOf(value) }
        var shouldThrow: Boolean = false
        var lastPrompt: String? = null
        var lastSystemInstruction: String? = null
        var isClosed: Boolean = false

        override fun initialize() {}
        override fun isAvailable(): Boolean = available

        override fun runInference(
            prompt: String,
            systemInstruction: String,
            maxTokens: Int,
            onChunk: (String) -> Unit,
        ): String {
            lastPrompt = prompt
            lastSystemInstruction = systemInstruction
            if (shouldThrow) throw IllegalStateException("Simulated LLM engine failure")
            val sb = StringBuilder()
            for (chunk in chunksToStream) {
                sb.append(chunk)
                onChunk(chunk)
            }
            return sb.toString()
        }

        override fun close() {
            isClosed = true
        }
    }

    class FakeWhisperTranscriber : WhisperTranscriber {
        var nextContext: Long = 100L
        var shouldFailLoad: Boolean = false
        var transcriptToReturn: String = "Transcribed user request"
        var releasedContexts = mutableListOf<Long>()

        override fun loadModel(modelPath: String): Long {
            if (shouldFailLoad) return 0L
            return nextContext++
        }

        override fun transcribe(context: Long, samples: FloatArray, maxChars: Int): String {
            return transcriptToReturn
        }

        override fun release(context: Long) {
            releasedContexts.add(context)
        }
    }

    @Before
    fun setUp() {
        filesDir = tempDir.newFolder("files")
        cacheDir = tempDir.newFolder("cache")
        fakeLlmRunner = FakeLlmInferenceRunner()
        fakeWhisperTranscriber = FakeWhisperTranscriber()

        viewModel = MainViewModel(
            filesDir = filesDir,
            cacheDir = cacheDir,
            modelFileStore = ModelFileStore(filesDir),
            whisperModelStore = WhisperModelStore(filesDir),
            llmRunner = fakeLlmRunner,
            whisperTranscriber = fakeWhisperTranscriber,
            workerExecutor = Executor { it.run() },
            mainDispatcher = { it.run() },
        )
    }

    @Test
    fun rejectsModelImportWithInvalidExtension() {
        viewModel.importModel("weights.bin") {
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }

        val state = viewModel.uiState.value
        assertFalse(state.isModelReady)
        assertEquals("Choose a LiteRT-LM model file ending in .litertlm.", state.statusMessage)
    }

    @Test
    fun importsValidModelSuccessfully() {
        val modelBytes = "Gemma model weights".toByteArray()
        viewModel.importModel("gemma-1b.litertlm") {
            ByteArrayInputStream(modelBytes)
        }

        val state = viewModel.uiState.value
        assertTrue(state.isModelReady)
        assertEquals(modelBytes.size.toLong(), state.modelSizeBytes)
        assertTrue(state.modelText.contains("MODEL READY"))
        assertTrue(File(filesDir, "model.litertlm").exists())
    }

    @Test
    fun importsTextAttachmentsAndEnforcesLimit() {
        val file1 = "Small note"
        val file2 = "X".repeat(15_000)

        viewModel.importTextFiles(
            listOf(
                "note.txt" to ByteArrayInputStream(file1.toByteArray()),
                "big.txt" to ByteArrayInputStream(file2.toByteArray()),
            )
        )

        val state = viewModel.uiState.value
        assertTrue(state.hasAttachment)
        assertEquals(2, state.attachedNames.size)
        assertTrue(state.attachedContext.length <= MainUiState.MAX_ATTACHMENT_CHARS)
        assertTrue(state.attachedContext.contains("--- note.txt ---"))
        assertTrue(state.attachedContext.contains("--- big.txt ---"))
    }

    @Test
    fun clearsAttachmentsCleanly() {
        viewModel.importTextFiles(
            listOf("note.txt" to ByteArrayInputStream("Sample text".toByteArray()))
        )
        assertTrue(viewModel.uiState.value.hasAttachment)

        viewModel.clearAttachments()

        val state = viewModel.uiState.value
        assertFalse(state.hasAttachment)
        assertEquals("", state.attachedContext)
        assertEquals(1L, state.attachmentGeneration)
    }

    @Test
    fun executesInferenceAndFormatsStreamingResponse() {
        // Prepare model and input
        fakeLlmRunner.available = true
        fakeLlmRunner.chunksToStream = listOf(
            "Here are the steps:\n",
            "1. **Open Settings**\n",
            "2. Turn on Wi-Fi\n",
        )

        viewModel.onTaskInputChanged("Help me turn on wifi")
        viewModel.suggestSteps()

        val state = viewModel.uiState.value
        assertEquals(MainUiState.GenerationStatus.COMPLETED, state.generationStatus)
        assertTrue(state.answerIsComplete)
        assertTrue(state.isOutputVisible)
        assertNotNull(fakeLlmRunner.lastPrompt)
        assertTrue(fakeLlmRunner.lastPrompt!!.contains("Help me turn on wifi"))

        // Display formatted text: ** stripped, numbered list kept
        assertTrue(state.formattedAnswer.contains("1. Open Settings"))
        assertTrue(state.formattedAnswer.contains("2. Turn on Wi-Fi"))

        // Plain text stripped for TTS:
        assertTrue(state.plainTextAnswer.contains("1. Open Settings"))
        assertFalse(state.plainTextAnswer.contains("**"))
    }

    @Test
    fun handlesInferenceFailureGracefully() {
        fakeLlmRunner.available = true
        fakeLlmRunner.shouldThrow = true

        viewModel.onTaskInputChanged("Crash test")
        viewModel.suggestSteps()

        val state = viewModel.uiState.value
        assertEquals(MainUiState.GenerationStatus.ERROR, state.generationStatus)
        assertFalse(state.isOutputVisible)
        assertFalse(state.answerIsComplete)
        assertTrue(state.statusMessage.contains("Could not generate suggestions"))
    }

    @Test
    fun validatesAnswerBeforeSavingAndExportsToStream() {
        // Before generation: saving not allowed
        assertNull(viewModel.prepareSaveAnswer())

        // Simulate complete answer
        fakeLlmRunner.available = true
        fakeLlmRunner.chunksToStream = listOf("1. Step one\n2. Step two")
        viewModel.onTaskInputChanged("Task")
        viewModel.suggestSteps()

        val answerToSave = viewModel.prepareSaveAnswer()
        assertNotNull(answerToSave)
        assertTrue(answerToSave!!.contains("1. Step one"))

        // Export to output stream
        val outStream = ByteArrayOutputStream()
        viewModel.saveAnswerToStream(outStream, answerToSave)

        val written = outStream.toString(Charsets.UTF_8.name())
        assertTrue(written.contains("1. Step one"))
        assertTrue(written.endsWith("\n"))
        assertTrue(viewModel.uiState.value.statusMessage.contains("Answer saved"))
    }

    @Test
    fun importsAndRemovesWhisperModelSafely() {
        val dummyWeights = "ggml valid whisper model bytes".toByteArray()

        // 1. Rejects wrong extension
        viewModel.importWhisperModel("whisper.litertlm") {
            ByteArrayInputStream(dummyWeights)
        }
        assertFalse(viewModel.uiState.value.isWhisperModelReady)

        // 2. Imports .bin model
        viewModel.importWhisperModel("ggml-base.bin") {
            ByteArrayInputStream(dummyWeights)
        }
        assertTrue(viewModel.uiState.value.isWhisperModelReady)
        assertTrue(viewModel.uiState.value.canTranscribeWav)

        // 3. Removes model
        viewModel.removeWhisperModel()
        assertFalse(viewModel.uiState.value.isWhisperModelReady)
        assertFalse(viewModel.uiState.value.canTranscribeWav)
        assertEquals(1, fakeWhisperTranscriber.releasedContexts.size)
    }

    @Test
    fun closesResourcesOnClose() {
        viewModel.close()
        assertTrue(fakeLlmRunner.isClosed)
    }

    @Test
    fun sessionLifecycleCreateSwitchRenamePinDelete() {
        // 1. Create new session
        viewModel.createNewSession()
        val s1Id = viewModel.uiState.value.currentSessionId
        assertNotNull(s1Id)

        // 2. Rename session
        viewModel.renameSession(s1Id!!, "Custom Project Chat")
        assertEquals("Custom Project Chat", viewModel.uiState.value.currentSessionTitle)

        // 3. Pin session
        viewModel.pinSession(s1Id, true)
        assertTrue(viewModel.uiState.value.isSessionPinned)

        // 4. Create second session
        viewModel.createNewSession()
        val s2Id = viewModel.uiState.value.currentSessionId
        assertNotNull(s2Id)
        assertFalse(s1Id == s2Id)

        // 5. Switch back to s1
        viewModel.switchSession(s1Id)
        assertEquals(s1Id, viewModel.uiState.value.currentSessionId)
        assertEquals("Custom Project Chat", viewModel.uiState.value.currentSessionTitle)
        assertTrue(viewModel.uiState.value.isSessionPinned)

        // 6. Delete s1
        viewModel.deleteSession(s1Id)
        assertFalse(viewModel.uiState.value.currentSessionId == s1Id)
    }

    @Test
    fun suggestsStepsAutoSavesTurnsToSessionAndSupportsRegenerate() {
        fakeLlmRunner.available = true
        fakeLlmRunner.responseToReturn = "1. Step one\n2. Step two"

        viewModel.createNewSession()
        val sessId = viewModel.uiState.value.currentSessionId!!

        viewModel.updateTaskInput("Help me build offline search")
        viewModel.suggestSteps()

        // Verify generation completed
        val state = viewModel.uiState.value
        assertEquals("1. Step one\n2. Step two", state.generatedAnswer)
        assertTrue(state.canRegenerate)
        assertEquals("Help me build offline search", state.lastUserPrompt)

        // Verify export contains turns
        val md = viewModel.exportSession(sessId, asMarkdown = true)
        assertNotNull(md)
        assertTrue(md!!.contains("Help me build offline search"))
        assertTrue(md.contains("1. Step one"))

        // Test regenerate
        fakeLlmRunner.responseToReturn = "1. Better step one\n2. Better step two"
        viewModel.regenerateLastResponse()
        assertEquals("1. Better step one\n2. Better step two", viewModel.uiState.value.generatedAnswer)
    }

    @Test
    fun stopGenerationAbortsStreamingCleanly() {
        fakeLlmRunner.available = true
        viewModel.updateTaskInput("Long generation task")
        viewModel.suggestSteps()

        viewModel.stopGeneration()
        assertFalse(viewModel.uiState.value.isGenerating)
    }

    @Test
    fun suggestStepsExtractsDocumentCitationsFromAttachedContext() {
        fakeLlmRunner.available = true
        fakeLlmRunner.responseToReturn = "1. Follow instructions from attached document."

        viewModel.createNewSession()
        viewModel.importTextFiles(
            listOf("specs.txt" to java.io.ByteArrayInputStream("System Architecture: Offline-first assistant with strict zero telemetry constraints.".toByteArray()))
        )
        viewModel.updateTaskInput("What are the system architecture constraints?")
        viewModel.suggestSteps()

        val state = viewModel.uiState.value
        assertEquals("1. Follow instructions from attached document.", state.generatedAnswer)
        assertTrue(state.documentCitations.isNotEmpty())
        assertEquals("specs.txt", state.documentCitations[0].documentName)
    }

    @Test
    fun suggestStepsExtractsAndPersistsUserMemoryFacts() {
        fakeLlmRunner.available = true
        fakeLlmRunner.responseToReturn = "1. Understood, I will remember."

        viewModel.createNewSession()
        viewModel.updateTaskInput("I prefer Kotlin and clean architectural code.")
        viewModel.suggestSteps()

        val state = viewModel.uiState.value
        assertTrue(state.userMemories.isNotEmpty())
        assertTrue(state.userMemories.any { it.content.contains("Kotlin", ignoreCase = true) })

        val enrichedInstruction = fakeLlmRunner.lastSystemInstruction
        assertNotNull(enrichedInstruction)
        assertTrue(enrichedInstruction!!.contains("[User Profile & Stored Preferences]:"))
        assertTrue(enrichedInstruction.contains("Kotlin"))
    }

    @Test
    fun manualMemoryManagementAddDeleteClear() {
        viewModel.addMemory("Prefers dark theme", UserMemoryStore.MemoryCategory.PREFERENCE)
        assertEquals(1, viewModel.uiState.value.userMemories.size)
        val memId = viewModel.uiState.value.userMemories[0].id

        viewModel.addMemory("Lives in Kolkata", UserMemoryStore.MemoryCategory.PROFILE)
        assertEquals(2, viewModel.uiState.value.userMemories.size)

        viewModel.deleteMemory(memId)
        assertEquals(1, viewModel.uiState.value.userMemories.size)
        assertEquals("Lives in Kolkata", viewModel.uiState.value.userMemories[0].content)

        viewModel.clearAllMemories()
        assertTrue(viewModel.uiState.value.userMemories.isEmpty())
    }

    @Test
    fun memoryDisabledPreventsExtractionAndInjection() {
        fakeLlmRunner.available = true
        fakeLlmRunner.responseToReturn = "1. Answer without memory."

        viewModel.setMemoryEnabled(false)
        assertFalse(viewModel.uiState.value.isMemoryEnabled)

        viewModel.updateTaskInput("I prefer Rust for backend development.")
        viewModel.suggestSteps()

        assertTrue(viewModel.uiState.value.userMemories.isEmpty())
        assertEquals(MainUiState.SYSTEM_INSTRUCTION, fakeLlmRunner.lastSystemInstruction)
    }
}
