package com.example.pocketagent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InferenceEngineManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class FakeEngine(val name: String) : AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class FakeConversation : ConversationHandle {
        var closed = false
        var cancelCalls = 0

        override fun sendMessage(
            prompt: String,
            callback: com.google.ai.edge.litertlm.MessageCallback,
            maxOutputTokens: Int,
        ) = Unit

        override fun cancel() {
            cancelCalls++
        }

        override fun close() {
            closed = true
        }
    }

    private fun newModelAndCache(): Pair<File, File> {
        val model = temporaryFolder.newFile("model.litertlm").apply { writeText("stub-model") }
        val cache = temporaryFolder.newFolder("cache")
        return model to cache
    }

    @Test
    fun fallsBackToCpuWhenGpuInitFails() {
        val (model, cache) = newModelAndCache()
        val events = mutableListOf<String>()
        val manager = InferenceEngineManager<FakeEngine>(
            engineFactory = { isGpu, _, _ ->
                if (isGpu) error("gpu unavailable") else FakeEngine("cpu")
            }
        )
        manager.onFallback = { events.add(it) }

        val engine = manager.getOrInitializeEngine(model, cache)

        assertEquals("cpu", engine.name)
        assertEquals(ActiveBackend.CPU, manager.activeBackend)
        assertTrue(events.any { it.contains("falling back to CPU") })
        assertNotNull(manager.lastFallbackReason)
        manager.close()
    }

    @Test
    fun reusesEngineInstanceAndCachesConversation() {
        val (model, cache) = newModelAndCache()
        var engineCreates = 0
        var conversationCreates = 0
        val manager = InferenceEngineManager<FakeEngine>(
            engineFactory = { _, _, _ -> engineCreates++; FakeEngine("cpu") },
            conversationFactory = { _, _ ->
                conversationCreates++
                FakeConversation()
            }
        )

        manager.getOrInitializeEngine(model, cache)
        manager.getOrInitializeEngine(model, cache)
        val key = manager.conversationKey("s1", model.absolutePath, "sys", 0)
        manager.getOrCreateConversation(key, "sys")
        assertTrue(manager.isConversationWarm(key))
        manager.getOrCreateConversation(key, "sys")

        assertEquals(1, engineCreates)
        assertEquals(1, conversationCreates)
        assertEquals(1, manager.conversationReuseCount)
        manager.close()
        assertFalse(manager.isConversationWarm(key))
    }

    @Test
    fun invalidateConversationClearsCachedHandleAndTokenCounter() {
        val (model, cache) = newModelAndCache()
        var conversationCreates = 0
        val manager = InferenceEngineManager<FakeEngine>(
            engineFactory = { _, _, _ -> FakeEngine("cpu") },
            conversationFactory = { _, _ ->
                conversationCreates++
                FakeConversation()
            }
        )
        manager.getOrInitializeEngine(model, cache)
        val key = manager.conversationKey("s1", model.absolutePath, "sys", 0)
        manager.getOrCreateConversation(key, "sys")
        manager.recordConversationTurnTokens(1250)
        assertEquals(1250, manager.cachedConversationTokens)

        manager.invalidateConversation("test")
        assertFalse(manager.isConversationWarm(key))
        assertEquals(0, manager.cachedConversationTokens)
        manager.getOrCreateConversation(key, "sys")

        assertEquals(2, conversationCreates)
        assertEquals("test", manager.lastInvalidationReason)
        manager.close()
    }

    @Test
    fun switchBackendTearsDownOnlyMismatchedActiveEngine() {
        val (model, cache) = newModelAndCache()
        val created = mutableListOf<FakeEngine>()
        val manager = InferenceEngineManager<FakeEngine>(
            preference = BackendPreference.GPU_PREFERRED,
            engineFactory = { isGpu, _, _ ->
                FakeEngine(if (isGpu) "gpu" else "cpu").also { created += it }
            }
        )

        val gpuEngine = manager.getOrInitializeEngine(model, cache)
        assertEquals(ActiveBackend.GPU, manager.activeBackend)

        // Switching to the same preference keeps the warm engine alive.
        manager.switchBackend(BackendPreference.GPU_PREFERRED)
        assertFalse(gpuEngine.closed)
        assertNotNull(manager.engine)

        // Switching to CPU_ONLY closes the GPU engine so the next call uses CPU.
        manager.switchBackend(BackendPreference.CPU_ONLY)
        assertTrue(gpuEngine.closed)
        assertNull(manager.engine)
        assertEquals(BackendPreference.CPU_ONLY, manager.preference)

        val cpuEngine = manager.getOrInitializeEngine(model, cache)
        assertEquals("cpu", cpuEngine.name)
        assertEquals(ActiveBackend.CPU, manager.activeBackend)
        assertEquals(2, created.size)
        manager.close()
    }

    @Test
    fun fallbackToCpuAfterRuntimeFailureReplacesGpuEngineWithCpu() {
        val (model, cache) = newModelAndCache()
        val created = mutableListOf<FakeEngine>()
        val manager = InferenceEngineManager<FakeEngine>(
            preference = BackendPreference.GPU_PREFERRED,
            engineFactory = { isGpu, _, _ ->
                FakeEngine(if (isGpu) "gpu" else "cpu").also { created += it }
            }
        )

        val gpuEngine = manager.getOrInitializeEngine(model, cache)
        val switched = manager.fallbackToCpuAfterRuntimeFailure("OpenCL queue error", model, cache)

        assertTrue(switched)
        assertTrue(gpuEngine.closed)
        assertEquals(ActiveBackend.CPU, manager.activeBackend)
        assertEquals(BackendPreference.CPU_ONLY, manager.preference)
        assertEquals("cpu", manager.engine?.name)
        assertTrue(manager.lastFallbackReason?.contains("OpenCL queue error") == true)

        // Calling again once already on CPU is a no-op.
        assertFalse(manager.fallbackToCpuAfterRuntimeFailure("ignored", model, cache))
        assertEquals(2, created.size)
        manager.close()
    }

    @Test
    fun cancelActiveConversationForwardsCancelSignal() {
        val (model, cache) = newModelAndCache()
        val conversations = mutableListOf<FakeConversation>()
        val manager = InferenceEngineManager<FakeEngine>(
            engineFactory = { _, _, _ -> FakeEngine("gpu") },
            conversationFactory = { _, _ -> FakeConversation().also { conversations += it } }
        )

        manager.getOrInitializeEngine(model, cache)
        // Safe no-op before any conversation is open.
        manager.cancelActiveConversation()

        val key = manager.conversationKey("s1", model.absolutePath, "sys", 42)
        manager.getOrCreateConversation(key, "sys")
        manager.cancelActiveConversation()

        assertEquals(1, conversations.single().cancelCalls)
        manager.close()
        assertTrue(conversations.single().closed)
    }
}
