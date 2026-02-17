package com.example.betterfiles

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class EventHashCacheStore private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE event_hash_cache (
                path TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                file_mtime_ms INTEGER NOT NULL,
                hash64 INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (path, size_bytes, file_mtime_ms)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_event_hash_updated ON event_hash_cache(updated_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == newVersion) return
        db.execSQL("DROP TABLE IF EXISTS event_hash_cache")
        onCreate(db)
    }

    fun get(path: String, sizeBytes: Long, fileMtimeMs: Long): Long? {
        val sql = """
            SELECT hash64
            FROM event_hash_cache
            WHERE path = ? AND size_bytes = ? AND file_mtime_ms = ?
            LIMIT 1
        """.trimIndent()
        readableDatabase.rawQuery(
            sql,
            arrayOf(path, sizeBytes.toString(), fileMtimeMs.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    fun put(path: String, sizeBytes: Long, fileMtimeMs: Long, hash64: Long) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("path", path)
            put("size_bytes", sizeBytes)
            put("file_mtime_ms", fileMtimeMs)
            put("hash64", hash64)
            put("updated_at", now)
        }
        val db = writableDatabase
        val inserted = db.insertWithOnConflict(
            "event_hash_cache",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
        if (inserted == -1L) {
            db.execSQL(
                """
                UPDATE event_hash_cache
                SET hash64 = ?, updated_at = ?
                WHERE path = ? AND size_bytes = ? AND file_mtime_ms = ?
                """.trimIndent(),
                arrayOf(hash64, now, path, sizeBytes, fileMtimeMs)
            )
        }
    }

    companion object {
        private const val DB_NAME = "event_hash_cache.db"
        private const val DB_VERSION = 1

        @Volatile
        private var instance: EventHashCacheStore? = null

        fun get(context: Context): EventHashCacheStore {
            return instance ?: synchronized(this) {
                instance ?: EventHashCacheStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
