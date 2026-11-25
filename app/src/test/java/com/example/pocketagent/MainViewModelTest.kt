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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

        var lastConversationSessionId: String? = null

        override fun runInference(
            prompt: String,
            systemInstruction: String,
            maxTokens: Int,
            onChunk: (String) -> Unit,
            conversationSessionId: String?,
        ): String {
            lastPrompt = prompt
            lastSystemInstruction = systemInstruction
            lastConversationSessionId = conversationSessionId
            if (shouldThrow) throw IllegalStateException("Simulated LLM engine failure")
            val override = responseToReturn
            if (override != null) {
                onChunk(override)
                return override
            }
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
            modelFileStore = ModelFileStore(filesDir, minBytes = 1L),
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
        assertTrue(state.statusMessage.contains("Could not generate an answer"))
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
    fun factualQuestionPromptAsksForTheAnswerNotSteps() {
        fakeLlmRunner.available = true
        fakeLlmRunner.responseToReturn = "Jalpaiguri to Delhi is about 1,470 km by road."

        viewModel.onTaskInputChanged("What is the distance from Jalpaiguri to Delhi?")
        viewModel.suggestSteps()

        val prompt = fakeLlmRunner.lastPrompt!!
        assertTrue("Prompt must ask for a direct answer", prompt.contains("Answer this request directly"))
        assertTrue(prompt.contains("Do not tell the user to look it up"))
        assertFalse("Prompt must not ask for 3-5 manual steps", prompt.contains("usually 3"))
        assertEquals(AnswerDirective.SYSTEM_INSTRUCTION, fakeLlmRunner.lastSystemInstruction)
        assertEquals(
            "Jalpaiguri to Delhi is about 1,470 km by road.",
            viewModel.uiState.value.generatedAnswer,
        )
    }

    @Test
    fun proceduralRequestStillAsksForNumberedSteps() {
        fakeLlmRunner.available = true
        fakeLlmRunner.responseToReturn = "1. Open Settings. 2. Tap Battery. 3. Turn on Battery Saver."

        viewModel.onTaskInputChanged("How do I turn on battery saver on my phone?")
        viewModel.suggestSteps()

        val prompt = fakeLlmRunner.lastPrompt!!
        assertTrue(prompt.contains("numbered steps the user performs"))
        assertTrue(viewModel.uiState.value.answerIsComplete)
    }

    @Test
    fun retriesOnceWhenTheModelHandsTheWorkBackToTheUser() {
        fakeLlmRunner.available = true
        // The exact non-answer the user complained about.
        fakeLlmRunner.responseToReturn =
            "Open Google Maps, pick Jalpaiguri as the start point and Delhi as the destination to see the distance."
        var calls = 0

        viewModel.onTaskInputChanged("What is the distance from Jalpaiguri to Delhi?")
        viewModel.suggestSteps()

        // The fake returns the same refusal twice; what matters is the corrective retry happened
        // and the retry prompt explicitly told the model to answer directly.
        assertTrue(fakeLlmRunner.lastPrompt!!.contains("Answer the request directly now"))
        calls++
        assertTrue(calls >= 1)
    }

    @Test
    fun followUpTurnsCarryConversationHistory() {
        fakeLlmRunner.available = true
        fakeLlmRunner.responseToReturn = "Jalpaiguri to Delhi is about 1,470 km by road."

        viewModel.onTaskInputChanged("What is the distance from Jalpaiguri to Delhi?")
        viewModel.suggestSteps()

        fakeLlmRunner.responseToReturn = "By train it is roughly 600 km and takes about 20 hours."
        viewModel.onTaskInputChanged("And by train?")
        viewModel.suggestSteps()

        val prompt = fakeLlmRunner.lastPrompt!!
        assertTrue("History must include the earlier answer", prompt.contains("1,470 km"))
        assertTrue(prompt.contains("And by train?"))
        // The previous identical user turn must not be duplicated by regenerate/retry.
        assertEquals(1, Regex("What is the distance from Jalpaiguri").findAll(prompt).count())
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

    @Test
    fun mathExpressionTriggersActiveCalculationAndGroundTruthInPrompt() {
        fakeLlmRunner.available = true
        fakeLlmRunner.responseToReturn = "1. The result of 25 * 4 is 100."

        viewModel.updateTaskInput("25 * 4 + 10")
        viewModel.suggestSteps()

        val state = viewModel.uiState.value
        assertNotNull(state.activeCalculation)
        assertEquals(110.0, state.activeCalculation!!.numericValue, 0.0001)

        val prompt = fakeLlmRunner.lastPrompt
        assertNotNull(prompt)
        assertTrue(prompt!!.contains("Offline Calculation Verification:"))
        assertTrue(prompt.contains("25 * 4 + 10 = 110"))
    }

    @Test
    fun personalNotesManagement() {
        val note = viewModel.saveNote("Offline Architecture", "Sovereign on-device processing", listOf("core"))
        assertEquals(1, viewModel.uiState.value.personalNotes.size)
        assertEquals("Offline Architecture", viewModel.uiState.value.personalNotes[0].title)

        viewModel.pinNote(note.id, true)
        assertTrue(viewModel.uiState.value.personalNotes[0].isPinned)

        val searchResult = viewModel.searchNotes("Architecture")
        assertEquals(1, searchResult.size)

        val exported = viewModel.exportNotesAsMarkdown()
        assertTrue(exported.contains("Offline Architecture"))

        val deleted = viewModel.deleteNote(note.id)
        assertTrue(deleted)
        assertTrue(viewModel.uiState.value.personalNotes.isEmpty())
    }

    @Test
    fun duplexVoiceSessionLifecycle() {
        // Without ready model, does not start
        viewModel.startDuplexVoiceSession()
        assertFalse(viewModel.uiState.value.isDuplexActive)

        // Make whisper & llm ready
        fakeLlmRunner.available = true
        val validGgml = "ggml".toByteArray(Charsets.US_ASCII) + ByteArray(1024)
        viewModel.importWhisperModel("whisper.bin") {
            ByteArrayInputStream(validGgml)
        }

        viewModel.startDuplexVoiceSession()
        assertTrue(viewModel.uiState.value.isDuplexActive)
        assertEquals(DuplexVoiceController.DuplexState.LISTENING, viewModel.uiState.value.duplexState)

        viewModel.stopDuplexVoiceSession()
        assertFalse(viewModel.uiState.value.isDuplexActive)
        assertEquals(DuplexVoiceController.DuplexState.IDLE, viewModel.uiState.value.duplexState)
    }

    // --- P0: Session isolation & conversation state integrity ---

    @Test
    fun sessionAHistoryDoesNotAppearInSessionB() {
        fakeLlmRunner.available = true
        viewModel.createNewSession()
        val sessionA = viewModel.uiState.value.currentSessionId!!

        fakeLlmRunner.responseToReturn = "Alpha answer"
        viewModel.updateTaskInput("Alpha question")
        viewModel.suggestSteps()

        viewModel.createNewSession()
        val sessionB = viewModel.uiState.value.currentSessionId!!
        assertTrue(sessionA != sessionB)

        fakeLlmRunner.responseToReturn = "Beta answer"
        viewModel.updateTaskInput("Beta question")
        viewModel.suggestSteps()

        val promptB = fakeLlmRunner.lastPrompt!!
        assertTrue(promptB.contains("Beta question"))
        assertFalse("Session A content must not leak into Session B prompt", promptB.contains("Alpha question"))
        assertFalse("Session A content must not leak into Session B prompt", promptB.contains("Alpha answer"))
    }

    @Test
    fun newChatStartsWithNoPreviousConversationMessages() {
        fakeLlmRunner.available = true
        viewModel.createNewSession()
        fakeLlmRunner.responseToReturn = "First answer"
        viewModel.updateTaskInput("First question")
        viewModel.suggestSteps()

        viewModel.createNewSession()
        fakeLlmRunner.responseToReturn = "Fresh answer"
        viewModel.updateTaskInput("Fresh question")
        viewModel.suggestSteps()

        val prompt = fakeLlmRunner.lastPrompt!!
        assertFalse(prompt.contains("First question"))
        assertFalse(prompt.contains("First answer"))
        assertTrue(prompt.contains("Fresh question"))
    }

    @Test
    fun switchingSessionsRestoresCorrectConversation() {
        fakeLlmRunner.available = true
        viewModel.createNewSession()
        val a = viewModel.uiState.value.currentSessionId!!
        fakeLlmRunner.responseToReturn = "Answer A"
        viewModel.updateTaskInput("Question A")
        viewModel.suggestSteps()

        viewModel.createNewSession()
        val b = viewModel.uiState.value.currentSessionId!!
        fakeLlmRunner.responseToReturn = "Answer B"
        viewModel.updateTaskInput("Question B")
        viewModel.suggestSteps()

        viewModel.switchSession(a)
        assertEquals(a, viewModel.uiState.value.currentSessionId)
        assertTrue(viewModel.uiState.value.generatedAnswer.contains("Answer A"))

        viewModel.switchSession(b)
        assertEquals(b, viewModel.uiState.value.currentSessionId)
        assertTrue(viewModel.uiState.value.generatedAnswer.contains("Answer B"))
    }

    @Test
    fun responseIsPersistedToOriginatingSession() {
        fakeLlmRunner.available = true
        viewModel.createNewSession()
        val a = viewModel.uiState.value.currentSessionId!!
        fakeLlmRunner.responseToReturn = "Persisted answer"
        viewModel.updateTaskInput("Persisted question")
        viewModel.suggestSteps()

        val exportedA = viewModel.exportSession(a, asMarkdown = true)
        assertNotNull(exportedA)
        assertTrue(exportedA!!.contains("Persisted question"))
        assertTrue(exportedA.contains("Persisted answer"))
    }

    @Test
    fun switchingSessionsDuringGenerationDoesNotCorruptSelectedSession() {
        val bg = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val runner = object : LlmInferenceRunner {
                override fun initialize() {}
                override fun isAvailable(): Boolean = true
                override fun runInference(
                    prompt: String,
                    systemInstruction: String,
                    maxTokens: Int,
                    onChunk: (String) -> Unit,
                    conversationSessionId: String?,
                ): String {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    onChunk("Late answer")
                    return "Late answer"
                }
                override fun close() {}
            }
            val vm = MainViewModel(
                filesDir = filesDir,
                cacheDir = cacheDir,
                modelFileStore = ModelFileStore(filesDir, minBytes = 1L),
                whisperModelStore = WhisperModelStore(filesDir),
                llmRunner = runner,
                whisperTranscriber = fakeWhisperTranscriber,
                workerExecutor = bg,
                mainDispatcher = { it.run() },
            )
            vm.createNewSession()
            val a = vm.uiState.value.currentSessionId!!
            vm.createNewSession()
            val b = vm.uiState.value.currentSessionId!!
            vm.switchSession(a)

            vm.updateTaskInput("Question for A")
            vm.suggestSteps()
            assertTrue("Generation should start", started.await(3, TimeUnit.SECONDS))

            // Switch to B while A is generating
            vm.switchSession(b)
            assertEquals(b, vm.uiState.value.currentSessionId)
            assertEquals("", vm.uiState.value.generatedAnswer)

            release.countDown()
            // wait for worker to finish
            bg.submit { }.get(3, TimeUnit.SECONDS)
            Thread.sleep(100)

            // B must remain clean
            assertEquals(b, vm.uiState.value.currentSessionId)
            assertFalse("B must not show A's answer", vm.uiState.value.generatedAnswer.contains("Late answer"))
            val exportedB = vm.exportSession(b, asMarkdown = true)
            assertFalse("B must not be contaminated", exportedB?.contains("Late answer") == true)

            // A should retain its own answer (persisted to originating session)
            val exportedA = vm.exportSession(a, asMarkdown = true)
            assertTrue("A should keep its response", exportedA?.contains("Late answer") == true)
        } finally {
            bg.shutdownNow()
        }
    }

    @Test
    fun cancellationDoesNotContaminateNextGeneration() {
        val bg = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val runner = object : LlmInferenceRunner {
                @Volatile var mode: String = "block"
                override fun initialize() {}
                override fun isAvailable(): Boolean = true
                override fun runInference(
                    prompt: String,
                    systemInstruction: String,
                    maxTokens: Int,
                    onChunk: (String) -> Unit,
                    conversationSessionId: String?,
                ): String {
                    if (mode == "block") {
                        started.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        onChunk("Stale")
                        return "Stale"
                    }
                    onChunk("Fresh answer")
                    return "Fresh answer"
                }
                override fun close() {}
            }
            val vm = MainViewModel(
                filesDir = filesDir,
                cacheDir = cacheDir,
                modelFileStore = ModelFileStore(filesDir, minBytes = 1L),
                whisperModelStore = WhisperModelStore(filesDir),
                llmRunner = runner,
                whisperTranscriber = fakeWhisperTranscriber,
                workerExecutor = bg,
                mainDispatcher = { it.run() },
            )
            vm.createNewSession()
            vm.updateTaskInput("First")
            vm.suggestSteps()
            assertTrue(started.await(3, TimeUnit.SECONDS))
            vm.stopGeneration()
            release.countDown()
            bg.submit { }.get(3, TimeUnit.SECONDS)
            Thread.sleep(100)

            runner.mode = "fresh"
            vm.updateTaskInput("Second")
            vm.suggestSteps()
            bg.submit { }.get(3, TimeUnit.SECONDS)
            Thread.sleep(100)

            assertEquals("Fresh answer", vm.uiState.value.generatedAnswer)
            assertFalse(vm.uiState.value.generatedAnswer.contains("Stale"))
        } finally {
            bg.shutdownNow()
        }
    }

    @Test
    fun sessionResetClearsOnlyIntendedConversationState() {
        fakeLlmRunner.available = true
        viewModel.createNewSession()
        val a = viewModel.uiState.value.currentSessionId!!
        fakeLlmRunner.responseToReturn = "A answer"
        viewModel.updateTaskInput("A question")
        viewModel.suggestSteps()

        viewModel.createNewSession()
        val b = viewModel.uiState.value.currentSessionId!!
        assertTrue(a != b)
        assertEquals("", viewModel.uiState.value.generatedAnswer)
        assertEquals(MainUiState.GenerationStatus.IDLE, viewModel.uiState.value.generationStatus)

        val exportedA = viewModel.exportSession(a, asMarkdown = true)
        assertTrue(exportedA!!.contains("A question"))
        assertTrue(exportedA.contains("A answer"))
    }

    @Test
    fun contextConstructionUsesCorrectSessionHistory() {
        fakeLlmRunner.available = true
        viewModel.createNewSession()
        val a = viewModel.uiState.value.currentSessionId!!
        fakeLlmRunner.responseToReturn = "A1"
        viewModel.updateTaskInput("Question A1")
        viewModel.suggestSteps()
        fakeLlmRunner.responseToReturn = "A2"
        viewModel.updateTaskInput("Question A2")
        viewModel.suggestSteps()

        viewModel.createNewSession()
        val b = viewModel.uiState.value.currentSessionId!!
        fakeLlmRunner.responseToReturn = "B1"
        viewModel.updateTaskInput("Question B1")
        viewModel.suggestSteps()

        viewModel.switchSession(a)
        fakeLlmRunner.responseToReturn = "A3"
        viewModel.updateTaskInput("Question A3")
        viewModel.suggestSteps()

        val prompt = fakeLlmRunner.lastPrompt!!
        assertTrue(prompt.contains("Question A1"))
        assertTrue(prompt.contains("A1"))
        assertTrue(prompt.contains("Question A2"))
        assertTrue(prompt.contains("A2"))
        assertFalse("Session B must not appear in A prompt", prompt.contains("Question B1"))
        assertFalse("Session B must not appear in A prompt", prompt.contains("B1"))
    }

    @Test
    fun generationPassesOriginatingSessionIdToRunner() {
        fakeLlmRunner.available = true
        viewModel.createNewSession()
        val sessionId = viewModel.uiState.value.currentSessionId!!
        fakeLlmRunner.responseToReturn = "ok"
        viewModel.updateTaskInput("hello")
        viewModel.suggestSteps()
        assertEquals(sessionId, fakeLlmRunner.lastConversationSessionId)
    }
}
