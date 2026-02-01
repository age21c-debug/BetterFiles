package com.example.betterfiles

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap // ★ 추가됨: 확장자 사전
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale // ★ 추가됨: 소문자 변환용

class FileRepository(private val context: Context) {

    // 1. 이미지 파일 가져오기 (전체)
    suspend fun getAllImages(): List<FileItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        queryMediaStore(collection, null, null, sortOrder)
    }

    // 2. 비디오 파일 가져오기 (전체)
    suspend fun getAllVideos(): List<FileItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        queryMediaStore(collection, null, null, sortOrder)
    }

    // 3. 오디오 파일 가져오기 (전체)
    suspend fun getAllAudio(): List<FileItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        queryMediaStore(collection, null, null, sortOrder)
    }

    // 4. 다운로드 폴더 파일 가져오기
    suspend fun getDownloads(): List<FileItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri("external")
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("Download/%")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        queryMediaStore(collection, selection, selectionArgs, sortOrder)
    }

    // 5. 실제 경로(Path)를 기반으로 파일 목록 가져오기 (폴더 탐색용)
    // ★ 여기가 수정되었습니다!
    suspend fun getFilesByPath(path: String): List<FileItem> = withContext(Dispatchers.IO) {
        val root = File(path)
        val fileList = mutableListOf<FileItem>()

        if (root.exists() && root.isDirectory) {
            val list = root.listFiles() ?: emptyArray()
            // 정렬: 폴더 먼저, 그 다음 파일 (이름 오름차순)
            val sortedList = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

            for (file in sortedList) {
                if (file.name.startsWith(".")) continue // 숨김 파일 제외

                // ▼▼▼ [핵심 변경] 파일 확장자로 MIME Type 알아내기 ▼▼▼
                val mimeType = if (file.isDirectory) {
                    "resource/folder"
                } else {
                    // 파일의 확장자만 뽑아냄 (예: jpg)
                    val extension = file.extension.lowercase(Locale.ROOT)
                    // 안드로이드 사전을 뒤져서 MIME Type을 찾음 (예: image/jpeg)
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
                }
                // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

                fileList.add(
                    FileItem(
                        id = file.hashCode().toLong(),
                        name = file.name,
                        path = file.absolutePath,
                        size = if (file.isDirectory) 0 else file.length(),
                        dateModified = file.lastModified() / 1000,
                        mimeType = mimeType, // 찾아낸 진짜 타입 적용
                        isDirectory = file.isDirectory,
                        contentUri = null
                    )
                )
            }
        }
        return@withContext fileList
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