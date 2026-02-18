package com.example.betterfiles

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max

class FileRepository(private val context: Context) {
    companion object {
        private const val DEFAULT_LARGE_FILE_MIN_BYTES: Long = 100L * 1024L * 1024L // 100MB
        private const val AUTO_CLEAN_MIN_BYTES: Long = 20L * 1024L * 1024L // 20MB
        private const val LOW_USAGE_CANDIDATE_MIN_SCORE = 5
        private const val LOW_USAGE_STRONG_RECOMMEND_SCORE = 8
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val AUTO_CLEAN_STALE_DAYS = 30L
        private const val AUTO_CLEAN_SHARE_WINDOW_DAYS = 60L
        private const val QUICK_FINGERPRINT_CHUNK_BYTES = 64 * 1024
        private const val DUPLICATE_PERF_TAG = "DuplicatePerf"
    }

    private val usageStore = SmartShareHistoryStore.get(context)
    private val recentComparator = compareByDescending<FileItem> { it.dateModified }
        .thenBy { it.path.lowercase(Locale.ROOT) }

    // ▼▼▼ [수정] 검색어(query) 파라미터 추가 (기본값 null) ▼▼▼
    // 1. 이미지 파일 가져오기 (전체 or 검색)
    suspend fun getAllImages(query: String? = null): List<FileItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        // 검색어가 있으면 이름(DISPLAY_NAME)으로 필터링
        val selection = if (query != null) "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" else null
        val selectionArgs = if (query != null) arrayOf("%$query%") else null

        queryMediaStore(collection, selection, selectionArgs, sortOrder)
    }

    // 2. 비디오 파일 가져오기 (전체 or 검색)
    suspend fun getAllVideos(query: String? = null): List<FileItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        val selection = if (query != null) "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" else null
        val selectionArgs = if (query != null) arrayOf("%$query%") else null

        queryMediaStore(collection, selection, selectionArgs, sortOrder)
    }

    // 3. 오디오 파일 가져오기 (전체 or 검색)
    suspend fun getAllAudio(query: String? = null): List<FileItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        val selection = if (query != null) "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" else null
        val selectionArgs = if (query != null) arrayOf("%$query%") else null

        queryMediaStore(collection, selection, selectionArgs, sortOrder)
    }

    // 4. 다운로드 폴더 파일 가져오기 (목록 로딩용)
    // * 검색 시에는 searchRecursive를 직접 사용할 것이므로 여기는 그대로 둡니다.
    suspend fun getDownloads(): List<FileItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri("external")
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("Download/%")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        val roots = StorageVolumeHelper.getStorageRoots(context)
        queryMediaStore(collection, selection, selectionArgs, sortOrder)
            .filter { StorageVolumeHelper.detectVolume(it.path, roots) == StorageVolumeType.INTERNAL }
    }

    suspend fun getMessengerFiles(query: String? = null, sourceApp: String? = null): List<FileItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri("external")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        val ownerPackages = MessengerPathMatcher.sources
            .flatMap { it.ownerPackages }
            .distinct()
        val ownerToSource = MessengerPathMatcher.sources
            .flatMap { source -> source.ownerPackages.map { owner -> owner to source.appName } }
            .toMap()
        val whitelistPathPatterns = MessengerPathMatcher.sources
            .flatMap { it.pathPatterns }
            .distinct()
        val downloadsOwnerBlacklist = setOf(
            "com.android.providers.downloads",
            "com.google.android.providers.downloads"
        )
        val relativePathLikeClauses = whitelistPathPatterns
            .joinToString(" OR ") { "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?" }
        val selectionParts = mutableListOf(
            "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL",
            "${MediaStore.Files.FileColumns.SIZE} >= ?",
            "(" +
                "${MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME} IN (${ownerPackages.joinToString(",") { "?" }}) OR " +
                relativePathLikeClauses +
            ")"
        )
        val selectionArgs = mutableListOf(
            (10L * 1024L).toString()
        )
        selectionArgs += ownerPackages
        selectionArgs += whitelistPathPatterns.map { "%$it%" }

        if (!query.isNullOrBlank()) {
            selectionParts += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            selectionArgs += "%$query%"
        }

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME
        )
        val queried = mutableListOf<FileItem>()
        try {
            context.contentResolver.query(
                collection,
                projection,
                selectionParts.joinToString(" AND "),
                selectionArgs.toTypedArray(),
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val relativePathCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val ownerCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val dataPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                    val relativePath = if (relativePathCol >= 0) cursor.getString(relativePathCol) else null
                    val path = when {
                        !dataPath.isNullOrBlank() -> dataPath
                        !relativePath.isNullOrBlank() -> File(Environment.getExternalStorageDirectory(), relativePath).resolve(name).absolutePath
                        else -> continue
                    }
                    val size = cursor.getLong(sizeCol)
                    if (!MessengerPathMatcher.isValidSize(size)) continue
                    if (path.contains("/cache/", ignoreCase = true) ||
                        path.contains("/temp/", ignoreCase = true) ||
                        path.contains("/thumbnails/", ignoreCase = true)
                    ) continue

                    val owner = if (ownerCol >= 0) cursor.getString(ownerCol) else null
                    val isDedicatedMessengerPath = MessengerPathMatcher.isMessengerPath(path)
                    val normalizedRel = (relativePath ?: "").replace('\\', '/').trim()
                    val relNoSlash = normalizedRel.trimEnd('/')
                    val isDownloadRootFile = relNoSlash.equals("Download", ignoreCase = true)
                    val ownerAllowedForDownloadRoot = !owner.isNullOrBlank() &&
                        ownerPackages.contains(owner) &&
                        !downloadsOwnerBlacklist.contains(owner)
                    val shouldInclude = isDedicatedMessengerPath || (isDownloadRootFile && ownerAllowedForDownloadRoot)
                    if (!shouldInclude) continue
                    val resolvedSource = if (isDedicatedMessengerPath) {
                        MessengerPathMatcher.detectSourceName(path)
                    } else {
                        owner?.let { ownerToSource[it] } ?: "Other"
                    }

                    val item = FileItem(
                        id = id,
                        name = name,
                        path = path,
                        size = size,
                        dateModified = cursor.getLong(dateCol),
                        mimeType = cursor.getString(mimeCol) ?: "application/octet-stream",
                        isDirectory = false,
                        contentUri = ContentUris.withAppendedId(collection, id)
                    )
                    if (sourceApp.isNullOrBlank() || resolvedSource == sourceApp) {
                        queried += item
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MessengerFiles", "query failed", e)
        }

        val scanned = scanKnownMessengerDirectories(query, sourceApp)
        val merged = LinkedHashMap<String, FileItem>()
        queried.forEach { merged[it.path] = it }
        scanned.forEach { merged[it.path] = it }
        val matched = merged.values.sortedByDescending { it.dateModified }

        Log.d(
            "MessengerFiles",
            "queried=${queried.size} scanned=${scanned.size} merged=${matched.size} query=${query ?: "<none>"} source=${sourceApp ?: "<all>"}"
        )
        if (matched.isEmpty()) {
            queried.take(5).forEachIndexed { index, item ->
                Log.d("MessengerFiles", "sample[$index]=${item.path}")
            }
        }
        matched
    }

    private fun scanKnownMessengerDirectories(query: String?, sourceApp: String?): List<FileItem> {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val knownDirs = MessengerPathMatcher.sources
            .flatMap { it.pathPatterns }
            .distinct()
            .map { "$root/$it" }

        val result = mutableListOf<FileItem>()
        val queryLower = query?.trim()?.lowercase(Locale.ROOT)
        knownDirs.forEach { dirPath ->
            val dir = File(dirPath)
            if (!dir.exists() || !dir.isDirectory) return@forEach

            dir.walkTopDown()
                .maxDepth(6)
                .forEach { file ->
                    if (!file.exists() || file.isDirectory) return@forEach
                    if (!MessengerPathMatcher.isValidSize(file.length())) return@forEach
                    if (!MessengerPathMatcher.isMessengerPath(file.absolutePath)) return@forEach
                    if (!sourceApp.isNullOrBlank() && MessengerPathMatcher.detectSourceName(file.absolutePath) != sourceApp) return@forEach
                    if (!queryLower.isNullOrBlank() && !file.name.lowercase(Locale.ROOT).contains(queryLower)) return@forEach

                    val ext = file.extension.lowercase(Locale.ROOT)
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                    result += FileItem(
                        id = file.hashCode().toLong(),
                        name = file.name,
                        path = file.absolutePath,
                        size = file.length(),
                        dateModified = file.lastModified() / 1000L,
                        mimeType = mime,
                        isDirectory = false,
                        contentUri = Uri.fromFile(file)
                    )
                }
        }
        return result
    }

    // 5. 문서 파일 가져오기 (전체 or 검색)
    suspend fun getAllDocuments(query: String? = null): List<FileItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri("external")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        val selectionParts = mutableListOf(
            "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL",
            "(${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?)",
            "${MediaStore.Files.FileColumns.MIME_TYPE} != ?",
            "${MediaStore.Files.FileColumns.MIME_TYPE} != ?"
        )
        val selectionArgs = mutableListOf(
            "application/%",
            "text/%",
            "application/vnd.android.package-archive",
            "application/zip"
        )

        if (query != null) {
            selectionParts += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            selectionArgs += "%$query%"
        }

        queryMediaStore(collection, selectionParts.joinToString(" AND "), selectionArgs.toTypedArray(), sortOrder)
    }

    suspend fun getAllApps(query: String? = null): List<FileItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri("external")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        val selectionParts = mutableListOf(
            "(${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)"
        )
        val selectionArgs = mutableListOf(
            "application/vnd.android.package-archive",
            "%.apk"
        )

        if (query != null) {
            selectionParts += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            selectionArgs += "%$query%"
        }

        queryMediaStore(collection, selectionParts.joinToString(" AND "), selectionArgs.toTypedArray(), sortOrder)
    }

    suspend fun getLargeFiles(
        query: String? = null,
        minBytes: Long = DEFAULT_LARGE_FILE_MIN_BYTES
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri("external")
        val sortOrder = "${MediaStore.Files.FileColumns.SIZE} DESC"
        val selectionParts = mutableListOf(
            "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL",
            "${MediaStore.Files.FileColumns.SIZE} >= ?",
            "(${MediaStore.Files.FileColumns.DATA} IS NULL OR ${MediaStore.Files.FileColumns.DATA} NOT LIKE ?)"
        )
        val selectionArgs = mutableListOf(
            minBytes.toString(),
            "%/Android/data/%"
        )

        if (!query.isNullOrBlank()) {
            selectionParts += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            selectionArgs += "%$query%"
        }

        queryMediaStore(
            collectionUri = collection,
            selection = selectionParts.joinToString(" AND "),
            selectionArgs = selectionArgs.toTypedArray(),
            sortOrder = sortOrder
        ).filter { item ->
            !item.isDirectory && item.size >= minBytes && File(item.path).exists()
        }
    }

    suspend fun getLowUsageLargeFiles(
        query: String? = null
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val largeFiles = getLargeFiles(query = query, minBytes = DEFAULT_LARGE_FILE_MIN_BYTES)
            .filter { !it.isDirectory }
        if (largeFiles.isEmpty()) return@withContext emptyList()

        val normalizedPathToItem = LinkedHashMap<String, FileItem>(largeFiles.size)
        largeFiles.forEach { item ->
            normalizePath(item.path)?.let { normalized ->
                normalizedPathToItem[normalized] = item
            }
        }
        if (normalizedPathToItem.isEmpty()) return@withContext emptyList()

        val usageByPath = usageStore.queryPathUsageSignals(normalizedPathToItem.keys.toList())
        val now = System.currentTimeMillis()

        val scored = normalizedPathToItem.mapNotNull { (normalizedPath, item) ->
            val usage = usageByPath[normalizedPath]
            val openCount = usage?.openCountTotal ?: 0
            val lastOpenedAt = usage?.lastOpenedAt ?: 0L
            val baselineMs = if (lastOpenedAt > 0L) lastOpenedAt else (item.dateModified * 1000L)
            val ageDays = ((now - baselineMs).coerceAtLeast(0L) / DAY_MS).toInt()

            val sizeScore = when {
                item.size >= 500L * 1024L * 1024L -> 3
                item.size >= 200L * 1024L * 1024L -> 2
                item.size >= 100L * 1024L * 1024L -> 1
                else -> 0
            }
            val openScore = when {
                openCount <= 0 -> 3
                openCount == 1 -> 2
                openCount <= 3 -> 1
                else -> 0
            }
            val ageScore = when {
                ageDays >= 180 -> 3
                ageDays >= 90 -> 2
                ageDays >= 30 -> 1
                else -> 0
            }
            val totalScore = sizeScore + openScore + ageScore
            if (totalScore < LOW_USAGE_CANDIDATE_MIN_SCORE) return@mapNotNull null

            val isStrongRecommend = totalScore >= LOW_USAGE_STRONG_RECOMMEND_SCORE && openCount == 0
            val strongBonus = if (isStrongRecommend) 100 else 0
            val rankScore = strongBonus + totalScore
            ScoredLowUsageItem(
                item = item.copy(smartScore = rankScore),
                isStrongRecommend = isStrongRecommend,
                totalScore = totalScore,
                lastOpenedAt = lastOpenedAt,
                openCount = openCount
            )
        }

        scored
            .sortedWith(
                compareByDescending<ScoredLowUsageItem> { it.isStrongRecommend }
                    .thenByDescending { it.totalScore }
                    .thenByDescending { it.item.size }
                    .thenByDescending { if (it.lastOpenedAt > 0L) it.lastOpenedAt else (it.item.dateModified * 1000L) }
                    .thenBy { it.item.path.lowercase(Locale.ROOT) }
            )
            .map { it.item }
    }

    suspend fun getAutoCleanCandidates(query: String? = null): List<FileItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val deduped = LinkedHashMap<String, FileItem>()

        addAutoCleanCandidates(
            target = deduped,
            candidates = getLowUsageLargeFiles(query = null),
            requireNoisePathFilter = false
        )
        addAutoCleanCandidates(
            target = deduped,
            candidates = buildOldDownloadCandidates(now),
            requireNoisePathFilter = true
        )
        addAutoCleanCandidates(
            target = deduped,
            candidates = buildOldSharedCandidates(now),
            requireNoisePathFilter = true
        )

        var merged = deduped.values.toList()
            .sortedWith(
                compareByDescending<FileItem> { it.size }
                    .thenByDescending { it.dateModified }
                    .thenBy { it.path.lowercase(Locale.ROOT) }
            )
        if (!query.isNullOrBlank()) {
            merged = merged.filter {
                it.name.contains(query, ignoreCase = true) || it.path.contains(query, ignoreCase = true)
            }
        }
        merged
    }

    suspend fun getOldDownloadFiles(query: String? = null): List<FileItem> = withContext(Dispatchers.IO) {
        val internalRoot = StorageVolumeHelper.getStorageRoots(context).internalRoot
        val downloadRoot = normalizePath(File(internalRoot, "Download").absolutePath)
        val rootPrefix = if (downloadRoot.isNullOrBlank()) null else "$downloadRoot${File.separator}"

        val mergedBase = LinkedHashMap<String, FileItem>()
        val downloadCandidates = getDownloads()
        for (item in downloadCandidates) {
            if (item.isDirectory) continue
            val normalized = normalizePath(item.path) ?: continue
            val inDownloadTree = normalized == downloadRoot || (rootPrefix != null && normalized.startsWith(rootPrefix))
            if (!inDownloadTree) continue
            mergedBase.putIfAbsent(normalized.lowercase(Locale.ROOT), item)
        }

        val messengerCandidates = getMessengerFiles(sourceApp = null)
        for (item in messengerCandidates) {
            if (item.isDirectory) continue
            val normalized = normalizePath(item.path) ?: continue
            mergedBase.putIfAbsent(normalized.lowercase(Locale.ROOT), item)
        }

        if (mergedBase.isEmpty()) return@withContext emptyList()

        val now = System.currentTimeMillis()
        val usageByPath = usageStore.queryPathUsageSignals(mergedBase.keys.toList())
        val dateAddedByPathMs = queryDateAddedByPaths(mergedBase.values.map { it.path })

        val scored = mergedBase.mapNotNull { (normalizedPath, item) ->
            if (item.size < 100L * 1024L * 1024L) return@mapNotNull null
            if (item.path.contains("${File.separator}Android${File.separator}data${File.separator}", ignoreCase = true)) {
                return@mapNotNull null
            }
            if (isNoisePath(item.path)) return@mapNotNull null
            val file = File(item.path)
            if (!file.exists() || file.isDirectory) return@mapNotNull null

            val usage = usageByPath[normalizedPath]
            val lastOpenedAt = usage?.lastOpenedAt ?: 0L
            val dateModifiedMs = item.dateModified * 1000L
            val dateAddedMs = dateAddedByPathMs[normalizedPath] ?: 0L
            val baselineMs = when {
                lastOpenedAt > 0L -> lastOpenedAt
                dateAddedMs > 0L -> dateAddedMs
                else -> dateModifiedMs
            }
            val ageDays = ((now - baselineMs).coerceAtLeast(0L) / DAY_MS).toInt()

            val sizeScore = when {
                item.size >= 500L * 1024L * 1024L -> 3
                item.size >= 200L * 1024L * 1024L -> 2
                item.size >= 100L * 1024L * 1024L -> 1
                else -> 0
            }
            val ageScore = when {
                ageDays >= 180 -> 3
                ageDays >= 90 -> 2
                ageDays >= 30 -> 1
                else -> 0
            }
            val totalScore = sizeScore + ageScore
            if (totalScore <= 3) return@mapNotNull null

            val isStrongRecommend = totalScore > 5
            val rankScore = if (isStrongRecommend) 100 + totalScore else totalScore
            item.copy(smartScore = rankScore)
        }

        var result = scored
            .sortedWith(
                compareByDescending<FileItem> { it.smartScore >= 100 }
                    .thenByDescending { if (it.smartScore >= 100) it.smartScore - 100 else it.smartScore }
                    .thenByDescending { it.size }
                    .thenByDescending { it.dateModified }
                    .thenBy { it.path.lowercase(Locale.ROOT) }
            )
        if (!query.isNullOrBlank()) {
            result = result.filter { it.name.contains(query, ignoreCase = true) || it.path.contains(query, ignoreCase = true) }
        }
        result
    }

    suspend fun getOldSharedFiles(query: String? = null): List<FileItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val shareCandidates = usageStore.queryRecentShareCandidates(
            nowMinus60Days = 0L,
            minSharesInWindow = 1,
            limit = 3000
        )
        if (shareCandidates.isEmpty()) return@withContext emptyList()

        val deduped = LinkedHashMap<String, FileItem>()
        for (candidate in shareCandidates) {
            val lastSharedAt = candidate.lastSharedAt
            if (lastSharedAt <= 0L) continue

            val base = resolveShareCandidateToFileItem(candidate) ?: continue
            if (base.isDirectory || base.size < AUTO_CLEAN_MIN_BYTES) continue
            if (base.path.contains("${File.separator}Android${File.separator}data${File.separator}", ignoreCase = true)) continue
            if (isNoisePath(base.path)) continue

            val ageDays = ((now - lastSharedAt).coerceAtLeast(0L) / DAY_MS).toInt()
            val sizeScore = when {
                base.size >= 500L * 1024L * 1024L -> 3
                base.size >= 200L * 1024L * 1024L -> 2
                base.size >= 50L * 1024L * 1024L -> 1
                else -> 0
            }
            val ageScore = when {
                ageDays >= 240 -> 3
                ageDays >= 120 -> 2
                ageDays >= 60 -> 1
                else -> 0
            }
            if (ageScore <= 0) continue

            val totalScore = sizeScore + ageScore

            // Option A: include from 60d, strong starts at 120d.
            val isStrong = ageDays >= 120
            val rankScore = if (isStrong) 100 + totalScore else totalScore
            val enriched = base.copy(
                smartScore = rankScore,
                shareCount60d = candidate.shareCount60d,
                lastSharedAtMs = lastSharedAt,
                dateModified = (lastSharedAt / 1000L)
            )

            val key = normalizePath(enriched.path)?.lowercase(Locale.ROOT) ?: continue
            val existing = deduped[key]
            if (existing == null || enriched.smartScore > existing.smartScore || (enriched.smartScore == existing.smartScore && enriched.size > existing.size)) {
                deduped[key] = enriched
            }
        }

        var result = deduped.values
            .sortedWith(
                compareByDescending<FileItem> { it.smartScore >= 100 }
                    .thenByDescending { if (it.smartScore >= 100) it.smartScore - 100 else it.smartScore }
                    .thenByDescending { it.size }
                    .thenByDescending { it.lastSharedAtMs }
                    .thenBy { it.path.lowercase(Locale.ROOT) }
            )
        if (!query.isNullOrBlank()) {
            result = result.filter { it.name.contains(query, ignoreCase = true) || it.path.contains(query, ignoreCase = true) }
        }
        result
    }

    private fun queryDateAddedByPaths(paths: List<String>): Map<String, Long> {
        if (paths.isEmpty()) return emptyMap()
        val normalized = paths.mapNotNull { normalizePath(it) }.distinct()
        if (normalized.isEmpty()) return emptyMap()

        val result = LinkedHashMap<String, Long>(normalized.size)
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DATE_ADDED
        )
        val chunkSize = 300
        for (i in normalized.indices step chunkSize) {
            val chunk = normalized.subList(i, minOf(i + chunkSize, normalized.size))
            val placeholders = chunk.joinToString(",") { "?" }
            val selection = "${MediaStore.Files.FileColumns.DATA} IN ($placeholders)"
            val args = chunk.toTypedArray()
            try {
                context.contentResolver.query(
                    collection,
                    projection,
                    selection,
                    args,
                    null
                )?.use { cursor ->
                    val pathCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                    val dateAddedCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                    while (cursor.moveToNext()) {
                        if (pathCol < 0 || dateAddedCol < 0) continue
                        val rawPath = cursor.getString(pathCol) ?: continue
                        val normalizedPath = normalizePath(rawPath)?.lowercase(Locale.ROOT) ?: continue
                        val dateAddedSec = cursor.getLong(dateAddedCol)
                        if (dateAddedSec > 0L) {
                            result[normalizedPath] = dateAddedSec * 1000L
                        }
                    }
                }
            } catch (_: Exception) {
                // Best-effort enrichment: if DATE_ADDED lookup fails, caller falls back to lastOpened/dateModified.
            }
        }
        return result
    }

    private fun addAutoCleanCandidates(
        target: LinkedHashMap<String, FileItem>,
        candidates: List<FileItem>,
        requireNoisePathFilter: Boolean
    ) {
        for (item in candidates) {
            if (item.isDirectory) continue
            if (item.path.contains("${File.separator}Android${File.separator}data${File.separator}", ignoreCase = true)) {
                continue
            }
            if (requireNoisePathFilter && isNoisePath(item.path)) continue

            val file = File(item.path)
            if (!file.exists() || file.isDirectory) continue

            val key = normalizePath(item.path)?.lowercase(Locale.ROOT) ?: continue
            if (!target.containsKey(key)) {
                target[key] = item
            }
        }
    }

    private suspend fun buildOldDownloadCandidates(now: Long): List<FileItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri("external")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        val selectionParts = mutableListOf(
            "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL",
            "${MediaStore.Files.FileColumns.SIZE} >= ?",
            "(${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?)",
            "(${MediaStore.Files.FileColumns.DATA} IS NULL OR ${MediaStore.Files.FileColumns.DATA} NOT LIKE ?)"
        )
        val selectionArgs = mutableListOf(
            AUTO_CLEAN_MIN_BYTES.toString(),
            "Download/%",
            "%/Download/%",
            "%/Android/data/%"
        )

        val base = queryMediaStore(
            collectionUri = collection,
            selection = selectionParts.joinToString(" AND "),
            selectionArgs = selectionArgs.toTypedArray(),
            sortOrder = sortOrder
        ).filter { item ->
            !item.isDirectory &&
                item.size >= AUTO_CLEAN_MIN_BYTES &&
                isDownloadLikePath(item.path)
        }
        if (base.isEmpty()) return@withContext emptyList()

        val normalizedPaths = base.mapNotNull { normalizePath(it.path) }.distinct()
        val usageByPath = usageStore.queryPathUsageSignals(normalizedPaths)
        val staleMs = AUTO_CLEAN_STALE_DAYS * DAY_MS

        base.filter { item ->
            val normalized = normalizePath(item.path) ?: return@filter false
            val usage = usageByPath[normalized]
            val baselineMs = if ((usage?.lastOpenedAt ?: 0L) > 0L) usage!!.lastOpenedAt else (item.dateModified * 1000L)
            (now - baselineMs) >= staleMs
        }
    }

    private suspend fun buildOldSharedCandidates(now: Long): List<FileItem> = withContext(Dispatchers.IO) {
        val windowStart = now - (AUTO_CLEAN_SHARE_WINDOW_DAYS * DAY_MS)
        val staleCutoff = now - (AUTO_CLEAN_STALE_DAYS * DAY_MS)
        val shareCandidates = usageStore.queryRecentShareCandidates(
            nowMinus60Days = windowStart,
            minSharesInWindow = 1,
            limit = 2000
        )
        if (shareCandidates.isEmpty()) return@withContext emptyList()

        shareCandidates.mapNotNull { candidate ->
            if (candidate.lastSharedAt > staleCutoff) return@mapNotNull null
            val item = resolveShareCandidateToFileItem(candidate) ?: return@mapNotNull null
            if (item.size < AUTO_CLEAN_MIN_BYTES) return@mapNotNull null
            if (isNoisePath(item.path)) return@mapNotNull null
            item.copy(
                shareCount60d = candidate.shareCount60d,
                lastSharedAtMs = candidate.lastSharedAt
            )
        }
    }

    private fun resolveShareCandidateToFileItem(candidate: SmartShareCandidate): FileItem? {
        return when (candidate.keyType) {
            SmartShareKeyType.PATH -> {
                val file = File(candidate.key)
                if (!file.exists() || file.isDirectory) return null
                val ext = file.extension.lowercase(Locale.ROOT)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                FileItem(
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
            SmartShareKeyType.MEDIA_ID -> {
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
                    return null
                }
                null
            }
            else -> null
        }
    }

    private fun parseMediaKey(key: String): ParsedMediaKey? {
        val parts = key.split(":")
        if (parts.size != 3) return null
        if (parts[0] != "media") return null
        val id = parts[2].toLongOrNull() ?: return null
        return ParsedMediaKey(id = id)
    }

    private fun isDownloadLikePath(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase(Locale.ROOT)
        return normalized.contains("/download/")
    }

    private fun isNoisePath(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase(Locale.ROOT)
        return normalized.contains("/cache/") ||
            normalized.contains("/temp/") ||
            normalized.contains("/thumbnails/")
    }

    suspend fun getDuplicateFiles(query: String? = null): List<FileItem> = withContext(Dispatchers.IO) {
        val totalStartMs = SystemClock.elapsedRealtime()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )

        val selectionParts = mutableListOf(
            "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL",
            "${MediaStore.Files.FileColumns.SIZE} > 0",
            "(${MediaStore.Files.FileColumns.DATA} IS NULL OR ${MediaStore.Files.FileColumns.DATA} NOT LIKE ?)"
        )
        val selectionArgs = mutableListOf("%/Android/data/%")
        if (!query.isNullOrBlank()) {
            selectionParts += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            selectionArgs += "%$query%"
        }

        val candidates = mutableListOf<FileItem>()
        val queryStartMs = SystemClock.elapsedRealtime()
        try {
            context.contentResolver.query(
                collection,
                projection,
                selectionParts.joinToString(" AND "),
                selectionArgs.toTypedArray(),
                "${MediaStore.Files.FileColumns.SIZE} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val relativePathCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    val dataPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                    val relativePath = if (relativePathCol >= 0) cursor.getString(relativePathCol) else null
                    val path = when {
                        !dataPath.isNullOrBlank() -> dataPath
                        !relativePath.isNullOrBlank() ->
                            File(Environment.getExternalStorageDirectory(), relativePath).resolve(name).absolutePath
                        else -> continue
                    }
                    if (path.contains("${File.separator}Android${File.separator}data${File.separator}", ignoreCase = true)) {
                        continue
                    }

                    candidates += FileItem(
                        id = cursor.getLong(idCol),
                        name = name,
                        path = path,
                        size = cursor.getLong(sizeCol),
                        dateModified = cursor.getLong(dateCol),
                        mimeType = cursor.getString(mimeCol) ?: "application/octet-stream",
                        isDirectory = false,
                        contentUri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(DUPLICATE_PERF_TAG, "query_failed", e)
            e.printStackTrace()
            return@withContext emptyList()
        }
        val queryElapsedMs = SystemClock.elapsedRealtime() - queryStartMs
        Log.d(
            DUPLICATE_PERF_TAG,
            "phase=query elapsedMs=$queryElapsedMs candidates=${candidates.size} query=${query ?: "<none>"}"
        )

        val groupStartMs = SystemClock.elapsedRealtime()
        val sameSizeGroups = candidates.groupBy { it.size }.values.filter { it.size > 1 }
        val groupingElapsedMs = SystemClock.elapsedRealtime() - groupStartMs
        val groupedCandidateCount = sameSizeGroups.sumOf { it.size }
        Log.d(
            DUPLICATE_PERF_TAG,
            "phase=group_by_size elapsedMs=$groupingElapsedMs groups=${sameSizeGroups.size} groupedCandidates=$groupedCandidateCount"
        )
        if (sameSizeGroups.isEmpty()) {
            Log.d(
                DUPLICATE_PERF_TAG,
                "phase=done elapsedMs=${SystemClock.elapsedRealtime() - totalStartMs} duplicates=0"
            )
            return@withContext emptyList()
        }

        val duplicateGroups = mutableListOf<List<FileItem>>()
        var quickPhaseElapsedMs = 0L
        var quickFingerprintChecks = 0
        var narrowedGroupCount = 0
        for (group in sameSizeGroups) {
            currentCoroutineContext().ensureActive()

            val quickPhaseStartMs = SystemClock.elapsedRealtime()
            val quickFingerprintGroups = LinkedHashMap<String, MutableList<FileItem>>()
            for (item in group) {
                quickFingerprintChecks++
                val file = File(item.path)
                if (!file.exists() || file.isDirectory) continue
                val quickFingerprint = computeQuickFingerprint(file) ?: continue
                quickFingerprintGroups.getOrPut(quickFingerprint) { mutableListOf() }.add(item)
            }
            quickPhaseElapsedMs += (SystemClock.elapsedRealtime() - quickPhaseStartMs)

            val narrowedGroups = quickFingerprintGroups
                .values
                .filter { it.size > 1 }
                .map { groupItems ->
                    groupItems.sortedBy { it.path.lowercase(Locale.ROOT) }
                }
            narrowedGroupCount += narrowedGroups.size
            duplicateGroups.addAll(narrowedGroups)
        }

        val duplicates = buildDuplicateGroupItems(duplicateGroups)

        Log.d(
            DUPLICATE_PERF_TAG,
            "phase=hash elapsedQuickMs=$quickPhaseElapsedMs quickChecks=$quickFingerprintChecks narrowedGroups=$narrowedGroupCount fullHashSkipped=true duplicateHits=${duplicates.size}"
        )

        val sortStartMs = SystemClock.elapsedRealtime()
        val sorted = duplicates
        val sortElapsedMs = SystemClock.elapsedRealtime() - sortStartMs
        val totalElapsedMs = SystemClock.elapsedRealtime() - totalStartMs
        Log.d(
            DUPLICATE_PERF_TAG,
            "phase=done elapsedSortMs=$sortElapsedMs totalElapsedMs=$totalElapsedMs result=${sorted.size}"
        )
        sorted
    }

    suspend fun getDuplicateFilesProgressive(
        query: String? = null,
        onSizeOnlyReady: suspend (List<FileItem>) -> Unit
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val totalStartMs = SystemClock.elapsedRealtime()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )

        val selectionParts = mutableListOf(
            "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL",
            "${MediaStore.Files.FileColumns.SIZE} > 0",
            "(${MediaStore.Files.FileColumns.DATA} IS NULL OR ${MediaStore.Files.FileColumns.DATA} NOT LIKE ?)"
        )
        val selectionArgs = mutableListOf("%/Android/data/%")
        if (!query.isNullOrBlank()) {
            selectionParts += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            selectionArgs += "%$query%"
        }

        val candidates = mutableListOf<FileItem>()
        val queryStartMs = SystemClock.elapsedRealtime()
        try {
            context.contentResolver.query(
                collection,
                projection,
                selectionParts.joinToString(" AND "),
                selectionArgs.toTypedArray(),
                "${MediaStore.Files.FileColumns.SIZE} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val relativePathCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    val dataPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                    val relativePath = if (relativePathCol >= 0) cursor.getString(relativePathCol) else null
                    val path = when {
                        !dataPath.isNullOrBlank() -> dataPath
                        !relativePath.isNullOrBlank() ->
                            File(Environment.getExternalStorageDirectory(), relativePath).resolve(name).absolutePath
                        else -> continue
                    }
                    if (path.contains("${File.separator}Android${File.separator}data${File.separator}", ignoreCase = true)) {
                        continue
                    }

                    candidates += FileItem(
                        id = cursor.getLong(idCol),
                        name = name,
                        path = path,
                        size = cursor.getLong(sizeCol),
                        dateModified = cursor.getLong(dateCol),
                        mimeType = cursor.getString(mimeCol) ?: "application/octet-stream",
                        isDirectory = false,
                        contentUri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(DUPLICATE_PERF_TAG, "query_failed", e)
            e.printStackTrace()
            return@withContext emptyList()
        }
        val queryElapsedMs = SystemClock.elapsedRealtime() - queryStartMs
        Log.d(
            DUPLICATE_PERF_TAG,
            "phase=query elapsedMs=$queryElapsedMs candidates=${candidates.size} query=${query ?: "<none>"} progressive=true"
        )

        val groupStartMs = SystemClock.elapsedRealtime()
        val sameSizeGroups = candidates.groupBy { it.size }.values.filter { it.size > 1 }
        val groupingElapsedMs = SystemClock.elapsedRealtime() - groupStartMs
        val groupedCandidateCount = sameSizeGroups.sumOf { it.size }
        Log.d(
            DUPLICATE_PERF_TAG,
            "phase=group_by_size elapsedMs=$groupingElapsedMs groups=${sameSizeGroups.size} groupedCandidates=$groupedCandidateCount progressive=true"
        )
        if (sameSizeGroups.isEmpty()) {
            Log.d(
                DUPLICATE_PERF_TAG,
                "phase=done elapsedMs=${SystemClock.elapsedRealtime() - totalStartMs} duplicates=0 progressive=true"
            )
            return@withContext emptyList()
        }

        val stage1StartMs = SystemClock.elapsedRealtime()
        val sizeOnlyGroups = sameSizeGroups.map { group ->
            group.filter {
                val file = File(it.path)
                file.exists() && !file.isDirectory
            }.sortedBy { it.path.lowercase(Locale.ROOT) }
        }.filter { it.size > 1 }
        val sizeOnly = buildDuplicateGroupItems(sizeOnlyGroups)
        val stage1ElapsedMs = SystemClock.elapsedRealtime() - stage1StartMs
        Log.d(
            DUPLICATE_PERF_TAG,
            "phase=stage1_size_only elapsedMs=$stage1ElapsedMs result=${sizeOnly.size} progressive=true"
        )

        withContext(Dispatchers.Main) {
            onSizeOnlyReady(sizeOnly)
        }

        val duplicateGroups = mutableListOf<List<FileItem>>()
        var quickPhaseElapsedMs = 0L
        var quickFingerprintChecks = 0
        var narrowedGroupCount = 0
        for (group in sameSizeGroups) {
            currentCoroutineContext().ensureActive()

            val quickPhaseStartMs = SystemClock.elapsedRealtime()
            val quickFingerprintGroups = LinkedHashMap<String, MutableList<FileItem>>()
            for (item in group) {
                quickFingerprintChecks++
                val file = File(item.path)
                if (!file.exists() || file.isDirectory) continue
                val quickFingerprint = computeQuickFingerprint(file) ?: continue
                quickFingerprintGroups.getOrPut(quickFingerprint) { mutableListOf() }.add(item)
            }
            quickPhaseElapsedMs += (SystemClock.elapsedRealtime() - quickPhaseStartMs)

            val narrowedGroups = quickFingerprintGroups
                .values
                .filter { it.size > 1 }
                .map { groupItems ->
                    groupItems.sortedBy { it.path.lowercase(Locale.ROOT) }
                }
            narrowedGroupCount += narrowedGroups.size
            duplicateGroups.addAll(narrowedGroups)
        }

        val duplicates = buildDuplicateGroupItems(duplicateGroups)

        Log.d(
            DUPLICATE_PERF_TAG,
            "phase=hash elapsedQuickMs=$quickPhaseElapsedMs quickChecks=$quickFingerprintChecks narrowedGroups=$narrowedGroupCount fullHashSkipped=true duplicateHits=${duplicates.size} progressive=true"
        )

        val sortStartMs = SystemClock.elapsedRealtime()
        val sorted = duplicates
        val sortElapsedMs = SystemClock.elapsedRealtime() - sortStartMs
        val totalElapsedMs = SystemClock.elapsedRealtime() - totalStartMs
        Log.d(
            DUPLICATE_PERF_TAG,
            "phase=done elapsedSortMs=$sortElapsedMs totalElapsedMs=$totalElapsedMs result=${sorted.size} progressive=true"
        )
        sorted
    }

    suspend fun getRecentFiles(
        query: String? = null,
        limit: Int? = null,
        maxAgeDays: Int? = 30
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val selectionParts = mutableListOf<String>()
        val args = mutableListOf<String>()
        selectionParts += "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL"
        selectionParts += "(${MediaStore.Files.FileColumns.DATA} IS NULL OR ${MediaStore.Files.FileColumns.DATA} NOT LIKE ?)"
        args += "%/Android/data/%"

        if (!query.isNullOrBlank()) {
            selectionParts += "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            args += "%$query%"
        }

        if (maxAgeDays != null) {
            val nowSeconds = System.currentTimeMillis() / 1000
            val cutoffSeconds = nowSeconds - (maxAgeDays.toLong() * 24L * 60L * 60L)
            selectionParts += "(${MediaStore.Files.FileColumns.DATE_MODIFIED} >= ? OR ${MediaStore.Files.FileColumns.DATE_ADDED} >= ?)"
            args += cutoffSeconds.toString()
            args += cutoffSeconds.toString()
        }

        val selection = selectionParts.joinToString(" AND ")
        val selectionArgs = args.toTypedArray()

        if (limit != null && query.isNullOrBlank()) {
            val byModified = queryRecentFilesBySort(
                selection = selection,
                selectionArgs = selectionArgs,
                sortColumn = MediaStore.Files.FileColumns.DATE_MODIFIED,
                maxRows = limit
            )
            val byAdded = queryRecentFilesBySort(
                selection = selection,
                selectionArgs = selectionArgs,
                sortColumn = MediaStore.Files.FileColumns.DATE_ADDED,
                maxRows = limit
            )

            val merged = LinkedHashMap<String, FileItem>()
            for (item in byModified) merged["${item.id}|${item.path}"] = item
            for (item in byAdded) merged["${item.id}|${item.path}"] = item

            return@withContext merged.values
                .sortedWith(recentComparator)
                .take(limit)
        }

        val recentItems = queryRecentFilesBySort(
            selection = selection,
            selectionArgs = selectionArgs,
            sortColumn = MediaStore.Files.FileColumns.DATE_MODIFIED,
            maxRows = limit
        )

        val sorted = recentItems.sortedWith(recentComparator)
        if (limit != null) sorted.take(limit) else sorted
    }

    private fun queryRecentFilesBySort(
        selection: String,
        selectionArgs: Array<String>,
        sortColumn: String,
        maxRows: Int?
    ): List<FileItem> {
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
        val sortOrder = "$sortColumn DESC"
        val recentItems = mutableListOf<FileItem>()
        val exclusionRules = RecentExclusionManager.getRules(context)

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val relativePathCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val dataPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                    val relativePath = if (relativePathCol >= 0) cursor.getString(relativePathCol) else null
                    val path = when {
                        !dataPath.isNullOrBlank() -> dataPath
                        !relativePath.isNullOrBlank() ->
                            File(Environment.getExternalStorageDirectory(), relativePath).resolve(name).absolutePath
                        else -> continue
                    }
                    if (path.contains("${File.separator}Android${File.separator}data${File.separator}", ignoreCase = true)) {
                        continue
                    }
                    if (RecentExclusionManager.isExcluded(path, exclusionRules)) {
                        continue
                    }
                    val file = File(path)
                    if (!file.exists() || file.isDirectory) continue

                    val dateModified = cursor.getLong(dateModifiedCol)
                    val dateAdded = cursor.getLong(dateAddedCol)
                    val recentTimestamp = max(dateModified, dateAdded)
                    val mimeType = cursor.getString(mimeCol) ?: "application/octet-stream"
                    val contentUri = ContentUris.withAppendedId(collection, id)

                    recentItems += FileItem(
                        id = id,
                        name = name,
                        path = path,
                        size = cursor.getLong(sizeCol),
                        dateModified = recentTimestamp,
                        mimeType = mimeType,
                        isDirectory = false,
                        contentUri = contentUri
                    )

                    if (maxRows != null && recentItems.size >= maxRows) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return recentItems
    }

    // 5. 실제 경로(Path)를 기반으로 파일 목록 가져오기 (폴더 탐색용)
    suspend fun getFilesByPath(path: String): List<FileItem> = withContext(Dispatchers.IO) {
        val root = File(path)
        val fileList = mutableListOf<FileItem>()

        if (root.exists() && root.isDirectory) {
            val list = root.listFiles() ?: emptyArray()
            val sortedList = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

            for (file in sortedList) {
                if (file.name.startsWith(".")) continue
                fileList.add(fileToFileItem(file))
            }
        }
        return@withContext fileList
    }

    // 6. 하위 폴더까지 뒤지는 재귀 검색 기능
    suspend fun searchRecursive(path: String, query: String): List<FileItem> = withContext(Dispatchers.IO) {
        val resultList = mutableListOf<FileItem>()
        val rootDir = File(path)
        val coroutineContext = currentCoroutineContext()

        if (!rootDir.exists() || !rootDir.isDirectory) return@withContext emptyList()

        fun traverse(dir: File) {
            coroutineContext.ensureActive()
            val list = dir.listFiles() ?: return

            for (file in list) {
                if (file.name.startsWith(".")) continue
                coroutineContext.ensureActive()

                if (file.name.contains(query, ignoreCase = true)) {
                    resultList.add(fileToFileItem(file))
                }

                if (file.isDirectory) {
                    traverse(file)
                }
            }
        }

        traverse(rootDir)
        resultList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    // --- [공통] File 객체를 FileItem으로 변환 ---
    private fun fileToFileItem(file: File): FileItem {
        val mimeType = if (file.isDirectory) {
            "resource/folder"
        } else {
            val extension = file.extension.lowercase(Locale.ROOT)
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
        }

        return FileItem(
            id = file.hashCode().toLong(),
            name = file.name,
            path = file.absolutePath,
            size = if (file.isDirectory) 0 else file.length(),
            dateModified = file.lastModified() / 1000,
            mimeType = mimeType,
            isDirectory = file.isDirectory,
            contentUri = Uri.fromFile(file)
        )
    }

    // --- 공통 쿼리 로직 ---
    private fun queryMediaStore(
        collectionUri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): List<FileItem> {
        val fileList = mutableListOf<FileItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )

        try {
            context.contentResolver.query(
                collectionUri, projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val relativePathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Unknown"
                    val dataPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                    val relativePath = if (relativePathCol >= 0) cursor.getString(relativePathCol) else null
                    val path = when {
                        !dataPath.isNullOrBlank() -> dataPath
                        !relativePath.isNullOrBlank() -> {
                            File(Environment.getExternalStorageDirectory(), relativePath).resolve(name).absolutePath
                        }
                        else -> name
                    }
                    val size = cursor.getLong(sizeCol)
                    val dateModified = cursor.getLong(dateCol)
                    val mimeType = cursor.getString(mimeCol) ?: "application/octet-stream"
                    val contentUri = ContentUris.withAppendedId(collectionUri, id)

                    fileList.add(FileItem(id, name, path, size, dateModified, mimeType, false, contentUri))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return fileList
    }

    private fun buildDuplicateGroupItems(groups: List<List<FileItem>>): List<FileItem> {
        if (groups.isEmpty()) return emptyList()

        val sortedGroups = groups
            .filter { it.size > 1 }
            .sortedWith(
                compareByDescending<List<FileItem>> { it.firstOrNull()?.size ?: 0L }
                    .thenBy { it.firstOrNull()?.path?.lowercase(Locale.ROOT) ?: "" }
            )

        val result = ArrayList<FileItem>(sortedGroups.sumOf { it.size })
        for ((groupIndex, groupItems) in sortedGroups.withIndex()) {
            val groupKey = "dup-${groupItems.first().size}-${groupIndex}"
            val groupCount = groupItems.size
            for (item in groupItems) {
                result += item.copy(
                    duplicateGroupKey = groupKey,
                    duplicateGroupCount = groupCount,
                    duplicateGroupSavingsBytes = (groupCount - 1L) * item.size
                )
            }
        }
        return result
    }

    private fun normalizePath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }
    }

    private data class ScoredLowUsageItem(
        val item: FileItem,
        val isStrongRecommend: Boolean,
        val totalScore: Int,
        val lastOpenedAt: Long,
        val openCount: Int
    )

    private data class ParsedMediaKey(
        val id: Long
    )

    private fun computeQuickFingerprint(file: File): String? {
        return try {
            val length = file.length()
            if (length <= 0L) return null

            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(length.toString().toByteArray())

            val chunkSize = minOf(QUICK_FINGERPRINT_CHUNK_BYTES.toLong(), length).toInt()
            val offsets = linkedSetOf<Long>()
            offsets += 0L
            if (length > chunkSize) {
                offsets += max(0L, (length / 2L) - (chunkSize / 2L))
            }
            if (length > (chunkSize.toLong() * 2L)) {
                offsets += max(0L, length - chunkSize)
            }

            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(chunkSize)
                for (offset in offsets) {
                    raf.seek(offset)
                    val bytesToRead = minOf(chunkSize.toLong(), length - offset).toInt()
                    var totalRead = 0
                    while (totalRead < bytesToRead) {
                        val read = raf.read(buffer, totalRead, bytesToRead - totalRead)
                        if (read <= 0) break
                        totalRead += read
                    }
                    if (totalRead > 0) digest.update(buffer, 0, totalRead)
                }
            }

            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

}
