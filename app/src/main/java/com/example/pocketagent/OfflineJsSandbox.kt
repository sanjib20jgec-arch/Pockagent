package com.example.pocketagent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 100% offline, network-isolated JavaScript evaluation sandbox backed by an ephemeral [WebView].
 *
 * Safe to invoke from background threads (e.g. `Dispatchers.IO`). Creates and destroys the
 * [WebView] on the main looper and caps returned output to [MAX_RESULT_CHARS].
 */
object OfflineJsSandbox {

    const val DEFAULT_TIMEOUT_MS = 3_000L
    const val MAX_RESULT_CHARS = 1_500

    fun evaluate(
        context: Context,
        script: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): String {
        if (script.isBlank()) return "No JavaScript code provided."
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return "JavaScript sandbox cannot run synchronously on the main thread."
        }

        val mainHandler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<String?>(null)
        val errorRef = AtomicReference<String?>(null)
        var webViewRef: WebView? = null

        return try {
            mainHandler.post {
                try {
                    val webView = WebView(context)
                    webViewRef = webView
                    webView.settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        blockNetworkLoads = true
                        blockNetworkImage = true
                        loadsImagesAutomatically = false
                    }
                    webView.evaluateJavascript(script) { value ->
                        resultRef.set(value ?: "null")
                        latch.countDown()
                    }
                } catch (t: Throwable) {
                    errorRef.set(t.message ?: t.javaClass.simpleName)
                    latch.countDown()
                }
            }

            val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                return "JavaScript execution timed out after ${timeoutMs}ms."
            }
            errorRef.get()?.let { err ->
                return "JavaScript error: $err".take(MAX_RESULT_CHARS)
            }
            (resultRef.get() ?: "null").take(MAX_RESULT_CHARS)
        } finally {
            mainHandler.post {
                webViewRef?.let { webView ->
                    try {
                        webView.stopLoading()
                        webView.destroy()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }
}
