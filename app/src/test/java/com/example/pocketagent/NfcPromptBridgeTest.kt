package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcPromptBridgeTest {

    @Test
    fun roundTripAsciiProjectAndPrompt() {
        val uri = NfcPromptBridge.buildLaunchUri(
            projectId = "proj-42",
            prompt = "Summarize my offline notes & tasks = today?",
        )
        assertTrue(uri.startsWith("pocketagent://run?"))

        val parsed = NfcPromptBridge.parseLaunchUri(uri)
        assertNotNull(parsed)
        assertEquals("proj-42", parsed?.projectId)
        assertEquals("Summarize my offline notes & tasks = today?", parsed?.prompt)
    }

    @Test
    fun roundTripBengaliUtf8Prompt() {
        val bengaliPrompt = "আমার নোটগুলো সংক্ষেপে বাংলায় লেখো এবং ৩টি কাজ বের করো।"
        val uri = NfcPromptBridge.buildLaunchUri(
            projectId = "বাংলার-প্রজেক্ট",
            prompt = bengaliPrompt,
        )

        val parsed = NfcPromptBridge.parseLaunchUri(uri)
        assertNotNull(parsed)
        assertEquals("বাংলার-প্রজেক্ট", parsed?.projectId)
        assertEquals(bengaliPrompt, parsed?.prompt)
    }

    @Test
    fun roundTripWithOnlyPromptOrEmptyParams() {
        val promptOnlyUri = NfcPromptBridge.buildLaunchUri(projectId = null, prompt = "Quick check")
        val parsedPromptOnly = NfcPromptBridge.parseLaunchUri(promptOnlyUri)
        assertNotNull(parsedPromptOnly)
        assertNull(parsedPromptOnly?.projectId)
        assertEquals("Quick check", parsedPromptOnly?.prompt)

        val emptyUri = NfcPromptBridge.buildLaunchUri(projectId = null, prompt = null)
        assertEquals("pocketagent://run", emptyUri)
        val parsedEmpty = NfcPromptBridge.parseLaunchUri(emptyUri)
        assertNotNull(parsedEmpty)
        assertNull(parsedEmpty?.projectId)
        assertNull(parsedEmpty?.prompt)
    }

    @Test
    fun invalidOrMissingSchemesReturnNull() {
        assertNull(NfcPromptBridge.parseLaunchUri(""))
        assertNull(NfcPromptBridge.parseLaunchUri("   "))
        assertNull(NfcPromptBridge.parseLaunchUri("https://example.com/run?prompt=hi"))
        assertNull(NfcPromptBridge.parseLaunchUri("pocketagent://other?prompt=hi"))
        assertNull(NfcPromptBridge.parseLaunchUri("://broken-uri"))
    }
}
