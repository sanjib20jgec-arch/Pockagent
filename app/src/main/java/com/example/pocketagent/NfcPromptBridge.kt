package com.example.pocketagent

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class NfcLaunchRequest(
    val projectId: String?,
    val prompt: String?,
)

object NfcPromptBridge {

    private const val SCHEME = "pocketagent"
    private const val HOST = "run"
    private const val PARAM_PROJECT_ID = "projectId"
    private const val PARAM_PROMPT = "prompt"
    private val UTF8_NAME: String = StandardCharsets.UTF_8.name()

    fun buildLaunchUri(projectId: String?, prompt: String?): String {
        val params = buildList {
            if (!projectId.isNullOrBlank()) {
                add("$PARAM_PROJECT_ID=${URLEncoder.encode(projectId, UTF8_NAME)}")
            }
            if (!prompt.isNullOrBlank()) {
                add("$PARAM_PROMPT=${URLEncoder.encode(prompt, UTF8_NAME)}")
            }
        }
        return if (params.isEmpty()) {
            "$SCHEME://$HOST"
        } else {
            "$SCHEME://$HOST?${params.joinToString("&")}"
        }
    }

    fun parseLaunchUri(uriString: String): NfcLaunchRequest? {
        if (uriString.isBlank()) return null
        val uri = try {
            URI(uriString.trim())
        } catch (_: Exception) {
            return null
        }

        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        if (!scheme.equals(SCHEME, ignoreCase = true) || !host.equals(HOST, ignoreCase = true)) {
            return null
        }

        var projectId: String? = null
        var prompt: String? = null
        val rawQuery = uri.rawQuery
        if (!rawQuery.isNullOrBlank()) {
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val eqIndex = pair.indexOf('=')
                val rawKey = if (eqIndex >= 0) pair.substring(0, eqIndex) else pair
                val rawValue = if (eqIndex >= 0) pair.substring(eqIndex + 1) else ""
                val key = decodeUtf8(rawKey) ?: continue
                val value = decodeUtf8(rawValue) ?: continue
                when (key) {
                    PARAM_PROJECT_ID -> if (value.isNotBlank()) projectId = value
                    PARAM_PROMPT -> if (value.isNotBlank()) prompt = value
                }
            }
        }

        return NfcLaunchRequest(projectId = projectId, prompt = prompt)
    }

    fun extractFromIntent(intent: Intent?): NfcLaunchRequest? {
        if (intent == null) return null

        intent.data?.toString()?.let(::parseLaunchUri)?.let { return it }

        val rawMessages: Array<out Any?>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        }

        if (rawMessages != null) {
            for (item in rawMessages) {
                val message = item as? NdefMessage ?: continue
                for (record in message.records) {
                    val uriText = record.toUri()?.toString() ?: continue
                    parseLaunchUri(uriText)?.let { return it }
                }
            }
        }

        return null
    }

    private fun decodeUtf8(value: String): String? =
        try {
            URLDecoder.decode(value, UTF8_NAME)
        } catch (_: Exception) {
            null
        }
}
