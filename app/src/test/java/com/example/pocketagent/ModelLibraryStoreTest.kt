package com.example.pocketagent

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelLibraryStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = ModelLibraryStore(tmp.newFolder("models"))

    private fun fakeModel(dir: File, name: String = "model.litertlm"): File {
        return File(dir, name).also { it.writeBytes(ByteArray(16)) }
    }

    @Test fun `empty store returns empty list`() {
        assertTrue(store().loadAll().isEmpty())
    }

    @Test fun `registerModel adds entry`() {
        val dir = tmp.newFolder("mdir")
        val s = ModelLibraryStore(tmp.newFolder("meta"))
        val model = fakeModel(dir)
        val entry = s.registerModel(model.absolutePath, "My Model")
        assertNotNull(entry)
        assertEquals("My Model", entry!!.displayName)
        assertEquals(1, s.loadAll().size)
    }

    @Test fun `registerModel ignores non-litertlm files`() {
        val dir = tmp.newFolder("mdir2")
        val s = ModelLibraryStore(tmp.newFolder("meta2"))
        val f = File(dir, "model.bin").also { it.writeText("x") }
        assertNull(s.registerModel(f.absolutePath, "Bad"))
    }

    @Test fun `setActive marks one active`() {
        val dir = tmp.newFolder("mdir3")
        val s = ModelLibraryStore(tmp.newFolder("meta3"))
        val m = fakeModel(dir, "a.litertlm")
        val entry = s.registerModel(m.absolutePath, "A")!!
        assertTrue(s.setActive(entry.id))
        assertEquals(entry.id, s.getActive()?.id)
    }

    @Test fun `remove deletes entry`() {
        val dir = tmp.newFolder("mdir4")
        val s = ModelLibraryStore(tmp.newFolder("meta4"))
        val m = fakeModel(dir, "b.litertlm")
        val entry = s.registerModel(m.absolutePath, "B")!!
        assertTrue(s.remove(entry.id))
        assertTrue(s.loadAll().isEmpty())
    }

    @Test fun `sizeLabel formats correctly`() {
        val e = ModelLibraryStore.ModelEntry("id", "Name", "path", 1_200_000_000L)
        assertTrue(e.sizeLabel.contains("GB") || e.sizeLabel.contains("MB"))
    }

    @Test fun `ModelEntry requires non-blank id`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelLibraryStore.ModelEntry("", "Name", "path", 0)
        }
    }

    @Test fun `updateBenchmark persists tokensPerSecond`() {
        val dir = tmp.newFolder("mdir5")
        val s = ModelLibraryStore(tmp.newFolder("meta5"))
        val m = fakeModel(dir, "c.litertlm")
        val entry = s.registerModel(m.absolutePath, "C")!!
        s.updateBenchmark(entry.id, 42.5f)
        assertEquals(42.5f, s.loadAll().first { it.id == entry.id }.tokensPerSecond, 0.01f)
    }
}
