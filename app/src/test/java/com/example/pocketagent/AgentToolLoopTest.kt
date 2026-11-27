package com.example.pocketagent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentToolLoopTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun loop(withNotes: Boolean = false): AgentToolLoop {
        val notes = if (withNotes) PersonalNotesStore(tmp.newFolder("notes")) else null
        return AgentToolLoop(notesStore = notes, maxIterations = 3)
    }

    @Test fun `no tool calls returns final answer unchanged`() {
        val (hasTools, result) = loop().processOutput("Here is my answer.")
        assertFalse(hasTools)
        assertEquals("Here is my answer.", result.finalAnswer)
        assertTrue(result.toolResults.isEmpty())
    }

    @Test fun `extracts single tool call`() {
        val calls = loop().extractToolCalls("Let me calculate. TOOL_CALL: calc(2 + 2)")
        assertEquals(1, calls.size)
        assertEquals("calc", calls[0].first)
        assertEquals("2 + 2", calls[0].second)
    }

    @Test fun `extracts multiple tool calls`() {
        val calls = loop().extractToolCalls(
            "TOOL_CALL: calc(10 * 5)\nSome text.\nTOOL_CALL: search_notes(kotlin)")
        assertEquals(2, calls.size)
    }

    @Test fun `calc tool executes`() {
        val result = loop().executeToolCall("calc", "2 + 2")
        assertTrue(result.isNotEmpty())
    }

    @Test fun `search_notes returns no-store message when store is null`() {
        assertTrue(AgentToolLoop(notesStore = null).executeToolCall("search_notes", "test")
            .contains("No notes store"))
    }

    @Test fun `unknown tool returns error message`() {
        assertTrue(loop().executeToolCall("unknown_tool_xyz", "arg").contains("Unknown tool"))
    }

    @Test fun `buildEnrichedPrompt appends tool results`() {
        val results = listOf(AgentToolLoop.ToolCallResult("calc", "2+2", "4", 0))
        val enriched = loop().buildEnrichedPrompt("What is 2+2?", results)
        assertTrue(enriched.contains("[TOOL_RESULT:"))
    }

    @Test fun `processOutput detects tool call and executes`() {
        val (hasTools, result) = loop().processOutput("TOOL_CALL: calc(10 + 20)\nSome answer.")
        assertTrue(hasTools)
        assertEquals(1, result.toolResults.size)
    }

    @Test fun `removeToolCallLines strips tool call lines`() {
        val (_, result) = loop().processOutput("Here is text.\nTOOL_CALL: calc(1+1)\nMore text.")
        assertFalse(result.finalAnswer.contains("TOOL_CALL:"))
        assertTrue(result.finalAnswer.contains("Here is text."))
    }

    @Test fun `save_note with notes store`() {
        val l = loop(withNotes = true)
        val result = l.executeToolCall("save_note", "Test Title|Test Content")
        assertTrue(result.contains("Saved note"))
    }

    @Test fun `search_docs queries document vault when provided`() {
        val vault = DocumentVault(tmp.newFolder("vault_for_tool"))
        vault.addDocument("guide", "Offline Guide", "PocketAgent runs LiteRT-LM models on GPU and CPU backends cleanly.")
        val loopWithVault = AgentToolLoop(notesStore = null, documentVault = vault, maxIterations = 3)
        val result = loopWithVault.executeToolCall("search_docs", "LiteRT-LM backend")
        assertTrue(result.contains("Offline Guide"))
        assertTrue(result.contains("LiteRT-LM"))
    }

    @Test fun `executeToolCall catches unexpected tool exceptions`() {
        val throwingLoop = object : AgentToolLoop(toolTimeoutMs = 200L) {
            override fun executeToolCallInternal(toolName: String, input: String): String {
                throw IllegalStateException("boom")
            }
        }
        val result = throwingLoop.executeToolCall("calc", "1+1")
        assertTrue(result.contains("failed"))
        assertTrue(result.contains("boom"))
    }

    @Test fun `executeToolCallSafe bounds hanging tool with timeout`() = runTest {
        val hangingLoop = object : AgentToolLoop(toolTimeoutMs = 50L) {
            override fun executeToolCallInternal(toolName: String, input: String): String {
                Thread.sleep(2_000L)
                return "never"
            }
        }
        val result = hangingLoop.executeToolCallSafe("calc", "1+1")
        assertTrue(result.contains("timed out"))
    }

    @Test fun `processOutputSafe isolates throwing and succeeding tool calls`() = runTest {
        val mixedLoop = object : AgentToolLoop(toolTimeoutMs = 5_000L) {
            override fun executeToolCallInternal(toolName: String, input: String): String {
                if (toolName == "search_notes") throw RuntimeException("disk error")
                return "ok:$input"
            }
        }
        val (hasTools, loopResult) = mixedLoop.processOutputSafe(
            "TOOL_CALL: search_notes(test)\nTOOL_CALL: calc(2 + 3)\nDone."
        )
        assertTrue(hasTools)
        assertEquals(2, loopResult.toolResults.size)
        assertTrue(loopResult.toolResults[0].output.contains("disk error"))
        assertEquals("ok:2 + 3", loopResult.toolResults[1].output)
    }
}
