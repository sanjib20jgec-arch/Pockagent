package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFactExtractorTest {

    @Test
    fun `extracts English preference patterns`() {
        val facts = MemoryFactExtractor.extractCandidateFacts("I prefer dark mode and clean layouts.")
        assertTrue(facts.isNotEmpty())
        val pref = facts.firstOrNull { it.category == UserMemoryStore.MemoryCategory.PREFERENCE }
        assertTrue(pref != null)
        assertTrue(pref!!.content.contains("dark mode", ignoreCase = true))
    }

    @Test
    fun `extracts Bengali preference patterns`() {
        val facts = MemoryFactExtractor.extractCandidateFacts("আমি পছন্দ করি বাংলায় উত্তর পাওয়া")
        assertTrue(facts.isNotEmpty())
        val pref = facts.firstOrNull { it.category == UserMemoryStore.MemoryCategory.PREFERENCE }
        assertTrue(pref != null)
    }

    @Test
    fun `extracts English profile patterns`() {
        val facts = MemoryFactExtractor.extractCandidateFacts("My name is Sanjib and I work as an engineer.")
        assertTrue(facts.isNotEmpty())
        val profile = facts.filter { it.category == UserMemoryStore.MemoryCategory.PROFILE }
        assertTrue(profile.isNotEmpty())
    }

    @Test
    fun `extracts Bengali profile patterns`() {
        val facts = MemoryFactExtractor.extractCandidateFacts("আমার নাম সঞ্জীব")
        assertTrue(facts.isNotEmpty())
        val profile = facts.firstOrNull { it.category == UserMemoryStore.MemoryCategory.PROFILE }
        assertTrue(profile != null)
        assertEquals("সঞ্জীব", profile!!.content)
    }

    @Test
    fun `extracts offline constraint patterns`() {
        val facts = MemoryFactExtractor.extractCandidateFacts("Note that my phone has only 4GB RAM and no internet.")
        assertTrue(facts.isNotEmpty())
        val constraint = facts.firstOrNull { it.category == UserMemoryStore.MemoryCategory.CONSTRAINT }
        assertTrue(constraint != null)
    }

    @Test
    fun `extracts learning goal patterns`() {
        val facts = MemoryFactExtractor.extractCandidateFacts("I am learning Kotlin for Android development.")
        assertTrue(facts.isNotEmpty())
        val goal = facts.firstOrNull { it.category == UserMemoryStore.MemoryCategory.GOAL }
        assertTrue(goal != null)
    }

    @Test
    fun `rejects short or generic queries`() {
        assertTrue(MemoryFactExtractor.extractCandidateFacts("hi").isEmpty())
        assertTrue(MemoryFactExtractor.extractCandidateFacts("what time is it?").isEmpty())
        assertTrue(MemoryFactExtractor.extractCandidateFacts("how to bake bread?").isEmpty())
    }
}
