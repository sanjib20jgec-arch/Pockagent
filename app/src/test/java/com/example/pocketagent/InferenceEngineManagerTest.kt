package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class InferenceEngineManagerTest {

    private class FakeEngine(val name: String) : AutoCloseable {
        var closed = false
        override fun close() { closed = true }
    }

    @Test
    fun `falls back to cpu when gpu init fails`() {
        val model = Files.createTempFile("model", ".litertlm").toFile().apply { writeText("x") }
        val cache = Files.createTempDirectory("cache").toFile()
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
    fun `reuses engine instance and caches conversation`() {
        val model = Files.createTempFile("model", ".litertlm").toFile().apply { writeText("x") }
        val cache = Files.createTempDirectory("cache").toFile()
        var engineCreates = 0
        var conversationCreates = 0
        val manager = InferenceEngineManager<FakeEngine>(
            engineFactory = { _, _, _ -> engineCreates++; FakeEngine("cpu") },
            conversationFactory = { _, _ ->
                conversationCreates++
                object : ConversationHandle {
                    override fun sendMessage(prompt: String, callback: com.google.ai.edge.litertlm.MessageCallback, maxOutputTokens: Int) {}
                    override fun close() {}
                }
            }
        )

        manager.getOrInitializeEngine(model, cache)
        manager.getOrInitializeEngine(model, cache)
        val key = manager.conversationKey("s1", model.absolutePath, "sys", 0)
        manager.getOrCreateConversation(key, "sys")
        manager.getOrCreateConversation(key, "sys")

        assertEquals(1, engineCreates)
        assertEquals(1, conversationCreates)
        assertEquals(1, manager.conversationReuseCount)
        manager.close()
    }

    @Test
    fun `invalidateConversation clears cached handle`() {
        val model = Files.createTempFile("model", ".litertlm").toFile().apply { writeText("x") }
        val cache = Files.createTempDirectory("cache").toFile()
        var conversationCreates = 0
        val manager = InferenceEngineManager<FakeEngine>(
            engineFactory = { _, _, _ -> FakeEngine("cpu") },
            conversationFactory = { _, _ ->
                conversationCreates++
                object : ConversationHandle {
                    override fun sendMessage(prompt: String, callback: com.google.ai.edge.litertlm.MessageCallback, maxOutputTokens: Int) {}
                    override fun close() {}
                }
            }
        )
        manager.getOrInitializeEngine(model, cache)
        val key = manager.conversationKey("s1", model.absolutePath, "sys", 0)
        manager.getOrCreateConversation(key, "sys")
        manager.invalidateConversation("test")
        manager.getOrCreateConversation(key, "sys")

        assertEquals(2, conversationCreates)
        assertEquals("test", manager.lastInvalidationReason)
        manager.close()
    }

    @Test
    fun `updateMaxContextTokens closes engine and rejects unsupported values`() {
        val model = Files.createTempFile("model", ".litertlm").toFile().apply { writeText("x") }
        val cache = Files.createTempDirectory("cache").toFile()
        var closedCount = 0
        val manager = InferenceEngineManager<AutoCloseable>(
            engineFactory = { _, _, _ -> AutoCloseable { closedCount++ } },
        )
        manager.getOrInitializeEngine(model, cache)
        manager.updateMaxContextTokens(8192)

        assertEquals(8192, manager.maxContextTokens)
        assertEquals(1, closedCount)
        assertEquals(null, manager.engine)

        manager.updateMaxContextTokens(999_999)
        assertEquals(ContextBudgetManager.TokenBudget.DEFAULT_MAX_CONTEXT_TOKENS, manager.maxContextTokens)
    }
}
