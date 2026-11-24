package com.example.pocketagent

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream
import java.io.Writer

/** Extracts selectable PDF text only. Scanned-page OCR is intentionally not provided. */
object PdfTextExtractor {
    const val MAX_PDF_BYTES = 20L * 1024 * 1024
    const val MAX_PDF_PAGES = 100

    fun extract(context: Context, input: InputStream, maxChars: Int): Extraction {
        require(maxChars > 0) { "There is no remaining attachment space for PDF text." }
        val staged = BoundedPdfFile.stage(input, context.cacheDir, MAX_PDF_BYTES)
        try {
            PDFBoxResourceLoader.init(context.applicationContext)
            val scratchSettings = MemoryUsageSetting.setupTempFileOnly(MAX_PDF_BYTES * 2)
                .setTempDir(context.cacheDir)
            PDDocument.load(staged, scratchSettings).use { document ->
                val pageCount = document.numberOfPages
                require(pageCount > 0) { "The selected PDF has no pages." }
                require(pageCount <= MAX_PDF_PAGES) {
                    "PDF has $pageCount pages; the limit is $MAX_PDF_PAGES pages."
                }

                val result = StringBuilder(minOf(maxChars, 8_192))
                val extractedPages = mutableListOf<ExtractedPdfPage>()
                for (page in 1..pageCount) {
                    val remaining = maxChars - result.length
                    if (remaining <= 0) break
                    val pageWriter = LimitedWriter(remaining)
                    val stripper = PDFTextStripper().apply {
                        startPage = page
                        endPage = page
                    }
                    stripper.writeText(document, pageWriter)
                    val pageText = pageWriter.content().trim()
                    if (pageText.isNotEmpty()) {
                        extractedPages.add(ExtractedPdfPage(page, pageText, pageText.length))
                        appendWithinLimit(result, "\n\n[PDF page $page]\n", maxChars)
                        appendWithinLimit(result, pageText, maxChars)
                    }
                }

                val extracted = result.toString().trim()
                require(extracted.isNotEmpty()) {
                    "No selectable text was found. Scanned PDFs are not supported."
                }
                return Extraction(extracted, pageCount, extracted.length >= maxChars, extractedPages)
            }
        } finally {
            staged.delete()
        }
    }

    typealias ExtractedPdfPage = DocumentChunker.DocumentPage

    data class Extraction(
        val text: String,
        val pageCount: Int,
        val limitReached: Boolean,
        val pages: List<ExtractedPdfPage> = emptyList(),
    )

    private fun appendWithinLimit(target: StringBuilder, value: String, limit: Int) {
        val remaining = limit - target.length
        if (remaining > 0) target.append(value, 0, minOf(value.length, remaining))
    }

    private class LimitedWriter(private val limit: Int) : Writer() {
        private val buffer = StringBuilder(minOf(limit, 8_192))

        override fun write(chars: CharArray, offset: Int, length: Int) {
            val remaining = limit - buffer.length
            if (remaining > 0 && length > 0) {
                buffer.append(chars, offset, minOf(length, remaining))
            }
        }

        override fun flush() = Unit
        override fun close() = Unit
        fun content(): String = buffer.toString()
    }
}
