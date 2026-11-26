package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtifactLanguageTest {
    @Test fun nullLanguageFallsBackToHtml() = assertEquals("html", normalizeArtifactLanguage(null))

    @Test fun blankLanguageFallsBackToHtml() = assertEquals("html", normalizeArtifactLanguage("   "))

    @Test fun languageIsTrimmed() = assertEquals("kotlin", normalizeArtifactLanguage("  kotlin  "))
}
