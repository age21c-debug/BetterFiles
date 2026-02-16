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
    val id: Long,               // 怨좎쑀 ID
    val name: String,           // ?뚯씪 ?대쫫
    val path: String,           // ?뚯씪 寃쎈줈
    val size: Long,             // ?ш린
    val dateModified: Long,     // ?섏젙 ?좎쭨
    val mimeType: String,       // ?뚯씪 醫낅쪟 (MIME)
    val isDirectory: Boolean,   // ?대뜑 ?щ?
    val contentUri: Uri?,       // ?뚯씪 二쇱냼 (Uri)
    var isSelected: Boolean = false,
    val duplicateGroupKey: String? = null,
    val duplicateGroupCount: Int = 0,
    val duplicateGroupSavingsBytes: Long = 0L
) : Parcelable {

    // ?좎쭨瑜?蹂닿린 醫뗪쾶 諛붽씀??湲곕뒫 (?? 2024-01-30)
    fun getFormattedDate(): String {
        val date = Date(dateModified * 1000)
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return format.format(date)
    }

    // ?⑸웾??蹂닿린 醫뗪쾶 諛붽씀??湲곕뒫 (?? 5 MB)
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
