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
                UNIQUE(file_key_type, file_key)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_file_usage_last_shared ON file_usage(last_shared_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == newVersion) return
        db.execSQL("DROP TABLE IF EXISTS share_event")
        db.execSQL("DROP TABLE IF EXISTS file_usage")
        onCreate(db)
    }

    fun recordShare(keys: List<SmartShareFileKey>, sharedAt: Long, batchId: String) {
        if (keys.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            keys.forEach { key ->
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

    companion object {
        private const val DB_NAME = "smart_share.db"
        private const val DB_VERSION = 1

        @Volatile
        private var instance: SmartShareHistoryStore? = null

        fun get(context: Context): SmartShareHistoryStore {
            return instance ?: synchronized(this) {
                instance ?: SmartShareHistoryStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
