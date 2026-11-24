package com.example.pocketagent

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Throttles streaming model output onto the UI thread.
 *
 * Inference threads deliver chunks far faster than a TextView can usefully repaint, and running
 * Markdown formatting on every chunk on the main thread was the single biggest source of jank.
 * This controller:
 *
 *  - buffers the newest partial text in an [AtomicReference], so bursts collapse into one update,
 *  - repaints in **plain text** at most once per [minIntervalMillis], never formatting mid-stream,
 *  - runs [MarkdownFormatter.formatForDisplay] exactly once per answer, off the UI thread, when
 *    generation completes.
 *
 * Plain-text streaming is intentional: half-parsed Markdown flickers (a `**` becomes bold, then
 * flips back when the closing `**` arrives), and the final formatted render replaces it anyway.
 *
 * All public methods are safe to call from any thread.
 */
class StreamingRenderController(
    /** Target thread for UI updates. Defaults to the main looper. */
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    /** Minimum gap between two UI paints while streaming. */
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
    /** Where final Markdown formatting runs. Must not be the UI thread. */
    private val formatExecutor: Executor = defaultFormatExecutor(),
) {

    companion object {
        /** Streaming repaint cadence: ~10 fps is smooth to read and cheap to render. */
        const val DEFAULT_MIN_INTERVAL_MILLIS = 100L

        /** Only reformat the tail of a very long answer during the final pass. */
        private const val MAX_LIVE_PREVIEW_CHARS = 24_000

        /**
         * Single background thread shared by all formatting work. Formatting is short and
         * occasional (once per answer), so one thread is enough and avoids thread churn.
         */
        private val sharedFormatExecutor: Executor by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "PocketAgentMarkdownFormatter").apply { isDaemon = true }
            }
        }

        private fun defaultFormatExecutor(): Executor = sharedFormatExecutor
    }

    private val latestText = AtomicReference("")

    /** Incremented on every [reset]/[cancel] so stale in-flight callbacks can be dropped. */
    private val generation = AtomicLong(0)

    private val hasPendingPaint = AtomicBoolean(false)
    private val lastPaintAt = AtomicLong(0)

    /** Receives plain-text streaming updates on the UI thread. */
    @Volatile
    var onPartialText: ((String) -> Unit)? = null

    /** Called alongside each throttled repaint so callers can follow the scroll position. */
    @Volatile
    var onAutoScroll: (() -> Unit)? = null

    /** Starts a new answer: clears buffered text and invalidates earlier callbacks. */
    fun reset() {
        generation.incrementAndGet()
        latestText.set("")
        hasPendingPaint.set(false)
        lastPaintAt.set(0)
    }

    /**
     * Drops any pending paint and ignores later chunks from the current generation.
     * Call this when generation is stopped or superseded.
     */
    fun cancel() {
        generation.incrementAndGet()
        latestText.set("")
        hasPendingPaint.set(false)
    }

    /**
     * Feeds one streaming chunk. Safe to call at token rate from an inference thread.
     *
     * The text is buffered immediately; a UI paint is scheduled only if one is not already
     * pending and the throttle window has elapsed.
     */
    fun onPartial(accumulated: String) {
        if (accumulated.isBlank()) return
        latestText.set(
            if (accumulated.length > MAX_LIVE_PREVIEW_CHARS) {
                accumulated.takeLast(MAX_LIVE_PREVIEW_CHARS)
            } else {
                accumulated
            }
        )
        schedulePaint(generation.get())
    }

    /**
     * Finishes the answer: formats [finalText] off the UI thread, then delivers it to
     * [onFormatted] on the UI thread.
     *
     * Formatting is skipped (the plain text is passed straight through) when the text contains no
     * Markdown syntax, which is the common case and avoids the work entirely.
     */
    fun onComplete(
        finalText: String,
        onFormatted: (CharSequence) -> Unit,
    ) {
        val ticket = generation.incrementAndGet()
        hasPendingPaint.set(false)

        if (finalText.isBlank()) {
            postIfCurrent(ticket) { onFormatted("") }
            return
        }

        if (!MarkdownFormatter.containsMarkdownSyntax(finalText)) {
            postIfCurrent(ticket) { onFormatted(finalText.trim()) }
            return
        }

        formatExecutor.execute {
            val formatted = try {
                MarkdownFormatter.formatForDisplay(finalText)
            } catch (_: Exception) {
                // A formatting failure must never swallow the answer.
                finalText
            }
            postIfCurrent(ticket) {
                onFormatted(if (formatted.isBlank()) finalText else formatted)
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private fun schedulePaint(ticket: Long) {
        if (!hasPendingPaint.compareAndSet(false, true)) return

        val now = System.currentTimeMillis()
        val wait = (lastPaintAt.get() + minIntervalMillis - now).coerceAtLeast(0L)

        mainHandler.postDelayed({
            hasPendingPaint.set(false)
            if (ticket != generation.get()) return@postDelayed
            lastPaintAt.set(System.currentTimeMillis())
            val text = latestText.get()
            if (text.isEmpty()) return@postDelayed
            onPartialText?.invoke(text)
            onAutoScroll?.invoke()
        }, wait)
    }

    private fun postIfCurrent(ticket: Long, block: () -> Unit) {
        mainHandler.post {
            if (ticket == generation.get()) block()
        }
    }
}
