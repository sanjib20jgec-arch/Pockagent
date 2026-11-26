package com.example.pocketagent

/** Normalizes the optional language reported by the artifact sandbox before saving. */
internal fun normalizeArtifactLanguage(language: String?): String =
    language?.trim()?.takeIf { it.isNotEmpty() } ?: "html"
