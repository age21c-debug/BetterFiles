package com.example.betterfiles

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class FileRepository(private val context: Context) {

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

        if (!rootDir.exists() || !rootDir.isDirectory) return@withContext emptyList()

        fun traverse(dir: File) {
            val list = dir.listFiles() ?: return

            for (file in list) {
                if (file.name.startsWith(".")) continue
                if (Thread.currentThread().isInterrupted) return

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
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Unknown"
                    val path = cursor.getString(pathCol) ?: ""
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