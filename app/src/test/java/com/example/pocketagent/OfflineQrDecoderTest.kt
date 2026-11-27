package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineQrDecoderTest {

    @Test
    fun `parses wifi payload with security and password`() {
        val raw = "WIFI:T:WPA;S:CoffeeShop\\;Guest;P:pass\\:123;H:true;;"
        val payload = OfflineQrDecoder.parseStructuredPayload(raw)

        assertEquals(QrType.WIFI, payload.type)
        assertEquals(raw, payload.raw)
        assertTrue(payload.summary.contains("CoffeeShop;Guest"))
        assertTrue(payload.summary.contains("WPA"))
        assertTrue(payload.summary.contains("pass:123"))
        assertTrue(payload.summary.contains("Hidden"))
    }

    @Test
    fun `parses open wifi payload without password`() {
        val raw = "WIFI:T:nopass;S:AirportFree;;"
        val payload = OfflineQrDecoder.parseStructuredPayload(raw)

        assertEquals(QrType.WIFI, payload.type)
        assertTrue(payload.summary.contains("AirportFree"))
        assertTrue(payload.summary.contains("nopass"))
    }

    @Test
    fun `parses vcard payload with contact fields`() {
        val raw = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Ada Lovelace
            ORG:Analytical Engine Ltd
            TEL;TYPE=CELL:+441234567890
            EMAIL;TYPE=WORK:ada@example.org
            END:VCARD
        """.trimIndent()
        val payload = OfflineQrDecoder.parseStructuredPayload(raw)

        assertEquals(QrType.VCARD, payload.type)
        assertTrue(payload.summary.contains("Ada Lovelace"))
        assertTrue(payload.summary.contains("Analytical Engine Ltd"))
        assertTrue(payload.summary.contains("+441234567890"))
        assertTrue(payload.summary.contains("ada@example.org"))
    }

    @Test
    fun `parses vcard falling back to structured N field`() {
        val raw = "BEGIN:VCARD\nN:Turing;Alan;;;\nTEL:555-0100\nEND:VCARD"
        val payload = OfflineQrDecoder.parseStructuredPayload(raw)

        assertEquals(QrType.VCARD, payload.type)
        assertTrue(payload.summary.contains("Alan Turing"))
        assertTrue(payload.summary.contains("555-0100"))
    }

    @Test
    fun `parses http and https urls`() {
        val httpsPayload = OfflineQrDecoder.parseStructuredPayload("  https://example.com/docs?q=1  ")
        assertEquals(QrType.URL, httpsPayload.type)
        assertEquals("https://example.com/docs?q=1", httpsPayload.raw)
        assertEquals("URL: https://example.com/docs?q=1", httpsPayload.summary)

        val httpPayload = OfflineQrDecoder.parseStructuredPayload("http://localhost:8080/health")
        assertEquals(QrType.URL, httpPayload.type)
        assertEquals("http://localhost:8080/health", httpPayload.raw)
    }

    @Test
    fun `parses generic text and handles blank or missing file`() {
        val textPayload = OfflineQrDecoder.parseStructuredPayload("Meeting at 3pm in Room 4B")
        assertEquals(QrType.TEXT, textPayload.type)
        assertEquals("Meeting at 3pm in Room 4B", textPayload.summary)

        val emptyPayload = OfflineQrDecoder.parseStructuredPayload("   ")
        assertEquals(QrType.TEXT, emptyPayload.type)
        assertEquals("", emptyPayload.raw)

        assertNull(OfflineQrDecoder.decodeFromFile(""))
        assertNull(OfflineQrDecoder.decodeFromFile("nonexistent_qr_image_12345.png"))
    }
}
