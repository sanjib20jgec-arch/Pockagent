package com.example.pocketagent

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Offline HTML / SVG / Canvas preview detector, HTML5 viewport wrapper, and sandboxed WebView
 * dialog for running locally generated artifacts (such as interactive Canvas games or diagrams)
 * with zero network or filesystem access.
 */
object HtmlArtifactSandbox {

    private const val MIN_PREVIEW_CODE_CHARS = 15
    private const val SANDBOX_BASE_URL = "https://pocketagent.local/"
    private const val VIEWPORT_META =
        """<meta name="viewport" content="width=device-width, initial-scale=1.0">"""

    private val PREVIEWABLE_LANGUAGES = setOf("html", "htm", "xhtml", "svg")
    private val NON_PREVIEW_LANGUAGES = setOf(
        "css", "scss", "sass", "less",
        "js", "javascript", "mjs", "cjs", "ts", "typescript", "jsx", "tsx",
        "kotlin", "kt", "kts", "java", "py", "python", "sql", "sqlite",
        "sh", "bash", "zsh", "shell", "powershell", "ps1", "bat", "cmd",
        "json", "yaml", "yml", "toml", "ini", "conf", "properties",
        "c", "cpp", "cc", "cxx", "h", "hpp", "cs", "csharp",
        "go", "golang", "rust", "rs", "ruby", "rb", "perl", "pl",
        "swift", "dart", "r", "lua", "php", "dockerfile", "makefile",
    )
    private val ALLOWED_SANDBOX_SCHEMES = setOf("data", "about", "blob")

    private val VIEWPORT_EXISTS_REGEX =
        Regex("""<meta\b[^>]*\bname\s*=\s*["']viewport["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val HEAD_OPEN_REGEX = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val HTML_OPEN_REGEX = Regex("""<html\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val DOCTYPE_REGEX = Regex("""<!doctype\b[^>]*>""", RegexOption.IGNORE_CASE)

    /**
     * Returns `true` when [code] (optionally tagged with [language]) is safe and meaningful to
     * render in the offline HTML/SVG sandbox.
     */
    fun isPreviewableCode(language: String?, code: String): Boolean {
        val trimmed = code.trim()
        if (trimmed.length < MIN_PREVIEW_CODE_CHARS) return false

        val normalizedLang = language?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalizedLang in PREVIEWABLE_LANGUAGES) return true
        if (normalizedLang in NON_PREVIEW_LANGUAGES) return false

        val lower = trimmed.lowercase(Locale.ROOT)
        val hasRunnableMarkup = lower.contains("<html") ||
            lower.contains("<!doctype") ||
            lower.contains("<canvas") ||
            lower.contains("<svg")
        return hasRunnableMarkup && lower.contains('>') && lower.contains("</")
    }

    /**
     * Ensures [code] contains a responsive mobile viewport meta tag when `<head>`/`<html>` is
     * present, or wraps standalone HTML/SVG snippets in a viewport-ready dark/light HTML5 document.
     */
    fun prepareSandboxHtml(code: String, isDark: Boolean): String {
        val trimmed = code.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        val headMatch = HEAD_OPEN_REGEX.find(trimmed)
        if (headMatch != null) {
            if (VIEWPORT_EXISTS_REGEX.containsMatchIn(trimmed)) return trimmed
            val insertAt = headMatch.range.last + 1
            return trimmed.substring(0, insertAt) + "\n$VIEWPORT_META" + trimmed.substring(insertAt)
        }

        val htmlMatch = HTML_OPEN_REGEX.find(trimmed)
        if (htmlMatch != null) {
            if (VIEWPORT_EXISTS_REGEX.containsMatchIn(trimmed)) return trimmed
            val insertAt = htmlMatch.range.last + 1
            return trimmed.substring(0, insertAt) +
                "\n<head>\n$VIEWPORT_META\n</head>" +
                trimmed.substring(insertAt)
        }

        val doctypeMatch = DOCTYPE_REGEX.find(trimmed)
        if (doctypeMatch != null) {
            if (VIEWPORT_EXISTS_REGEX.containsMatchIn(trimmed)) return trimmed
            val insertAt = doctypeMatch.range.last + 1
            return trimmed.substring(0, insertAt) +
                "\n<head>\n$VIEWPORT_META\n</head>" +
                trimmed.substring(insertAt)
        }

        val bg = if (isDark) "#0E1412" else "#F4F7F6"
        val fg = if (isDark) "#E9F1EF" else "#162D2A"
        val svgLayout = if (lower.startsWith("<svg")) {
            "display:flex;align-items:center;justify-content:center;min-height:100vh;"
        } else {
            ""
        }
        val hasBodyTag = lower.contains("<body")

        return buildString {
            append("<!DOCTYPE html>\n")
            append("<html>\n<head>\n")
            append("<meta charset=\"utf-8\">\n")
            append(VIEWPORT_META).append('\n')
            append("<style>")
            append("html,body{margin:0;padding:12px;box-sizing:border-box;")
            append("background:").append(bg).append(";color:").append(fg)
            append(";font-family:sans-serif;").append(svgLayout).append('}')
            append("svg,canvas{max-width:100%;height:auto;}")
            append("</style>\n")
            append("</head>\n")
            if (hasBodyTag) {
                append(trimmed).append('\n')
            } else {
                append("<body>\n").append(trimmed).append("\n</body>\n")
            }
            append("</html>")
        }
    }

    /**
     * Fail-closed URL scheme check for sandbox sub-resource requests.
     * Blocks `http`, `https`, `file`, `content`, `ws`, `wss`, `ftp`, and unknown/empty schemes,
     * allowing only in-memory `data:`, `about:`, and `blob:` resources.
     */
    internal fun isBlockedSandboxScheme(scheme: String?): Boolean {
        val normalized = scheme?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalized.isEmpty()) return true
        return normalized !in ALLOWED_SANDBOX_SCHEMES
    }

    /**
     * Opens an almost-full-screen themed [AlertDialog] containing an offline-sandboxed [WebView]
     * rendering [code].
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun showPreviewDialog(
        context: Context,
        code: String,
        language: String?,
        palette: UiPalette,
    ) {
        if (context is Activity && (context.isFinishing || context.isDestroyed)) return

        val preparedHtml = prepareSandboxHtml(code, isDark = palette.isDark)
        val metrics = context.resources.displayMetrics
        val dialogWidth = (metrics.widthPixels * 0.95f).toInt().coerceAtLeast(dp(context, 300))
        val dialogHeight = (metrics.heightPixels * 0.88f).toInt().coerceAtLeast(dp(context, 360))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dialogWidth
            minimumHeight = (dialogHeight * 0.9f).toInt()
            background = roundedShape(
                color = palette.surface,
                radiusPx = dp(context, 16).toFloat(),
                strokeWidthPx = maxOf(1, dp(context, 1)),
                strokeColor = palette.border,
            )
            clipToOutline = true
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.surfaceElevated)
            setPadding(dp(context, 14), dp(context, 4), dp(context, 6), dp(context, 4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val titleView = TextView(context).apply {
            text = "Live Preview"
            textSize = 15f
            setTextColor(palette.ink)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            maxLines = 1
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleView)

        val langBadge = language?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)
        if (langBadge != null) {
            val badgeView = TextView(context).apply {
                text = langBadge
                textSize = 10.5f
                setTextColor(palette.teal)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                background = roundedShape(palette.mint, dp(context, 10).toFloat())
                setPadding(dp(context, 8), dp(context, 3), dp(context, 8), dp(context, 3))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = dp(context, 6)
                }
            }
            headerRow.addView(badgeView)
        }

        lateinit var webView: WebView
        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .create()

        fun createHeaderActionButton(
            label: String,
            accessibilityLabel: String,
            textColor: Int,
            onClick: () -> Unit,
        ): Button = Button(context).apply {
            val minTouch = dp(context, 48)
            text = label
            contentDescription = accessibilityLabel
            setAllCaps(false)
            textSize = 12.5f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            minHeight = minTouch
            minimumHeight = minTouch
            minWidth = minTouch
            minimumWidth = minTouch
            setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6))
            background = rippleDrawable(
                roundedShape(Color.TRANSPARENT, dp(context, 12).toFloat()),
                palette.rippleOnSurface,
            )
            backgroundTintList = null
            stateListAnimator = null
            isFocusable = true
            setOnClickListener {
                performSubtleHaptic(this)
                onClick()
            }
        }

        val reloadButton = createHeaderActionButton(
            label = "Reload",
            accessibilityLabel = "Reload preview",
            textColor = palette.teal,
        ) {
            webView.stopLoading()
            webView.loadDataWithBaseURL(SANDBOX_BASE_URL, preparedHtml, "text/html", "utf-8", null)
        }
        headerRow.addView(reloadButton)

        val closeButton = createHeaderActionButton(
            label = "Close",
            accessibilityLabel = "Close live preview",
            textColor = palette.muted,
        ) {
            dialog.dismiss()
        }
        headerRow.addView(closeButton)

        root.addView(headerRow)
        root.addView(dividerView(context, palette))

        webView = WebView(context).apply {
            setBackgroundColor(palette.bg)
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                domStorageEnabled = false
                databaseEnabled = false
                blockNetworkLoads = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                @Suppress("DEPRECATION")
                run {
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = true

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val scheme = request?.url?.scheme
                    if (isBlockedSandboxScheme(scheme)) {
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0)),
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    result?.confirm()
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    result?.cancel()
                    return true
                }
            }
        }
        root.addView(webView)

        dialog.setOnDismissListener {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }

        dialog.window?.setBackgroundDrawable(
            roundedShape(
                color = palette.surface,
                radiusPx = dp(context, 16).toFloat(),
                strokeWidthPx = maxOf(1, dp(context, 1)),
                strokeColor = palette.border,
            )
        )
        dialog.show()
        dialog.window?.apply {
            setLayout(dialogWidth, dialogHeight)
            clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            )
        }
        root.layoutParams = root.layoutParams?.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }

        webView.loadDataWithBaseURL(SANDBOX_BASE_URL, preparedHtml, "text/html", "utf-8", null)
    }
}
