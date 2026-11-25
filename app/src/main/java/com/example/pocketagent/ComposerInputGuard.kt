package com.example.pocketagent

import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText

/**
 * Viewport helpers for the pinned composer.
 *
 * These exist because [EditText] has two traps that are easy to hit and hard to spot:
 *
 *  1. Setting `isSingleLine = false` **resets `maxLines` to [Int.MAX_VALUE]** regardless of what it
 *     was set to earlier. Order matters, and `isSingleLine` must not be used at all for a bounded
 *     multiline field.
 *  2. A multiline `EditText` inside a scrolling parent steals vertical drags, so the user cannot
 *     scroll the input's own overflow or fling the chat behind it. The touch guard below hands the
 *     gesture to whichever of the two can actually scroll in that direction.
 *
 * Nothing here allocates per-frame state or touches inference; it is presentation only.
 */
object ComposerInputGuard {

    /** Default cap for the composer input, in lines. */
    const val DEFAULT_MAX_LINES = 4

    /** Default pixel height cap for the composer input, in dp. */
    const val DEFAULT_MAX_HEIGHT_DP = 124

    /** Expanded line ceiling available while reviewing dictated voice transcripts. */
    const val DICTATION_REVIEW_MAX_LINES = 6

    /**
     * Makes [editText] a bounded, internally-scrolling multiline field.
     *
     * Caps the height so a long draft can never expand the composer enough to crush the chat
     * canvas (which is `height = 0, weight = 1f`) down to zero.
     *
     * @param maxHeightPx hard ceiling in pixels; text beyond this scrolls inside the field.
     */
    fun configureBoundedMultilineInput(
        editText: EditText,
        maxLines: Int = DEFAULT_MAX_LINES,
        maxHeightPx: Int,
        scrollBarStyle: Int = View.SCROLLBARS_INSIDE_INSET,
    ) {
        // inputType first: changing it later can reset line behaviour.
        editText.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

        // Never call setSingleLine/isSingleLine here: it resets maxLines to Int.MAX_VALUE.
        editText.setHorizontallyScrolling(false)

        val boundedLines = maxLines.coerceAtLeast(1)
        editText.minLines = 1
        editText.maxLines = boundedLines
        editText.maxHeight = maxHeightPx.coerceAtLeast(1)

        editText.isVerticalScrollBarEnabled = true
        editText.scrollBarStyle = scrollBarStyle
        editText.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

        attachScrollInterceptGuard(editText)
    }

    /**
     * Computes a bounded multiline ceiling for reviewing a voice transcript without leaking
     * `Int.MAX_VALUE` if the field's prior `maxLines` was left unbounded.
     */
    fun resolveReviewMaxLines(
        currentMaxLines: Int,
        minReviewLines: Int = DICTATION_REVIEW_MAX_LINES,
    ): Int {
        val safeMinReview = minReviewLines.coerceAtLeast(1)
        val safeCurrent = if (currentMaxLines in 1 until Int.MAX_VALUE) {
            currentMaxLines
        } else {
            DEFAULT_MAX_LINES
        }
        return maxOf(safeCurrent, safeMinReview)
    }

    /**
     * Populates [editText] with [mergedText] and re-enables multiline wrapping after assignment so
     * multi-word voice transcripts unwrap vertically for review instead of scrolling on one line.
     */
    fun applyTranscriptForReview(
        editText: EditText,
        mergedText: String,
        maxLinesForReview: Int = 5,
    ) {
        val targetMaxLines = resolveReviewMaxLines(editText.maxLines, maxLinesForReview)
        editText.setText(mergedText)
        editText.setHorizontallyScrolling(false)
        editText.minLines = 1
        editText.maxLines = targetMaxLines
        val endIndex = editText.text?.length ?: 0
        editText.setSelection(endIndex)
        editText.requestLayout()
        editText.post { editText.requestLayout() }
    }

    /**
     * Lets the field keep vertical drags only while it has something to scroll; otherwise the
     * gesture is released to the parent so the chat scroller gets it.
     *
     * Returns `false` from [View.OnTouchListener.onTouch] in every case, so native cursor
     * placement, selection handles, and fling still work normally.
     */
    fun attachScrollInterceptGuard(editText: EditText) {
        editText.setOnTouchListener { view: View, event: MotionEvent ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val canScrollUp = view.canScrollVertically(-1)
                    val canScrollDown = view.canScrollVertically(1)
                    if (canScrollUp || canScrollDown) {
                        disallowParentIntercept(view, disallow = true)
                    } else if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        // A fresh touch on a field with no overflow: let the parent scroll freely.
                        disallowParentIntercept(view, disallow = false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    disallowParentIntercept(view, disallow = false)
                }
            }
            false
        }
    }

    /**
     * Keeps the composer clear of the IME and the navigation bar without double-padding.
     *
     * [baseBottomPaddingPx] is the resting padding the dock wants when no keyboard is shown. Only
     * the part of the keyboard the window resize did not already cover is added on top, so the
     * composer is never lifted twice.
     *
     * @param imeBottomPx current IME inset in pixels (0 when the keyboard is closed)
     * @param windowAlreadyResizedPx how much `adjustResize` already shortened the window
     */
    fun resolveDockBottomPadding(
        baseBottomPaddingPx: Int,
        imeBottomPx: Int,
        windowAlreadyResizedPx: Int,
    ): Int {
        val residual = (imeBottomPx - windowAlreadyResizedPx).coerceAtLeast(0)
        return baseBottomPaddingPx + residual
    }

    /**
     * Builds the window-insets listener that keeps the pinned dock above the keyboard.
     *
     * The listener pads [rootLayout] itself, because the dock is the last child of that column, so
     * root padding lifts the dock exactly as much as it needs. `coveredByResize` is measured as the
     * gap between the decor view and the resized root; only the part of the IME that the resize did
     * **not** already cover becomes padding, so the composer is never pushed up twice.
     *
     * The IME inset is read through [imeBottomPx] so this stays free of `Build.VERSION` checks;
     * the caller supplies the version-appropriate accessor.
     */
    fun applySafeComposerInsets(
        imeBottomPx: (Any) -> Int,
        baseBottomPaddingPx: Int = 0,
        horizontalPaddingPx: Int = -1,
        topPaddingPx: Int = -1,
    ): View.OnApplyWindowInsetsListener = View.OnApplyWindowInsetsListener { view, insets ->
        val imeBottom = imeBottomPx(insets)
        // How much adjustResize already shortened this window.
        val coveredByResize = (view.rootView.height - view.height).coerceAtLeast(0)
        val bottom = resolveDockBottomPadding(baseBottomPaddingPx, imeBottom, coveredByResize)
        if (view.paddingBottom != bottom) {
            val left = if (horizontalPaddingPx >= 0) horizontalPaddingPx else view.paddingLeft
            val right = if (horizontalPaddingPx >= 0) horizontalPaddingPx else view.paddingRight
            val top = if (topPaddingPx >= 0) topPaddingPx else view.paddingTop
            view.setPadding(left, top, right, bottom)
        }
        insets
    }

    /**
     * Collapses the composer input back to a single line after the draft has been sent.
     *
     * Assigning empty text does not always re-measure an `EditText` that had grown, so the height
     * is explicitly reset here while leaving `maxLines` (and therefore the cap) intact.
     */
    fun resetInputHeight(editText: EditText) {
        if (editText.text.isNullOrEmpty()) {
            editText.minLines = 1
            editText.maxLines = editText.maxLines.coerceIn(1, DEFAULT_MAX_LINES)
            editText.requestLayout()
        }
    }

    private fun disallowParentIntercept(view: View, disallow: Boolean) {
        var parent = view.parent
        while (parent is ViewGroup) {
            parent.requestDisallowInterceptTouchEvent(disallow)
            parent = parent.parent
        }
    }
}
