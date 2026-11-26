package com.example.pocketagent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Converts a density-independent pixel [value] to integer pixels. */
fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()

/** Creates a rounded [GradientDrawable] with optional stroke styling. */
fun roundedShape(
    color: Int,
    radiusPx: Float,
    strokeWidthPx: Int = 0,
    strokeColor: Int? = null,
): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx
    if (strokeWidthPx > 0 && strokeColor != null) {
        setStroke(strokeWidthPx, strokeColor)
    }
}

/** Wraps [content] in a [RippleDrawable] tinted with [rippleColor]. */
fun rippleDrawable(content: Drawable, rippleColor: Int): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)

/** Creates a horizontal gradient pill [GradientDrawable]. */
fun gradientPillDrawable(
    startColor: Int,
    endColor: Int,
    radiusPx: Float,
): GradientDrawable = GradientDrawable(
    GradientDrawable.Orientation.LEFT_RIGHT,
    intArrayOf(startColor, endColor),
).apply {
    cornerRadius = radiusPx
}

/** Creates a styled [TextView] with explicit text size, color, and optional bold weight. */
fun styledText(
    context: Context,
    text: CharSequence,
    sizeSp: Float,
    color: Int,
    bold: Boolean = false,
): TextView = TextView(context).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(color)
    typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
}

/** Creates a primary mint-filled pill [Button] with a minimum 48dp touch target. */
fun pillButton(
    context: Context,
    palette: UiPalette,
    label: String,
    accessibilityLabel: String = label,
    compact: Boolean = false,
    onClick: () -> Unit,
): Button = createActionPillButton(
    context = context,
    label = label,
    accessibilityLabel = accessibilityLabel,
    compact = compact,
    textColor = palette.teal,
    backgroundDrawable = rippleDrawable(
        roundedShape(palette.mint, dp(context, 30).toFloat()),
        palette.rippleOnMint,
    ),
    onClick = onClick,
)

/** Creates a bordered surface-filled outline [Button] with a minimum 48dp touch target. */
fun secondaryOutlineButton(
    context: Context,
    palette: UiPalette,
    label: String,
    accessibilityLabel: String = label,
    compact: Boolean = false,
    textColor: Int = palette.ink,
    onClick: () -> Unit,
): Button = createActionPillButton(
    context = context,
    label = label,
    accessibilityLabel = accessibilityLabel,
    compact = compact,
    textColor = textColor,
    backgroundDrawable = rippleDrawable(
        roundedShape(
            color = palette.surface,
            radiusPx = dp(context, 30).toFloat(),
            strokeWidthPx = maxOf(1, dp(context, 1)),
            strokeColor = palette.border,
        ),
        palette.rippleOnSurface,
    ),
    onClick = onClick,
)

/** Creates a semantic danger-tinted [Button] with a minimum 48dp touch target. */
fun dangerButton(
    context: Context,
    palette: UiPalette,
    label: String,
    accessibilityLabel: String = label,
    compact: Boolean = false,
    onClick: () -> Unit,
): Button = createActionPillButton(
    context = context,
    label = label,
    accessibilityLabel = accessibilityLabel,
    compact = compact,
    textColor = palette.dangerInk,
    backgroundDrawable = rippleDrawable(
        roundedShape(
            color = palette.dangerBg,
            radiusPx = dp(context, 30).toFloat(),
            strokeWidthPx = maxOf(1, dp(context, 1)),
            strokeColor = palette.dangerBorder,
        ),
        palette.rippleOnSurface,
    ),
    onClick = onClick,
)

/**
 * Creates an accessible 48dp × 48dp borderless icon/symbol touch [Button] with a bounded circular
 * ripple, suitable for dialog close (`✕`), overflow (`⋮`), or pin actions.
 */
fun iconTouchButton(
    context: Context,
    palette: UiPalette,
    iconText: String,
    accessibilityLabel: String,
    textColor: Int = palette.muted,
    textSizeSp: Float = 18f,
    onClick: () -> Unit,
): Button = Button(context).apply {
    val touchSize = dp(context, 48)
    val pad = dp(context, 8)
    val mask = roundedShape(Color.WHITE, dp(context, 24).toFloat())
    text = iconText
    contentDescription = accessibilityLabel
    setAllCaps(false)
    textSize = textSizeSp
    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    setTextColor(textColor)
    gravity = Gravity.CENTER
    setPadding(pad, pad, pad, pad)
    minWidth = touchSize
    minimumWidth = touchSize
    minHeight = touchSize
    minimumHeight = touchSize
    background = RippleDrawable(ColorStateList.valueOf(palette.rippleOnSurface), null, mask)
    backgroundTintList = null
    stateListAnimator = null
    isFocusable = true
    setOnClickListener { onClick() }
}

/** Loads [drawableRes], mutates it, and applies [tintColor] safely. */
fun tintedVectorDrawable(context: Context, drawableRes: Int, tintColor: Int): Drawable? =
    context.getDrawable(drawableRes)?.mutate()?.apply {
        setTint(tintColor)
    }

/**
 * Creates an accessible [ImageButton] displaying a tinted vector drawable inside a rounded
 * ripple container (default 48dp × 48dp touch target).
 */
fun vectorIconButton(
    context: Context,
    palette: UiPalette,
    drawableRes: Int,
    accessibilityLabel: String,
    tintColor: Int = palette.ink,
    bgColor: Int = palette.surfaceElevated,
    borderColor: Int? = palette.border,
    rippleColor: Int = palette.rippleOnSurface,
    cornerDp: Int = 24,
    sizeDp: Int = 48,
    iconSizeDp: Int = 20,
    onClick: () -> Unit,
): ImageButton = ImageButton(context).apply {
    val touchPx = dp(context, sizeDp)
    val padPx = dp(context, ((sizeDp - iconSizeDp).coerceAtLeast(0)) / 2)
    setImageDrawable(tintedVectorDrawable(context, drawableRes, tintColor))
    scaleType = ImageView.ScaleType.CENTER_INSIDE
    contentDescription = accessibilityLabel
    setPadding(padPx, padPx, padPx, padPx)
    minimumWidth = touchPx
    minimumHeight = touchPx
    background = rippleDrawable(
        roundedShape(
            color = bgColor,
            radiusPx = dp(context, cornerDp).toFloat(),
            strokeWidthPx = if (borderColor != null) maxOf(1, dp(context, 1)) else 0,
            strokeColor = borderColor,
        ),
        rippleColor,
    )
    backgroundTintList = null
    stateListAnimator = null
    isFocusable = true
    setOnClickListener { onClick() }
}

/** Triggers a subtle tactile haptic tick or confirmation pulse on [view]. */
fun performSubtleHaptic(view: View?, isConfirm: Boolean = false) {
    view ?: return
    val constant = if (isConfirm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.KEYBOARD_TAP
    }
    view.performHapticFeedback(constant)
}

/** Creates a vertical [LinearLayout] card styled with surface/border tokens from [palette]. */
fun cardContainer(
    context: Context,
    palette: UiPalette,
    cornerDp: Int = 16,
    elevated: Boolean = false,
    paddingDp: Int = 14,
): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    val bgColor = if (elevated) palette.surfaceElevated else palette.surface
    background = roundedShape(
        color = bgColor,
        radiusPx = dp(context, cornerDp).toFloat(),
        strokeWidthPx = maxOf(1, dp(context, 1)),
        strokeColor = palette.border,
    )
    val pad = dp(context, paddingDp)
    setPadding(pad, pad, pad, pad)
    if (elevated) {
        elevation = dp(context, 2).toFloat()
    }
}

/** Creates a 1dp horizontal hairline divider tinted with [palette.border]. */
fun dividerView(context: Context, palette: UiPalette): View = View(context).apply {
    setBackgroundColor(palette.border)
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        maxOf(1, dp(context, 1)),
    )
}

/** Applies consistent [palette] text, hint, highlight, border, and API 29+ cursor styling. */
fun styleEditText(
    editText: EditText,
    palette: UiPalette,
    cornerDp: Int = 14,
) {
    val context = editText.context
    val hPad = dp(context, 14)
    val vPad = dp(context, 12)
    editText.setTextColor(palette.ink)
    editText.setHintTextColor(palette.muted)
    editText.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    editText.highlightColor = palette.mint
    editText.setPadding(hPad, vPad, hPad, vPad)
    editText.background = roundedShape(
        color = palette.surface,
        radiusPx = dp(context, cornerDp).toFloat(),
        strokeWidthPx = maxOf(1, dp(context, 1)),
        strokeColor = palette.border,
    )
    editText.backgroundTintList = null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        editText.textCursorDrawable = GradientDrawable().apply {
            setSize(maxOf(2, dp(context, 2)), -1)
            setColor(palette.teal)
        }
    }
}

/** Creates a themed vertical dialog content container with rounded surface card styling. */
fun styleDialogContainer(
    context: Context,
    palette: UiPalette,
    paddingDp: Int = 20,
): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = roundedShape(
        color = palette.surface,
        radiusPx = dp(context, 20).toFloat(),
        strokeWidthPx = maxOf(1, dp(context, 1)),
        strokeColor = palette.border,
    )
    val pad = dp(context, paddingDp)
    setPadding(pad, pad, pad, pad)
}

/**
 * Applies themed rounded surface styling to [dialog], displays it, and tints its action buttons
 * and standard text elements according to [palette].
 */
fun showStyledAlertDialog(dialog: AlertDialog, palette: UiPalette) {
    val context = dialog.context
    dialog.window?.setBackgroundDrawable(
        roundedShape(
            color = palette.surface,
            radiusPx = dp(context, 20).toFloat(),
            strokeWidthPx = maxOf(1, dp(context, 1)),
            strokeColor = palette.border,
        )
    )
    dialog.show()

    val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
    if (titleId != 0) {
        dialog.findViewById<TextView>(titleId)?.apply {
            setTextColor(palette.ink)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
    }
    dialog.findViewById<TextView>(android.R.id.message)?.apply {
        setTextColor(palette.muted)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.apply {
        setTextColor(palette.teal)
        setAllCaps(false)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.apply {
        setTextColor(palette.muted)
        setAllCaps(false)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.apply {
        setTextColor(palette.teal)
        setAllCaps(false)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
}

/**
 * Wraps [content] in a bottom-anchored [AlertDialog] styled with `22dp` rounded top corners,
 * a centered `36dp × 4dp` drag handle pill, slide-up entrance motion, and safe `navigationBar`
 * bottom inset padding.
 */
fun showBottomSheetDialog(
    context: Context,
    palette: UiPalette,
    content: LinearLayout,
): AlertDialog {
    val topRadiusPx = dp(context, 22).toFloat()
    val hPad = dp(context, 20)
    val topPad = dp(context, 12)
    val baseBottomPad = dp(context, 20)

    val sheetBackground = GradientDrawable().apply {
        setColor(palette.surface)
        cornerRadii = floatArrayOf(
            topRadiusPx, topRadiusPx,
            topRadiusPx, topRadiusPx,
            0f, 0f,
            0f, 0f,
        )
        setStroke(maxOf(1, dp(context, 1)), palette.border)
    }

    val dragHandle = View(context).apply {
        background = roundedShape(palette.border, dp(context, 2).toFloat())
        layoutParams = LinearLayout.LayoutParams(dp(context, 36), dp(context, 4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(context, 14)
        }
    }

    (content.parent as? ViewGroup)?.removeView(content)
    val sheetContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = sheetBackground
        setPadding(hPad, topPad, hPad, baseBottomPad)
        setOnApplyWindowInsetsListener { view, insets ->
            val navBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            view.setPadding(hPad, topPad, hPad, baseBottomPad + navBottom)
            insets
        }
        addView(dragHandle)
        addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    val dialog = AlertDialog.Builder(context)
        .setView(sheetContainer)
        .create()

    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setGravity(Gravity.BOTTOM)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setDimAmount(0.45f)
    }

    sheetContainer.requestApplyInsets()
    sheetContainer.translationY = dp(context, 48).toFloat()
    sheetContainer.alpha = 0f
    sheetContainer.animate()
        .translationY(0f)
        .alpha(1f)
        .setDuration(220L)
        .setInterpolator(DecelerateInterpolator())
        .start()

    return dialog
}

/**
 * Colors the status/navigation bars to [palette.bg] and updates light/dark system bar icon
 * appearance using [WindowInsetsController] on API 30+ and `systemUiVisibility` on API 28-29.
 */
fun applyWindowBars(activity: Activity, palette: UiPalette) {
    val window = activity.window ?: return
    window.decorView.setBackgroundColor(palette.bg)
    @Suppress("DEPRECATION")
    run {
        window.statusBarColor = palette.bg
        window.navigationBarColor = palette.bg
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val controller = window.insetsController ?: return
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val appearance = if (palette.isDark) 0 else mask
        controller.setSystemBarsAppearance(appearance, mask)
    } else {
        @Suppress("DEPRECATION")
        run {
            var flags = window.decorView.systemUiVisibility
            flags = if (palette.isDark) {
                flags and
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = flags
        }
    }
}

private fun createActionPillButton(
    context: Context,
    label: String,
    accessibilityLabel: String,
    compact: Boolean,
    textColor: Int,
    backgroundDrawable: Drawable,
    onClick: () -> Unit,
): Button = Button(context).apply {
    val minTouch = dp(context, 48)
    val hPad = dp(context, if (compact) 10 else 14)
    val vPad = dp(context, if (compact) 6 else 8)
    text = label
    contentDescription = accessibilityLabel
    setAllCaps(false)
    textSize = if (compact) 12f else 13f
    typeface = Typeface.create("sans-serif", Typeface.BOLD)
    gravity = Gravity.CENTER
    setTextColor(textColor)
    setPadding(hPad, vPad, hPad, vPad)
    minHeight = minTouch
    minimumHeight = minTouch
    minWidth = minTouch
    minimumWidth = minTouch
    background = backgroundDrawable
    backgroundTintList = null
    stateListAnimator = null
    isFocusable = true
    setOnClickListener { onClick() }
}
