package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFtsIndexTest {

    @Test
    fun `normalizeToken stems common English suffixes and strips punctuation`() {
        assertEquals("search", LocalFtsIndex.normalizeToken("Searching!"))
        assertEquals("run", LocalFtsIndex.normalizeToken("running"))
        assertEquals("stop", LocalFtsIndex.normalizeToken("stopped"))
        assertEquals("query", LocalFtsIndex.normalizeToken("queries"))
        assertEquals("document", LocalFtsIndex.normalizeToken("documents"))
        assertEquals("quick", LocalFtsIndex.normalizeToken("quickly"))
        assertEquals("happy", LocalFtsIndex.normalizeToken("happy"))
        assertEquals("ai", LocalFtsIndex.normalizeToken("AI"))
        assertEquals("class", LocalFtsIndex.normalizeToken("class"))
    }

    @Test
    fun `buildFtsQuery filters stop words normalizes tokens and formats OR query`() {
        val query = LocalFtsIndex.buildFtsQuery("How are documents indexed and searched?")
        assertEquals("document* OR index* OR search*", query)
    }

    @Test
    fun `buildFtsQuery deduplicates normalized tokens`() {
        val query = LocalFtsIndex.buildFtsQuery("search searching searched")
        assertEquals("search*", query)
    }

    @Test
    fun `buildFtsQuery returns empty string for blank or punctuation only input`() {
        assertEquals("", LocalFtsIndex.buildFtsQuery(""))
        assertEquals("", LocalFtsIndex.buildFtsQuery("   "))
        assertEquals("", LocalFtsIndex.buildFtsQuery("?!@#$%"))
    }

    @Test
    fun `buildFtsQuery falls back to raw tokens if all tokens are stop words`() {
        val query = LocalFtsIndex.buildFtsQuery("what is it")
        assertTrue(query.isNotBlank())
        assertTrue(query.contains("*"))
    }
}
