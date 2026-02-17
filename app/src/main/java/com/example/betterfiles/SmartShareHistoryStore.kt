package com.example.betterfiles

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SmartShareCandidate(
    val keyType: String,
    val key: String,
    val shareCount60d: Int,
    val lastSharedAt: Long
)

object SmartShareKeyType {
    const val DOC_URI = "DOC_URI"
    const val MEDIA_ID = "MEDIA_ID"
    const val PATH = "PATH"
}

data class SmartShareFileKey(
    val type: String,
    val key: String
)

data class FileUsageSignal(
    val openCountTotal: Int,
    val lastOpenedAt: Long
)

class SmartShareHistoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE share_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_key_type TEXT NOT NULL,
                file_key TEXT NOT NULL,
                shared_at INTEGER NOT NULL,
                batch_id TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_share_event_shared_at ON share_event(shared_at)")
        db.execSQL("CREATE INDEX idx_share_event_file_key ON share_event(file_key_type, file_key)")

        db.execSQL(
            """
            CREATE TABLE file_usage (
                file_key_type TEXT NOT NULL,
                file_key TEXT NOT NULL,
                share_count_total INTEGER NOT NULL,
                last_shared_at INTEGER NOT NULL,
                open_count_total INTEGER NOT NULL DEFAULT 0,
                last_opened_at INTEGER NOT NULL DEFAULT 0,
                UNIQUE(file_key_type, file_key)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_file_usage_last_shared ON file_usage(last_shared_at)")
        db.execSQL("CREATE INDEX idx_file_usage_last_opened ON file_usage(last_opened_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == newVersion) return
        var version = oldVersion
        if (version < 2) {
            db.execSQL("ALTER TABLE file_usage ADD COLUMN open_count_total INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE file_usage ADD COLUMN last_opened_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_file_usage_last_opened ON file_usage(last_opened_at)")
            version = 2
        }
        if (version != newVersion) {
            db.execSQL("DROP TABLE IF EXISTS share_event")
            db.execSQL("DROP TABLE IF EXISTS file_usage")
            onCreate(db)
        }
    }

    fun recordShare(
        keys: List<SmartShareFileKey>,
        sharedAt: Long,
        batchId: String,
        dedupeWindowMs: Long = 0L
    ) {
        if (keys.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            keys.forEach { key ->
                if (dedupeWindowMs > 0L && isDuplicateShareWithinWindow(db, key, sharedAt, dedupeWindowMs)) {
                    return@forEach
                }
                val eventValues = ContentValues().apply {
                    put("file_key_type", key.type)
                    put("file_key", key.key)
                    put("shared_at", sharedAt)
                    put("batch_id", batchId)
                }
                db.insert("share_event", null, eventValues)
                val usageValues = ContentValues().apply {
                    put("file_key_type", key.type)
                    put("file_key", key.key)
                    put("share_count_total", 1)
                    put("last_shared_at", sharedAt)
                    put("open_count_total", 0)
                    put("last_opened_at", 0)
                }
                val inserted = db.insertWithOnConflict(
                    "file_usage",
                    null,
                    usageValues,
                    SQLiteDatabase.CONFLICT_IGNORE
                )
                if (inserted == -1L) {
                    db.execSQL(
                        """
                        UPDATE file_usage
                        SET share_count_total = share_count_total + 1,
                            last_shared_at = ?
                        WHERE file_key_type = ? AND file_key = ?
                        """.trimIndent(),
                        arrayOf(sharedAt, key.type, key.key)
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recordOpen(keys: List<SmartShareFileKey>, openedAt: Long) {
        if (keys.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            keys.forEach { key ->
                val usageValues = ContentValues().apply {
                    put("file_key_type", key.type)
                    put("file_key", key.key)
                    put("share_count_total", 0)
                    put("last_shared_at", 0)
                    put("open_count_total", 1)
                    put("last_opened_at", openedAt)
                }
                val inserted = db.insertWithOnConflict(
                    "file_usage",
                    null,
                    usageValues,
                    SQLiteDatabase.CONFLICT_IGNORE
                )
                if (inserted == -1L) {
                    db.execSQL(
                        """
                        UPDATE file_usage
                        SET open_count_total = open_count_total + 1,
                            last_opened_at = ?
                        WHERE file_key_type = ? AND file_key = ?
                        """.trimIndent(),
                        arrayOf(openedAt, key.type, key.key)
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun isDuplicateShareWithinWindow(
        db: SQLiteDatabase,
        key: SmartShareFileKey,
        sharedAt: Long,
        dedupeWindowMs: Long
    ): Boolean {
        db.rawQuery(
            """
            SELECT MAX(shared_at)
            FROM share_event
            WHERE file_key_type = ? AND file_key = ?
            """.trimIndent(),
            arrayOf(key.type, key.key)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return false
            if (cursor.isNull(0)) return false
            val lastSharedAt = cursor.getLong(0)
            return (sharedAt - lastSharedAt) in 0..dedupeWindowMs
        }
    }

    fun queryRecentShareCandidates(
        nowMinus60Days: Long,
        minSharesInWindow: Int,
        limit: Int = 200
    ): List<SmartShareCandidate> {
        val sql = """
            SELECT
                file_key_type,
                file_key,
                COUNT(*) AS share_count_60d,
                MAX(shared_at) AS last_shared_at
            FROM share_event
            WHERE shared_at >= CAST(? AS INTEGER)
            GROUP BY file_key_type, file_key
            HAVING COUNT(*) >= CAST(? AS INTEGER)
            LIMIT $limit
        """.trimIndent()
        val result = mutableListOf<SmartShareCandidate>()
        readableDatabase.rawQuery(
            sql,
            arrayOf(nowMinus60Days.toString(), minSharesInWindow.toString())
        ).use { cursor ->
            val typeCol = cursor.getColumnIndexOrThrow("file_key_type")
            val keyCol = cursor.getColumnIndexOrThrow("file_key")
            val countCol = cursor.getColumnIndexOrThrow("share_count_60d")
            val lastCol = cursor.getColumnIndexOrThrow("last_shared_at")
            while (cursor.moveToNext()) {
                result += SmartShareCandidate(
                    keyType = cursor.getString(typeCol),
                    key = cursor.getString(keyCol),
                    shareCount60d = cursor.getInt(countCol),
                    lastSharedAt = cursor.getLong(lastCol)
                )
            }
        }
        return result
    }

    fun pruneOldEvents(nowMinus180Days: Long) {
        writableDatabase.delete(
            "share_event",
            "shared_at < ?",
            arrayOf(nowMinus180Days.toString())
        )
    }

    fun queryPathUsageSignals(paths: List<String>): Map<String, FileUsageSignal> {
        if (paths.isEmpty()) return emptyMap()

        val normalized = paths.mapNotNull { normalizePath(it) }.distinct()
        if (normalized.isEmpty()) return emptyMap()

        val result = LinkedHashMap<String, FileUsageSignal>(normalized.size)
        val chunkSize = 300
        for (i in normalized.indices step chunkSize) {
            val chunk = normalized.subList(i, minOf(i + chunkSize, normalized.size))
            val placeholders = chunk.joinToString(",") { "?" }
            val sql = """
                SELECT file_key, open_count_total, last_opened_at
                FROM file_usage
                WHERE file_key_type = ? AND file_key IN ($placeholders)
            """.trimIndent()
            val args = arrayOf(SmartShareKeyType.PATH) + chunk.toTypedArray()
            readableDatabase.rawQuery(sql, args).use { cursor ->
                val keyCol = cursor.getColumnIndexOrThrow("file_key")
                val openCountCol = cursor.getColumnIndexOrThrow("open_count_total")
                val lastOpenedCol = cursor.getColumnIndexOrThrow("last_opened_at")
                while (cursor.moveToNext()) {
                    val key = cursor.getString(keyCol) ?: continue
                    result[key] = FileUsageSignal(
                        openCountTotal = cursor.getInt(openCountCol),
                        lastOpenedAt = cursor.getLong(lastOpenedCol)
                    )
                }
            }
        }
        return result
    }

    private fun normalizePath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return runCatching { java.io.File(path).canonicalPath }.getOrElse { java.io.File(path).absolutePath }
    }

    companion object {
        private const val DB_NAME = "smart_share.db"
        private const val DB_VERSION = 2

        @Volatile
        private var instance: SmartShareHistoryStore? = null

        fun get(context: Context): SmartShareHistoryStore {
            return instance ?: synchronized(this) {
                instance ?: SmartShareHistoryStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
