package com.example.betterfiles

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

@Parcelize
data class FileItem(
    val id: Long,               // 고유 ID
    val name: String,           // 파일 이름
    val path: String,           // 파일 경로
    val size: Long,             // 크기
    val dateModified: Long,     // 수정 날짜
    val mimeType: String,       // 파일 종류 (MIME)
    val isDirectory: Boolean,   // 폴더 여부
    val contentUri: Uri?,       // 파일 주소 (Uri)
    var isSelected: Boolean = false // 체크박스 선택 여부
) : Parcelable {

    // 날짜를 보기 좋게 바꾸는 기능 (예: 2024-01-30)
    fun getFormattedDate(): String {
        val date = Date(dateModified * 1000)
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return format.format(date)
    }

    // 용량을 보기 좋게 바꾸는 기능 (예: 5 MB)
    fun getFormattedSize(): String {
        if (isDirectory) return ""
        if (size <= 0) return "0 B"

        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()

        return DecimalFormat("#,##0.#").format(
            size / 1024.0.pow(digitGroups.toDouble())
        ) + " " + units[digitGroups]
    }
}