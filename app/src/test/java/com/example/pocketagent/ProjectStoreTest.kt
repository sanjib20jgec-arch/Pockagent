package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = ProjectStore(tmp.newFolder("projects"))

    @Test fun `empty store returns empty list`() {
        assertTrue(store().loadAll().isEmpty())
    }

    @Test fun `save and load roundtrip`() {
        val s = store()
        val p = ProjectStore.Project(name = "My Project",
            systemInstructions = "Act as a coding assistant.",
            createdAt = 1000L, updatedAt = 2000L)
        assertTrue(s.save(p))
        val loaded = s.loadAll()
        assertEquals(1, loaded.size)
        assertEquals("My Project", loaded[0].name)
        assertEquals("Act as a coding assistant.", loaded[0].systemInstructions)
    }

    @Test fun `update existing project`() {
        val s = store()
        val p = ProjectStore.Project(name = "Old", createdAt = 1000L, updatedAt = 1000L)
        s.save(p)
        s.save(p.copy(name = "New", updatedAt = 2000L))
        val all = s.loadAll()
        assertEquals(1, all.size)
        assertEquals("New", all[0].name)
    }

    @Test fun `delete removes project`() {
        val s = store()
        val p = ProjectStore.Project(name = "ToDelete", createdAt = 1000L, updatedAt = 1000L)
        s.save(p)
        assertTrue(s.delete(p.id))
        assertTrue(s.loadAll().isEmpty())
    }

    @Test fun `project init requires non-blank name`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectStore.Project(name = "", createdAt = 1000L, updatedAt = 1000L)
        }
    }

    @Test fun `project init requires updatedAt ge createdAt`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectStore.Project(name = "X", createdAt = 2000L, updatedAt = 1000L)
        }
    }

    @Test fun `pinnedNoteIds roundtrip`() {
        val s = store()
        val p = ProjectStore.Project(name = "NoteProject",
            pinnedNoteIds = listOf("note-1", "note-2"),
            createdAt = 1000L, updatedAt = 1000L)
        s.save(p)
        assertEquals(listOf("note-1", "note-2"), s.loadAll()[0].pinnedNoteIds)
    }

    @Test fun `special characters in name and instructions roundtrip`() {
        val s = store()
        val p = ProjectStore.Project(name = "Test \"quotes\" here",
            systemInstructions = "Line1\nLine2\tTabbed",
            createdAt = 1000L, updatedAt = 1000L)
        s.save(p)
        val loaded = s.loadAll()[0]
        assertEquals("Test \"quotes\" here", loaded.name)
        assertEquals("Line1\nLine2\tTabbed", loaded.systemInstructions)
    }
}
