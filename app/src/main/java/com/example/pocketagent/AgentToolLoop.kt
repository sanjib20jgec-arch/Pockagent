package com.example.pocketagent

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
 *   TOOL_CALL: search_notes(<query>)
 *   TOOL_CALL: save_note(<title>|<content>)
 *   TOOL_CALL: search_docs(<query>)
 *
 * 100% offline, zero network calls, zero telemetry.
 */
open class AgentToolLoop(
    private val notesStore: PersonalNotesStore? = null,
    private val documentVault: DocumentVault? = null,
    private val maxIterations: Int = 3,
    private val toolTimeoutMs: Long = TOOL_EXECUTION_TIMEOUT_MS,
) {

    companion object {
        const val TOOL_EXECUTION_TIMEOUT_MS = 10_000L
        const val AGENT_MODE_INSTRUCTION =
            "Agent Mode is active. You have access to offline on-device tools:\n" +
                "- TOOL_CALL: calc(<math_or_conversion_expression>)\n" +
                "- TOOL_CALL: search_notes(<keywords>)\n" +
                "- TOOL_CALL: save_note(<title>|<content>)\n" +
                "- TOOL_CALL: search_docs(<keywords>)\n" +
                "If a calculation, stored note, or indexed document lookup helps answer accurately, " +
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
