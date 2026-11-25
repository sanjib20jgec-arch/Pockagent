package com.example.pocketagent

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.transition.TransitionManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

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
 * Callback bundle wired into an assistant turn card for code copy/preview, answer export,
 * regeneration, and refinement actions.
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
    val onPreviewCode: (code: String, language: String?) -> Unit = { _, _ -> },
)

/**
 * Mutable handle to an assistant message card supporting live streaming updates (including
 * incremental code-fence cards), optional tool trace chips, and completed rich markdown binding
 * with collapsible long-turn support.
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
    val toolTraceContainer: LinearLayout,
    val liveStreamContainer: LinearLayout,
    private val latestOnlyActionViews: List<View> = emptyList(),
    private val palette: UiPalette,
    private val callbacks: AssistantTurnCallbacks,
    initialLatest: Boolean = false,
) {
    private var fullMarkdown: String = ""
    private var isCollapsed: Boolean = false
    var latestActionsEnabled: Boolean = initialLatest

    init {
        showMoreButton.setOnClickListener {
            if (fullMarkdown.isEmpty()) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            TransitionManager.beginDelayedTransition(rootCard)
            isCollapsed = !isCollapsed
            renderCurrentMarkdownState()
        }
    }

    /**
     * Binds throttled output while generation is actively streaming, rendering live code fences
     * in dark monospace preview cards while keeping plain prose on the lightweight TextView path.
     */
    fun bindStreamingText(plainText: String) {
        fullMarkdown = plainText
        isCollapsed = false
        richMarkdownContainer.visibility = View.GONE
        richMarkdownContainer.removeAllViews()
        showMoreButton.visibility = View.GONE
        actionsContainer.visibility = View.GONE

        if (!plainText.contains("```")) {
            liveStreamContainer.visibility = View.GONE
            liveStreamContainer.removeAllViews()
            streamingTextView.text = plainText
            streamingTextView.visibility = if (plainText.isNotEmpty()) View.VISIBLE else View.GONE
            return
        }

        streamingTextView.text = ""
        streamingTextView.visibility = View.GONE
        liveStreamContainer.removeAllViews()

        val segments = StreamingCodePreviewParser.splitForStreamingPreview(plainText)
        if (segments.isEmpty()) {
            liveStreamContainer.visibility = View.GONE
            return
        }

        val context = liveStreamContainer.context
        liveStreamContainer.visibility = View.VISIBLE
        for (segment in segments) {
            val child = when (segment) {
                is StreamingCodePreviewParser.Segment.Prose -> buildStreamingProseView(context, segment.text)
                is StreamingCodePreviewParser.Segment.Code -> buildStreamingCodeCard(context, segment)
            }
            liveStreamContainer.addView(child)
        }
    }

    /**
     * Replaces streaming output with structured rich markdown blocks, configuring initial collapse
     * state for historical long turns and action-row visibility.
     */
    fun bindCompletedMarkdown(
        markdown: String,
        isLatest: Boolean,
        showActions: Boolean,
    ) {
        fullMarkdown = markdown
        latestActionsEnabled = isLatest
        isCollapsed = shouldCollapseByDefault(markdown, isLatestAssistant = isLatest)

        streamingTextView.text = ""
        streamingTextView.visibility = View.GONE
        liveStreamContainer.visibility = View.GONE
        liveStreamContainer.removeAllViews()
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
        latestActionsEnabled = latest
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

    /**
     * Binds compact agent/tool activity pill chips (e.g. `Calculator · 42`, `Notes · 1 match`,
     * `Memory`) and an optional collapsible [summaryLine] inside [toolTraceContainer].
     */
    fun bindAgentTraceChips(steps: List<Pair<String, String>>, summaryLine: String? = null) {
        toolTraceContainer.removeAllViews()
        val cleanedSteps = steps.mapNotNull { (tool, detail) ->
            val cleanTool = tool.trim()
            val cleanDetail = detail.trim()
            if (cleanTool.isEmpty() && cleanDetail.isEmpty()) {
                null
            } else {
                cleanTool to cleanDetail
            }
        }
        val trimmedSummary = summaryLine?.trim().orEmpty()
        if (cleanedSteps.isEmpty() && trimmedSummary.isEmpty()) {
            toolTraceContainer.visibility = View.GONE
            return
        }

        val context = toolTraceContainer.context
        toolTraceContainer.visibility = View.VISIBLE

        if (cleanedSteps.isNotEmpty()) {
            val chipsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for ((index, step) in cleanedSteps.withIndex()) {
                val (tool, detail) = step
                val chipText = SpannableStringBuilder().apply {
                    if (tool.isNotEmpty() && detail.isNotEmpty()) {
                        append(tool)
                        setSpan(ForegroundColorSpan(palette.teal), 0, tool.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(StyleSpan(Typeface.BOLD), 0, tool.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        val detailStart = length
                        append(" · ").append(detail)
                        setSpan(ForegroundColorSpan(palette.muted), detailStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        val single = tool.ifEmpty { detail }
                        append(single)
                        setSpan(ForegroundColorSpan(palette.teal), 0, single.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(StyleSpan(Typeface.BOLD), 0, single.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                val chipView = TextView(context).apply {
                    text = chipText
                    textSize = 11.5f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    maxLines = 1
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = dp(context, 230)
                    background = roundedShape(
                        color = palette.surfaceElevated,
                        radiusPx = dp(context, 14).toFloat(),
                        strokeWidthPx = maxOf(1, dp(context, 1)),
                        strokeColor = palette.border,
                    )
                    setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (index < cleanedSteps.lastIndex) {
                            marginEnd = dp(context, 6)
                        }
                    }
                }
                chipsRow.addView(chipView)
            }

            val chipsScroller = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                addView(chipsRow)
            }
            toolTraceContainer.addView(chipsScroller)
        }

        if (trimmedSummary.isNotEmpty()) {
            val collapsedMaxLines = if (cleanedSteps.isEmpty()) 3 else 1
            var expanded = false
            val summaryView = TextView(context).apply {
                text = trimmedSummary
                textSize = 11.5f
                setTextColor(palette.muted)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                maxLines = collapsedMaxLines
                ellipsize = TextUtils.TruncateAt.END
                if (cleanedSteps.isEmpty()) {
                    background = roundedShape(
                        color = palette.surfaceElevated,
                        radiusPx = dp(context, 12).toFloat(),
                        strokeWidthPx = maxOf(1, dp(context, 1)),
                        strokeColor = palette.border,
                    )
                    setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6))
                } else {
                    setPadding(dp(context, 2), dp(context, 5), dp(context, 2), 0)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setOnClickListener {
                    expanded = !expanded
                    TransitionManager.beginDelayedTransition(rootCard)
                    maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines
                }
            }
            toolTraceContainer.addView(summaryView)
        }
    }

    internal fun showMoreActionsDialog() {
        val context = rootCard.context
        val items = mutableListOf(
            Triple("Share answer", "Share this answer", callbacks.onShareAnswer),
        )
        if (latestActionsEnabled) {
            items += Triple("Save to .txt", "Save answer to a text file", callbacks.onSaveAnswer)
            items += Triple("Export Markdown", "Export answer as Markdown", callbacks.onExportMarkdown)
        }

        val container = styleDialogContainer(context, palette, paddingDp = 16)
        val titleView = styledText(
            context = context,
            text = "More actions",
            sizeSp = 15f,
            color = palette.ink,
            bold = true,
        ).apply {
            setPadding(dp(context, 4), 0, dp(context, 4), dp(context, 10))
        }
        container.addView(titleView)
        container.addView(dividerView(context, palette))

        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()

        for ((index, spec) in items.withIndex()) {
            val (label, a11y, action) = spec
            val rowButton = Button(context).apply {
                val minTouch = dp(context, 48)
                text = label
                contentDescription = a11y
                setAllCaps(false)
                textSize = 14f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setTextColor(palette.ink)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = minTouch
                minimumHeight = minTouch
                setPadding(dp(context, 8), dp(context, 10), dp(context, 8), dp(context, 10))
                background = rippleDrawable(
                    roundedShape(Color.TRANSPARENT, dp(context, 12).toFloat()),
                    palette.rippleOnSurface,
                )
                backgroundTintList = null
                stateListAnimator = null
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(context, if (index == 0) 6 else 2)
                }
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    dialog.dismiss()
                    action()
                }
            }
            container.addView(rowButton)
        }

        showStyledAlertDialog(dialog, palette)
    }

    private fun buildStreamingProseView(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(palette.ink)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            includeFontPadding = true
            breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
            setHorizontallyScrolling(false)
            setLineSpacing(dp(context, 4).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 2)
                bottomMargin = dp(context, 6)
            }
        }

    private fun buildStreamingCodeCard(
        context: Context,
        segment: StreamingCodePreviewParser.Segment.Code,
    ): LinearLayout {
        val radiusPx = dp(context, 14).toFloat()
        val strokePx = maxOf(1, dp(context, 1))
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedShape(
                color = palette.codeBg,
                radiusPx = radiusPx,
                strokeWidthPx = strokePx,
                strokeColor = palette.codeBorder,
            )
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 4)
                bottomMargin = dp(context, 8)
            }
        }

        val innerRadius = dp(context, 13).toFloat()
        val headerBackground = GradientDrawable().apply {
            setColor(palette.codeHeaderBg)
            cornerRadii = floatArrayOf(
                innerRadius, innerRadius,
                innerRadius, innerRadius,
                0f, 0f,
                0f, 0f,
            )
        }
        val langLabel = (segment.language?.takeIf { it.isNotBlank() } ?: "code").uppercase(Locale.ROOT)
        val headerTitle = if (segment.isClosed) langLabel else "WRITING $langLabel…"

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = headerBackground
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
        }
        val badgeView = TextView(context).apply {
            text = headerTitle
            textSize = 11f
            setTextColor(if (segment.isClosed) palette.codeComment else palette.teal)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(badgeView)
        card.addView(headerRow)

        val codeView = TextView(context).apply {
            text = segment.code
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.codeText)
            setLineSpacing(dp(context, 2).toFloat(), 1f)
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 12))
        }
        val scrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            addView(codeView)
        }
        card.addView(scrollView)
        return card
    }

    private fun renderCurrentMarkdownState() {
        val canCollapse = canOfferCollapseToggle(fullMarkdown)
        val displayed = if (isCollapsed) collapsePreview(fullMarkdown) else fullMarkdown

        MarkdownRichRenderer.renderInto(
            container = richMarkdownContainer,
            markdown = displayed,
            palette = palette,
            onCopyCode = callbacks.onCopyCode,
            onPreviewCode = callbacks.onPreviewCode,
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
        val screenWidthPx = context.resources.displayMetrics.widthPixels
        // Reserve chatCanvas (28dp) + row start indent (44dp) so the bubble never overflows narrow windows.
        val availableRowW = (screenWidthPx - dp(context, 72)).coerceAtLeast(dp(context, 120))
        val maxBubbleW = minOf(
            (screenWidthPx * 0.78f).toInt().coerceAtLeast(dp(context, 160)),
            availableRowW,
        )
        val maxBodyW = (maxBubbleW - dp(context, 28)).coerceAtLeast(dp(context, 92))

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
                text = "Attached: $summary"
                textSize = 12f
                setTextColor(palette.teal)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                maxWidth = maxBodyW
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
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                includeFontPadding = true
                breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
                maxWidth = maxBodyW
                setLineSpacing(dp(context, 3).toFloat(), 1f)
                val desiredW = ceil(Layout.getDesiredWidth(text, paint).toDouble()).toInt()
                val clampedMin = minOf(dp(context, 24), maxBodyW)
                layoutParams = LinearLayout.LayoutParams(
                    (desiredW + dp(context, 4)).coerceIn(clampedMin, maxBodyW),
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setTextIsSelectable(true)
                setHorizontallyScrolling(false)
            }
            bubble.addView(bodyView)
        }

        val tsLabel = formatTimestampLabel(message.timestamp)
        if (tsLabel.isNotEmpty()) {
            val tsView = TextView(context).apply {
                text = tsLabel
                textSize = 11f
                setTextColor(palette.muted)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
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
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
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
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
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

        val toolTraceContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 8)
            }
        }
        rootCard.addView(toolTraceContainer)

        val streamingTextView = TextView(context).apply {
            textSize = 15f
            setTextColor(palette.ink)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            includeFontPadding = true
            breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
            setLineSpacing(dp(context, 4).toFloat(), 1f)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 10)
            }
            setTextIsSelectable(true)
            setHorizontallyScrolling(false)
        }
        rootCard.addView(streamingTextView)

        val liveStreamContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 10)
            }
        }
        rootCard.addView(liveStreamContainer)

        val richMarkdownContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 10)
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
                topMargin = dp(context, 8)
            }
        }
        rootCard.addView(citationsContainer)

        val refineButton = pillButton(
            context = context,
            palette = palette,
            label = "Refine answer",
            accessibilityLabel = "Refine this answer with a corrective second pass",
            compact = true,
            onClick = callbacks.onRefine,
        ).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 10)
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
                topMargin = dp(context, 8)
            }
        }
        rootCard.addView(systemActionContainer)

        val actionsStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val latestOnlyViews = mutableListOf<View>()
        var handleRef: ActiveAssistantCardHandle? = null

        fun buildCompactActionButton(
            label: String,
            accessibilityLabel: String,
            textColor: Int = palette.muted,
            onClick: () -> Unit,
        ): Button = Button(context).apply {
            val minTouch = dp(context, 48)
            val vInset = dp(context, 6)
            text = label
            contentDescription = accessibilityLabel
            setAllCaps(false)
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            minHeight = minTouch
            minimumHeight = minTouch
            minWidth = minTouch
            minimumWidth = minTouch
            background = InsetDrawable(
                rippleDrawable(
                    roundedShape(palette.surfaceElevated, dp(context, 12).toFloat()),
                    palette.rippleOnSurface,
                ),
                0,
                vInset,
                0,
                vInset,
            )
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            backgroundTintList = null
            stateListAnimator = null
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }

        val openMoreMenu: () -> Unit = { handleRef?.showMoreActionsDialog() }
        val compactSpecs = listOf(
            Triple("Copy", "Copy answer", callbacks.onCopyAnswer) to false,
            Triple("Speak", "Read answer aloud", callbacks.onSpeakAnswer) to false,
            Triple("Regenerate", "Regenerate answer", callbacks.onRegenerate) to true,
            Triple("More ▾", "More answer actions", openMoreMenu) to false,
        )
        for ((index, pair) in compactSpecs.withIndex()) {
            val (spec, latestOnly) = pair
            val (label, a11y, action) = spec
            val btn = buildCompactActionButton(
                label = label,
                accessibilityLabel = a11y,
                textColor = if (label == "More ▾") palette.teal else palette.muted,
                onClick = action,
            ).apply {
                if (latestOnly && !isLatest) visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (index < compactSpecs.lastIndex) {
                        marginEnd = dp(context, 4)
                    }
                }
            }
            if (latestOnly) latestOnlyViews += btn
            actionsStrip.addView(btn)
        }

        val actionsContainer = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
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
            toolTraceContainer = toolTraceContainer,
            liveStreamContainer = liveStreamContainer,
            latestOnlyActionViews = latestOnlyViews,
            palette = palette,
            callbacks = callbacks,
            initialLatest = isLatest,
        )
        handleRef = handle

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
