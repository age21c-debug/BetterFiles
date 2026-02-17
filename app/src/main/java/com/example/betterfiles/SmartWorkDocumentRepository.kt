package com.example.betterfiles

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class SmartWorkDocumentRepository(private val context: Context) {
    companion object {
        const val TYPE_ALL = "all"
        const val TYPE_PDF = "pdf"
        const val TYPE_WORD = "word"
        const val TYPE_EXCEL = "excel"
        const val TYPE_PPT = "ppt"
        const val TYPE_HWP = "hwp"
        const val TYPE_OTHER = "other"

        private const val MIN_BYTES = 2L * 1024L
        private const val SCORE_THRESHOLD = 3
        private val RECENT_WINDOW_MS = TimeUnit.DAYS.toMillis(180)
        private val CACHE_TTL_MS = TimeUnit.HOURS.toMillis(3)

        private val lock = Any()
        private var cachedItems: List<FileItem>? = null
        private var cacheAtMs: Long = 0L
        private var cacheDirty: Boolean = true
        private var observerRegistered: Boolean = false
        private var observer: ContentObserver? = null
    }

    init {
        ensureObserverRegistered(context.applicationContext)
    }

    suspend fun getWorkDocuments(
        query: String? = null,
        typeFilter: String = TYPE_ALL
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val normalizedQuery = query?.trim()?.lowercase(Locale.ROOT).orEmpty()
        getCachedOrLoad().asSequence()
            .filter { typeFilter == TYPE_ALL || it.smartDocumentType == typeFilter }
            .filter { normalizedQuery.isBlank() || it.name.lowercase(Locale.ROOT).contains(normalizedQuery) }
            .sortedWith(
                compareByDescending<FileItem> { it.smartScore }
                    .thenByDescending { it.dateModified }
                    .thenBy { it.path.lowercase(Locale.ROOT) }
            )
            .toList()
    }

    private fun getCachedOrLoad(): List<FileItem> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val cache = cachedItems
            val isValid = cache != null && !cacheDirty && (now - cacheAtMs) <= CACHE_TTL_MS
            if (isValid) {
                return cache!!
            }
        }

        val scanned = scanAndScore(now)
        synchronized(lock) {
            cachedItems = scanned
            cacheAtMs = now
            cacheDirty = false
        }
        return scanned
    }

    private fun scanAndScore(nowMs: Long): List<FileItem> {
        val cutoffSec = (nowMs - RECENT_WINDOW_MS) / 1000L
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL AND ${MediaStore.Files.FileColumns.SIZE} >= ?"
        val selectionArgs = arrayOf(MIN_BYTES.toString())
        val result = mutableListOf<FileItem>()

        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            val relCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val addCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val mimeType = cursor.getString(mimeCol)?.lowercase(Locale.ROOT) ?: "application/octet-stream"
                val dataPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                val relPath = if (relCol >= 0) cursor.getString(relCol) else null
                val path = when {
                    !dataPath.isNullOrBlank() -> dataPath
                    !relPath.isNullOrBlank() -> File(Environment.getExternalStorageDirectory(), relPath).resolve(name).absolutePath
                    else -> continue
                }
                if (isExcludedPath(path)) continue

                val size = cursor.getLong(sizeCol)
                val modifiedSec = cursor.getLong(modCol)
                val addedSec = cursor.getLong(addCol)
                val timestampSec = maxOf(modifiedSec, addedSec)

                val type = detectType(name, mimeType) ?: continue
                val score = calculateScore(
                    name = name,
                    path = path,
                    timestampSec = timestampSec,
                    cutoffSec = cutoffSec
                )
                if (score < SCORE_THRESHOLD) continue

                result += FileItem(
                    id = id,
                    name = name,
                    path = path,
                    size = size,
                    dateModified = timestampSec,
                    mimeType = mimeType,
                    isDirectory = false,
                    contentUri = ContentUris.withAppendedId(collection, id),
                    smartScore = score,
                    smartDocumentType = type
                )
            }
        }

        return result.sortedWith(
            compareByDescending<FileItem> { it.smartScore }
                .thenByDescending { it.dateModified }
                .thenBy { it.path.lowercase(Locale.ROOT) }
        )
    }

    private fun detectType(name: String, mimeType: String): String? {
        val lowerName = name.lowercase(Locale.ROOT)
        val ext = lowerName.substringAfterLast('.', "")

        if (ext in EXCLUDED_EXTENSIONS || mimeType in EXCLUDED_MIME_TYPES) return null
        if (ext in PDF_EXTENSIONS || mimeType in PDF_MIME_TYPES) return TYPE_PDF
        if (ext in WORD_EXTENSIONS || mimeType in WORD_MIME_TYPES) return TYPE_WORD
        if (ext in EXCEL_EXTENSIONS || mimeType in EXCEL_MIME_TYPES) return TYPE_EXCEL
        if (ext in PPT_EXTENSIONS || mimeType in PPT_MIME_TYPES) return TYPE_PPT
        if (ext in HWP_EXTENSIONS || mimeType in HWP_MIME_TYPES) return TYPE_HWP
        if (ext in OTHER_DOC_EXTENSIONS || mimeType in OTHER_DOC_MIME_TYPES) return TYPE_OTHER
        return null
    }

    private fun calculateScore(
        name: String,
        path: String,
        timestampSec: Long,
        cutoffSec: Long
    ): Int {
        val normalizedName = name.lowercase(Locale.ROOT)
        val normalizedPath = normalize(path).lowercase(Locale.ROOT)
        val keywordScore = countMatches(normalizedName, FILE_NAME_KEYWORDS).coerceAtMost(5)
        val pathScore = countMatches(normalizedPath, PATH_HINTS).coerceAtMost(4)
        val recencyScore = if (timestampSec >= cutoffSec) 2 else 0
        return keywordScore + pathScore + recencyScore
    }

    private fun isExcludedPath(path: String): Boolean {
        val normalized = normalize(path).lowercase(Locale.ROOT)
        if (normalized.contains("/cache/") || normalized.contains("/temp/") || normalized.contains("/thumbnails/")) {
            return true
        }
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.any { it.startsWith(".") }) return true
        return false
    }

    private fun countMatches(target: String, words: List<String>): Int {
        var count = 0
        for (word in words) {
            if (containsWord(target, word)) {
                count++
            }
        }
        return count
    }

    private fun containsWord(target: String, word: String): Boolean {
        if (word.isBlank()) return false
        val token = word.lowercase(Locale.ROOT)
        val asciiWord = token.all { it in 'a'..'z' || it in '0'..'9' || it == ' ' }
        if (!asciiWord) return target.contains(token)

        if (token.contains(' ')) return target.contains(token)
        val regex = Regex("(^|[^a-z0-9])${Regex.escape(token)}([^a-z0-9]|$)")
        return regex.containsMatchIn(target)
    }

    private fun normalize(path: String): String = path.replace('\\', '/').trim()

    private fun ensureObserverRegistered(appContext: Context) {
        synchronized(lock) {
            if (observerRegistered) return
            val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    markCacheDirty()
                }

                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    markCacheDirty()
                }
            }
            appContext.contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri("external"),
                true,
                contentObserver
            )
            observer = contentObserver
            observerRegistered = true
        }
    }

    private fun markCacheDirty() {
        synchronized(lock) {
            cacheDirty = true
        }
    }

    private val FILE_NAME_KEYWORDS = listOf(
        "계약", "견적", "청구", "세금", "영수", "거래", "발주", "입금", "정산", "보고",
        "기획", "제안", "회의", "회의록", "요약", "문서", "양식", "신청", "제출", "증빙",
        "이력서", "경력", "포트폴리오",
        "invoice", "receipt", "quote", "estimate", "contract", "proposal", "report",
        "minutes", "meeting", "summary", "statement", "tax", "order", "purchase",
        "resume", "cv", "portfolio"
    )

    private val PATH_HINTS = listOf(
        "work", "업무", "회사", "project", "projects", "report", "reports",
        "계약", "견적", "invoice", "proposal", "minutes", "resume", "portfolio",
        "hr", "finance", "청구", "세금", "거래", "발주", "정산", "회의", "제출", "증빙"
    )

    private val PDF_EXTENSIONS = setOf("pdf")
    private val WORD_EXTENSIONS = setOf("doc", "docx")
    private val EXCEL_EXTENSIONS = setOf("xls", "xlsx")
    private val PPT_EXTENSIONS = setOf("ppt", "pptx")
    private val HWP_EXTENSIONS = setOf("hwp", "hwpx")
    private val OTHER_DOC_EXTENSIONS = setOf("csv", "rtf", "txt")

    private val PDF_MIME_TYPES = setOf("application/pdf")
    private val WORD_MIME_TYPES = setOf(
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    )
    private val EXCEL_MIME_TYPES = setOf(
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )
    private val PPT_MIME_TYPES = setOf(
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    )
    private val HWP_MIME_TYPES = setOf(
        "application/x-hwp",
        "application/haansofthwp",
        "application/hwp+zip"
    )
    private val OTHER_DOC_MIME_TYPES = setOf(
        "text/csv",
        "application/csv",
        "application/rtf",
        "text/rtf",
        "text/plain"
    )
    private val EXCLUDED_EXTENSIONS = setOf(
        "zip", "apk", "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif", "html", "htm", "md", "markdown"
    )
    private val EXCLUDED_MIME_TYPES = setOf(
        "application/zip",
        "application/vnd.android.package-archive",
        "text/html",
        "text/markdown"
    )
}
