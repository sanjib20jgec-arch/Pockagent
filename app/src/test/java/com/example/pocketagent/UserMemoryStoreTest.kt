package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserMemoryStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: UserMemoryStore

    @Before
    fun setUp() {
        val baseDir = tempFolder.newFolder("memories")
        store = UserMemoryStore(baseDir, maxMemories = 5)
    }

    @Test
    fun `initial store is empty`() {
        assertTrue(store.listMemories().isEmpty())
        assertEquals("", store.buildPromptContext())
    }

    @Test
    fun `addFact persists and retrieves memory`() {
        val mem = store.addFact("Prefers concise Kotlin code", UserMemoryStore.MemoryCategory.PREFERENCE)
        assertNotNull(mem.id)
        assertEquals("Prefers concise Kotlin code", mem.content)
        assertEquals(UserMemoryStore.MemoryCategory.PREFERENCE, mem.category)

        val list = store.listMemories()
        assertEquals(1, list.size)
        assertEquals(mem.id, list[0].id)
        assertEquals("Prefers concise Kotlin code", list[0].content)
    }

    @Test
    fun `deleteMemory removes item`() {
        val mem1 = store.addFact("Prefers dark mode", UserMemoryStore.MemoryCategory.PREFERENCE)
        val mem2 = store.addFact("Name is Alice", UserMemoryStore.MemoryCategory.PROFILE)

        assertEquals(2, store.listMemories().size)

        val deleted = store.deleteMemory(mem1.id)
        assertTrue(deleted)

        val remaining = store.listMemories()
        assertEquals(1, remaining.size)
        assertEquals(mem2.id, remaining[0].id)

        assertFalse(store.deleteMemory("non-existent-id"))
    }

    @Test
    fun `clearMemories wipes all stored entries`() {
        store.addFact("Fact 1", UserMemoryStore.MemoryCategory.PREFERENCE)
        store.addFact("Fact 2", UserMemoryStore.MemoryCategory.GOAL)
        assertEquals(2, store.listMemories().size)

        assertTrue(store.clearMemories())
        assertTrue(store.listMemories().isEmpty())
    }

    @Test
    fun `prevents duplicate content within same category`() {
        store.addFact("Likes tea", UserMemoryStore.MemoryCategory.PREFERENCE)
        store.addFact("likes tea", UserMemoryStore.MemoryCategory.PREFERENCE)

        val list = store.listMemories()
        assertEquals(1, list.size)
    }

    @Test
    fun `enforces maximum capacity bound by dropping oldest non-custom entry`() {
        for (i in 1..5) {
            store.addFact("Fact $i", UserMemoryStore.MemoryCategory.PREFERENCE)
        }
        assertEquals(5, store.listMemories().size)

        // Adding 6th should evict Fact 1
        store.addFact("Fact 6", UserMemoryStore.MemoryCategory.PREFERENCE)
        val list = store.listMemories()
        assertEquals(5, list.size)
        assertFalse(list.any { it.content == "Fact 1" })
        assertTrue(list.any { it.content == "Fact 6" })
    }

    @Test
    fun `buildPromptContext formats lines and respects token cap`() {
        store.addFact("Prefers bullet points", UserMemoryStore.MemoryCategory.PREFERENCE)
        store.addFact("Speaks English and Bengali", UserMemoryStore.MemoryCategory.PROFILE)

        val prompt = store.buildPromptContext(maxTokens = 80)
        assertTrue(prompt.contains("[User Profile & Stored Preferences]:"))
        assertTrue(prompt.contains("Preference: Prefers bullet points"))
        assertTrue(prompt.contains("Profile: Speaks English and Bengali"))
    }

    @Test
    fun `reloads from disk accurately`() {
        store.addFact("Memory persistent across restarts", UserMemoryStore.MemoryCategory.CONSTRAINT)

        val newStore = UserMemoryStore(store.baseDir, maxMemories = 5)
        val memories = newStore.listMemories()
        assertEquals(1, memories.size)
        assertEquals("Memory persistent across restarts", memories[0].content)
        assertEquals(UserMemoryStore.MemoryCategory.CONSTRAINT, memories[0].category)
    }

    @Test
    fun `round trips escaped sequences and control chars in memory content`() {
        val trickyContent = "Uses \"JSON\" with literal \\n, tab\t, and bell\u0007"
        store.addFact(trickyContent, UserMemoryStore.MemoryCategory.CUSTOM)

        val reloaded = UserMemoryStore(store.baseDir, maxMemories = 5).listMemories().single()
        assertEquals(trickyContent, reloaded.content)
    }
}
