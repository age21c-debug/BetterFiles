package com.example.betterfiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.ImageView
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.bumptech.glide.Glide
import java.io.File
import java.util.Locale

object FileVisualRules {

    enum class FileType {
        FOLDER,
        IMAGE,
        VIDEO,
        APK,
        PDF,
        VOICE,
        AUDIO,
        ZIP,
        OTHER
    }

    fun resolveType(fileName: String, mimeType: String = "", isDirectory: Boolean = false): FileType {
        if (isDirectory) return FileType.FOLDER

        val lowerName = fileName.lowercase(Locale.getDefault())
        val lowerMime = mimeType.lowercase(Locale.getDefault())

        return when {
            isImage(lowerName, lowerMime) -> FileType.IMAGE
            isVideo(lowerName, lowerMime) -> FileType.VIDEO
            isApk(lowerName, lowerMime) -> FileType.APK
            isPdf(lowerName, lowerMime) -> FileType.PDF
            isVoice(lowerName, lowerMime) -> FileType.VOICE
            isAudio(lowerName, lowerMime) -> FileType.AUDIO
            isZip(lowerName) -> FileType.ZIP
            else -> FileType.OTHER
        }
    }

    fun typeIconRes(type: FileType): Int {
        return when (type) {
            FileType.FOLDER -> R.drawable.ic_folder_solid
            FileType.IMAGE -> R.drawable.ic_image_file
            FileType.VIDEO -> R.drawable.ic_video
            FileType.APK -> R.drawable.ic_android_file
            FileType.PDF -> R.drawable.ic_pdf
            FileType.VOICE -> R.drawable.ic_mic
            FileType.AUDIO -> R.drawable.ic_music_note
            FileType.ZIP -> R.drawable.ic_zip
            FileType.OTHER -> R.drawable.ic_file
        }
    }

    fun typeIconColor(type: FileType): Int? {
        return when (type) {
            FileType.FOLDER -> null
            FileType.IMAGE -> Color.parseColor("#E53935")
            FileType.VIDEO -> Color.parseColor("#1565C0")
            FileType.APK -> Color.parseColor("#3DDC84")
            FileType.PDF -> Color.parseColor("#F44336")
            FileType.VOICE -> Color.parseColor("#009688")
            FileType.AUDIO -> Color.parseColor("#9C27B0")
            FileType.ZIP -> Color.parseColor("#FFC107")
            FileType.OTHER -> Color.parseColor("#5F6368")
        }
    }

    fun shouldUseBitmapThumbnail(type: FileType): Boolean {
        return type == FileType.IMAGE || type == FileType.VIDEO || type == FileType.APK || type == FileType.PDF
    }

    fun bindThumbnail(
        context: Context,
        target: ImageView,
        fileItem: FileItem,
        file: File = File(fileItem.path),
        pdfCache: LruCache<String, Bitmap>,
        pdfThumbWidth: Int,
        overlayView: ImageView? = null,
        clearTargetFirst: Boolean = true,
        usePlaceholder: Boolean = true
    ) {
        val type = resolveType(fileItem.name, fileItem.mimeType, fileItem.isDirectory)

        if (clearTargetFirst) {
            Glide.with(context).clear(target)
            target.setImageDrawable(null)
        }
        target.clearColorFilter()

        when (type) {
            FileType.FOLDER -> {
                target.setImageResource(R.drawable.ic_folder_solid)
            }

            FileType.IMAGE, FileType.VIDEO -> {
                val request = Glide.with(context)
                    .load(file)
                    .centerCrop()
                    .dontAnimate()
                if (usePlaceholder) {
                    request.placeholder(R.drawable.ic_file)
                }
                request.into(target)
            }

            FileType.APK -> {
                val apkIcon = loadApkIcon(context, file)
                if (apkIcon != null) {
                    val request = Glide.with(context)
                        .load(apkIcon)
                        .centerCrop()
                        .dontAnimate()
                    if (usePlaceholder) {
                        request.placeholder(R.drawable.ic_android)
                    }
                    request.into(target)
                } else {
                    applyTypeIcon(target, type)
                }
            }

            FileType.PDF -> {
                val pdfThumb = loadPdfThumbnail(file, pdfCache, pdfThumbWidth)
                if (pdfThumb != null) {
                    val request = Glide.with(context)
                        .load(pdfThumb)
                        .centerCrop()
                        .dontAnimate()
                    if (usePlaceholder) {
                        request.placeholder(R.drawable.ic_pdf)
                    }
                    request.into(target)
                } else {
                    applyTypeIcon(target, type)
                }
            }

            else -> applyTypeIcon(target, type)
        }

        overlayView?.let { overlay ->
            if (shouldShowRecentOverlay(type)) {
                overlay.visibility = View.VISIBLE
                overlay.setImageResource(typeIconRes(type))
                val color = typeIconColor(type)
                if (color != null) {
                    overlay.setColorFilter(color)
                } else {
                    overlay.clearColorFilter()
                }
            } else {
                overlay.visibility = View.GONE
            }
        }
    }

    fun loadApkIcon(context: Context, file: File): Drawable? {
        return try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
            packageInfo.applicationInfo.sourceDir = file.absolutePath
            packageInfo.applicationInfo.publicSourceDir = file.absolutePath
            packageInfo.applicationInfo.loadIcon(pm)
        } catch (_: Exception) {
            null
        }
    }

    fun loadPdfThumbnail(file: File, cache: LruCache<String, Bitmap>, targetWidth: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        cache.get(file.absolutePath)?.let { return it }

        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount <= 0) return null
                    renderer.openPage(0).use { page ->
                        val ratio = targetWidth.toFloat() / page.width.toFloat()
                        val targetHeight = (page.height * ratio).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        cache.put(file.absolutePath, bitmap)
                        bitmap
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun applyTypeIcon(target: ImageView, type: FileType) {
        target.setImageResource(typeIconRes(type))
        val color = typeIconColor(type)
        if (color != null) {
            target.setColorFilter(color)
        } else {
            target.clearColorFilter()
        }
    }

    private fun shouldShowRecentOverlay(type: FileType): Boolean {
        return when (type) {
            FileType.PDF, FileType.APK -> true
            else -> false
        }
    }

    private fun isImage(lowerName: String, lowerMime: String): Boolean {
        return lowerMime.startsWith("image/") ||
            lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") ||
            lowerName.endsWith(".gif") || lowerName.endsWith(".webp") || lowerName.endsWith(".bmp")
    }

    private fun isVideo(lowerName: String, lowerMime: String): Boolean {
        return lowerMime.startsWith("video/") ||
            lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") || lowerName.endsWith(".avi") ||
            lowerName.endsWith(".mov") || lowerName.endsWith(".wmv") || lowerName.endsWith(".webm") ||
            lowerName.endsWith(".3gp")
    }

    private fun isApk(lowerName: String, lowerMime: String): Boolean {
        return lowerMime == "application/vnd.android.package-archive" || lowerName.endsWith(".apk")
    }

    private fun isPdf(lowerName: String, lowerMime: String): Boolean {
        return lowerMime == "application/pdf" || lowerName.endsWith(".pdf")
    }

    private fun isVoice(lowerName: String, lowerMime: String): Boolean {
        return lowerName.endsWith(".m4a") || lowerMime == "audio/mp4" || lowerMime == "audio/x-m4a"
    }

    private fun isAudio(lowerName: String, lowerMime: String): Boolean {
        return lowerMime.startsWith("audio/") ||
            lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") || lowerName.endsWith(".ogg") ||
            lowerName.endsWith(".flac") || lowerName.endsWith(".aac") || lowerName.endsWith(".wma")
    }

    private fun isZip(lowerName: String): Boolean {
        return lowerName.endsWith(".zip") || lowerName.endsWith(".rar") || lowerName.endsWith(".7z") ||
            lowerName.endsWith(".tar") || lowerName.endsWith(".gz")
    }
}
