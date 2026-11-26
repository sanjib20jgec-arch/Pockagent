package com.example.pocketagent

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentVaultTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun vault() = DocumentVault(tmp.newFolder("vault"))

    @Test fun `empty vault returns empty list`() {
        assertTrue(vault().loadAll().isEmpty())
    }

    @Test fun `addDocument saves document`() {
        val v = vault()
        val doc = v.addDocument("doc1", "My Doc", "This is content about Kotlin programming.")
        assertNotNull(doc)
        assertEquals("My Doc", doc!!.name)
        assertEquals(1, v.loadAll().size)
    }

    @Test fun `removeDocument deletes entry`() {
        val v = vault()
        v.addDocument("doc2", "Del", "Content")
        v.removeDocument("doc2")
        assertTrue(v.loadAll().isEmpty())
    }

    @Test fun `search returns matching chunks`() {
        val v = vault()
        v.addDocument("doc3", "Tech Doc", "Kotlin is a modern programming language for Android development.")
        val results = v.search("kotlin android")
        assertTrue(results.isNotEmpty())
    }

    @Test fun `duplicate add returns existing doc`() {
        val v = vault()
        v.addDocument("doc4", "X", "content")
        val second = v.addDocument("doc4", "X2", "other")
        assertNotNull(second)
        assertEquals(1, v.loadAll().size)
    }

    @Test fun `VaultDocument requires non-blank id`() {
        assertThrows(IllegalArgumentException::class.java) {
            DocumentVault.VaultDocument("", "name", "", 0)
        }
    }

    @Test fun `text with special chars roundtrips`() {
        val v = vault()
        v.addDocument("doc5", "Quote Doc", "He said \"hello\" and went to\nnew line.")
        val loaded = v.loadAll().firstOrNull { it.id == "doc5" }
        assertNotNull(loaded)
        assertTrue(loaded!!.extractedText.contains("hello"))
    }

    @Test fun `bm25 search ignores stopword noise across multiple docs`() {
        val v = vault()
        v.addDocument("d1", "Cooking", "Boil pasta in water for ten minutes.")
        v.addDocument("d2", "Cryptography", "ChaCha20-Poly1305 encrypts sovereign device vaults locally.")
        val hits = v.search("How does ChaCha20 work in the vault?")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits[0].contains("Cryptography"))
    }
}
