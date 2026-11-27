package com.example.pocketagent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File

enum class QrType {
    WIFI,
    VCARD,
    URL,
    TEXT,
}

data class QrPayload(
    val type: QrType,
    val raw: String,
    val summary: String,
)

/**
 * 100% offline QR / barcode decoder backed by ZXing core.
 */
object OfflineQrDecoder {

    private const val MAX_IMAGE_DIMENSION_PX = 1600

    fun parseStructuredPayload(raw: String): QrPayload {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return QrPayload(type = QrType.TEXT, raw = "", summary = "")
        }

        return when {
            trimmed.startsWith("WIFI:", ignoreCase = true) -> parseWifi(trimmed)
            trimmed.startsWith("BEGIN:VCARD", ignoreCase = true) -> parseVCard(trimmed)
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> {
                QrPayload(
                    type = QrType.URL,
                    raw = trimmed,
                    summary = "URL: $trimmed",
                )
            }
            else -> {
                val preview = if (trimmed.length > 240) {
                    trimmed.take(240) + "..."
                } else {
                    trimmed
                }
                QrPayload(
                    type = QrType.TEXT,
                    raw = trimmed,
                    summary = preview,
                )
            }
        }
    }

    fun decodeFromFile(imagePath: String): QrPayload? {
        if (imagePath.isBlank()) return null
        val file = File(imagePath)
        if (!file.exists() || !file.isFile || !file.canRead()) return null

        val bitmap = decodeSampledBitmap(file.absolutePath) ?: return null
        return try {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return null

            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val hints = mapOf(DecodeHintType.TRY_HARDER to true)
            val result = MultiFormatReader().decode(binaryBitmap, hints)
            val text = result.text
            if (text.isNullOrBlank()) null else parseStructuredPayload(text)
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun decodeSampledBitmap(path: String): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, bounds)
            val origW = bounds.outWidth
            val origH = bounds.outHeight
            if (origW <= 0 || origH <= 0) return null

            var sampleSize = 1
            while (origW / sampleSize > MAX_IMAGE_DIMENSION_PX ||
                origH / sampleSize > MAX_IMAGE_DIMENSION_PX
            ) {
                sampleSize *= 2
            }

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(path, options)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun parseWifi(raw: String): QrPayload {
        val body = raw.substringAfter(':', missingDelimiterValue = "")
        val fields = splitEscaped(body, ';')

        var authType = ""
        var ssid = ""
        var password = ""
        var hidden = false

        for (field in fields) {
            if (field.isEmpty()) continue
            val colonIdx = field.indexOf(':')
            if (colonIdx <= 0) continue
            val key = field.substring(0, colonIdx).uppercase()
            val value = unescapeQrValue(field.substring(colonIdx + 1))
            when (key) {
                "T" -> authType = value
                "S" -> ssid = value
                "P" -> password = value
                "H" -> hidden = value.equals("true", ignoreCase = true)
            }
        }

        val parts = mutableListOf<String>()
        val networkName = ssid.ifBlank { "(unknown)" }
        val securityLabel = authType.ifBlank { if (password.isBlank()) "nopass" else "WPA" }
        parts.add("Wi-Fi: $networkName ($securityLabel)")
        if (password.isNotBlank()) {
            parts.add("Password: $password")
        }
        if (hidden) {
            parts.add("Hidden")
        }

        return QrPayload(
            type = QrType.WIFI,
            raw = raw,
            summary = parts.joinToString(" | "),
        )
    }

    private fun parseVCard(raw: String): QrPayload {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var fullName = ""
        var structuredName = ""
        var org = ""
        var tel = ""
        var email = ""

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx <= 0) continue

            val keyPart = trimmed.substring(0, colonIdx)
            val propName = keyPart.substringBefore(';').uppercase()
            val value = trimmed.substring(colonIdx + 1).trim()
            if (value.isEmpty()) continue

            when (propName) {
                "FN" -> if (fullName.isEmpty()) fullName = value
                "N" -> if (structuredName.isEmpty()) {
                    structuredName = value.split(';')
                        .filter { it.isNotBlank() }
                        .reversed()
                        .joinToString(" ")
                        .trim()
                }
                "ORG" -> if (org.isEmpty()) org = value.replace(';', ' ').trim()
                "TEL" -> if (tel.isEmpty()) tel = value
                "EMAIL" -> if (email.isEmpty()) email = value
            }
        }

        val displayName = fullName.ifBlank { structuredName }.ifBlank { "Unnamed" }
        val parts = mutableListOf("Contact: $displayName")
        if (org.isNotBlank()) parts.add("Org: $org")
        if (tel.isNotBlank()) parts.add("Tel: $tel")
        if (email.isNotBlank()) parts.add("Email: $email")

        return QrPayload(
            type = QrType.VCARD,
            raw = raw,
            summary = parts.joinToString(" | "),
        )
    }

    private fun splitEscaped(input: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (ch in input) {
            if (escaped) {
                current.append('\\').append(ch)
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == delimiter) {
                result.add(current.toString())
                current.clear()
            } else {
                current.append(ch)
            }
        }
        if (escaped) {
            current.append('\\')
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    private fun unescapeQrValue(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                sb.append(value[i + 1])
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}
