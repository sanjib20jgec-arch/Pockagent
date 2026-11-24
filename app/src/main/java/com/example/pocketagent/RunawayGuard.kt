package com.example.pocketagent

/**
 * Detects and stops runaway generation.
 *
 * On-device models degenerate: they lock onto a short phrase and emit it forever. Because the
 * UI re-renders the whole buffer on every token, the user sees the same one or two lines
 * repeating endlessly and the app never finishes, so every later request is stuck behind it.
 *
 * This guard watches the accumulating text and reports when the model has clearly stopped
 * producing new content. It is pure Kotlin so the behaviour is unit-testable without a model.
 *
 * Design notes:
 * - The trigger is repetition. Length alone is not treated as degeneration: a long list of
 *   numbered steps legitimately exceeds any line cap.
 * - Only the trailing window is inspected, so a model that repeats a phrase once and then moves
 *   on is left alone.
 * - Detection is deliberately conservative at small sizes (a short answer legitimately repeats a
 *   word), which is why the smallest runaway pattern allowed is [MIN_RUNAWAY_LINE_REPEATS] lines
 *   or [MIN_RUNAWAY_TAIL_CHARS] characters of a looping tail.
 */
object RunawayGuard {

    /** A single line repeated this many times in a row counts as a loop. */
    const val MIN_RUNAWAY_LINE_REPEATS = 4

    /** A looping tail shorter than this is too small to call degeneration (short answers repeat words). */
    const val MIN_RUNAWAY_TAIL_CHARS = 400

    /** How much trailing text is inspected for a looping period. */
    private const val TAIL_WINDOW_CHARS = 2000

    /** Shortest period (in characters) considered a loop, to avoid flagging normal prose. */
    private const val MIN_PERIOD_CHARS = 12

    /** Longest period (in characters) considered a loop. */
    private const val MAX_PERIOD_CHARS = 220

    /** Consecutive blank lines tolerated before the output is treated as degenerate padding. */
    const val MAX_CONSECUTIVE_BLANK_LINES = 24

    /** Absolute backstop on answer size, independent of repetition. */
    const val MAX_ANSWER_CHARS = 12000

    /**
     * True when [text] shows degenerate repetition and generation should be stopped.
     */
    fun shouldStop(text: String): Boolean {
        if (text.length > MAX_ANSWER_CHARS) return true
        if (hasRepeatedLineLoop(text)) return true
        if (hasLoopingTail(text)) return true
        if (hasBlankLineFlood(text)) return true
        return false
    }

    /**
     * The degenerate fragment a loop is stuck on, for a user-facing explanation, or null.
     */
    fun stuckFragment(text: String): String? = when {
        hasRepeatedLineLoop(text) -> repeatedLine(text)
        hasLoopingTail(text) -> loopingTail(text)
        else -> null
    }

    // ---------------------------------------------------------------- detectors

    /** The same non-trivial line emitted several times in a row, e.g. two lines looping forever. */
    private fun hasRepeatedLineLoop(text: String): Boolean = repeatedLine(text) != null

    private fun repeatedLine(text: String): String? {
        val lines = text.trimEnd().split('\n')
        if (lines.size < MIN_RUNAWAY_LINE_REPEATS) return null

        var runLength = 1
        for (i in lines.size - 1 downTo 1) {
            if (lines[i].isNotBlank() && lines[i] == lines[i - 1]) {
                runLength++
                if (runLength >= MIN_RUNAWAY_LINE_REPEATS) return lines[i]
            } else {
                runLength = 1
            }
        }
        return null
    }

    /** A short block of text emitted over and over, which can also loop mid-line. */
    private fun hasLoopingTail(text: String): Boolean = loopingTail(text) != null

    private fun loopingTail(text: String): String? {
        val tail = text.takeLast(TAIL_WINDOW_CHARS)
        val maxPeriod = minOf(MAX_PERIOD_CHARS, tail.length / MIN_RUNAWAY_LINE_REPEATS)
        if (maxPeriod < MIN_PERIOD_CHARS) return null

        for (period in MIN_PERIOD_CHARS..maxPeriod) {
            val unit = tail.substring(tail.length - period)
            if (unit.isBlank()) continue

            var repeats = 1
            var end = tail.length - period
            while (end - period >= 0 && tail.regionMatches(end - period, unit, 0, period)) {
                repeats++
                end -= period
            }

            if (repeats >= MIN_RUNAWAY_LINE_REPEATS && repeats * period >= MIN_RUNAWAY_TAIL_CHARS) {
                return unit.trim()
            }
        }
        return null
    }

    /** Endless blank padding: some models emit newlines indefinitely when they cannot stop. */
    private fun hasBlankLineFlood(text: String): Boolean {
        var blanks = 0
        for (line in text.takeLast(2000).split('\n')) {
            if (line.isBlank()) {
                blanks++
                if (blanks >= MAX_CONSECUTIVE_BLANK_LINES) return true
            } else {
                blanks = 0
            }
        }
        return false
    }

    /**
     * User-facing note appended when generation was cut off, so the truncated answer is not
     * mistaken for a complete one.
     */
    fun stoppedNotice(fragment: String?): String = if (fragment.isNullOrBlank()) {
        "Stopped: the model stopped producing new text. Tap Ask again for a fresh attempt."
    } else {
        "Stopped: the model began repeating \"${fragment.take(60)}\" without adding new text. " +
            "Tap Ask again for a fresh attempt."
    }
}

/**
 * Turns a raw token buffer into a clean, finished answer.
 *
 * Applies the runaway guard and collapses a looping tail so a degenerated reply still ends with
 * something readable instead of a wall of duplicates.
 */
object AnswerFinalizer {

    const val STOPPED_MARKER = "…"
    const val SENTENCE_END_MARKER = "…"

    /**
     * @return the cleaned answer, plus whether it was cut short.
     */
    data class Result(
        val text: String,
        val wasTruncated: Boolean,
    )

    fun finalize(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result("", false)

        val collapsed = collapseLoopingTail(trimmed)
        if (collapsed != trimmed) {
            return Result(finishSentence(collapsed), true)
        }
        return Result(trimmed, false)
    }

    /**
     * Trims a repeating tail down to a single instance of the repeating unit, so the answer ends
     * once instead of dozens of times.
     */
    fun collapseLoopingTail(text: String): String {
        val tailWindow = minOf(text.length, 2000)
        val tailStart = text.length - tailWindow
        val tail = text.substring(tailStart)
        val maxPeriod = minOf(220, tail.length / 4)
        if (maxPeriod < 12) return text

        for (period in 12..maxPeriod) {
            val unit = tail.substring(tail.length - period)
            if (unit.isBlank()) continue

            var repeats = 1
            var end = tail.length - period
            while (end - period >= 0 && tail.regionMatches(end - period, unit, 0, period)) {
                repeats++
                end -= period
            }
            if (repeats >= 4) {
                // Keep everything before the loop plus exactly one copy of the repeated unit, so
                // meaningful text the model looped on is not thrown away.
                return text.substring(0, tailStart + end + period).trimEnd()
            }
        }
        return text
    }

    /**
     * Makes sure a cut-off answer ends at a sentence boundary rather than mid-word, and marks it
     * so the user can tell it was cut short.
     */
    fun finishSentence(text: String): String {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.last() in ".!?…:") return trimmed

        val boundary = trimmed.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
        if (boundary > trimmed.length * 3 / 4) {
            return trimmed.substring(0, boundary + 1).trimEnd()
        }
        return "$trimmed$SENTENCE_END_MARKER"
    }
}

/**
 * Runs one inference pass while enforcing the runaway guard.
 *
 * On-device models can lock onto a short phrase and repeat it indefinitely. The UI re-renders the
 * whole buffer per token, so the user sees two lines looping and generation never ends, blocking
 * every later request on the single worker thread. This stops as soon as the output stops
 * producing new content.
 *
 * Shared by the Activity and the ViewModel so both paths behave identically.
 */
class GuardedGeneration(
    /** Called with the text accumulated so far, on every accepted chunk. */
    private val onPartial: (String) -> Unit = {},
) {

    @Volatile
    var runawayFragment: String? = null
        private set

    @Volatile
    var wasCancelled: Boolean = false
        private set

    val isStopped: Boolean get() = runawayFragment != null || wasCancelled

    /** Marks the generation as user-cancelled, so chunks are ignored from now on. */
    fun cancel() {
        wasCancelled = true
    }

    /**
     * Accumulates one chunk.
     *
     * @return true while generation should continue, false once it was cancelled or stopped.
     */
    fun accept(accumulated: StringBuilder, chunk: String): Boolean {
        if (isStopped) return false
        val text = synchronized(accumulated) {
            accumulated.append(chunk)
            accumulated.toString()
        }
        if (RunawayGuard.shouldStop(text)) {
            runawayFragment = RunawayGuard.stuckFragment(text) ?: ""
            return false
        }
        onPartial(text)
        return true
    }

    /** Raw text accumulated so far. */
    fun text(accumulated: StringBuilder): String = synchronized(accumulated) { accumulated.toString() }
}
