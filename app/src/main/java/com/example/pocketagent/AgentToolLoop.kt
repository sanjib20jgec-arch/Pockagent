package com.example.pocketagent

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

/**
 * Offline multi-step tool execution loop for PocketAgent.
 *
 * Parses tool calls from model output, executes offline tools, injects results back,
 * and iterates up to [maxIterations] times.
 *
 * Supported tools:
 *   TOOL_CALL: calc(<expression>)
 *   TOOL_CALL: get_datetime()
 *   TOOL_CALL: search_notes(<query>)
 *   TOOL_CALL: list_notes()
 *   TOOL_CALL: save_note(<title>|<content>)
 *   TOOL_CALL: update_note(<title>|<content>)
 *   TOOL_CALL: search_docs(<query>)
 *   TOOL_CALL: list_docs()
 *   TOOL_CALL: search_memory(<query>)
 *   TOOL_CALL: search_history(<query>)
 *   TOOL_CALL: save_artifact(<title>|<language>|<code>)
 *   TOOL_CALL: read_artifact(<title_or_language>)
 *   TOOL_CALL: device_status()
 *   TOOL_CALL: run_js(<script>)
 *   TOOL_CALL: trigger_system_action(<type>|<details>)
 *   TOOL_CALL: read_clipboard()
 *   TOOL_CALL: write_clipboard(<text>)
 *
 * 100% offline, zero network calls, zero telemetry.
 */
open class AgentToolLoop(
    private val notesStore: PersonalNotesStore? = null,
    private val documentVault: DocumentVault? = null,
    private val maxIterations: Int = 3,
    private val toolTimeoutMs: Long = TOOL_EXECUTION_TIMEOUT_MS,
    private val userMemoryStore: UserMemoryStore? = null,
    private val sessionStore: SessionStore? = null,
    private val artifactStore: ArtifactStore? = null,
    private val deviceStatusProvider: (() -> String)? = null,
    private val jsEvaluator: ((String) -> String)? = null,
    private val systemActionBridge: ((String, String) -> String)? = null,
    private val clipboardReader: (() -> String)? = null,
    private val clipboardWriter: ((String) -> String)? = null,
) {

    companion object {
        const val TOOL_EXECUTION_TIMEOUT_MS = 10_000L
        const val AGENT_MODE_INSTRUCTION =
            "Agent Mode is active. You have access to offline on-device tools:\n" +
                "- TOOL_CALL: calc(<math_or_conversion_expression>)\n" +
                "- TOOL_CALL: get_datetime()\n" +
                "- TOOL_CALL: search_notes(<keywords>)\n" +
                "- TOOL_CALL: list_notes()\n" +
                "- TOOL_CALL: save_note(<title>|<content>)\n" +
                "- TOOL_CALL: update_note(<title>|<content>)\n" +
                "- TOOL_CALL: search_docs(<keywords>)\n" +
                "- TOOL_CALL: list_docs()\n" +
                "- TOOL_CALL: search_memory(<keywords_or_all>)\n" +
                "- TOOL_CALL: search_history(<keywords>)\n" +
                "- TOOL_CALL: save_artifact(<title>|<language>|<code>)\n" +
                "- TOOL_CALL: read_artifact(<title_or_language>)\n" +
                "- TOOL_CALL: device_status()\n" +
                "- TOOL_CALL: run_js(<javascript_code>)\n" +
                "- TOOL_CALL: trigger_system_action(<type>|<details>)\n" +
                "- TOOL_CALL: read_clipboard()\n" +
                "- TOOL_CALL: write_clipboard(<text>)\n" +
                "If a calculation, local lookup, artifact operation, or offline tool helps answer accurately, " +
                "emit one or more exact TOOL_CALL lines cleanly and wait for [TOOL_RESULT: ...]. " +
                "Once you have what you need, write the final answer without TOOL_CALL lines."

        private val TOOL_CALL_REGEX = Regex("""TOOL_CALL:\s*(\w+)\(([^)\r\n]*)\)""")
    }

    data class ToolCallResult(
        val toolName: String,
        val input: String,
        val output: String,
        val iterationIndex: Int,
    )

    data class LoopResult(
        val finalAnswer: String,
        val toolResults: List<ToolCallResult>,
        val iterationsUsed: Int,
        val wasTerminatedEarly: Boolean,
    )

    fun processOutput(modelOutput: String, iterationIndex: Int = 0): Pair<Boolean, LoopResult> {
        val toolCalls = extractToolCalls(modelOutput)
        if (toolCalls.isEmpty()) {
            return false to LoopResult(
                finalAnswer = modelOutput,
                toolResults = emptyList(),
                iterationsUsed = iterationIndex,
                wasTerminatedEarly = false,
            )
        }

        val results = mutableListOf<ToolCallResult>()
        for ((toolName, toolInput) in toolCalls) {
            val output = executeToolCall(toolName, toolInput)
            results.add(
                ToolCallResult(
                    toolName = toolName,
                    input = toolInput,
                    output = output,
                    iterationIndex = iterationIndex,
                )
            )
        }

        val cleanedOutput = removeToolCallLines(modelOutput)
        return true to LoopResult(
            finalAnswer = cleanedOutput,
            toolResults = results,
            iterationsUsed = iterationIndex + 1,
            wasTerminatedEarly = iterationIndex + 1 >= maxIterations,
        )
    }

    /**
     * Suspending variant of [processOutput] that executes each tool call with a structured
     * timeout and exception boundary via [executeToolCallSafe].
     */
    suspend fun processOutputSafe(modelOutput: String, iterationIndex: Int = 0): Pair<Boolean, LoopResult> {
        val toolCalls = extractToolCalls(modelOutput)
        if (toolCalls.isEmpty()) {
            return false to LoopResult(
                finalAnswer = modelOutput,
                toolResults = emptyList(),
                iterationsUsed = iterationIndex,
                wasTerminatedEarly = false,
            )
        }

        val results = mutableListOf<ToolCallResult>()
        for ((toolName, toolInput) in toolCalls) {
            val output = executeToolCallSafe(toolName, toolInput)
            results.add(
                ToolCallResult(
                    toolName = toolName,
                    input = toolInput,
                    output = output,
                    iterationIndex = iterationIndex,
                )
            )
        }

        val cleanedOutput = removeToolCallLines(modelOutput)
        return true to LoopResult(
            finalAnswer = cleanedOutput,
            toolResults = results,
            iterationsUsed = iterationIndex + 1,
            wasTerminatedEarly = iterationIndex + 1 >= maxIterations,
        )
    }

    fun buildEnrichedPrompt(originalPrompt: String, toolResults: List<ToolCallResult>): String {
        if (toolResults.isEmpty()) return originalPrompt
        val sb = StringBuilder(originalPrompt)
        sb.append("\n\n")
        toolResults.forEach { r ->
            sb.append("[TOOL_RESULT: ${r.toolName}(${r.input}) \u2192 ${r.output}]\n")
        }
        sb.append("\nUse these verified local tool results to produce the complete final answer without emitting duplicate TOOL_CALL lines.")
        return sb.toString()
    }

    fun extractToolCalls(text: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        TOOL_CALL_REGEX.findAll(text).forEach { m ->
            val name = m.groupValues[1].lowercase().trim()
            val args = m.groupValues[2].trim()
            results.add(name to args)
        }
        return results
    }

    /**
     * Executes a single tool call with a structured [timeoutMs] boundary and exception guard so a
     * hanging or throwing tool never crashes the inference loop.
     */
    suspend fun executeToolCallSafe(
        toolName: String,
        input: String,
        timeoutMs: Long = toolTimeoutMs,
    ): String {
        return try {
            withTimeout(timeoutMs) {
                runInterruptible(Dispatchers.IO) {
                    executeToolCallInternal(toolName, input)
                }
            }
        } catch (_: TimeoutCancellationException) {
            "Tool '$toolName' timed out after ${timeoutMs}ms."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Tool '$toolName' failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun executeToolCall(toolName: String, input: String): String {
        return try {
            executeToolCallInternal(toolName, input)
        } catch (e: Exception) {
            "Tool '$toolName' failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    protected open fun executeToolCallInternal(toolName: String, input: String): String {
        return when (toolName) {
            "calc", "calculate" -> {
                val result = LocalCalculatorTool.tryEvaluate(input)
                result?.displayOutput ?: "Could not evaluate: $input"
            }
            "search_notes" -> {
                val store = notesStore ?: return "No notes store available."
                val matches = store.searchNotes(input).take(3)
                if (matches.isEmpty()) "No notes found for: $input"
                else matches.joinToString("\n---\n") { "**${it.title}**\n${it.content.take(300)}" }
            }
            "save_note" -> {
                val store = notesStore ?: return "No notes store available."
                val parts = input.split("|", limit = 2)
                val title = parts.getOrElse(0) { "Quick Note" }.trim()
                val content = parts.getOrElse(1) { input }.trim()
                if (title.isBlank()) return "Title cannot be blank."
                try {
                    store.createNote(title, content)
                    "Saved note: '$title'"
                } catch (_: Exception) {
                    "Could not save note."
                }
            }
            "search_docs" -> {
                val vault = documentVault ?: return "No documents indexed. Please attach or index a document first."
                val hits = vault.search(input, topK = 3)
                if (hits.isEmpty()) "No matching excerpts in Document Vault for: $input"
                else hits.joinToString("\n---\n")
            }
            "get_datetime" -> {
                val now = ZonedDateTime.now()
                val date = now.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val day = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                val time = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                "$date ($day) $time [${now.zone.id}]"
            }
            "list_notes" -> {
                val store = notesStore ?: return "No notes store available."
                val notes = store.listNotes().take(10)
                if (notes.isEmpty()) "No notes saved."
                else notes.joinToString("\n") { "- ${it.title}" }
            }
            "update_note" -> {
                val store = notesStore ?: return "No notes store available."
                val parts = input.split("|", limit = 2)
                val title = parts.getOrElse(0) { "" }.trim()
                val content = parts.getOrElse(1) { "" }.trim()
                if (title.isBlank()) return "Title cannot be blank."
                val existing = store.listNotes().firstOrNull { it.title.equals(title, ignoreCase = true) }
                if (existing != null) {
                    store.saveNote(existing.withUpdatedContent(newContent = content))
                    "Updated note: '${existing.title}'"
                } else {
                    store.createNote(title, content)
                    "Saved note: '$title'"
                }
            }
            "list_docs" -> {
                val vault = documentVault ?: return "No documents indexed. Please attach or index a document first."
                val docs = vault.loadAll().take(10)
                if (docs.isEmpty()) "No documents indexed in Document Vault."
                else docs.joinToString("\n") { "- ${it.name} (${it.extractedText.length} chars)" }
            }
            "search_memory" -> {
                val store = userMemoryStore ?: return "No memory store available."
                val memories = store.listMemories()
                if (memories.isEmpty()) return "No memories stored."
                val trimmed = input.trim()
                val matches = if (trimmed.isBlank() || trimmed.equals("all", ignoreCase = true)) {
                    memories.take(5)
                } else {
                    val tokens = trimmed.lowercase(Locale.ROOT)
                        .split(Regex("""\s+"""))
                        .filter { it.isNotBlank() }
                    memories.filter { memory ->
                        val haystack = "${memory.displayLabel} ${memory.content}".lowercase(Locale.ROOT)
                        tokens.any { token -> haystack.contains(token) }
                    }.take(5)
                }
                if (matches.isEmpty()) "No memories found for: $input"
                else matches.joinToString("\n") { "- [${it.displayLabel}] ${it.content}" }
            }
            "search_history" -> {
                val store = sessionStore ?: return "No session history available."
                val matches = store.searchSessions(input).take(3)
                if (matches.isEmpty()) "No matching conversations found for: $input"
                else matches.joinToString("\n") { session ->
                    val date = Instant.ofEpochMilli(session.updatedAt)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val queryLower = input.trim().lowercase(Locale.ROOT)
                    val snippetSource = if (queryLower.isNotEmpty()) {
                        session.messages.firstOrNull { it.content.lowercase(Locale.ROOT).contains(queryLower) }?.content
                            ?: session.lastMessage?.content
                            ?: ""
                    } else {
                        session.lastMessage?.content ?: ""
                    }
                    val snippet = snippetSource.replace('\n', ' ').trim().take(120)
                    "- ${session.title} ($date): $snippet"
                }
            }
            "save_artifact" -> {
                val store = artifactStore ?: return "No artifact store available."
                val parts = input.split("|", limit = 3)
                if (parts.size < 3) return "Invalid format. Expected: <title>|<language>|<code>"
                val title = parts[0].trim()
                val language = parts[1].trim().ifBlank { "text" }
                val code = parts[2]
                if (title.isBlank()) return "Artifact title cannot be blank."
                val saved = store.save(ArtifactStore.Artifact(title = title, language = language, code = code))
                if (saved) "Saved artifact: '$title' ($language)" else "Could not save artifact."
            }
            "read_artifact" -> {
                val store = artifactStore ?: return "No artifact store available."
                val all = store.loadAll()
                if (all.isEmpty()) return "No artifacts saved."
                val query = input.trim()
                val match = if (query.isBlank()) {
                    all.first()
                } else {
                    all.firstOrNull { it.title.equals(query, ignoreCase = true) }
                        ?: all.firstOrNull {
                            it.title.contains(query, ignoreCase = true) ||
                                it.language.equals(query, ignoreCase = true)
                        }
                }
                if (match == null) "No artifact found for: $input"
                else "[${match.title} (${match.language})]\n${match.code.take(1_200)}"
            }
            "device_status" -> {
                deviceStatusProvider?.invoke() ?: "Device status unavailable."
            }
            "run_js" -> {
                jsEvaluator?.invoke(input) ?: "JavaScript sandbox unavailable."
            }
            "trigger_system_action" -> {
                val bridge = systemActionBridge ?: return "System action bridge unavailable."
                val parts = input.split("|", limit = 2)
                val type = parts.getOrElse(0) { "" }.trim()
                val details = parts.getOrElse(1) { "" }.trim()
                if (type.isBlank()) return "System action type cannot be blank."
                bridge.invoke(type, details)
            }
            "read_clipboard" -> {
                clipboardReader?.invoke() ?: "Clipboard access unavailable."
            }
            "write_clipboard" -> {
                if (input.isBlank()) "Clipboard text cannot be blank."
                else clipboardWriter?.invoke(input) ?: "Clipboard write unavailable."
            }
            else -> "Unknown tool: $toolName"
        }
    }

    fun stripToolCallLines(text: String): String = removeToolCallLines(text)

    private fun removeToolCallLines(text: String): String {
        return text.lines()
            .filter { !it.trim().startsWith("TOOL_CALL:") }
            .joinToString("\n")
            .trim()
    }
}
