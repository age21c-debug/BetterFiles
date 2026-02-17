package com.example.betterfiles

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class SmartSelectionRepository(private val context: Context) {
    private val store = SmartShareHistoryStore.get(context)
    private val tag = "SmartSelection"

    suspend fun getFrequentlySharedFiles(
        query: String? = null,
        maxResults: Int = 50
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val windowStart = now - WINDOW_DAYS * 24L * 60L * 60L * 1000L

        val candidates = store.queryRecentShareCandidates(
            nowMinus60Days = windowStart,
            minSharesInWindow = MIN_SHARES_IN_WINDOW,
            limit = 200
        )
        Log.d(tag, "candidates=${candidates.size} query=${query ?: "<none>"}")

        val resolved = candidates.mapNotNull { candidate ->
            val baseItem = resolveCandidate(candidate) ?: return@mapNotNull null
            val item = baseItem.copy(
                shareCount60d = candidate.shareCount60d,
                lastSharedAtMs = candidate.lastSharedAt
            )
            if (!query.isNullOrBlank() && !item.name.contains(query, ignoreCase = true)) {
                return@mapNotNull null
            }
            val score = scoreOf(candidate.shareCount60d, candidate.lastSharedAt, now)
            ScoredItem(item, score, candidate.lastSharedAt, candidate.shareCount60d)
        }
        Log.d(tag, "resolved=${resolved.size}")

        val finalItems = resolved
            .sortedWith(
                compareByDescending<ScoredItem> { it.score }
                    .thenByDescending { it.lastSharedAt }
                    .thenByDescending { it.shareCount60d }
            )
            .take(maxResults)
            .map { it.item }
        Log.d(tag, "final=${finalItems.size}")
        finalItems
    }

    private fun resolveCandidate(candidate: SmartShareCandidate): FileItem? {
        return when (candidate.keyType) {
            SmartShareKeyType.PATH -> resolvePathCandidate(candidate)
            SmartShareKeyType.MEDIA_ID -> resolveMediaCandidate(candidate)
            SmartShareKeyType.DOC_URI -> resolveDocUriCandidate(candidate)
            else -> null
        }
    }

    private fun resolvePathCandidate(candidate: SmartShareCandidate): FileItem? {
        val file = File(candidate.key)
        if (!file.exists() || file.isDirectory) return null
        val ext = file.extension.lowercase(Locale.ROOT)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        return FileItem(
            id = file.hashCode().toLong(),
            name = file.name,
            path = file.absolutePath,
            size = file.length(),
            dateModified = (candidate.lastSharedAt / 1000L),
            mimeType = mime,
            isDirectory = false,
            contentUri = Uri.fromFile(file)
        )
    }

    private fun resolveMediaCandidate(candidate: SmartShareCandidate): FileItem? {
        val parsed = parseMediaKey(candidate.key) ?: return null
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE
        )
        try {
            context.contentResolver.query(
                uri,
                projection,
                "${MediaStore.MediaColumns._ID} = ?",
                arrayOf(parsed.id.toString()),
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) ?: return null
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val relCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                val relPath = if (relCol >= 0) cursor.getString(relCol) else null
                val path = when {
                    !dataPath.isNullOrBlank() -> dataPath
                    !relPath.isNullOrBlank() -> File(Environment.getExternalStorageDirectory(), relPath).resolve(name).absolutePath
                    else -> File(Environment.getExternalStorageDirectory(), name).absolutePath
                }
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
                    ?: "application/octet-stream"

                return FileItem(
                    id = parsed.id,
                    name = name,
                    path = path,
                    size = size,
                    dateModified = (candidate.lastSharedAt / 1000L),
                    mimeType = mime,
                    isDirectory = false,
                    contentUri = ContentUris.withAppendedId(uri, parsed.id)
                )
            }
        } catch (e: Exception) {
            Log.w(tag, "resolveMediaCandidate failed key=${candidate.key}", e)
            return null
        }
        return null
    }

    private fun resolveDocUriCandidate(candidate: SmartShareCandidate): FileItem? {
        // Without fingerprint/remap, SAF Uri cannot be safely mapped after move/rename.
        // Keep it simple for now: only include when a concrete file path is available.
        return null
    }

    private fun parseMediaKey(key: String): ParsedMediaKey? {
        val parts = key.split(":")
        if (parts.size != 3) return null
        if (parts[0] != "media") return null
        val collection = parts[1]
        val id = parts[2].toLongOrNull() ?: return null
        if (collection.isBlank()) return null
        return ParsedMediaKey(id = id)
    }

    private fun scoreOf(shareCount60d: Int, lastSharedAt: Long, now: Long): Int {
        val ageDays = ((now - lastSharedAt).coerceAtLeast(0L) / (24L * 60L * 60L * 1000L)).toInt()
        val recencyBonus = when {
            ageDays <= 7 -> 30
            ageDays <= 30 -> 15
            else -> 5
        }
        return shareCount60d * 10 + recencyBonus
    }

    private data class ParsedMediaKey(
        val id: Long
    )

    private data class ScoredItem(
        val item: FileItem,
        val score: Int,
        val lastSharedAt: Long,
        val shareCount60d: Int
    )

    companion object {
        private const val WINDOW_DAYS = 60L
        private const val MIN_SHARES_IN_WINDOW = 3
    }
}
