package com.example.pocketagent

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.Locale

/** Semantic status tone for the compact active-model pill in the top bar. */
enum class ModelStatusTone {
    LOADED_GPU,
    LOADED_CPU,
    STANDBY,
    MISSING,
}

/** Immutable display state for [ModelStatusPillView]. */
data class ModelBadgeState(
    val label: String,
    val contentDescription: String,
    val tone: ModelStatusTone,
)

/**
 * Pure-Kotlin formatting and badge-state helpers for composer and top-bar chrome.
 * Separated from Android View classes so all formatting rules execute on JVM 17 without mocks.
 */
object ComposerChromeHelper {
    private const val MODEL_EXTENSION = ".litertlm"
    private const val MAX_MODEL_SHORT_LEN = 14

    /** Builds a concise top-bar [ModelBadgeState] for the current model and backend state. */
    fun formatModelBadge(
        displayName: String?,
        sizeBytes: Long,
        activeBackend: ActiveBackend?,
        preference: BackendPreference,
        formattedSize: String,
    ): ModelBadgeState {
        if (sizeBytes <= 0L) {
            return ModelBadgeState(
                label = "Choose model",
                contentDescription = "No active model. Tap to choose a LiteRT-LM model",
                tone = ModelStatusTone.MISSING,
            )
        }

        val cleanName = stripModelExtension(displayName)
        val shortName = truncateWithEllipsis(cleanName, MAX_MODEL_SHORT_LEN)
        val sizeTrimmed = formattedSize.trim()
        val sizePart = if (sizeTrimmed.isNotEmpty()) " ($sizeTrimmed)" else ""
        val prefLabel = when (preference) {
            BackendPreference.GPU_PREFERRED -> "GPU preferred"
            BackendPreference.CPU_ONLY -> "CPU only"
        }

        return when (activeBackend) {
            ActiveBackend.GPU -> ModelBadgeState(
                label = "$shortName · GPU",
                contentDescription = "Model $cleanName$sizePart loaded on GPU, preference $prefLabel. Tap to manage model",
                tone = ModelStatusTone.LOADED_GPU,
            )
            ActiveBackend.CPU -> ModelBadgeState(
                label = "$shortName · CPU",
                contentDescription = "Model $cleanName$sizePart loaded on CPU, preference $prefLabel. Tap to manage model",
                tone = ModelStatusTone.LOADED_CPU,
            )
            null -> ModelBadgeState(
                label = "$shortName · Standby",
                contentDescription = "Model $cleanName$sizePart on standby, preference $prefLabel. Tap to manage model",
                tone = ModelStatusTone.STANDBY,
            )
        }
    }

    /** Formats an elapsed streaming duration in milliseconds into a compact status string. */
    fun formatElapsedDuration(elapsedMs: Long): String {
        if (elapsedMs < 0L) return "0.0s"
        if (elapsedMs < 60_000L) {
            return String.format(Locale.US, "%.1fs", elapsedMs / 1000.0)
        }
        val totalSeconds = elapsedMs / 1000L
        val mins = totalSeconds / 60L
        val secs = totalSeconds % 60L
        return String.format(Locale.US, "%dm %02ds", mins, secs)
    }

    /** Formats a compact attachment character budget label such as `"3.4k / 12k chars"`. */
    fun formatCharBudgetLabel(usedChars: Int, maxChars: Int): String {
        val safeUsed = usedChars.coerceAtLeast(0)
        val safeMax = maxChars.coerceAtLeast(0)
        return "${formatCompactCount(safeUsed)} / ${formatCompactCount(safeMax)} chars"
    }

    /** Filters blanks and truncates attachment names to [maxChipLen] while preserving order. */
    fun formatIndividualChipLabels(names: List<String>, maxChipLen: Int = 22): List<String> {
        val safeMax = maxChipLen.coerceAtLeast(1)
        return names.mapNotNull { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                null
            } else {
                truncateWithEllipsis(trimmed, safeMax)
            }
        }
    }

    private fun stripModelExtension(displayName: String?): String {
        val trimmed = displayName?.trim().orEmpty()
        val withoutExt = if (trimmed.endsWith(MODEL_EXTENSION, ignoreCase = true)) {
            trimmed.dropLast(MODEL_EXTENSION.length).trim()
        } else {
            trimmed
        }
        return withoutExt.ifBlank { "Model" }
    }

    private fun truncateWithEllipsis(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        if (maxLen <= 1) return text.take(maxLen)
        return text.take(maxLen - 1) + "…"
    }

    private fun formatCompactCount(count: Int): String {
        if (count < 1000) return count.toString()
        val formatted = String.format(Locale.US, "%.1f", count / 1000.0)
        val clean = if (formatted.endsWith(".0")) formatted.dropLast(2) else formatted
        return "${clean}k"
    }
}

/**
 * Clickable top-bar pill showing an 8dp color-coded status dot and single-line model badge label
 * inside a minimum 48dp vertical touch target.
 */
class ModelStatusPillView(
    context: Context,
    initialPalette: UiPalette,
    onClick: () -> Unit,
) : LinearLayout(context) {

    private val dotDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
    }

    private val dotView = View(context).apply {
        val dotSize = dp(context, 8)
        background = dotDrawable
        layoutParams = LayoutParams(dotSize, dotSize).apply {
            marginEnd = dp(context, 7)
        }
    }

    private val labelView = styledText(
        context = context,
        text = "Choose model",
        sizeSp = 12.5f,
        color = initialPalette.ink,
        bold = true,
    ).apply {
        maxLines = 1
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val minTouch = dp(context, 48)
        minimumHeight = minTouch
        setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }

        addView(dotView)
        addView(labelView)

        update(
            ModelBadgeState(
                label = "Choose model",
                contentDescription = "No active model. Tap to choose a LiteRT-LM model",
                tone = ModelStatusTone.MISSING,
            ),
            initialPalette,
        )
    }

    /** Updates the status dot color, badge text, ink color, ripple background, and description. */
    fun update(state: ModelBadgeState, palette: UiPalette) {
        labelView.text = state.label
        contentDescription = state.contentDescription

        val dotColor: Int
        val bgColor: Int
        val borderColor: Int
        val textColor: Int
        val rippleColor: Int

        when (state.tone) {
            ModelStatusTone.LOADED_GPU -> {
                dotColor = palette.statusLoadedGpu
                bgColor = palette.mint
                borderColor = palette.userBubbleBorder
                textColor = palette.teal
                rippleColor = palette.rippleOnMint
            }
            ModelStatusTone.LOADED_CPU -> {
                dotColor = palette.statusLoadedCpu
                bgColor = palette.mint
                borderColor = palette.userBubbleBorder
                textColor = palette.teal
                rippleColor = palette.rippleOnMint
            }
            ModelStatusTone.STANDBY -> {
                dotColor = palette.statusStandby
                bgColor = palette.surfaceElevated
                borderColor = palette.border
                textColor = palette.ink
                rippleColor = palette.rippleOnSurface
            }
            ModelStatusTone.MISSING -> {
                dotColor = palette.statusMissing
                bgColor = palette.dangerBg
                borderColor = palette.dangerBorder
                textColor = palette.dangerInk
                rippleColor = palette.rippleOnSurface
            }
        }

        dotDrawable.setColor(dotColor)
        labelView.setTextColor(textColor)

        val vInset = dp(context, 4)
        val capsule = rippleDrawable(
            roundedShape(
                color = bgColor,
                radiusPx = dp(context, 20).toFloat(),
                strokeWidthPx = maxOf(1, dp(context, 1)),
                strokeColor = borderColor,
            ),
            rippleColor,
        )
        background = InsetDrawable(capsule, 0, vInset, 0, vInset)
    }
}

/** View builders for compact composer attachment chips and floating scroll-to-latest chrome. */
object ComposerChromeViews {

    /**
     * Builds a compact attachment strip containing a [HorizontalScrollView] of individual file
     * chips, a character budget indicator pill, and 48dp `"Edit"` / `"×"` controls.
     */
    fun buildAttachmentChipsStrip(
        context: Context,
        palette: UiPalette,
        names: List<String>,
        usedChars: Int,
        maxChars: Int,
        onInspect: () -> Unit,
        onClear: () -> Unit,
    ): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }

        val scrollRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val chipLabels = ComposerChromeHelper.formatIndividualChipLabels(names)
        for (chipLabel in chipLabels) {
            val chip = styledText(
                context = context,
                text = chipLabel,
                sizeSp = 12f,
                color = palette.teal,
                bold = true,
            ).apply {
                maxLines = 1
                isSingleLine = true
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(context, 36)
                val hPad = dp(context, 10)
                val vPad = dp(context, 6)
                setPadding(hPad, vPad, hPad, vPad)
                background = rippleDrawable(
                    roundedShape(
                        color = palette.mint,
                        radiusPx = dp(context, 18).toFloat(),
                        strokeWidthPx = maxOf(1, dp(context, 1)),
                        strokeColor = palette.userBubbleBorder,
                    ),
                    palette.rippleOnMint,
                )
                contentDescription = "Inspect attachment $chipLabel"
                isClickable = true
                isFocusable = true
                setOnClickListener { onInspect() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = dp(context, 6)
                }
            }
            scrollRow.addView(chip)
        }

        val nearBudget = maxChars > 0 && usedChars >= (maxChars * 0.9f)
        val budgetPill = styledText(
            context = context,
            text = ComposerChromeHelper.formatCharBudgetLabel(usedChars, maxChars),
            sizeSp = 11.5f,
            color = if (nearBudget) palette.warningInk else palette.muted,
            bold = nearBudget,
        ).apply {
            maxLines = 1
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(context, 36)
            val hPad = dp(context, 10)
            val vPad = dp(context, 6)
            setPadding(hPad, vPad, hPad, vPad)
            background = roundedShape(
                color = if (nearBudget) palette.warningBg else palette.surfaceElevated,
                radiusPx = dp(context, 18).toFloat(),
                strokeWidthPx = maxOf(1, dp(context, 1)),
                strokeColor = if (nearBudget) palette.warningBorder else palette.border,
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(context, 6)
            }
        }
        scrollRow.addView(budgetPill)

        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                scrollRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        root.addView(scroller)

        val editButton = secondaryOutlineButton(
            context = context,
            palette = palette,
            label = "Edit",
            accessibilityLabel = "Edit attachments",
            compact = true,
            textColor = palette.teal,
            onClick = onInspect,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(context, 4)
            }
        }
        root.addView(editButton)

        val clearButton = iconTouchButton(
            context = context,
            palette = palette,
            iconText = "×",
            accessibilityLabel = "Clear attachments",
            textColor = palette.muted,
            textSizeSp = 18f,
            onClick = onClear,
        )
        root.addView(clearButton)

        return root
    }

    /**
     * Builds the floating `"↓ Latest"` jump-to-bottom pill button, initially [View.GONE].
     */
    fun buildJumpToBottomButton(
        context: Context,
        palette: UiPalette,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        val minTouch = dp(context, 48)
        val hPad = dp(context, 16)
        val vPad = dp(context, 10)
        text = "↓ Latest"
        contentDescription = "Jump to latest message"
        setAllCaps(false)
        textSize = 13f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(palette.teal)
        gravity = Gravity.CENTER
        setPadding(hPad, vPad, hPad, vPad)
        minHeight = minTouch
        minimumHeight = minTouch
        minWidth = minTouch
        minimumWidth = minTouch
        background = rippleDrawable(
            roundedShape(
                color = palette.surfaceElevated,
                radiusPx = dp(context, 24).toFloat(),
                strokeWidthPx = maxOf(1, dp(context, 1)),
                strokeColor = palette.border,
            ),
            palette.rippleOnSurface,
        )
        backgroundTintList = null
        stateListAnimator = null
        elevation = dp(context, 6).toFloat()
        visibility = View.GONE
        isFocusable = true
        setOnClickListener { onClick() }
    }
}

/**
 * Leak-safe repeating alpha pulse animator (`1f` to `0.35f`, 700ms, infinite reverse).
 * Call [stop] in `onDestroy` to cancel the animation and clear view references.
 */
class PulseAnimator {
    private var animator: ObjectAnimator? = null
    private var targetRef: WeakReference<View>? = null

    /** Cancels any previous pulse and starts pulsing [target]'s alpha. */
    fun start(target: View) {
        stop()
        target.alpha = 1f
        targetRef = WeakReference(target)
        animator = ObjectAnimator.ofFloat(target, View.ALPHA, 1f, MIN_ALPHA).apply {
            duration = PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    /** Cancels the running animator and restores target alpha to `1f`. */
    fun stop() {
        animator?.apply {
            cancel()
            removeAllListeners()
        }
        animator = null
        targetRef?.get()?.alpha = 1f
        targetRef?.clear()
        targetRef = null
    }

    private companion object {
        const val MIN_ALPHA = 0.35f
        const val PULSE_DURATION_MS = 700L
    }
}
