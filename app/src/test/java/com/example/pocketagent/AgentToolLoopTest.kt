package com.example.pocketagent

import kotlinx.coroutines.runBlocking
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

    @Test fun `executeToolCallSafe bounds hanging tool with timeout`() = runBlocking {
        val hangingLoop = object : AgentToolLoop(toolTimeoutMs = 50L) {
            override fun executeToolCallInternal(toolName: String, input: String): String {
                Thread.sleep(2_000L)
                return "never"
            }
        }
        val result = hangingLoop.executeToolCallSafe("calc", "1+1")
        assertTrue(result.contains("timed out"))
    }

    @Test fun `processOutputSafe isolates throwing and succeeding tool calls`() = runBlocking {
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

    @Test fun `get_datetime returns formatted local date time and zone`() {
        val result = loop().executeToolCall("get_datetime", "")
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \(.+\) \d{2}:\d{2}:\d{2} \[.+\]""").containsMatchIn(result))
    }

    @Test fun `list_notes returns up to 10 note titles`() {
        val notes = PersonalNotesStore(tmp.newFolder("notes_list"))
        for (i in 1..12) {
            notes.createNote("Note $i", "Body $i")
        }
        val result = AgentToolLoop(notesStore = notes).executeToolCall("list_notes", "")
        val lines = result.lines().filter { it.isNotBlank() }
        assertEquals(10, lines.size)
        assertTrue(lines.all { it.startsWith("- Note ") })
    }

    @Test fun `update_note updates existing note case-insensitively and creates missing note`() {
        val notes = PersonalNotesStore(tmp.newFolder("notes_update"))
        notes.createNote("Shopping List", "Milk")
        val loopWithNotes = AgentToolLoop(notesStore = notes)

        val updateRes = loopWithNotes.executeToolCall("update_note", "shopping list|Milk, Eggs, Bread")
        assertTrue(updateRes.contains("Updated note"))
        val updated = notes.listNotes().first { it.title.equals("Shopping List", ignoreCase = true) }
        assertEquals("Milk, Eggs, Bread", updated.content)

        val createRes = loopWithNotes.executeToolCall("update_note", "New Idea|Build offline tool")
        assertTrue(createRes.contains("Saved note"))
        assertEquals(2, notes.listNotes().size)
    }

    @Test fun `list_docs lists indexed document names and lengths`() {
        val vault = DocumentVault(tmp.newFolder("vault_list"))
        vault.addDocument("d1", "Manual.txt", "Hello world")
        val result = AgentToolLoop(documentVault = vault).executeToolCall("list_docs", "")
        assertTrue(result.contains("Manual.txt"))
        assertTrue(result.contains("11 chars"))
    }

    @Test fun `search_memory filters by query tokens and supports all`() {
        val memoryStore = UserMemoryStore(tmp.newFolder("memories"))
        memoryStore.addFact("Prefers dark mode in IDE", UserMemoryStore.MemoryCategory.PREFERENCE)
        memoryStore.addFact("Lives in Seattle", UserMemoryStore.MemoryCategory.PROFILE)
        val loopWithMemory = AgentToolLoop(userMemoryStore = memoryStore)

        val filtered = loopWithMemory.executeToolCall("search_memory", "dark ide")
        assertTrue(filtered.contains("Prefers dark mode"))
        assertFalse(filtered.contains("Seattle"))

        val all = loopWithMemory.executeToolCall("search_memory", "all")
        assertTrue(all.contains("Prefers dark mode"))
        assertTrue(all.contains("Seattle"))
    }

    @Test fun `search_history returns matching session titles dates and snippets`() {
        val sessionStore = SessionStore(tmp.newFolder("sessions"))
        val session = ConversationSession(
            id = "sess-1",
            title = "Kotlin Coroutines",
            messages = listOf(ChatMessage.user("How do structured concurrency scopes work?")),
        )
        sessionStore.saveSession(session)

        val result = AgentToolLoop(sessionStore = sessionStore).executeToolCall("search_history", "concurrency")
        assertTrue(result.contains("Kotlin Coroutines"))
        assertTrue(result.contains("structured concurrency"))
    }

    @Test fun `save_artifact and read_artifact persist and retrieve code`() {
        val artifactStore = ArtifactStore(tmp.newFolder("artifacts"))
        val loopWithArtifacts = AgentToolLoop(artifactStore = artifactStore)

        val saveRes = loopWithArtifacts.executeToolCall("save_artifact", "Fib|python|def fib(n): return n")
        assertTrue(saveRes.contains("Saved artifact"))

        val readRes = loopWithArtifacts.executeToolCall("read_artifact", "Fib")
        assertTrue(readRes.contains("Fib (python)"))
        assertTrue(readRes.contains("def fib(n): return n"))
    }

    @Test fun `device_status invokes provider`() {
        val loopWithStatus = AgentToolLoop(deviceStatusProvider = { "Battery: 85%, RAM: 4GB free" })
        assertEquals("Battery: 85%, RAM: 4GB free", loopWithStatus.executeToolCall("device_status", ""))
    }

    @Test fun `run_js invokes evaluator and handles null evaluator`() {
        assertEquals("JavaScript sandbox unavailable.", loop().executeToolCall("run_js", "1 + 1"))
        val loopWithJs = AgentToolLoop(jsEvaluator = { script -> "eval:$script" })
        assertEquals("eval:2 + 2", loopWithJs.executeToolCall("run_js", "2 + 2"))
    }

    @Test fun `read_clipboard invokes reader and handles null reader`() {
        assertEquals("Clipboard access unavailable.", loop().executeToolCall("read_clipboard", ""))
        val loopWithClipboard = AgentToolLoop(clipboardReader = { "copied text" })
        assertEquals("copied text", loopWithClipboard.executeToolCall("read_clipboard", ""))
    }

    @Test fun `write_clipboard invokes writer and handles blank input or null writer`() {
        assertEquals("Clipboard text cannot be blank.", loop().executeToolCall("write_clipboard", "   "))
        assertEquals("Clipboard write unavailable.", loop().executeToolCall("write_clipboard", "hello"))

        var written = ""
        val loopWithClipboard = AgentToolLoop(
            clipboardWriter = { text ->
                written = text
                "Copied to clipboard (${text.length} chars)."
            }
        )
        assertEquals("Clipboard text cannot be blank.", loopWithClipboard.executeToolCall("write_clipboard", ""))
        assertEquals("Copied to clipboard (11 chars).", loopWithClipboard.executeToolCall("write_clipboard", "hello world"))
        assertEquals("hello world", written)
    }
}
