package com.example.betterfiles

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class SharedFilesSummary(
    val itemCount: Int,
    val totalBytes: Long,
    val lastSharedAt: Long?,
    val previewNames: List<String>
)

data class MessengerFilesSummary(
    val itemCount: Int,
    val totalBytes: Long,
    val lastReceivedAtMs: Long?
)

data class MessengerAppSummary(
    val appName: String,
    val itemCount: Int,
    val totalBytes: Long,
    val lastReceivedAtMs: Long?
)

data class WorkDocumentsSummary(
    val itemCount: Int,
    val totalBytes: Long,
    val lastModifiedAtMs: Long?
)

data class EventPhotoSummary(
    val itemCount: Int,
    val totalBytes: Long,
    val lastPhotoAtMs: Long?,
    val periodDays: Int
)

class SmartCategorySummaryRepository(private val context: Context) {
    private val store = SmartShareHistoryStore.get(context)
    private val fileRepository = FileRepository(context)
    private val workDocumentRepository = SmartWorkDocumentRepository(context)

    suspend fun getFrequentlySharedSummary(): SharedFilesSummary = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val windowStart = now - 60L * 24L * 60L * 60L * 1000L
        val candidates = store.queryRecentShareCandidates(
            nowMinus60Days = windowStart,
            minSharesInWindow = 3,
            limit = 200
        )

        val resolved = candidates.mapNotNull { candidate ->
            val meta = resolveCandidate(candidate) ?: return@mapNotNull null
            Triple(meta.name, meta.sizeBytes, candidate.lastSharedAt)
        }.sortedByDescending { it.third }

        SharedFilesSummary(
            itemCount = resolved.size,
            totalBytes = resolved.sumOf { it.second },
            lastSharedAt = resolved.firstOrNull()?.third,
            previewNames = resolved.take(2).map { it.first }
        )
    }

    suspend fun getMessengerFilesSummary(): MessengerFilesSummary = withContext(Dispatchers.IO) {
        val rows = fileRepository.getMessengerFiles().map { item ->
            MediaRow(
                name = item.name,
                path = item.path,
                sizeBytes = item.size,
                timestampMs = item.dateModified * 1000L
            )
        }
        MessengerFilesSummary(
            itemCount = rows.size,
            totalBytes = rows.sumOf { it.sizeBytes },
            lastReceivedAtMs = rows.maxOfOrNull { it.timestampMs }
        )
    }

    suspend fun getMessengerAppSummaries(): List<MessengerAppSummary> = withContext(Dispatchers.IO) {
        val rows = fileRepository.getMessengerFiles().map { item ->
            MediaRow(
                name = item.name,
                path = item.path,
                sizeBytes = item.size,
                timestampMs = item.dateModified * 1000L
            )
        }
        val grouped = rows.groupBy { MessengerPathMatcher.detectSourceName(it.path) }
        MessengerPathMatcher.sources.map { source ->
            val sourceRows = grouped[source.appName].orEmpty()
            MessengerAppSummary(
                appName = source.appName,
                itemCount = sourceRows.size,
                totalBytes = sourceRows.sumOf { it.sizeBytes },
                lastReceivedAtMs = sourceRows.maxOfOrNull { it.timestampMs }
            )
        }
    }

    suspend fun getWorkDocumentsSummary(): WorkDocumentsSummary = withContext(Dispatchers.IO) {
        val rows = workDocumentRepository.getWorkDocuments()
        WorkDocumentsSummary(
            itemCount = rows.size,
            totalBytes = rows.sumOf { it.size },
            lastModifiedAtMs = rows.maxOfOrNull { it.dateModified * 1000L }
        )
    }

    suspend fun getEventPhotoSummary(): EventPhotoSummary = withContext(Dispatchers.IO) {
        val cutoffSec = (System.currentTimeMillis() - 3L * 24L * 60L * 60L * 1000L) / 1000L
        val rows = queryFiles(
            selection = """
                ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? AND
                (${MediaStore.Files.FileColumns.DATE_MODIFIED} >= ? OR ${MediaStore.Files.FileColumns.DATE_ADDED} >= ?)
            """.trimIndent(),
            selectionArgs = arrayOf("image/%", cutoffSec.toString(), cutoffSec.toString())
        )
        EventPhotoSummary(
            itemCount = rows.size,
            totalBytes = rows.sumOf { it.sizeBytes },
            lastPhotoAtMs = rows.maxOfOrNull { it.timestampMs },
            periodDays = 3
        )
    }

    private data class MediaRow(
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val timestampMs: Long
    )

    private fun queryFiles(
        selection: String,
        selectionArgs: Array<String>,
        requireExistingFile: Boolean = true
    ): List<MediaRow> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_ADDED
        )
        val result = mutableListOf<MediaRow>()
        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val relCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val addCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                val dataPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                val relPath = if (relCol >= 0) cursor.getString(relCol) else null
                val path = when {
                    !dataPath.isNullOrBlank() -> dataPath
                    !relPath.isNullOrBlank() -> File(Environment.getExternalStorageDirectory(), relPath).resolve(name).absolutePath
                    else -> continue
                }
                if (requireExistingFile) {
                    val file = File(path)
                    if (!file.exists() || file.isDirectory) continue
                }

                val modifiedSec = cursor.getLong(modCol)
                val addedSec = cursor.getLong(addCol)
                val timestampMs = maxOf(modifiedSec, addedSec) * 1000L

                result += MediaRow(
                    name = name,
                    path = path,
                    sizeBytes = cursor.getLong(sizeCol),
                    timestampMs = timestampMs
                )
            }
        }
        return result
    }

    private data class ResolvedMeta(
        val name: String,
        val sizeBytes: Long
    )

    private fun resolveCandidate(candidate: SmartShareCandidate): ResolvedMeta? {
        return when (candidate.keyType) {
            SmartShareKeyType.PATH -> {
                val file = File(candidate.key)
                if (!file.exists() || file.isDirectory) null else ResolvedMeta(file.name, file.length())
            }
            SmartShareKeyType.MEDIA_ID -> {
                val id = parseMediaId(candidate.key) ?: return null
                val collection = MediaStore.Files.getContentUri("external")
                val projection = arrayOf(
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.SIZE
                )
                context.contentResolver.query(
                    collection,
                    projection,
                    "${MediaStore.Files.FileColumns._ID} = ?",
                    arrayOf(id.toString()),
                    null
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return null
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)) ?: return null
                    val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE))
                    return ResolvedMeta(name, size)
                }
                null
            }
            else -> null
        }
    }

    private fun parseMediaId(key: String): Long? {
        val parts = key.split(":")
        if (parts.size != 3) return null
        if (parts[0].lowercase(Locale.ROOT) != "media") return null
        return parts[2].toLongOrNull()
    }
}
