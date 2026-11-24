package com.example.pocketagent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCacheTest {

    private val model: File = File.createTempFile("model", ".litertlm").apply {
        writeText("stub")
        deleteOnExit()
    }
    private val cache: File = File.createTempFile("cache", ".dir").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }

    /** Stand-in for a live engine; the cache policy never inspects it. */
    private class FakeEngine : AutoCloseable {
        override fun close() {}
    }

    /** Stand-in for a live conversation; records creation and close. */
    private class FakeConversation(private val created: MutableList<Any?>) : ConversationHandle {
        var closed = false

        override fun sendMessage(
            prompt: String,
            callback: com.google.ai.edge.litertlm.MessageCallback,
            maxOutputTokens: Int,
        ) = Unit

        override fun close() {
            closed = true
        }

        init {
            created += this
        }
    }

    /**
     * Manager wired entirely with fakes.
     *
     * Real `Engine`/`Conversation` construction needs native LiteRT-LM code plus a specific
     * `EngineConfig` shape, so the cache policy is proved against [ConversationHandle] instead.
     * This is exactly why the manager is generic.
     */
    private fun manager(created: MutableList<Any?>) = InferenceEngineManager<FakeEngine>(
        preference = BackendPreference.CPU_ONLY,
        engineFactory = { _, _, _ -> FakeEngine() },
        conversationFactory = { _, _ -> FakeConversation(created) },
    )

    @Test
    fun sameKeyReusesTheWarmConversation() {
        val created = mutableListOf<Any?>()
        val m = manager(created)
        m.getOrInitializeEngine(model, cache)
        val a = m.getOrCreateConversation("k1", "sys")
        val b = m.getOrCreateConversation("k1", "sys")
        assertTrue("same key must return the same conversation", a === b)
        assertEquals(1, created.size)
        assertEquals(1, m.conversationReuseCount)
    }

    @Test
    fun differentSessionStartsAFreshConversation() {
        val created = mutableListOf<Any?>()
        val m = manager(created)
        m.getOrInitializeEngine(model, cache)
        val a = m.getOrCreateConversation(m.conversationKey("s1", "/m", "sys", 0), "sys")
        val b = m.getOrCreateConversation(m.conversationKey("s2", "/m", "sys", 0), "sys")
        assertTrue("a new chat must not inherit the old context", a !== b)
        assertEquals(2, created.size)
    }

    @Test
    fun differentAttachmentStartsAFreshConversation() {
        val created = mutableListOf<Any?>()
        val m = manager(created)
        m.getOrInitializeEngine(model, cache)
        m.getOrCreateConversation(m.conversationKey("s1", "/m", "sys", 0), "sys")
        m.getOrCreateConversation(m.conversationKey("s1", "/m", "sys", "pdf".hashCode()), "sys")
        assertEquals(2, created.size)
    }

    @Test
    fun differentModelStartsAFreshConversation() {
        val created = mutableListOf<Any?>()
        val m = manager(created)
        m.getOrInitializeEngine(model, cache)
        m.getOrCreateConversation(m.conversationKey("s1", "/a.litertlm", "sys", 0), "sys")
        m.getOrCreateConversation(m.conversationKey("s1", "/b.litertlm", "sys", 0), "sys")
        assertEquals(2, created.size)
    }

    @Test
    fun differentSystemInstructionStartsAFreshConversation() {
        val created = mutableListOf<Any?>()
        val m = manager(created)
        m.getOrInitializeEngine(model, cache)
        m.getOrCreateConversation(m.conversationKey("s1", "/m", "one", 0), "one")
        m.getOrCreateConversation(m.conversationKey("s1", "/m", "two", 0), "two")
        assertEquals(2, created.size)
    }

    @Test
    fun invalidationRecordsTheReason() {
        val created = mutableListOf<Any?>()
        val m = manager(created)
        m.getOrInitializeEngine(model, cache)
        m.getOrCreateConversation("k", "sys")
        m.invalidateConversation("new chat")
        assertEquals("new chat", m.lastInvalidationReason)
    }

    @Test
    fun invalidateForcesTheNextTurnToRebuild() {
        val created = mutableListOf<Any?>()
        val m = manager(created)
        m.getOrInitializeEngine(model, cache)
        m.getOrCreateConversation("k", "sys")
        m.invalidateConversation("attachment removed")
        m.getOrCreateConversation("k", "sys")
        assertEquals("same key but invalidated must rebuild", 2, created.size)
    }

    @Test
    fun invalidatingTwiceIsHarmless() {
        val m = manager(mutableListOf())
        m.getOrInitializeEngine(model, cache)
        m.getOrCreateConversation("k", "sys")
        m.invalidateConversation("a")
        m.invalidateConversation("b")
        assertEquals("b", m.lastInvalidationReason)
    }

    @Test
    fun closeDropsTheConversationToo() {
        val m = manager(mutableListOf())
        m.getOrInitializeEngine(model, cache)
        m.getOrCreateConversation("k", "sys")
        m.closeConversation()
        // Closing forgets the cache, so the next turn must build a fresh conversation.
        assertEquals(1, m.conversationCreateCount)
        m.getOrCreateConversation("k", "sys")
        assertEquals(2, m.conversationCreateCount)
    }

    @Test
    fun closeReleasesEverything() {
        val m = manager(mutableListOf())
        m.getOrInitializeEngine(model, cache)
        m.getOrCreateConversation("k", "sys")
        m.close()
        assertNull(m.engine)
        assertNull(m.activeBackend)
    }

    @Test
    fun generationFlagTracksBeginAndEnd() {
        val m = manager(mutableListOf())
        m.getOrInitializeEngine(model, cache)
        assertTrue(!m.isGenerationActive)
        m.beginGeneration()
        assertTrue(m.isGenerationActive)
        m.endGeneration()
        assertTrue(!m.isGenerationActive)
    }

    @Test
    fun conversationKeyIsStableAndSensitive() {
        val m = manager(mutableListOf())
        assertEquals(
            m.conversationKey("s", "/m", "sys", 7),
            m.conversationKey("s", "/m", "sys", 7),
        )
        assertNotEquals(
            m.conversationKey("s", "/m", "sys", 7),
            m.conversationKey("s", "/m", "sys", 8),
        )
        assertNotEquals(
            m.conversationKey("s", "/m", "sys", 7),
            m.conversationKey("t", "/m", "sys", 7),
        )
    }

    @Test
    fun noSessionIdStillProducesAKey() {
        val m = manager(mutableListOf())
        val k = m.conversationKey(null, "/m", "sys", 0)
        assertTrue(k.contains("no-session"))
    }
}
