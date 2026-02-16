package com.example.betterfiles

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max

class FileRepository(private val context: Context) {
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
        queryMediaStore(collection, selection, selectionArgs, sortOrder)
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
        val excludedFolders = RecentExclusionManager.getAll(context)

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
                    if (RecentExclusionManager.isExcluded(path, excludedFolders)) {
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
}
