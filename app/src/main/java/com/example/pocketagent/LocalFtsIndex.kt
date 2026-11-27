package com.example.pocketagent

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

data class FtsHit(
    val docId: String,
    val title: String,
    val snippet: String,
    val rank: Double,
)

/**
 * Local SQLite FTS5 (with FTS4 fallback) full-text search index for offline RAG.
 */
class LocalFtsIndex(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION,
) {
    @Volatile
    private var useFts5: Boolean = true

    override fun onCreate(db: SQLiteDatabase) {
        try {
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE_NAME USING fts5(" +
                    "doc_id UNINDEXED, title, body, tokenize='porter unicode61')",
            )
            useFts5 = true
        } catch (_: SQLiteException) {
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE_NAME USING fts4(" +
                    "doc_id, title, body, tokenize=porter)",
            )
            useFts5 = false
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    @Synchronized
    fun upsertDocument(docId: String, title: String, body: String) {
        if (docId.isBlank()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM $TABLE_NAME WHERE doc_id = ?", arrayOf(docId))
            db.execSQL(
                "INSERT INTO $TABLE_NAME (doc_id, title, body) VALUES (?, ?, ?)",
                arrayOf(docId, title, body),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun removeDocument(docId: String) {
        if (docId.isBlank()) return
        val db = writableDatabase
        db.execSQL("DELETE FROM $TABLE_NAME WHERE doc_id = ?", arrayOf(docId))
    }

    @Synchronized
    fun search(query: String, limit: Int = 5): List<FtsHit> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val ftsQuery = buildFtsQuery(query)
        if (ftsQuery.isBlank()) return emptyList()

        val db = readableDatabase
        val hits = mutableListOf<FtsHit>()
        try {
            val sql = if (useFts5) {
                "SELECT doc_id, title, snippet($TABLE_NAME, 2, '[', ']', '...', 24), " +
                    "bm25($TABLE_NAME) AS score " +
                    "FROM $TABLE_NAME WHERE $TABLE_NAME MATCH ? ORDER BY score LIMIT ?"
            } else {
                "SELECT doc_id, title, snippet($TABLE_NAME, '[', ']', '...', 2, 24), " +
                    "0.0 AS score " +
                    "FROM $TABLE_NAME WHERE $TABLE_NAME MATCH ? LIMIT ?"
            }
            db.rawQuery(sql, arrayOf(ftsQuery, limit.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    hits.add(
                        FtsHit(
                            docId = cursor.getString(0) ?: "",
                            title = cursor.getString(1) ?: "",
                            snippet = cursor.getString(2) ?: "",
                            rank = cursor.getDouble(3),
                        ),
                    )
                }
            }
        } catch (_: SQLiteException) {
            return emptyList()
        }
        return hits
    }

    companion object {
        private const val DB_NAME = "pocketagent_fts.db"
        private const val DB_VERSION = 1
        private const val TABLE_NAME = "docs_fts"

        private val NON_ALNUM_REGEX = Regex("[^a-zA-Z0-9]+")

        private val STOP_WORDS = setOf(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from",
            "has", "have", "how", "in", "is", "it", "of", "on", "or", "that",
            "the", "this", "to", "was", "were", "what", "when", "where", "which",
            "who", "why", "will", "with",
        )

        /**
         * Pure-Kotlin lightweight English stemmer and normalizer.
         */
        fun normalizeToken(token: String): String {
            val cleaned = token.lowercase(Locale.ROOT).replace(NON_ALNUM_REGEX, "")
            if (cleaned.length <= 2) return cleaned

            return when {
                cleaned.endsWith("ational") && cleaned.length > 9 ->
                    cleaned.dropLast(5) + "e"
                cleaned.endsWith("ization") && cleaned.length > 9 ->
                    cleaned.dropLast(5) + "e"
                cleaned.endsWith("ies") && cleaned.length > 4 ->
                    cleaned.dropLast(3) + "y"
                cleaned.endsWith("ing") && cleaned.length > 5 -> {
                    val stem = cleaned.dropLast(3)
                    deduplicateTrailingConsonant(stem)
                }
                cleaned.endsWith("ed") && cleaned.length > 4 -> {
                    val stem = cleaned.dropLast(2)
                    deduplicateTrailingConsonant(stem)
                }
                cleaned.endsWith("ness") && cleaned.length > 6 ->
                    cleaned.dropLast(4)
                cleaned.endsWith("ment") && cleaned.length > 6 ->
                    cleaned.dropLast(4)
                cleaned.endsWith("ly") && cleaned.length > 4 ->
                    cleaned.dropLast(2)
                cleaned.endsWith("es") && cleaned.length > 4 && (
                    cleaned.endsWith("ches") ||
                        cleaned.endsWith("shes") ||
                        cleaned.endsWith("sses") ||
                        cleaned.endsWith("xes") ||
                        cleaned.endsWith("zes")
                    ) -> cleaned.dropLast(2)
                cleaned.endsWith("s") && cleaned.length > 3 &&
                    !cleaned.endsWith("ss") &&
                    !cleaned.endsWith("us") &&
                    !cleaned.endsWith("is") ->
                    cleaned.dropLast(1)
                else -> cleaned
            }
        }

        /**
         * Converts a free-text user query into a safe SQLite FTS MATCH expression.
         */
        fun buildFtsQuery(userQuery: String): String {
            if (userQuery.isBlank()) return ""
            val rawTokens = userQuery
                .split(NON_ALNUM_REGEX)
                .map { it.lowercase(Locale.ROOT).trim() }
                .filter { it.isNotEmpty() }

            if (rawTokens.isEmpty()) return ""

            val contentTokens = rawTokens
                .filter { it.length >= 2 && it !in STOP_WORDS }
                .ifEmpty { rawTokens }

            val normalized = contentTokens
                .map { normalizeToken(it) }
                .filter { it.isNotEmpty() }
                .distinct()

            if (normalized.isEmpty()) return ""
            return normalized.joinToString(" OR ") { "$it*" }
        }

        private fun deduplicateTrailingConsonant(stem: String): String {
            if (stem.length >= 4 && stem[stem.length - 1] == stem[stem.length - 2]) {
                val ch = stem.last()
                if (ch !in charArrayOf('l', 's', 'z')) {
                    return stem.dropLast(1)
                }
            }
            return stem
        }
    }
}
