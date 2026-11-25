package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlArtifactSandboxTest {

    @Test
    fun isPreviewableCodeAcceptsExplicitHtmlAndSvgLanguagesAboveMinLength() {
        assertTrue(HtmlArtifactSandbox.isPreviewableCode("html", "<h1>Hello World!</h1>"))
        assertTrue(HtmlArtifactSandbox.isPreviewableCode(" HTM ", "<div>Demo Content</div>"))
        assertTrue(HtmlArtifactSandbox.isPreviewableCode("xhtml", "<html xmlns='x'></html>"))
        assertTrue(
            HtmlArtifactSandbox.isPreviewableCode(
                "svg",
                "<svg width=\"40\" height=\"40\"><circle cx=\"20\" cy=\"20\" r=\"10\"/></svg>",
            )
        )

        assertFalse(HtmlArtifactSandbox.isPreviewableCode("html", "   <p>hi</p>   "))
        assertFalse(HtmlArtifactSandbox.isPreviewableCode("svg", "   \n  "))
    }

    @Test
    fun isPreviewableCodeDetectsUntaggedAndMarkdownHtmlWhileRejectingCssAndJs() {
        val dxBall = "<!DOCTYPE html><html><body><canvas id=\"c\"></canvas></body></html>"
        assertTrue(HtmlArtifactSandbox.isPreviewableCode(null, dxBall))
        assertTrue(HtmlArtifactSandbox.isPreviewableCode("", dxBall))
        assertTrue(HtmlArtifactSandbox.isPreviewableCode("markdown", dxBall))
        assertTrue(
            HtmlArtifactSandbox.isPreviewableCode(
                null,
                "<canvas id=\"dx\" width=\"300\" height=\"400\"></canvas><script>draw();</script>",
            )
        )

        // Plain CSS and JS without runnable HTML markup must be rejected
        assertFalse(HtmlArtifactSandbox.isPreviewableCode("css", "body { background: #111; color: #fff; }"))
        assertFalse(HtmlArtifactSandbox.isPreviewableCode(null, "body { background: #111; color: #fff; }"))
        assertFalse(HtmlArtifactSandbox.isPreviewableCode(null, "<style>body { color: red; }</style>"))
        assertFalse(HtmlArtifactSandbox.isPreviewableCode("javascript", "const score = 10; console.log(score);"))
        assertFalse(HtmlArtifactSandbox.isPreviewableCode(null, "if (a < b && c > d) { run(); }"))
        assertFalse(HtmlArtifactSandbox.isPreviewableCode("javascript", "<canvas id=\"c\"></canvas>"))
        assertFalse(HtmlArtifactSandbox.isPreviewableCode("kotlin", "val s = \"<html></html>\""))
    }

    @Test
    fun prepareSandboxHtmlInjectsViewportOnceWhenHeadOrHtmlExists() {
        val withHead = "<!DOCTYPE html><html><head><title>App</title></head><body>Hi</body></html>"
        val preparedHead = HtmlArtifactSandbox.prepareSandboxHtml(withHead, isDark = true)
        assertTrue(preparedHead.contains("""<meta name="viewport" content="width=device-width, initial-scale=1.0">"""))
        assertTrue(preparedHead.indexOf("viewport") > preparedHead.indexOf("<head>"))

        val alreadyHasViewport =
            "<html><head><meta name='viewport' content='width=device-width, initial-scale=1.0'></head><body></body></html>"
        assertEquals(
            alreadyHasViewport,
            HtmlArtifactSandbox.prepareSandboxHtml(alreadyHasViewport, isDark = false),
        )

        val htmlNoHead = "<html><body><canvas id=\"g\"></canvas></body></html>"
        val preparedHtmlOnly = HtmlArtifactSandbox.prepareSandboxHtml(htmlNoHead, isDark = true)
        assertTrue(preparedHtmlOnly.contains("<head>"))
        assertTrue(preparedHtmlOnly.contains("""<meta name="viewport" content="width=device-width, initial-scale=1.0">"""))
        assertTrue(preparedHtmlOnly.contains("<body>"))
    }

    @Test
    fun prepareSandboxHtmlWrapsSnippetsInThemedViewportDocument() {
        val snippet = "<canvas id=\"dx\" width=\"300\" height=\"400\"></canvas>"
        val darkDoc = HtmlArtifactSandbox.prepareSandboxHtml(snippet, isDark = true)
        val lightDoc = HtmlArtifactSandbox.prepareSandboxHtml(snippet, isDark = false)

        for (doc in listOf(darkDoc, lightDoc)) {
            assertTrue(doc.startsWith("<!DOCTYPE html>"))
            assertTrue(doc.contains("""<meta name="viewport" content="width=device-width, initial-scale=1.0">"""))
            assertTrue(doc.contains("<body>"))
            assertTrue(doc.contains(snippet))
        }
        assertNotEquals(darkDoc, lightDoc)
        assertTrue(darkDoc.contains("#0E1412"))
        assertTrue(lightDoc.contains("#F4F7F6"))

        val bodySnippet = "<body><h1>Ready</h1></body>"
        val wrappedBody = HtmlArtifactSandbox.prepareSandboxHtml(bodySnippet, isDark = false)
        assertEquals(1, Regex("<body\\b", RegexOption.IGNORE_CASE).findAll(wrappedBody).count())
    }

    @Test
    fun sandboxSchemePolicyBlocksNetworkAndFileSchemesWhileAllowingInMemoryData() {
        assertTrue(HtmlArtifactSandbox.isBlockedSandboxScheme("http"))
        assertTrue(HtmlArtifactSandbox.isBlockedSandboxScheme("https"))
        assertTrue(HtmlArtifactSandbox.isBlockedSandboxScheme("HTTPS"))
        assertTrue(HtmlArtifactSandbox.isBlockedSandboxScheme("file"))
        assertTrue(HtmlArtifactSandbox.isBlockedSandboxScheme("content"))
        assertTrue(HtmlArtifactSandbox.isBlockedSandboxScheme("ftp"))
        assertTrue(HtmlArtifactSandbox.isBlockedSandboxScheme("ws"))
        assertTrue(HtmlArtifactSandbox.isBlockedSandboxScheme("wss"))
        assertTrue(HtmlArtifactSandbox.isBlockedSandboxScheme(null))
        assertTrue(HtmlArtifactSandbox.isBlockedSandboxScheme(""))

        assertFalse(HtmlArtifactSandbox.isBlockedSandboxScheme("data"))
        assertFalse(HtmlArtifactSandbox.isBlockedSandboxScheme("DATA"))
        assertFalse(HtmlArtifactSandbox.isBlockedSandboxScheme("about"))
        assertFalse(HtmlArtifactSandbox.isBlockedSandboxScheme("blob"))
    }
}
