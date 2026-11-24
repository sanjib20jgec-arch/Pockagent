package com.example.pocketagent

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val COLLAPSE_THRESHOLD_CHARS = 950
const val COLLAPSE_THRESHOLD_LINES = 18
private const val COLLAPSE_PREVIEW_MAX_LINES = 10

/**
 * Returns `true` when [content] is long enough to merit a collapse/expand toggle.
 */
fun canOfferCollapseToggle(content: String): Boolean {
    if (content.isEmpty()) return false
    if (content.length > COLLAPSE_THRESHOLD_CHARS) return true
    return countLogicalLines(content) > COLLAPSE_THRESHOLD_LINES
}

/**
 * Returns `true` when an assistant message should start collapsed.
 *
 * The latest assistant turn is always expanded by default so the reader sees the current answer
 * immediately, while older long turns collapse to keep scrollback compact.
 */
fun shouldCollapseByDefault(content: String, isLatestAssistant: Boolean): Boolean {
    if (isLatestAssistant) return false
    return canOfferCollapseToggle(content)
}

/**
 * Produces a clean collapsed preview of [content] bounded by [maxChars] (and excessive line count),
 * breaking at a word or line boundary when possible and appending an ellipsis when truncated.
 */
fun collapsePreview(content: String, maxChars: Int = 480): String {
    if (maxChars <= 0 || content.isEmpty()) return ""

    val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split('\n')
    val lineLimited = if (lines.size > COLLAPSE_THRESHOLD_LINES) {
        lines.take(COLLAPSE_PREVIEW_MAX_LINES).joinToString("\n")
    } else {
        normalized
    }
    val wasLineTruncated = lines.size > COLLAPSE_THRESHOLD_LINES

    if (!wasLineTruncated && lineLimited.length <= maxChars) {
        return content
    }

    val budget = (maxChars - 1).coerceAtLeast(0)
    if (budget == 0) return "…"

    val candidate = if (lineLimited.length > budget) {
        val rawSlice = lineLimited.substring(0, budget)
        val minBreakIndex = (budget * 6) / 10
        val lastBreak = rawSlice.indexOfLast { it == '\n' || it == ' ' || it == '\t' }
        if (lastBreak >= minBreakIndex) {
            rawSlice.substring(0, lastBreak)
        } else {
            rawSlice
        }
    } else {
        lineLimited
    }

    return candidate.trimEnd() + "…"
}

/**
 * Formats [epochMs] as `HH:mm` when on the current local calendar day, or `MMM d · HH:mm` for
 * earlier/other dates.
 */
fun formatTimestampLabel(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val nowCal = Calendar.getInstance()
    val msgCal = Calendar.getInstance().apply { timeInMillis = epochMs }
    val sameDay = nowCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
        nowCal.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "MMM d · HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMs))
}

private fun countLogicalLines(content: String): Int {
    if (content.isEmpty()) return 0
    var lines = 1
    var i = 0
    while (i < content.length) {
        val ch = content[i]
        if (ch == '\r') {
            lines++
            if (i + 1 < content.length && content[i + 1] == '\n') {
                i++
            }
        } else if (ch == '\n') {
            lines++
        }
        i++
    }
    return lines
}

/**
 * Callback bundle wired into an assistant turn card for code copy, answer export, regeneration,
 * and refinement actions.
 */
data class AssistantTurnCallbacks(
    val onCopyCode: (code: String, language: String?) -> Unit = { _, _ -> },
    val onCopyAnswer: () -> Unit = {},
    val onShareAnswer: () -> Unit = {},
    val onSpeakAnswer: () -> Unit = {},
    val onSaveAnswer: () -> Unit = {},
    val onExportMarkdown: () -> Unit = {},
    val onRegenerate: () -> Unit = {},
    val onRefine: () -> Unit = {},
)

/**
 * Mutable handle to an assistant message card supporting live plain-text streaming updates and
 * final rich markdown binding with collapsible long-turn support.
 */
class ActiveAssistantCardHandle(
    val rootCard: LinearLayout,
    val statusDot: View,
    val statusLabel: TextView,
    val timestampView: TextView,
    val streamingTextView: TextView,
    val richMarkdownContainer: LinearLayout,
    val citationsContainer: LinearLayout,
    val systemActionContainer: LinearLayout,
    val refineButton: Button,
    val actionsContainer: View,
    val showMoreButton: Button,
    private val latestOnlyActionViews: List<View> = emptyList(),
    private val palette: UiPalette,
    private val callbacks: AssistantTurnCallbacks,
) {
    private var fullMarkdown: String = ""
    private var isCollapsed: Boolean = false

    init {
        showMoreButton.setOnClickListener {
            if (fullMarkdown.isEmpty()) return@setOnClickListener
            isCollapsed = !isCollapsed
            renderCurrentMarkdownState()
        }
    }

    /**
     * Binds throttled plain-text output while generation is actively streaming.
     */
    fun bindStreamingText(plainText: String) {
        fullMarkdown = plainText
        isCollapsed = false
        streamingTextView.text = plainText
        streamingTextView.visibility = if (plainText.isNotEmpty()) View.VISIBLE else View.GONE
        richMarkdownContainer.visibility = View.GONE
        richMarkdownContainer.removeAllViews()
        showMoreButton.visibility = View.GONE
        actionsContainer.visibility = View.GONE
    }

    /**
     * Replaces streaming plain text with structured rich markdown blocks, configuring initial
     * collapse state for historical long turns and optional action-strip visibility.
     */
    fun bindCompletedMarkdown(
        markdown: String,
        isLatest: Boolean,
        showActions: Boolean,
    ) {
        fullMarkdown = markdown
        isCollapsed = shouldCollapseByDefault(markdown, isLatestAssistant = isLatest)

        streamingTextView.text = ""
        streamingTextView.visibility = View.GONE
        richMarkdownContainer.visibility = if (markdown.isNotBlank()) View.VISIBLE else View.GONE

        renderCurrentMarkdownState()
        latestOnlyActionViews.forEach { view ->
            view.visibility = if (isLatest) View.VISIBLE else View.GONE
        }
        actionsContainer.visibility = if (showActions && markdown.isNotBlank()) View.VISIBLE else View.GONE
    }

    /**
     * Updates whether this card is the latest assistant turn (controlling latest-only actions).
     */
    fun setLatestTurn(latest: Boolean) {
        latestOnlyActionViews.forEach { view ->
            view.visibility = if (latest) View.VISIBLE else View.GONE
        }
        if (!latest) {
            refineButton.visibility = View.GONE
        }
    }

    /**
     * Updates the top-left badge label and status dot color.
     */
    fun setStatusBadge(label: String, dotColor: Int) {
        statusLabel.text = label
        val dotSize = maxOf(dp(statusDot.context, 8), 8)
        statusDot.background = roundedShape(dotColor, (dotSize / 2f))
    }

    private fun renderCurrentMarkdownState() {
        val canCollapse = canOfferCollapseToggle(fullMarkdown)
        val displayed = if (isCollapsed) collapsePreview(fullMarkdown) else fullMarkdown

        MarkdownRichRenderer.renderInto(
            container = richMarkdownContainer,
            markdown = displayed,
            palette = palette,
            onCopyCode = callbacks.onCopyCode,
        )

        if (canCollapse) {
            showMoreButton.visibility = View.VISIBLE
            val label = if (isCollapsed) "Show more" else "Show less"
            showMoreButton.text = label
            showMoreButton.contentDescription =
                if (isCollapsed) "Expand full assistant message" else "Collapse assistant message"
        } else {
            showMoreButton.visibility = View.GONE
        }
    }
}

/**
 * Builds themed user and assistant chat cards parameterized by [UiPalette].
 */
object ChatMessageCardBuilder {

    /**
     * Builds a right-aligned user prompt bubble with optional attachment chip and timestamp.
     */
    fun buildUserCard(
        context: Context,
        message: ChatMessage,
        palette: UiPalette,
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(context, 44), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedShape(
                color = palette.userBubbleBg,
                radiusPx = dp(context, 18).toFloat(),
                strokeWidthPx = maxOf(1, dp(context, 1)),
                strokeColor = palette.userBubbleBorder,
            )
            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.END
            }
        }

        val summary = message.attachmentSummary?.trim().orEmpty()
        if (summary.isNotEmpty()) {
            val chip = TextView(context).apply {
                text = "📎 $summary"
                textSize = 12f
                setTextColor(palette.teal)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                background = roundedShape(palette.mint, dp(context, 12).toFloat())
                setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = if (message.content.isNotBlank()) dp(context, 6) else 0
                }
            }
            bubble.addView(chip)
        }

        if (message.content.isNotBlank()) {
            val bodyView = TextView(context).apply {
                text = message.content
                textSize = 15f
                setTextColor(palette.ink)
                setTextIsSelectable(true)
                setLineSpacing(dp(context, 3).toFloat(), 1f)
            }
            bubble.addView(bodyView)
        }

        val tsLabel = formatTimestampLabel(message.timestamp)
        if (tsLabel.isNotEmpty()) {
            val tsView = TextView(context).apply {
                text = tsLabel
                textSize = 11f
                setTextColor(palette.muted)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.END
                    topMargin = dp(context, 4)
                }
            }
            bubble.addView(tsView)
        }

        row.addView(bubble)
        return row
    }

    /**
     * Builds a full-width assistant turn card wrapped in an [ActiveAssistantCardHandle] for live
     * streaming and completed rich markdown rendering.
     */
    fun buildAssistantCard(
        context: Context,
        message: ChatMessage?,
        palette: UiPalette,
        isLatest: Boolean,
        callbacks: AssistantTurnCallbacks,
    ): ActiveAssistantCardHandle {
        val rootCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedShape(
                color = palette.assistantBubbleBg,
                radiusPx = dp(context, 18).toFloat(),
                strokeWidthPx = maxOf(1, dp(context, 1)),
                strokeColor = palette.assistantBubbleBorder,
            )
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val dotSize = dp(context, 8)
        val statusDot = View(context).apply {
            background = roundedShape(palette.teal, (dotSize / 2f))
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = dp(context, 8)
            }
        }
        headerRow.addView(statusDot)

        val statusLabel = styledText(
            context = context,
            text = "PocketAgent",
            sizeSp = 13f,
            color = palette.ink,
            bold = true,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(statusLabel)

        val tsText = message?.let { formatTimestampLabel(it.timestamp) }.orEmpty()
        val timestampView = TextView(context).apply {
            text = tsText
            textSize = 11f
            setTextColor(palette.muted)
            visibility = if (tsText.isNotEmpty()) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(context, 6)
            }
        }
        headerRow.addView(timestampView)

        val showMoreButton = secondaryOutlineButton(
            context = context,
            palette = palette,
            label = "Show more",
            accessibilityLabel = "Expand full assistant message",
            compact = true,
            textColor = palette.teal,
            onClick = {},
        ).apply {
            visibility = View.GONE
        }
        headerRow.addView(showMoreButton)
        rootCard.addView(headerRow)

        val streamingTextView = TextView(context).apply {
            textSize = 15f
            setTextColor(palette.ink)
            setTextIsSelectable(true)
            setLineSpacing(dp(context, 4).toFloat(), 1f)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 8)
            }
        }
        rootCard.addView(streamingTextView)

        val richMarkdownContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 8)
            }
        }
        rootCard.addView(richMarkdownContainer)

        val citationsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 6)
            }
        }
        rootCard.addView(citationsContainer)

        val refineButton = pillButton(
            context = context,
            palette = palette,
            label = "✨ Refine answer",
            accessibilityLabel = "Refine this answer with a corrective second pass",
            compact = true,
            onClick = callbacks.onRefine,
        ).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 8)
            }
        }
        rootCard.addView(refineButton)

        val systemActionContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 6)
            }
        }
        rootCard.addView(systemActionContainer)

        val actionsStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val latestOnlyViews = mutableListOf<View>()
        val actionSpecs = listOf(
            Triple("Copy", "Copy answer", callbacks.onCopyAnswer) to false,
            Triple("Share", "Share answer", callbacks.onShareAnswer) to false,
            Triple("Speak", "Read answer aloud", callbacks.onSpeakAnswer) to false,
            Triple("Save", "Save answer to text file", callbacks.onSaveAnswer) to true,
            Triple("Regenerate", "Regenerate answer", callbacks.onRegenerate) to true,
            Triple("Export MD", "Export answer as Markdown", callbacks.onExportMarkdown) to true,
        )
        for ((index, pair) in actionSpecs.withIndex()) {
            val (spec, latestOnly) = pair
            val (label, a11y, action) = spec
            val btn = secondaryOutlineButton(
                context = context,
                palette = palette,
                label = label,
                accessibilityLabel = a11y,
                compact = true,
                onClick = action,
            ).apply {
                if (latestOnly && !isLatest) visibility = View.GONE
                if (index < actionSpecs.lastIndex) {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        marginEnd = dp(context, 6)
                    }
                }
            }
            if (latestOnly) latestOnlyViews += btn
            actionsStrip.addView(btn)
        }

        val actionsContainer = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 10)
            }
            addView(actionsStrip)
        }
        rootCard.addView(actionsContainer)

        val handle = ActiveAssistantCardHandle(
            rootCard = rootCard,
            statusDot = statusDot,
            statusLabel = statusLabel,
            timestampView = timestampView,
            streamingTextView = streamingTextView,
            richMarkdownContainer = richMarkdownContainer,
            citationsContainer = citationsContainer,
            systemActionContainer = systemActionContainer,
            refineButton = refineButton,
            actionsContainer = actionsContainer,
            showMoreButton = showMoreButton,
            latestOnlyActionViews = latestOnlyViews,
            palette = palette,
            callbacks = callbacks,
        )

        if (message != null && message.content.isNotBlank()) {
            handle.bindCompletedMarkdown(
                markdown = message.content,
                isLatest = isLatest,
                showActions = true,
            )
        }

        return handle
    }
}
