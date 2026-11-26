package com.example.pocketagent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonalNotesStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: PersonalNotesStore

    @Before
    fun setUp() {
        val baseDir = tempFolder.newFolder("notes")
        store = PersonalNotesStore(baseDir, maxNotes = 5)
    }

    @Test
    fun `initial store is empty`() {
        assertTrue(store.listNotes().isEmpty())
        assertTrue(store.exportNotesAsMarkdown().contains("No notes saved"))
    }

    @Test
    fun `create and retrieve note`() {
        val note = store.createNote(
            title = "Project Architecture",
            content = "100% offline sovereign assistant.",
            tags = listOf("architecture", "offline"),
            isPinned = true,
        )

        assertNotNull(note.id)
        assertEquals("Project Architecture", note.title)
        assertTrue(note.isPinned)

        val retrieved = store.getNote(note.id)
        assertNotNull(retrieved)
        assertEquals("100% offline sovereign assistant.", retrieved!!.content)
        assertEquals(2, retrieved.tags.size)
    }

    @Test
    fun `update note content and title`() {
        val note = store.createNote("Shopping List", "Milk, Bread")
        val updated = store.saveNote(note.withUpdatedContent("Groceries", "Milk, Bread, Tea"))

        assertEquals("Groceries", updated.title)
        assertEquals("Milk, Bread, Tea", updated.content)
        assertEquals(1, store.listNotes().size)
    }

    @Test
    fun `pinning notes prioritizes them in list order`() {
        val n1 = store.createNote("Note A", "Content A", isPinned = false)
        val n2 = store.createNote("Note B", "Content B", isPinned = true)
        val n3 = store.createNote("Note C", "Content C", isPinned = false)

        val list = store.listNotes()
        assertEquals(n2.id, list[0].id) // Pinned note is first

        store.pinNote(n1.id, true)
        val updatedList = store.listNotes()
        assertTrue(updatedList[0].isPinned)
        assertTrue(updatedList[1].isPinned)
    }

    @Test
    fun `search notes by title and content`() {
        store.createNote("Kotlin coroutines", "Deep dive into flows and jobs")
        store.createNote("Whisper audio", "Offline speech transcription on CPU")

        val r1 = store.searchNotes("coroutines")
        assertEquals(1, r1.size)
        assertEquals("Kotlin coroutines", r1[0].title)

        val r2 = store.searchNotes("transcription")
        assertEquals(1, r2.size)
        assertEquals("Whisper audio", r2[0].title)

        val r3 = store.searchNotes("non-existent")
        assertTrue(r3.isEmpty())
    }

    @Test
    fun `delete and clear notes`() {
        val n1 = store.createNote("First", "Content")
        val n2 = store.createNote("Second", "Content")

        assertTrue(store.deleteNote(n1.id))
        assertEquals(1, store.listNotes().size)
        assertFalse(store.deleteNote("fake-id"))

        assertTrue(store.clearNotes())
        assertTrue(store.listNotes().isEmpty())
    }

    @Test
    fun `enforces max capacity bound by evicting oldest unpinned note`() {
        for (i in 1..5) {
            store.createNote("Note $i", "Content $i", isPinned = (i == 1))
        }
        assertEquals(5, store.listNotes().size)

        // Adding 6th should evict an unpinned note, but NOT the pinned Note 1
        store.createNote("Note 6", "Content 6")
        val list = store.listNotes()
        assertEquals(5, list.size)
        assertTrue(list.any { it.title == "Note 1" && it.isPinned })
        assertTrue(list.any { it.title == "Note 6" })
    }

    @Test
    fun `reloads from disk correctly`() {
        store.createNote("Persistent Note", "Saved locally in private JSON")

        val newStore = PersonalNotesStore(store.baseDir, maxNotes = 5)
        val list = newStore.listNotes()
        assertEquals(1, list.size)
        assertEquals("Persistent Note", list[0].title)
    }

    @Test
    fun `round trips escaped quotes backslashes and control characters`() {
        val trickyTitle = "Quote \"A\" & literal \\n slash"
        val trickyBody = "Line1\nLine2\tTabbed\u0007Bell & backslash \\"
        val trickyTags = listOf("tag\"1", "path\\2")
        store.createNote(trickyTitle, trickyBody, tags = trickyTags)

        val reloaded = PersonalNotesStore(store.baseDir, maxNotes = 5).listNotes().single()
        assertEquals(trickyTitle, reloaded.title)
        assertEquals(trickyBody, reloaded.content)
        assertEquals(trickyTags, reloaded.tags)
    }

    @Test
    fun `legacy note json still loads and is retagged on save`() {
        val legacy = """
            [
              {
                "id": "note-legacy",
                "title": "Legacy note",
                "content": "Legacy body",
                "tags": ["archive", "legacy"],
                "createdAt": 1700000000000,
                "updatedAt": 1700000000500,
                "isPinned": true
              }
            ]
        """.trimIndent()
        File(store.baseDir, "personal_notes.json").writeText(legacy)

        val restored = store.listNotes().single()

        assertEquals("note-legacy", restored.id)
        assertEquals("Legacy note", restored.title)
        assertEquals("Legacy body", restored.content)
        assertEquals(listOf("archive", "legacy"), restored.tags)
        assertTrue(restored.isPinned)
        store.createNote("Fresh note", "Fresh body")
        assertTrue(File(store.baseDir, "personal_notes.json").readText().contains("\"schema_version\":1"))
        assertEquals(2, store.listNotes().size)
    }
}
