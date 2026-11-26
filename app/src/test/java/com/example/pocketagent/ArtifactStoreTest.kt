package com.example.pocketagent

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtifactStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = ArtifactStore(tmp.newFolder())

    @Test fun `empty store returns empty list`() {
        assertTrue(store().loadAll().isEmpty())
    }

    @Test fun `save and load roundtrip`() {
        val s = store()
        val a = ArtifactStore.Artifact(title = "My Code", language = "kotlin",
            code = "fun hello() = println(\"Hello\")",
            createdAt = 1000L, updatedAt = 1000L)
        assertTrue(s.save(a))
        val all = s.loadAll()
        assertEquals(1, all.size)
        assertEquals("kotlin", all[0].language)
    }

    @Test fun `delete removes artifact`() {
        val s = store()
        val a = ArtifactStore.Artifact(title = "Del", language = "py",
            code = "print(1)", createdAt = 1000L, updatedAt = 1000L)
        s.save(a)
        s.delete(a.id)
        assertTrue(s.loadAll().isEmpty())
    }

    @Test fun `update artifact replaces entry`() {
        val s = store()
        val a = ArtifactStore.Artifact(title = "Old", language = "js",
            code = "x=1", createdAt = 1000L, updatedAt = 1000L)
        s.save(a)
        s.save(a.copy(title = "New", version = 2, updatedAt = 2000L))
        assertEquals(1, s.loadAll().size)
        assertEquals("New", s.loadAll()[0].title)
    }

    @Test fun `exportHtml produces valid HTML`() {
        val htmlArtifact = ArtifactStore.Artifact(
            title = "Test",
            language = "html",
            code = "<h1>Hi</h1>",
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        val exportedPage = store().exportHtml(htmlArtifact)
        assertTrue(exportedPage.contains("<!DOCTYPE html>"))
        assertTrue(exportedPage.contains("<h1>Hi</h1>"))

        val codeArtifact = ArtifactStore.Artifact(
            title = "Code Snippet",
            language = "kotlin",
            code = "fun test(): List<String> = emptyList()",
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        val exportedCode = store().exportHtml(codeArtifact)
        assertTrue(exportedCode.contains("<!DOCTYPE html>"))
        assertTrue(exportedCode.contains("Code Snippet"))
        assertTrue(exportedCode.contains("&lt;String&gt;"))
    }

    @Test fun `code with special chars roundtrips`() {
        val s = store()
        val code = "val x = \"hello\\nworld\""
        val a = ArtifactStore.Artifact(title = "Escape Test", language = "kotlin",
            code = code, createdAt = 1000L, updatedAt = 1000L)
        s.save(a)
        assertEquals(code, s.loadAll()[0].code)
    }

    @Test fun `artifact requires positive createdAt`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactStore.Artifact(title = "X", language = "k", code = "",
                createdAt = 0L, updatedAt = 0L)
        }
    }
}
