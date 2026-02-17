package com.example.betterfiles

import android.app.AlertDialog
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.view.MenuItem
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Size
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var repository: FileRepository
    private lateinit var recentAdapter: RecentFileAdapter
    private lateinit var drawerLayoutMain: DrawerLayout
    private lateinit var navViewMain: NavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = FileRepository(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }

        drawerLayoutMain = findViewById(R.id.drawerLayoutMain)
        navViewMain = findViewById(R.id.navViewMain)

        setupHomeDrawer()

        val btnInternal: View = findViewById(R.id.btnInternalStorage)
        val btnSdCard: View = findViewById(R.id.btnSdCardStorage)
        val btnImages: View = findViewById(R.id.btnImages)
        val btnVideos: View = findViewById(R.id.btnVideos)
        val btnAudio: View = findViewById(R.id.btnAudio)
        val btnDocuments: View = findViewById(R.id.btnDocuments)
        val btnDownloads: View = findViewById(R.id.btnDownloads)
        val btnApps: View = findViewById(R.id.btnApps)
        val btnSmartCategory: View = findViewById(R.id.btnSmartCategory)
        val btnMainMenu: View = findViewById(R.id.btnMainMenu)
        val btnHomeSearch: View = findViewById(R.id.btnHomeSearch)
        val cardStorageAnalysis: View = findViewById(R.id.cardStorageAnalysis)
        val tvRecentSeeAll: TextView = findViewById(R.id.tvRecentSeeAll)
        val tvCategoryTitle: TextView = findViewById(R.id.tvCategoryTitle)

        setupRecentSection()
        setupPasteEvents()
        updatePasteBarUI()

        btnInternal.setOnClickListener {
            openActivity(
                mode = "folder",
                path = StorageVolumeHelper.getStorageRoots(this).internalRoot,
                title = getString(R.string.internal_storage)
            )
        }

        btnSdCard.setOnClickListener {
            val sdRoot = StorageVolumeHelper.primarySdRoot(this)
            if (sdRoot != null) {
                openActivity(
                    mode = "folder",
                    path = sdRoot,
                    title = getString(R.string.sd_card)
                )
            } else {
                Toast.makeText(this, getString(R.string.sd_card_not_available), Toast.LENGTH_SHORT).show()
            }
        }

        btnImages.setOnClickListener {
            openActivity(mode = "image", title = getString(R.string.images))
        }

        btnVideos.setOnClickListener {
            openActivity(mode = "video", title = getString(R.string.videos))
        }

        btnAudio.setOnClickListener {
            openActivity(mode = "audio", title = getString(R.string.audio))
        }

        btnDocuments.setOnClickListener {
            openActivity(mode = "document", title = getString(R.string.documents))
        }

        btnDownloads.setOnClickListener {
            openActivity(mode = "download", title = getString(R.string.downloads))
        }

        btnApps.setOnClickListener {
            openActivity(mode = "app", title = getString(R.string.apps))
        }

        btnSmartCategory.setOnClickListener {
            startActivity(Intent(this, SmartCategoryActivity::class.java))
        }

        tvRecentSeeAll.setOnClickListener {
            openActivity(
                mode = "recent",
                title = getString(R.string.recent_files),
                path = StorageVolumeHelper.getStorageRoots(this).internalRoot
            )
        }

        btnMainMenu.setOnClickListener {
            drawerLayoutMain.openDrawer(GravityCompat.START)
        }

        cardStorageAnalysis.setOnClickListener {
            startActivity(Intent(this, StorageAnalysisActivity::class.java))
        }

        btnHomeSearch.setOnClickListener {
            openActivity(
                mode = "recent",
                title = getString(R.string.recent_files),
                path = StorageVolumeHelper.getStorageRoots(this).internalRoot,
                startSearch = true
            )
        }

        tvCategoryTitle.setOnLongClickListener {
            startActivity(Intent(this, IconPreviewActivity::class.java))
            true
        }
    }

    private fun setupHomeDrawer() {
        navViewMain.itemIconTintList = null
        val menu = navViewMain.menu
        val greyColor = Color.parseColor("#5F6368")
        menu.findItem(R.id.nav_document)?.icon?.mutate()?.clearColorFilter()
        menu.findItem(R.id.nav_image)?.icon?.mutate()?.setTint(greyColor)
        menu.findItem(R.id.nav_video)?.icon?.mutate()?.setTint(greyColor)
        menu.findItem(R.id.nav_audio)?.icon?.mutate()?.setTint(greyColor)
        menu.findItem(R.id.nav_internal_storage)?.icon?.mutate()?.setTint(greyColor)
        menu.findItem(R.id.nav_sd_card)?.icon?.mutate()?.setTint(greyColor)
        menu.findItem(R.id.nav_download)?.icon?.mutate()?.setTint(greyColor)
        menu.findItem(R.id.nav_recent)?.icon?.mutate()?.setTint(greyColor)

        navViewMain.setNavigationItemSelectedListener { menuItem: MenuItem ->
            when (menuItem.itemId) {
                R.id.nav_document -> {
                    openActivity(mode = "document", title = getString(R.string.documents))
                }
                R.id.nav_image -> {
                    openActivity(mode = "image", title = getString(R.string.images))
                }
                R.id.nav_video -> {
                    openActivity(mode = "video", title = getString(R.string.videos))
                }
                R.id.nav_audio -> {
                    openActivity(mode = "audio", title = getString(R.string.audio))
                }
                R.id.nav_internal_storage -> {
                    openActivity(
                        mode = "folder",
                        path = StorageVolumeHelper.getStorageRoots(this).internalRoot,
                        title = getString(R.string.internal_storage)
                    )
                }
                R.id.nav_sd_card -> {
                    val sdRoot = StorageVolumeHelper.primarySdRoot(this)
                    if (sdRoot != null) {
                        openActivity(
                            mode = "folder",
                            path = sdRoot,
                            title = getString(R.string.sd_card)
                        )
                    } else {
                        Toast.makeText(this, getString(R.string.sd_card_not_available), Toast.LENGTH_SHORT).show()
                    }
                }
                R.id.nav_download -> {
                    val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                    openActivity(mode = "folder", title = getString(R.string.downloads), path = downloadPath)
                }
                R.id.nav_recent -> {
                    openActivity(
                        mode = "recent",
                        title = getString(R.string.recent_files),
                        path = StorageVolumeHelper.getStorageRoots(this).internalRoot
                    )
                }
            }
            drawerLayoutMain.closeDrawer(GravityCompat.START)
            true
        }
        updateSdEntryVisibility()
        updateHomeDrawerMenu()
    }

    private fun updateHomeDrawerMenu() {
        val menu = navViewMain.menu
        val favoritesGroup = menu.findItem(R.id.nav_favorites_section)?.subMenu ?: return
        favoritesGroup.clear()

        val favorites = FavoritesManager.getAll(this)
        if (favorites.isEmpty()) {
            val item = favoritesGroup.add(0, 0, 0, getString(R.string.favorites_empty))
            item.isEnabled = false
            return
        }

        favorites.forEachIndexed { index, entry ->
            val file = File(entry.path)
            val title = entry.name ?: file.name
            val roots = StorageVolumeHelper.getStorageRoots(this)
            val volumePrefix = when (StorageVolumeHelper.detectVolume(entry.path, roots)) {
                StorageVolumeType.SD_CARD -> "[SD] "
                else -> ""
            }
            val item = favoritesGroup.add(0, index + 100, 0, "$volumePrefix$title")

            if (entry.isDirectory) {
                val drawable = getDrawable(R.drawable.ic_folder_solid)?.mutate()
                drawable?.setTint(Color.parseColor("#FFC107"))
                item.icon = drawable
                item.setOnMenuItemClickListener {
                    openActivity(mode = "folder", title = title, path = entry.path)
                    drawerLayoutMain.closeDrawer(GravityCompat.START)
                    true
                }
            } else {
                val type = FileVisualRules.resolveType(file.name, isDirectory = false)
                val iconRes = FileVisualRules.typeIconRes(type)
                val iconColor = FileVisualRules.typeIconColor(type) ?: Color.GRAY
                val drawable = getDrawable(iconRes)?.mutate()
                drawable?.setTint(iconColor)
                item.icon = drawable

                if (isImageFile(file.name) || isVideoFile(file.name)) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val thumbnail = loadThumbnail(file)
                        if (thumbnail != null) {
                            withContext(Dispatchers.Main) {
                                val roundedDrawable = RoundedBitmapDrawableFactory.create(resources, thumbnail)
                                roundedDrawable.cornerRadius = 16f
                                item.icon = roundedDrawable
                            }
                        }
                    }
                }

                item.setOnMenuItemClickListener {
                    openFile(file)
                    drawerLayoutMain.closeDrawer(GravityCompat.START)
                    true
                }
            }
        }
    }

    private fun loadThumbnail(file: File): Bitmap? {
        return try {
            val size = Size(144, 144)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createImageThumbnail(file, size, null)
            } else {
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                if (isVideoFile(file.name)) {
                    ThumbnailUtils.createVideoThumbnail(file.absolutePath, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
                } else {
                    BitmapFactory.decodeFile(file.absolutePath, options)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
    }

    private fun isVideoFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") ||
            lower.endsWith(".mov") || lower.endsWith(".wmv") || lower.endsWith(".webm") ||
            lower.endsWith(".3gp")
    }

    override fun onResume() {
        super.onResume()
        updateSdEntryVisibility()
        updatePasteBarUI()
        updateHomeDrawerMenu()
        loadRecentFiles()
        bindStorageAnalysisCard()
    }

    private fun bindStorageAnalysisCard() {
        val usageText: TextView = findViewById(R.id.tvStorageUsage)
        val usagePercentText: TextView = findViewById(R.id.tvStorageUsagePercent)
        val progress: android.widget.ProgressBar = findViewById(R.id.pbStorageAnalysis)

        try {
            // Use /data partition stats so "used" includes app/system data similarly to storage settings.
            val statFs = StatFs(Environment.getDataDirectory().absolutePath)
            val available = statFs.availableBytes
            val totalData = statFs.totalBytes

            // Match common OEM/file-app display: market capacity (e.g., 256 GB) - current available.
            val marketTotalBytes = estimateMarketTotalBytes(totalData)
            val used = (marketTotalBytes - available).coerceAtLeast(0L)
            val usedPercent = if (marketTotalBytes > 0L) ((used * 100) / marketTotalBytes).toInt() else 0

            val usedText = Formatter.formatFileSize(this, used)
            val totalText = formatMarketCapacity(marketTotalBytes)
            usageText.text = getString(R.string.storage_analysis_usage_format, usedText, totalText)
            usagePercentText.text = getString(R.string.storage_analysis_percent_format, usedPercent)
            progress.progress = usedPercent.coerceIn(0, 100)
        } catch (_: Exception) {
            usageText.text = getString(R.string.storage_analysis_usage_placeholder)
            usagePercentText.text = getString(R.string.storage_analysis_percent_format, 0)
            progress.progress = 0
        }
    }

    private fun estimateMarketTotalBytes(totalBytes: Long): Long {
        if (totalBytes <= 0L) return totalBytes
        val gbDecimal = totalBytes / 1_000_000_000.0
        val standardCapacitiesGb = doubleArrayOf(
            16.0, 32.0, 64.0, 128.0, 256.0, 512.0, 1024.0, 2048.0
        )
        val nearest = standardCapacitiesGb.minByOrNull { kotlin.math.abs(it - gbDecimal) } ?: gbDecimal
        return (nearest * 1_000_000_000.0).toLong()
    }

    private fun formatMarketCapacity(totalBytes: Long): String {
        if (totalBytes <= 0L) return Formatter.formatFileSize(this, totalBytes)

        val gbDecimal = totalBytes / 1_000_000_000.0
        val standardCapacitiesGb = doubleArrayOf(
            16.0, 32.0, 64.0, 128.0, 256.0, 512.0, 1024.0, 2048.0
        )
        val nearest = standardCapacitiesGb.minByOrNull { kotlin.math.abs(it - gbDecimal) } ?: gbDecimal
        return if (nearest >= 1024.0) {
            val tb = (nearest / 1024.0).toInt()
            getString(R.string.storage_capacity_tb_format, tb)
        } else {
            getString(R.string.storage_capacity_gb_format, nearest.toInt())
        }
    }

    private fun updateSdEntryVisibility() {
        val hasSd = StorageVolumeHelper.hasSdCard(this)
        findViewById<View>(R.id.btnSdCardStorage).visibility = if (hasSd) View.VISIBLE else View.GONE
        navViewMain.menu.findItem(R.id.nav_sd_card)?.isVisible = hasSd
    }

    private fun setupPasteEvents() {
        val btnCancelPaste: Button = findViewById(R.id.btnCancelPasteMain)
        val btnPaste: Button = findViewById(R.id.btnPasteMain)

        btnCancelPaste.setOnClickListener {
            FileClipboard.clear()
            updatePasteBarUI()
        }

        btnPaste.setOnClickListener {
            if (!FileClipboard.hasClip()) return@setOnClickListener
            val messageRes = if (FileClipboard.isMove) {
                R.string.paste_unavailable_here_move
            } else {
                R.string.paste_unavailable_here_copy
            }
            Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePasteBarUI() {
        val layoutPasteBar: CardView = findViewById(R.id.layoutPasteBarMain)
        val btnPaste: Button = findViewById(R.id.btnPasteMain)
        val tvPasteInfo: TextView = findViewById(R.id.tvPasteInfoMain)

        val hasClip = FileClipboard.hasClip()
        if (!hasClip) {
            layoutPasteBar.visibility = View.GONE
            return
        }

        layoutPasteBar.visibility = View.VISIBLE
        val count = FileClipboard.files.size
        val isMove = FileClipboard.isMove
        if (isMove) {
            tvPasteInfo.text = getString(R.string.paste_waiting_move_format, count)
            btnPaste.text = getString(R.string.paste_here_move)
        } else {
            tvPasteInfo.text = getString(R.string.paste_waiting_copy_format, count)
            btnPaste.text = getString(R.string.paste_here_copy)
        }
        btnPaste.isEnabled = true
        btnPaste.alpha = 0.5f
    }

    private fun setupRecentSection() {
        val rvRecent: RecyclerView = findViewById(R.id.rvRecentFiles)
        recentAdapter = RecentFileAdapter(
            onOpen = { openFile(it) },
            onInfo = { showInfo(it) },
            onShare = { shareFile(it) },
            onGoToLocation = { openFileLocation(it) },
            onDelete = { deleteFile(it) }
        )
        rvRecent.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        rvRecent.adapter = recentAdapter
    }

    private fun loadRecentFiles() {
        val section: LinearLayout = findViewById(R.id.layoutRecentSection)

        if (!canShowRecentSection()) {
            section.visibility = View.GONE
            return
        }

        lifecycleScope.launch {
            val recent = withContext(Dispatchers.IO) {
                repository.getRecentFiles(limit = 10, maxAgeDays = null)
            }

            if (recent.isEmpty()) {
                section.visibility = View.GONE
            } else {
                section.visibility = View.VISIBLE
                recentAdapter.submitList(recent)
            }
        }
    }

    private fun canShowRecentSection(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    private fun openFile(fileItem: FileItem) {
        try {
            val file = File(fileItem.path)
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolveMimeType(file, fileItem.mimeType))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_no_app_to_open), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolveMimeType(file, ""))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_no_app_to_open), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showInfo(fileItem: FileItem) {
        val file = File(fileItem.path)
        val info = getString(
            R.string.info_format,
            getString(R.string.label_name, fileItem.name),
            getString(R.string.label_path, fileItem.path),
            getString(R.string.label_type, fileItem.mimeType),
            getString(
                R.string.label_modified,
                DateUtils.getRelativeTimeSpanString(
                    fileItem.dateModified * 1000,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                ).toString()
            )
        )
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setMessage(info)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun shareFile(fileItem: FileItem) {
        try {
            val file = File(fileItem.path)
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = resolveMimeType(file, fileItem.mimeType)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ShareEventLogger.recordShareAsync(applicationContext, listOf(fileItem))
            startActivity(Intent.createChooser(intent, getString(R.string.menu_share)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_cannot_share), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFileLocation(fileItem: FileItem) {
        val parentPath = File(fileItem.path).parent ?: return
        val folderTitle = File(parentPath).name.ifBlank { getString(R.string.internal_storage) }
        openActivity(mode = "folder", title = folderTitle, path = parentPath)
    }

    private fun deleteFile(fileItem: FileItem) {
        val file = File(fileItem.path)
        if (!file.exists()) {
            Toast.makeText(this, getString(R.string.error_delete_failed), Toast.LENGTH_SHORT).show()
            return
        }

        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (deleted || !file.exists()) {
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
            Toast.makeText(this, getString(R.string.msg_deleted), Toast.LENGTH_SHORT).show()
            loadRecentFiles()
        } else {
            Toast.makeText(this, getString(R.string.error_delete_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolveMimeType(file: File, fallback: String): String {
        if (fallback.isNotBlank() && fallback != "application/octet-stream") return fallback
        val ext = file.extension.lowercase(Locale.getDefault())
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun openActivity(
        mode: String,
        title: String,
        path: String? = null,
        startSearch: Boolean = false
    ) {
        val resolvedPath = path ?: StorageVolumeHelper.getStorageRoots(this).internalRoot
        val intent = Intent(this, FileListActivity::class.java)
        intent.putExtra("mode", mode)
        intent.putExtra("title", title)
        intent.putExtra("path", resolvedPath)
        intent.putExtra("startSearch", startSearch)
        startActivity(intent)
    }
}
