package com.example.pocketagent

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingRenderControllerTest {

    /** Runs posted work synchronously so the test can observe each throttled paint. */
    private class InlineHandler : Handler() {
        val paints = AtomicInteger(0)
        override fun post(r: Runnable): Boolean { paints.incrementAndGet(); r.run(); return true }
        override fun postDelayed(r: Runnable, ms: Long): Boolean { r.run(); return true }
    }

    @Test
    fun streamsPlainTextAndNeverFormatsWhileStreaming() {
        val paints = mutableListOf<String>()
        val controller = StreamingRenderController(
            mainHandler = InlineHandler(),
            formatExecutor = Executor { it.run() },
        )
        controller.onPartialText = { paints.add(it) }
        controller.reset()

        controller.onPartial("The distance ")
        controller.onPartial("The distance is 42 km. ")
        controller.onPartial("The distance is 42 km. It takes 5 hours.")

        // The last paint must be the raw markdown-free text, not a formatted variant.
        assertEquals("The distance is 42 km. It takes 5 hours.", paints.last())
    }

    @Test
    fun completeFormatsOnceOffThreadAndDelivers() {
        val formatted = AtomicReference<CharSequence>()
        val latch = CountDownLatch(1)
        val formatRuns = AtomicInteger(0)

        val controller = StreamingRenderController(
            mainHandler = InlineHandler(),
            formatExecutor = Executor { r -> formatRuns.incrementAndGet(); r.run() },
        )
        controller.reset()

        controller.onComplete("# Heading\n\n- one\n- two") { text ->
            formatted.set(text)
            latch.countDown()
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertNotNull(formatted.get())
        val text = formatted.get().toString()
        // Level-1 headings are uppercased by the formatter; the markup itself must be gone.
        assertTrue("heading should be formatted, got <<<$text>>>", text.contains("HEADING"))
        assertFalse("raw markdown must not survive, got <<<$text>>>", text.contains("# Heading"))
        assertEquals("format should run exactly once", 1, formatRuns.get())
    }

    @Test
    fun plainAnswerSkipsFormattingEntirely() {
        val formatRuns = AtomicInteger(0)
        val delivered = AtomicReference<CharSequence>()

        val controller = StreamingRenderController(
            mainHandler = InlineHandler(),
            formatExecutor = Executor { r -> formatRuns.incrementAndGet(); r.run() },
        )
        controller.reset()
        controller.onComplete("The distance is 42 km.") { delivered.set(it) }

        assertEquals("plain prose must not run the markdown pipeline", 0, formatRuns.get())
        assertEquals("The distance is 42 km.", delivered.get().toString())
    }

    @Test
    fun cancelDropsStaleCompletion() {
        val delivered = AtomicReference<CharSequence>("untouched")
        val executor = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)

        val controller = StreamingRenderController(
            mainHandler = InlineHandler(),
            formatExecutor = Executor { r -> executor.execute { gate.await(); r.run() } },
        )
        controller.reset()
        controller.onComplete("**bold** answer") { delivered.set(it) }

        // Cancel before the formatting work is released.
        controller.cancel()
        gate.countDown()
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)

        assertEquals("untouched", delivered.get().toString())
    }

    @Test
    fun resetDropsEarlierGenerationPaints() {
        val paints = AtomicInteger(0)
        val controller = StreamingRenderController(
            mainHandler = InlineHandler(),
            formatExecutor = Executor { it.run() },
        )
        controller.onPartialText = { paints.incrementAndGet() }

        controller.reset()
        controller.onPartial("first answer")
        val afterFirst = paints.get()

        controller.reset()
        controller.onPartial("second answer")
        assertTrue("a new generation must paint its own text", paints.get() > afterFirst)
    }

    @Test
    fun unformattableTextStillReachesTheUser() {
        val delivered = AtomicReference<CharSequence>()
        val controller = StreamingRenderController(
            mainHandler = InlineHandler(),
            formatExecutor = Executor { it.run() },
        )
        controller.reset()

        // A lone fence is malformed markdown; the answer must still be shown.
        controller.onComplete("```kotlin\nunterminated") { delivered.set(it) }
        assertNotNull(delivered.get())
        assertFalse(delivered.get().toString().isBlank())
    }
}
