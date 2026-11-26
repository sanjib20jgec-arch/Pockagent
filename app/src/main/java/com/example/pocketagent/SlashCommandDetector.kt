package com.example.pocketagent

/**
 * Detects slash commands typed at the start of the composer and resolves them
 * to system prompt prefixes that bias the model's output style.
 *
 * All commands are offline and rule-based; no network access.
 */
object SlashCommandDetector {

    data class SlashCommand(
        val trigger: String,
        val displayName: String,
        val description: String,
        val systemPromptPrefix: String,
    )

    val ALL_COMMANDS: List<SlashCommand> = listOf(
        SlashCommand(
            trigger = "/summarize",
            displayName = "Summarize",
            description = "Summarize the content concisely",
            systemPromptPrefix = "Please provide a clear, concise summary. Use bullet points for key points. Be brief.",
        ),
        SlashCommand(
            trigger = "/code",
            displayName = "Code",
            description = "Write or explain code",
            systemPromptPrefix = "You are a coding assistant. Provide well-commented, working code. Use proper code blocks with language tags.",
        ),
        SlashCommand(
            trigger = "/translate",
            displayName = "Translate",
            description = "Translate to Bengali",
            systemPromptPrefix = "Please translate the following to Bengali (\u09AC\u09BE\u0982\u09B2\u09BE). Preserve meaning and tone.",
        ),
        SlashCommand(
            trigger = "/eli5",
            displayName = "ELI5",
            description = "Explain like I'm 5",
            systemPromptPrefix = "Explain this in the simplest possible terms, as if to a 5-year-old child. Use simple words, short sentences, and fun analogies.",
        ),
        SlashCommand(
            trigger = "/steps",
            displayName = "Step by step",
            description = "Break down into numbered steps",
            systemPromptPrefix = "Please provide a detailed step-by-step breakdown. Number each step clearly.",
        ),
        SlashCommand(
            trigger = "/pros",
            displayName = "Pros & Cons",
            description = "List pros and cons",
            systemPromptPrefix = "List the pros and cons in a clear two-column format with \u2705 for pros and \u274C for cons.",
        ),
    )

    fun detectCommand(text: String): SlashCommand? {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("/")) return null
        return ALL_COMMANDS.firstOrNull { cmd ->
            trimmed.startsWith(cmd.trigger) &&
            (trimmed.length == cmd.trigger.length || trimmed[cmd.trigger.length] == ' ')
        }
    }

    fun getSuggestions(partialText: String): List<SlashCommand> {
        val trimmed = partialText.trimStart()
        if (!trimmed.startsWith("/")) return emptyList()
        val partial = trimmed.lowercase()
        return ALL_COMMANDS.filter { cmd ->
            cmd.trigger.startsWith(partial) || cmd.displayName.lowercase().startsWith(partial.removePrefix("/"))
        }
    }

    fun stripCommand(text: String, command: SlashCommand): String {
        val trimmed = text.trimStart()
        return if (trimmed.startsWith(command.trigger)) {
            trimmed.removePrefix(command.trigger).trimStart()
        } else trimmed
    }

    fun buildEffectivePrompt(text: String, baseSystemInstruction: String): Pair<String, String> {
        val command = detectCommand(text) ?: return text to baseSystemInstruction
        val strippedPrompt = stripCommand(text, command)
        val enrichedInstruction = "${command.systemPromptPrefix}\n\n$baseSystemInstruction"
        return strippedPrompt to enrichedInstruction
    }
}
