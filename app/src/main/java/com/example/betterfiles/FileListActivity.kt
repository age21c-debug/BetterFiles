package com.example.betterfiles

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaScannerConnection // [복구?? ??줄이 빠져???�러가 ?�습?�다
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.Size
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class FileListActivity : AppCompatActivity() {
    private enum class StorageScope { INTERNAL, SD_CARD }

    companion object {
        private const val RECENT_INITIAL_BATCH = 120
        private const val MENU_EXCLUDE_RECENT_FOLDER = 9001
        private const val MENU_RECENT_MANAGE_EXCLUDED = 9002
    }

    private lateinit var adapter: FileAdapter
    private lateinit var repository: FileRepository
    private lateinit var smartSelectionRepository: SmartSelectionRepository
    private lateinit var smartWorkDocumentRepository: SmartWorkDocumentRepository
    private lateinit var eventPhotoBundleRepository: EventPhotoBundleRepository

    // drawer/navigation
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView

    // async jobs
    private var loadJob: Job? = null

    private var searchJob: Job? = null
    private var isSearchMode: Boolean = false
    private var currentSearchQuery: String = ""

    // selection mode
    private var isSelectionMode: Boolean = false

    // navigation state
    private var currentPath: String = ""
    private var currentMode: String = "folder"
    private var messengerAppFilter: String? = null
    private var currentWorkDocTypeFilter: String = SmartWorkDocumentRepository.TYPE_ALL
    private var currentSmartDocumentsSort: String = "related"
    private var currentSmartDocumentsAscending: Boolean = false
    private var currentEventStartMs: Long = 0L
    private var currentEventEndMs: Long = 0L
    private lateinit var rootPath: String
    private lateinit var rootTitle: String
    private var pasteTargetPath: String = ""

    // sort state
    private lateinit var prefs: SharedPreferences
    private var currentSortMode = "name"
    private var isAscending = true
    private var lastRecentSearchQueryForSortReset: String? = null
    private var isRecentCountLoading: Boolean = false
    private var finishOnSearchBack: Boolean = false
    private var storageScope: StorageScope = StorageScope.INTERNAL
    private var hasSdCard: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_list)

        repository = FileRepository(this)
        smartSelectionRepository = SmartSelectionRepository(this)
        smartWorkDocumentRepository = SmartWorkDocumentRepository(this)
        eventPhotoBundleRepository = EventPhotoBundleRepository(this)
        prefs = getSharedPreferences("BetterFilesPrefs", Context.MODE_PRIVATE)

        drawerLayout = findViewById(R.id.drawerLayout)
        navView = findViewById(R.id.navView)
        val rvFiles: RecyclerView = findViewById(R.id.rvFiles)
        val btnBack: ImageView = findViewById(R.id.btnBack)

        val intentTitle = intent.getStringExtra("title") ?: getString(R.string.default_page_title)
        currentMode = intent.getStringExtra("mode") ?: "folder"
        messengerAppFilter = intent.getStringExtra("messengerApp")
        currentWorkDocTypeFilter = intent.getStringExtra("workDocType") ?: SmartWorkDocumentRepository.TYPE_ALL
        currentEventStartMs = intent.getLongExtra("startMs", 0L)
        currentEventEndMs = intent.getLongExtra("endMs", 0L)
        val intentPathExtra = intent.getStringExtra("path")
        val intentPath = if (intentPathExtra.isNullOrBlank()) {
            StorageVolumeHelper.getStorageRoots(this).internalRoot
        } else {
            intentPathExtra
        }
        finishOnSearchBack = intent.getBooleanExtra("startSearch", false)

        rootTitle = intentTitle
        if (currentMode == "event_cluster" && currentEventStartMs > 0L && currentEventEndMs > 0L) {
            rootTitle = formatEventClusterTitle(currentEventStartMs, currentEventEndMs)
        }
        rootPath = intentPath
        currentPath = intentPath
        pasteTargetPath = intentPath
        hasSdCard = StorageVolumeHelper.hasSdCard(this)

        loadSavedSortSettings()

        // 2. ?�댑???�정
        adapter = FileAdapter(
            onClick = { fileItem ->
                if (fileItem.isDirectory) {
                    if (isSearchMode) {
                        closeSearchMode()
                    }
                    loadData("folder", fileItem.path)
                } else {
                    handleFileClick(fileItem)
                }
            },
            onMoreClick = { view, fileItem ->
                showFileOptionMenu(view, fileItem)
            },
            onLongClick = { fileItem ->
                if (!FileClipboard.hasClip()) {
                    startSelectionMode(fileItem)
                }
            },
            onSelectionChanged = {
                updateSelectionUI()
            },
            onLowUsageHeaderClick = { isStrong ->
                toggleLowUsageSectionSelection(isStrong)
            }
        )
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = adapter
        (rvFiles.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        rvFiles.itemAnimator?.changeDuration = 0

        // 3. ?�로???�정
        setupDrawer()
        updateStorageTabsForMode(currentMode)

        // 4. ?�벤???�정
        btnBack.setOnClickListener { handleHeaderNavigationClick() }
        setupHeaderEvents()
        setupWorkDocumentChips()
        setupSelectionEvents()
        setupPasteEvents()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else if ((currentMode == "duplicate" || currentMode == "large" || currentMode == "low_usage_large") && isSelectionMode) {
                    val hasSelected = adapter.currentList.any { it.isSelected }
                    if (hasSelected) {
                        closeSelectionMode()
                    } else {
                        handleBackAction()
                    }
                } else if (isSelectionMode) {
                    closeSelectionMode()
                } else if (isSearchMode) {
                    if (finishOnSearchBack) finish() else closeSearchMode()
                } else {
                    handleBackAction()
                }
            }
        })

        loadData(currentMode, rootPath)

        if (intent.getBooleanExtra("startSearch", false)) {
            findViewById<View>(android.R.id.content).post {
                enterSearchMode()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val previousHasSd = hasSdCard
        hasSdCard = StorageVolumeHelper.hasSdCard(this)
        updateStorageTabsForMode(currentMode)
        updateDrawerSdVisibility()
        updateDrawerMenu()

        if (previousHasSd && !hasSdCard && storageScope == StorageScope.SD_CARD) {
            storageScope = StorageScope.INTERNAL
            if (currentMode == "folder") {
                rootTitle = getString(R.string.internal_storage)
                rootPath = StorageVolumeHelper.getStorageRoots(this).internalRoot
                currentPath = rootPath
            }
            loadData(currentMode, currentPath)
            Toast.makeText(this, getString(R.string.sd_card_removed_fallback), Toast.LENGTH_SHORT).show()
            return
        }

        if (!previousHasSd && hasSdCard) {
            updateDrawerSdVisibility()
        }
    }

    // ?�▼???�로??즐겨찾기) 관??로직 ?�▼??
    private fun setupDrawer() {
        // 1. 기본 ?�트(?�상 ??��?�기) ?�거 -> ?�리가 ?�하?????��??? ?�네???????�시?�기 ?�함
        navView.itemIconTintList = null

        // 2. ?�단 고정 메뉴(?�장 메모�? ?�운로드) ?�이콘을 ?�색?�로 ?�동 ?�정
        val menu = navView.menu
        val greyColor = Color.parseColor("#5F6368") // match home category icon tone

        val internalItem = menu.findItem(R.id.nav_internal_storage)
        internalItem?.icon?.mutate()?.setTint(greyColor)

        val documentItem = menu.findItem(R.id.nav_document)
        documentItem?.icon?.mutate()?.clearColorFilter()

        val downloadItem = menu.findItem(R.id.nav_download)
        downloadItem?.icon?.mutate()?.setTint(greyColor)

        val imageItem = menu.findItem(R.id.nav_image)
        imageItem?.icon?.mutate()?.setTint(greyColor)

        val videoItem = menu.findItem(R.id.nav_video)
        videoItem?.icon?.mutate()?.setTint(greyColor)

        val audioItem = menu.findItem(R.id.nav_audio)
        audioItem?.icon?.mutate()?.setTint(greyColor)

        val recentItem = menu.findItem(R.id.nav_recent)
        recentItem?.icon?.mutate()?.setTint(greyColor)
        val sdItem = menu.findItem(R.id.nav_sd_card)
        sdItem?.icon?.mutate()?.setTint(greyColor)

        // 3. ?�릭 리스??(기존 코드?�??�일)
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_internal_storage -> {
                    rootTitle = getString(R.string.internal_storage)
                    rootPath = StorageVolumeHelper.getStorageRoots(this).internalRoot
                    storageScope = StorageScope.INTERNAL
                    loadData("folder", rootPath)
                }
                R.id.nav_document -> {
                    rootTitle = getString(R.string.documents)
                    loadData("document", rootPath)
                }
                R.id.nav_download -> {
                    val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                    loadData("folder", downloadPath)
                }
                R.id.nav_image -> {
                    rootTitle = getString(R.string.images)
                    loadData("image", rootPath)
                }
                R.id.nav_video -> {
                    rootTitle = getString(R.string.videos)
                    loadData("video", rootPath)
                }
                R.id.nav_audio -> {
                    rootTitle = getString(R.string.audio)
                    loadData("audio", rootPath)
                }
                R.id.nav_recent -> {
                    rootTitle = getString(R.string.recent_files)
                    loadData("recent", rootPath)
                }
                R.id.nav_sd_card -> {
                    val sdRoot = StorageVolumeHelper.primarySdRoot(this)
                    if (sdRoot != null) {
                        rootTitle = getString(R.string.sd_card)
                        rootPath = sdRoot
                        storageScope = StorageScope.SD_CARD
                        loadData("folder", sdRoot)
                    } else {
                        Toast.makeText(this, getString(R.string.sd_card_not_available), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
        updateDrawerSdVisibility()
        updateDrawerMenu()
    }

    private fun updateDrawerMenu() {
        val menu = navView.menu
        val favoritesGroup = menu.findItem(R.id.nav_favorites_section)?.subMenu ?: return
        favoritesGroup.clear()

        val favorites = FavoritesManager.getAll(this)

        if (favorites.isEmpty()) {
            val item = favoritesGroup.add(0, 0, 0, getString(R.string.favorites_empty))
            item.isEnabled = false
        } else {
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
                    // Folder favorite icon tint
                    val drawable = getDrawable(R.drawable.ic_folder_solid)?.mutate()
                    drawable?.setTint(Color.parseColor("#FFC107")) // ?��????�용
                    item.icon = drawable

                    item.setOnMenuItemClickListener {
                        loadData("folder", entry.path)
                        drawerLayout.closeDrawer(GravityCompat.START)
                        true
                    }
                } else {
                    // [?�일] ?�선 기본 ?�이�?�??�상 ?�정
                    val iconRes = getFileIconResource(file.name)
                    val iconColor = getFileIconColor(file.name) ?: Color.GRAY
                    val drawable = getDrawable(iconRes)?.mutate()
                    drawable?.setTint(iconColor)
                    item.icon = drawable

                    // [추�??? ?�네?? ?��?지/비디?�는 비동기로 로딩?�여 ?�이�?교체
                    if (isImageFile(file.name) || isVideoFile(file.name)) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val thumbnail = loadThumbnail(file) // ?�래??추�????�수 ?�출
                            if (thumbnail != null) {
                                withContext(Dispatchers.Main) {
                                    // ?�근 모서�??�네???�성
                                    val roundedDrawable = RoundedBitmapDrawableFactory.create(resources, thumbnail)
                                    roundedDrawable.cornerRadius = 16f
                                    item.icon = roundedDrawable
                                }
                            }
                        }
                    }

                    item.setOnMenuItemClickListener {
                        openFile(file)
                        drawerLayout.closeDrawer(GravityCompat.START)
                        true
                    }
                }
            }
        }
    }

    private fun updateDrawerSdVisibility() {
        val menu = navView.menu
        menu.findItem(R.id.nav_sd_card)?.isVisible = hasSdCard
    }

    private fun supportsStorageTabs(mode: String): Boolean {
        return mode == "recent" ||
            mode == "image" ||
            mode == "video" ||
            mode == "audio" ||
            mode == "document" ||
            mode == "app" ||
            mode == "large" || mode == "duplicate" || mode == "low_usage_large" || mode == "smart_shared" || mode == "messenger" || mode == "smart_documents" || mode == "event_cluster"
    }

    private fun updateStorageTabsForMode(mode: String) {
        val layoutTabs = findViewById<LinearLayout>(R.id.layoutStorageTabs)
        val tabInternal = findViewById<TextView>(R.id.tabInternal)
        val tabSdCard = findViewById<TextView>(R.id.tabSdCard)

        val showTabs = hasSdCard && supportsStorageTabs(mode)
        layoutTabs.visibility = if (showTabs) View.VISIBLE else View.GONE
        if (!showTabs) {
            storageScope = StorageScope.INTERNAL
            return
        }

        tabInternal.setOnClickListener {
            if (storageScope != StorageScope.INTERNAL) {
                storageScope = StorageScope.INTERNAL
                updateStorageTabSelectionUI()
                if (isSearchMode) performSearch(currentSearchQuery) else loadData(currentMode, currentPath)
            }
        }
        tabSdCard.setOnClickListener {
            if (storageScope != StorageScope.SD_CARD) {
                storageScope = StorageScope.SD_CARD
                updateStorageTabSelectionUI()
                if (isSearchMode) performSearch(currentSearchQuery) else loadData(currentMode, currentPath)
            }
        }
        updateStorageTabSelectionUI()
    }

    private fun updateStorageTabSelectionUI() {
        val tabInternal = findViewById<TextView>(R.id.tabInternal)
        val tabSdCard = findViewById<TextView>(R.id.tabSdCard)

        if (storageScope == StorageScope.INTERNAL) {
            tabInternal.background = getDrawable(R.drawable.bg_storage_tab_selected)
            tabInternal.setTextColor(Color.parseColor("#0D47A1"))
            tabSdCard.background = getDrawable(R.drawable.bg_storage_tab_unselected)
            tabSdCard.setTextColor(Color.parseColor("#616161"))
        } else {
            tabSdCard.background = getDrawable(R.drawable.bg_storage_tab_selected)
            tabSdCard.setTextColor(Color.parseColor("#0D47A1"))
            tabInternal.background = getDrawable(R.drawable.bg_storage_tab_unselected)
            tabInternal.setTextColor(Color.parseColor("#616161"))
        }
    }

    private fun applyStorageScopeFilter(files: List<FileItem>): List<FileItem> {
        if (!hasSdCard || !supportsStorageTabs(currentMode)) return files
        val roots = StorageVolumeHelper.getStorageRoots(this)
        return files.filter { item ->
            when (StorageVolumeHelper.detectVolume(item.path, roots)) {
                StorageVolumeType.INTERNAL -> storageScope == StorageScope.INTERNAL
                StorageVolumeType.SD_CARD -> storageScope == StorageScope.SD_CARD
                StorageVolumeType.OTHER -> false
            }
        }
    }

    // [추�?] ?�네??로딩 ?�수
    // [추�?] ?�네??로딩 ?�퍼 ?�수
    private fun loadThumbnail(file: File): Bitmap? {
        return try {
            val size = Size(144, 144) // 메뉴 ?�이콘에 ?�당???�기
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createImageThumbnail(file, size, null)
            } else {
                // 구버???�환 (간단??비트�??�코??
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                if (isVideoFile(file.name)) {
                    ThumbnailUtils.createVideoThumbnail(file.absolutePath, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
                } else {
                    BitmapFactory.decodeFile(file.absolutePath, options)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun handleHeaderNavigationClick() {
        if (currentMode == "folder" && currentPath == rootPath) {
            drawerLayout.openDrawer(GravityCompat.START)
        } else {
            handleBackAction()
        }
    }

    private fun updateHeaderIcon() {
        val btnBack: ImageView = findViewById(R.id.btnBack)
        if (currentMode == "folder" && currentPath == rootPath) {
            btnBack.setImageResource(R.drawable.ic_menu)
        } else {
            btnBack.setImageResource(R.drawable.ic_arrow_back)
        }
    }
    // ?�▲???�로??로직 ???�▲??

    private fun handleFileClick(fileItem: FileItem) {
        val file = File(fileItem.path)
        if (file.extension.equals("zip", ignoreCase = true)) {
            showUnzipDialog(file)
        } else {
            openFile(fileItem)
        }
    }

    // [?�수 1] 메인 리스?�에???�릭 ??(FileItem ?�용)
    private fun openFile(fileItem: FileItem) {
        try {
            val file = File(fileItem.path)
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, fileItem.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
            ShareEventLogger.recordOpenPathAsync(applicationContext, file.absolutePath)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_no_app_to_open), Toast.LENGTH_SHORT).show()
        }
    }

    // [?�수 2] 즐겨찾기 ?�에???�일 경로만으�??�행 (File ?�용)
    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val mimeType = getMimeType(file)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
            ShareEventLogger.recordOpenPathAsync(applicationContext, file.absolutePath)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_no_app_to_open), Toast.LENGTH_SHORT).show()
        }
    }

    // [?�수] ?�일 ?�장?�로 MIME Type 찾기
    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase(Locale.getDefault())
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    }

    private fun showUnzipDialog(zipFile: File) {
        val view = layoutInflater.inflate(R.layout.dialog_zip_preview, null)
        val tvName: TextView = view.findViewById(R.id.tvZipName)
        val tvSummary: TextView = view.findViewById(R.id.tvZipSummary)
        val container: LinearLayout = view.findViewById(R.id.layoutZipContentContainer)

        tvName.text = zipFile.name
        tvSummary.text = getString(R.string.zip_info_loading)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.unzip_title))
            .setView(view)
            .setPositiveButton(getString(R.string.unzip_action)) { _, _ ->
                performUnzip(zipFile)
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val info = ZipManager.getZipInfo(zipFile)

                withContext(Dispatchers.Main) {
                    val sizeStr = Formatter.formatFileSize(this@FileListActivity, info.totalSize)
                    tvSummary.text = getString(R.string.zip_summary_format, info.fileCount, sizeStr)

                    container.removeAllViews()
                    val inflater = LayoutInflater.from(this@FileListActivity)

                    info.fileNames.forEach { fileName ->
                        val itemView = inflater.inflate(R.layout.item_zip_preview_row, container, false)
                        val ivIcon: ImageView = itemView.findViewById(R.id.ivZipItemIcon)
                        val tvItemName: TextView = itemView.findViewById(R.id.tvZipItemName)

                        tvItemName.text = fileName

                        val iconRes = getFileIconResource(fileName)
                        val iconColor = getFileIconColor(fileName)

                        ivIcon.setImageResource(iconRes)
                        if (iconColor != null) {
                            ivIcon.setColorFilter(iconColor)
                        } else {
                            ivIcon.clearColorFilter()
                        }

                        container.addView(itemView)
                    }

                    if (info.fileCount > 10) {
                        val moreView = TextView(this@FileListActivity)
                        moreView.text = getString(R.string.zip_more_items_format, info.fileCount - 10)
                        moreView.setPadding(8, 16, 8, 8)
                        moreView.setTextColor(getColor(android.R.color.darker_gray))
                        container.addView(moreView)
                    } else if (info.fileCount == 0) {
                        val emptyView = TextView(this@FileListActivity)
                        emptyView.text = getString(R.string.zip_content_empty)
                        emptyView.setPadding(8, 16, 8, 8)
                        container.addView(emptyView)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    tvSummary.text = getString(R.string.zip_info_failed)
                    val errorView = TextView(this@FileListActivity)
                    errorView.text = "Error: ${e.message}"
                    container.addView(errorView)
                }
            }
        }
    }

    private fun getFileIconResource(fileName: String): Int {
        val type = FileVisualRules.resolveType(fileName, isDirectory = fileName.endsWith("/"))
        return FileVisualRules.typeIconRes(type)
    }

    private fun getFileIconColor(fileName: String): Int? {
        val type = FileVisualRules.resolveType(fileName, isDirectory = fileName.endsWith("/"))
        return FileVisualRules.typeIconColor(type)
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

    private fun isApkFile(name: String): Boolean = name.lowercase().endsWith(".apk")
    private fun isVoiceFile(name: String): Boolean = name.lowercase().endsWith(".m4a")
    private fun isAudioFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg") ||
                lower.endsWith(".flac") || lower.endsWith(".aac") || lower.endsWith(".wma")
    }
    private fun isPdfFile(name: String): Boolean = name.lowercase().endsWith(".pdf")
    private fun isZipFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar") || lower.endsWith(".gz")
    }


    private fun performUnzip(zipFile: File) {
        val targetDir = File(currentPath)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val folderName = zipFile.nameWithoutExtension
                val extractDir = getUniqueFile(targetDir, folderName)

                ZipManager.unzip(zipFile, extractDir)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FileListActivity, getString(R.string.unzip_success), Toast.LENGTH_SHORT).show()
                    MediaScannerConnection.scanFile(this@FileListActivity, arrayOf(extractDir.absolutePath), null, null)
                    loadData(currentMode, currentPath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FileListActivity, getString(R.string.unzip_failed_format, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupPasteEvents() {
        val btnCancelPaste: Button = findViewById(R.id.btnCancelPaste)
        val btnPaste: Button = findViewById(R.id.btnPaste)

        btnCancelPaste.setOnClickListener {
            FileClipboard.clear()
            updatePasteBarUI()
        }

        btnPaste.setOnClickListener {
            if (!FileClipboard.hasClip()) return@setOnClickListener
            if (currentMode != "folder") {
                val messageRes = if (FileClipboard.isMove) {
                    R.string.paste_unavailable_here_move
                } else {
                    R.string.paste_unavailable_here_copy
                }
                Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val sourceParentPath = FileClipboard.files.firstOrNull()?.parent
            if (FileClipboard.isMove && sourceParentPath == pasteTargetPath) {
                Toast.makeText(this, getString(R.string.paste_unavailable_here_move), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            performPaste()
        }
    }

    private fun updatePasteBarUI() {
        val layoutPasteBar: CardView = findViewById(R.id.layoutPasteBar)
        val btnPaste: Button = findViewById(R.id.btnPaste)
        val tvPasteInfo: TextView = findViewById(R.id.tvPasteInfo)

        val hasClip = FileClipboard.hasClip()

        adapter.isPasteMode = hasClip
        adapter.notifyDataSetChanged()

        if (hasClip) {
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

            val sourceParentPath = FileClipboard.files.firstOrNull()?.parent

            btnPaste.isEnabled = true
            if (currentMode != "folder" || (isMove && sourceParentPath == pasteTargetPath)) {
                btnPaste.alpha = 0.5f
            } else {
                btnPaste.alpha = 1.0f
            }

        } else {
            layoutPasteBar.visibility = View.GONE
        }
    }

    private fun performPaste() {
        if (currentMode != "folder") {
            Toast.makeText(this, getString(R.string.folder_not_writable), Toast.LENGTH_SHORT).show()
            return
        }

        val targetDir = File(pasteTargetPath)
        if (!targetDir.canWrite()) {
            Toast.makeText(this, getString(R.string.folder_not_writable), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            val pathsToScan = mutableListOf<String>()

            FileClipboard.files.forEach { sourceFile ->
                if (!sourceFile.exists()) return@forEach
                val destFile = getUniqueFile(targetDir, sourceFile.name)

                try {
                    if (sourceFile.isDirectory) {
                        sourceFile.copyRecursively(destFile, overwrite = true)
                    } else {
                        sourceFile.copyTo(destFile, overwrite = true)
                    }

                    if (FileClipboard.isMove) {
                        if (sourceFile.isDirectory) sourceFile.deleteRecursively() else sourceFile.delete()
                        pathsToScan.add(sourceFile.absolutePath)
                    }

                    successCount++
                    pathsToScan.add(destFile.absolutePath)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            withContext(Dispatchers.Main) {
                if (successCount > 0) {
                    val actionText = if (FileClipboard.isMove) getString(R.string.paste_result_move) else getString(R.string.paste_result_copy)
                    Toast.makeText(this@FileListActivity, getString(R.string.paste_result_format, successCount, actionText), Toast.LENGTH_SHORT).show()
                    MediaScannerConnection.scanFile(this@FileListActivity, pathsToScan.toTypedArray(), null, null)
                    FileClipboard.clear()
                    updatePasteBarUI()
                    loadData(currentMode, currentPath)
                } else {
                    Toast.makeText(this@FileListActivity, getString(R.string.operation_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getUniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file

        val nameWithoutExtension = file.nameWithoutExtension
        val extension = file.extension
        var count = 1

        while (file.exists()) {
            val newName = if (extension.isNotEmpty()) {
                "$nameWithoutExtension ($count).$extension"
            } else {
                "$nameWithoutExtension ($count)"
            }
            file = File(dir, newName)
            count++
        }
        return file
    }

    private fun copyOrMoveSelected(isMove: Boolean) {
        val selectedItems = adapter.currentList.filter { it.isSelected }
        if (selectedItems.isEmpty()) return

        val files = selectedItems.map { File(it.path) }
        FileClipboard.files = files
        FileClipboard.isMove = isMove

        closeSelectionMode()
        updatePasteBarUI()

        // In category/recent modes, switch to folder view so destination selection is explicit.
        if (currentMode != "folder") {
            rootTitle = getString(R.string.internal_storage)
            loadData("folder", rootPath)
        }

        val message = if (isMove) getString(R.string.clipboard_ready_move) else getString(R.string.clipboard_ready_copy)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun setupSelectionEvents() {
        val btnCloseSelection: ImageView = findViewById(R.id.btnCloseSelection)
        val btnSelectAll: ImageView = findViewById(R.id.btnSelectAll)
        val btnShareSelection: ImageView = findViewById(R.id.btnShareSelection)
        val btnDeleteSelection: ImageView = findViewById(R.id.btnDeleteSelection)
        val btnSelectionMore: ImageView = findViewById(R.id.btnSelectionMore)
        val layoutSelectionDeleteBar: CardView = findViewById(R.id.layoutSelectionDeleteBar)

        btnCloseSelection.setOnClickListener { closeSelectionMode() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }

        btnSelectionMore.setOnClickListener { view ->
            showSelectionMoreMenu(view)
        }

        btnShareSelection.setOnClickListener { shareSelectedFiles() }
        btnDeleteSelection.setOnClickListener { showDeleteSelectionDialog() }
        layoutSelectionDeleteBar.setOnClickListener { showDeleteSelectionDialog() }
    }

    private fun showSelectionMoreMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.menu_selection_more, popup.menu)

        val selectedItems = adapter.currentList.filter { it.isSelected }
        val detailsItem = popup.menu.findItem(R.id.action_selection_details)
        val favoriteItem = popup.menu.findItem(R.id.action_selection_favorite)
        val renameItem = popup.menu.findItem(R.id.action_selection_rename)
        val excludeFolderItem = popup.menu.findItem(R.id.action_selection_exclude_folder)
        detailsItem.isVisible = selectedItems.size == 1
        favoriteItem.isVisible = selectedItems.size == 1
        renameItem.isVisible = selectedItems.size == 1
        excludeFolderItem.isVisible = currentMode == "recent" && selectedItems.isNotEmpty()
        if (selectedItems.size == 1) {
            val isFav = FavoritesManager.isFavorite(this, selectedItems.first())
            favoriteItem.title = if (isFav) getString(R.string.favorite_remove) else getString(R.string.menu_favorite_add)
        }

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_copy -> {
                    copyOrMoveSelected(isMove = false)
                    true
                }
                R.id.action_move -> {
                    copyOrMoveSelected(isMove = true)
                    true
                }
                R.id.action_zip -> {
                    showZipDialog(selectedItems)
                    true
                }
                R.id.action_selection_exclude_folder -> {
                    excludeParentFoldersFromRecent(selectedItems)
                    true
                }
                R.id.action_selection_favorite -> {
                    if (selectedItems.size == 1) {
                        toggleFavorite(selectedItems.first())
                    }
                    true
                }
                R.id.action_selection_rename -> {
                    if (selectedItems.size == 1) {
                        showRenameDialog(selectedItems.first())
                    }
                    true
                }
                R.id.action_selection_details -> {
                    if (selectedItems.size == 1) {
                        showFileDetailsDialog(selectedItems.first())
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun excludeParentFoldersFromRecent(selectedItems: List<FileItem>) {
        if (selectedItems.isEmpty()) return
        val parentPaths = selectedItems.mapNotNull { File(it.path).parent }.toSet()
        var addedCount = 0
        var alreadyCount = 0
        for (parent in parentPaths) {
            if (RecentExclusionManager.addFolder(this, parent)) {
                addedCount++
            } else {
                alreadyCount++
            }
        }
        Toast.makeText(
            this,
            getString(R.string.recent_exclusion_selection_result, addedCount, alreadyCount),
            Toast.LENGTH_SHORT
        ).show()
        closeSelectionMode()
        loadData("recent", rootPath)
    }

    private fun showZipDialog(selectedItems: List<FileItem>) {
        if (selectedItems.isEmpty()) return

        val editText = EditText(this)
        val defaultName = if (selectedItems.size == 1) {
            File(selectedItems.first().path).nameWithoutExtension
        } else {
            val firstName = File(selectedItems.first().path).nameWithoutExtension
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            firstName
        }
        editText.setText(defaultName)
        editText.selectAll()

        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.leftMargin = 50; params.rightMargin = 50
        editText.layoutParams = params
        container.addView(editText)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.zip_dialog_title))
            .setMessage(getString(R.string.zip_dialog_message_format, selectedItems.size))
            .setView(container)
            .setPositiveButton(getString(R.string.menu_zip)) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    performZip(selectedItems, name)
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun performZip(items: List<FileItem>, zipName: String) {
        val targetDir = resolveZipTargetDir()
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            Toast.makeText(this, getString(R.string.folder_create_failed), Toast.LENGTH_SHORT).show()
            return
        }
        if (!targetDir.canWrite()) {
            Toast.makeText(this, getString(R.string.folder_not_writable), Toast.LENGTH_SHORT).show()
            return
        }
        val finalName = if (zipName.endsWith(".zip", ignoreCase = true)) zipName else "$zipName.zip"
        val zipFile = getUniqueFile(targetDir, finalName)

        val filesToZip = items.map { File(it.path) }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ZipManager.zip(filesToZip, zipFile)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FileListActivity, getString(R.string.zip_success_format, zipFile.name), Toast.LENGTH_SHORT).show()
                    MediaScannerConnection.scanFile(this@FileListActivity, arrayOf(zipFile.absolutePath), null, null)

                    closeSelectionMode()
                    loadData(currentMode, currentPath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FileListActivity, getString(R.string.zip_failed_format, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun resolveZipTargetDir(): File {
        return if (currentMode == "folder") {
            File(currentPath)
        } else {
            File(rootPath, "Compressed files")
        }
    }

    private fun toggleSelectAll() {
        val currentList = adapter.currentList
        if (currentList.isEmpty()) return

        if (currentMode == "duplicate") {
            val minModifiedByGroup = currentList
                .asSequence()
                .mapNotNull { item ->
                    val key = item.duplicateGroupKey
                    if (key.isNullOrBlank()) null else key to item.dateModified
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, values) -> values.minOrNull() ?: 0L }

            val selectableItems = currentList.filter { item ->
                val key = item.duplicateGroupKey
                if (key.isNullOrBlank()) true else item.dateModified != minModifiedByGroup[key]
            }

            if (selectableItems.isEmpty()) return
            val isAllSelected = selectableItems.all { it.isSelected }
            val newState = !isAllSelected

            currentList.forEach { item ->
                val key = item.duplicateGroupKey
                val isOriginal = !key.isNullOrBlank() && item.dateModified == minModifiedByGroup[key]
                item.isSelected = if (isOriginal) false else newState
            }
        } else {
            val isAllSelected = currentList.all { it.isSelected }
            val newState = !isAllSelected
            currentList.forEach { it.isSelected = newState }
        }

        adapter.notifyDataSetChanged()
        updateSelectionUI()
    }

    private fun startSelectionMode(initialItem: FileItem) {
        if (isSelectionMode) return
        isSelectionMode = true
        initialItem.isSelected = true
        adapter.isSelectionMode = true
        adapter.notifyDataSetChanged()

        findViewById<LinearLayout>(R.id.headerNormal).visibility = View.GONE
        findViewById<LinearLayout>(R.id.headerSearch).visibility = View.GONE
        findViewById<LinearLayout>(R.id.headerSelection).visibility = View.VISIBLE

        val btnShareSelection: ImageView = findViewById(R.id.btnShareSelection)
        val btnDeleteSelection: ImageView = findViewById(R.id.btnDeleteSelection)
        val btnSelectionMore: ImageView = findViewById(R.id.btnSelectionMore)
        if (currentMode == "duplicate" || currentMode == "large" || currentMode == "low_usage_large") {
            btnShareSelection.visibility = View.GONE
            btnDeleteSelection.visibility = View.GONE
            btnSelectionMore.visibility = View.GONE
        } else {
            btnShareSelection.visibility = View.VISIBLE
            btnDeleteSelection.visibility = View.VISIBLE
            btnSelectionMore.visibility = View.VISIBLE
        }

        updateSelectionUI()
    }

    private fun ensureDuplicateSelectionMode() {
        if (currentMode != "duplicate" && currentMode != "large" && currentMode != "low_usage_large") return

        if (!isSelectionMode) {
            isSelectionMode = true
            adapter.isSelectionMode = true
        }

        findViewById<LinearLayout>(R.id.headerSelection).visibility = View.GONE
        if (isSearchMode) {
            findViewById<LinearLayout>(R.id.headerSearch).visibility = View.VISIBLE
            findViewById<LinearLayout>(R.id.headerNormal).visibility = View.GONE
        } else {
            findViewById<LinearLayout>(R.id.headerNormal).visibility = View.VISIBLE
            findViewById<LinearLayout>(R.id.headerSearch).visibility = View.GONE
        }

        findViewById<ImageView>(R.id.btnShareSelection).visibility = View.GONE
        findViewById<ImageView>(R.id.btnDeleteSelection).visibility = View.GONE
        findViewById<ImageView>(R.id.btnSelectionMore).visibility = View.GONE
        updateSelectionUI()
    }
    private fun closeSelectionMode() {
        if (currentMode == "duplicate" || currentMode == "large" || currentMode == "low_usage_large") {
            adapter.currentList.forEach { it.isSelected = false }
            isSelectionMode = true
            adapter.isSelectionMode = true
            adapter.notifyDataSetChanged()
            ensureDuplicateSelectionMode()
            return
        }

        isSelectionMode = false
        adapter.currentList.forEach { it.isSelected = false }
        adapter.isSelectionMode = false
        adapter.notifyDataSetChanged()

        findViewById<CardView>(R.id.layoutSelectionDeleteBar).visibility = View.GONE
        findViewById<ImageView>(R.id.btnShareSelection).visibility = View.VISIBLE
        findViewById<ImageView>(R.id.btnDeleteSelection).visibility = View.VISIBLE
        findViewById<ImageView>(R.id.btnSelectionMore).visibility = View.VISIBLE
        findViewById<LinearLayout>(R.id.headerSelection).visibility = View.GONE
        if (isSearchMode) {
            findViewById<LinearLayout>(R.id.headerSearch).visibility = View.VISIBLE
        } else {
            findViewById<LinearLayout>(R.id.headerNormal).visibility = View.VISIBLE
        }
    }

    private fun toggleLowUsageSectionSelection(isStrong: Boolean) {
        if (currentMode != "low_usage_large") return
        val currentList = adapter.currentList
        if (currentList.isEmpty()) return

        ensureDuplicateSelectionMode()

        val targets = currentList.filter { (it.smartScore >= 100) == isStrong }
        if (targets.isEmpty()) return

        val allSelected = targets.all { it.isSelected }
        val newState = !allSelected
        currentList.forEach { item ->
            if ((item.smartScore >= 100) == isStrong) {
                item.isSelected = newState
            }
        }
        adapter.notifyDataSetChanged()
        updateSelectionUI()
    }
    private fun updateSelectionUI() {
        val selectedItems = adapter.currentList.filter { it.isSelected }
        val count = selectedItems.size
        val tvSelectionCount: TextView = findViewById(R.id.tvSelectionCount)
        if (currentMode == "low_usage_large") {
            val selectedBytes = selectedItems.sumOf { if (it.isDirectory) 0L else it.size }
            val selectedSize = Formatter.formatFileSize(this, selectedBytes)
            tvSelectionCount.text = getString(R.string.selection_count_reclaim_format, count, selectedSize)
        } else {
            tvSelectionCount.text = getString(R.string.selection_count_format, count)
        }

        if (currentMode == "duplicate" || currentMode == "large" || currentMode == "low_usage_large") {
            val totalBytes = selectedItems.sumOf { if (it.isDirectory) 0L else it.size }
            val totalSize = Formatter.formatFileSize(this, totalBytes)
            applySelectionDeleteBarPreviewStyle(count, totalSize)
            findViewById<CardView>(R.id.layoutSelectionDeleteBar).visibility = if (count > 0) View.VISIBLE else View.GONE
        } else {
            findViewById<CardView>(R.id.layoutSelectionDeleteBar).visibility = View.GONE
        }
    }

    private fun applySelectionDeleteBarPreviewStyle(count: Int, totalSize: String) {
        val deleteBar: CardView = findViewById(R.id.layoutSelectionDeleteBar)
        val contentLayout: LinearLayout = findViewById(R.id.layoutSelectionDeleteContent)
        val centerLayout: LinearLayout = findViewById(R.id.layoutSelectionDeleteCenter)
        val ivDeleteIcon: ImageView = findViewById(R.id.ivSelectionDeleteIcon)
        val tvLabel: TextView = findViewById(R.id.tvSelectionDeleteLabel)
        val tvMeta: TextView = findViewById(R.id.tvSelectionDeleteMeta)
        val tvRight: TextView = findViewById(R.id.tvSelectionDeleteRight)

        val barParams = deleteBar.layoutParams as RelativeLayout.LayoutParams
        val centerParams = centerLayout.layoutParams as LinearLayout.LayoutParams
        val rightParams = tvRight.layoutParams as LinearLayout.LayoutParams
        val labelParams = tvLabel.layoutParams as LinearLayout.LayoutParams
        val iconParams = ivDeleteIcon.layoutParams as LinearLayout.LayoutParams
        val largeIconPx = (24 * resources.displayMetrics.density).toInt()

        iconParams.width = largeIconPx
        iconParams.height = largeIconPx
        ivDeleteIcon.layoutParams = iconParams

        barParams.width = RelativeLayout.LayoutParams.WRAP_CONTENT
        barParams.addRule(RelativeLayout.CENTER_HORIZONTAL, RelativeLayout.TRUE)
        deleteBar.layoutParams = barParams

        contentLayout.gravity = Gravity.CENTER

        centerParams.width = LinearLayout.LayoutParams.WRAP_CONTENT
        centerParams.weight = 0f
        centerLayout.layoutParams = centerParams
        centerLayout.gravity = Gravity.CENTER

        rightParams.width = 0
        rightParams.weight = 0f
        tvRight.layoutParams = rightParams
        tvRight.visibility = View.GONE

        tvLabel.text = getString(R.string.menu_delete)
        tvLabel.gravity = Gravity.CENTER
        tvMeta.visibility = View.VISIBLE
        tvMeta.gravity = Gravity.CENTER
        tvMeta.text = getString(R.string.selection_count_and_size_format, count, totalSize)

        val measuredMetaWidth = tvMeta.paint.measureText(tvMeta.text.toString()).toInt()
        labelParams.width = measuredMetaWidth.coerceAtLeast(tvLabel.measuredWidth)
        tvLabel.layoutParams = labelParams
    }

    private fun showDeleteSelectionDialog() {
        val selectedItems = adapter.currentList.filter { it.isSelected }
        if (selectedItems.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_delete))
            .setMessage(getString(R.string.confirm_delete_selected_message, selectedItems.size))
            .setPositiveButton(getString(R.string.menu_delete)) { _, _ -> deleteSelectedFiles(selectedItems) }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun deleteSelectedFiles(items: List<FileItem>) {
        var deletedCount = 0
        val pathsToScan = mutableListOf<String>()

        items.forEach { item ->
            val file = File(item.path)
            val existedBeforeDelete = file.exists()
            if (!existedBeforeDelete) return@forEach
            if (file.isDirectory) file.deleteRecursively() else file.delete()
            val deleted = !file.exists()
            if (deleted) {
                deletedCount++
                pathsToScan.add(file.absolutePath)
            }
        }

        if (deletedCount > 0) {
            Toast.makeText(this, getString(R.string.deleted_count_format, deletedCount), Toast.LENGTH_SHORT).show()
            MediaScannerConnection.scanFile(this, pathsToScan.toTypedArray(), null, null)
            closeSelectionMode()
            loadData(currentMode, currentPath)
        } else {
            Toast.makeText(this, getString(R.string.error_delete_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareSelectedFiles() {
        val selectedItems = adapter.currentList.filter { it.isSelected }
        if (selectedItems.isEmpty()) return

        val uris = ArrayList<Uri>()

        selectedItems.forEach { item ->
            val file = File(item.path)
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            uris.add(uri)
        }

        val mimeType = if (selectedItems.all { it.mimeType.startsWith("image/") }) "image/*"
        else if (selectedItems.all { it.mimeType.startsWith("video/") }) "video/*"
        else "*/*"

        try {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.menu_share)))
            ShareEventLogger.recordShareAsync(applicationContext, selectedItems)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_cannot_share), Toast.LENGTH_SHORT).show()
        }
    }

    // --- ?�렬, 검?? ?�이??로드 ---
    private fun loadSavedSortSettings() {
        if (currentMode == "recent") {
            currentSortMode = "date"
            isAscending = false
            return
        }

        val sortKey = "sort_mode_$currentMode"
        val ascKey = "is_ascending_$currentMode"
        val defaultSortMode = when (currentMode) {
            "folder" -> "name"
            "large", "duplicate", "low_usage_large" -> "size"
            "smart_shared", "smart_documents", "event_cluster" -> "date"
            else -> "date"
        }
        val defaultIsAscending = when (currentMode) {
            "folder" -> true
            "large", "duplicate", "low_usage_large" -> false
            "smart_shared", "smart_documents", "event_cluster" -> false
            else -> false
        }

        currentSortMode = prefs.getString(sortKey, defaultSortMode) ?: defaultSortMode
        isAscending = prefs.getBoolean(ascKey, defaultIsAscending)
    }

    private fun saveSortSettings() {
        if (currentMode == "recent" || currentMode == "smart_shared" || currentMode == "messenger" || currentMode == "smart_documents" || currentMode == "event_cluster") return

        val editor = prefs.edit()
        val sortKey = "sort_mode_$currentMode"
        val ascKey = "is_ascending_$currentMode"
        editor.putString(sortKey, currentSortMode)
        editor.putBoolean(ascKey, isAscending)
        editor.apply()
    }

    private fun setupHeaderEvents() {
        val btnSort: ImageView = findViewById(R.id.btnSort)
        val btnNewFolder: ImageView = findViewById(R.id.btnNewFolder)
        val btnSearch: ImageView = findViewById(R.id.btnSearch)
        val btnRecentMore: ImageView = findViewById(R.id.btnRecentMore)
        val btnCloseSearch: ImageView = findViewById(R.id.btnCloseSearch)
        val etSearch: EditText = findViewById(R.id.etSearch)
        val headerNormal: LinearLayout = findViewById(R.id.headerNormal)
        val headerSearch: LinearLayout = findViewById(R.id.headerSearch)

        val btnSearchSort: ImageView = findViewById(R.id.btnSearchSort)
        btnSearchSort.setOnClickListener { view -> showSortMenu(view) }

        btnSort.setOnClickListener { view -> showSortMenu(view) }
        btnNewFolder.setOnClickListener { showCreateFolderDialog() }

        btnSearch.setOnClickListener {
            if (currentMode == "duplicate" || currentMode == "large" || currentMode == "low_usage_large") {
                toggleSelectAll()
            } else {
                enterSearchMode()
            }
        }
        btnRecentMore.setOnClickListener { view ->
            showRecentHeaderMenu(view)
        }

        btnCloseSearch.setOnClickListener {
            if (finishOnSearchBack) finish() else closeSearchMode()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s.toString()
                performSearch(currentSearchQuery)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupWorkDocumentChips() {
        bindWorkDocumentChip(R.id.chipWorkAll, SmartWorkDocumentRepository.TYPE_ALL)
        bindWorkDocumentChip(R.id.chipWorkPdf, SmartWorkDocumentRepository.TYPE_PDF)
        bindWorkDocumentChip(R.id.chipWorkWord, SmartWorkDocumentRepository.TYPE_WORD)
        bindWorkDocumentChip(R.id.chipWorkExcel, SmartWorkDocumentRepository.TYPE_EXCEL)
        bindWorkDocumentChip(R.id.chipWorkPpt, SmartWorkDocumentRepository.TYPE_PPT)
        bindWorkDocumentChip(R.id.chipWorkHwp, SmartWorkDocumentRepository.TYPE_HWP)
        bindWorkDocumentChip(R.id.chipWorkOther, SmartWorkDocumentRepository.TYPE_OTHER)
        updateWorkDocumentChipSelection()
        updateWorkDocumentChipVisibility(currentMode)
    }

    private fun bindWorkDocumentChip(viewId: Int, type: String) {
        findViewById<TextView>(viewId).setOnClickListener {
            if (currentWorkDocTypeFilter == type && currentMode == "smart_documents") return@setOnClickListener
            currentWorkDocTypeFilter = type
            updateWorkDocumentChipSelection()
            if (currentMode == "smart_documents") {
                if (isSearchMode) {
                    performSearch(currentSearchQuery)
                } else {
                    loadData(currentMode, currentPath)
                }
            }
        }
    }

    private fun updateWorkDocumentChipVisibility(mode: String) {
        val chipLayout = findViewById<HorizontalScrollView>(R.id.layoutDocTypeChips)
        chipLayout.visibility = if (mode == "smart_documents") View.VISIBLE else View.GONE
    }

    private fun updateWorkDocumentChipSelection() {
        val selectedBg = R.drawable.bg_storage_tab_selected
        val unselectedBg = R.drawable.bg_storage_tab_unselected
        val selectedColor = Color.parseColor("#0D47A1")
        val unselectedColor = Color.parseColor("#616161")

        val mapping = listOf(
            R.id.chipWorkAll to SmartWorkDocumentRepository.TYPE_ALL,
            R.id.chipWorkPdf to SmartWorkDocumentRepository.TYPE_PDF,
            R.id.chipWorkWord to SmartWorkDocumentRepository.TYPE_WORD,
            R.id.chipWorkExcel to SmartWorkDocumentRepository.TYPE_EXCEL,
            R.id.chipWorkPpt to SmartWorkDocumentRepository.TYPE_PPT,
            R.id.chipWorkHwp to SmartWorkDocumentRepository.TYPE_HWP,
            R.id.chipWorkOther to SmartWorkDocumentRepository.TYPE_OTHER
        )

        mapping.forEach { (viewId, type) ->
            val chip = findViewById<TextView>(viewId)
            val selected = type == currentWorkDocTypeFilter
            chip.setBackgroundResource(if (selected) selectedBg else unselectedBg)
            chip.setTextColor(if (selected) selectedColor else unselectedColor)
        }
    }
    private fun enterSearchMode() {
        val etSearch: EditText = findViewById(R.id.etSearch)
        val headerNormal: LinearLayout = findViewById(R.id.headerNormal)
        val headerSearch: LinearLayout = findViewById(R.id.headerSearch)
        val btnSearchSort: ImageView = findViewById(R.id.btnSearchSort)

        isSearchMode = true
        if (currentMode == "recent") {
            currentSortMode = "date"
            isAscending = false
        } else if (currentMode == "large" || currentMode == "duplicate" || currentMode == "low_usage_large") {
            currentSortMode = "size"
            isAscending = false
        } else {
            currentSortMode = "name"
            isAscending = true
        }
        btnSearchSort.visibility = if (currentMode == "large" || currentMode == "duplicate" || currentMode == "low_usage_large" || currentMode == "smart_shared" || currentMode == "messenger" || currentMode == "event_cluster") View.GONE else View.VISIBLE

        headerNormal.visibility = View.GONE
        headerSearch.visibility = View.VISIBLE
        etSearch.setText("")
        etSearch.requestFocus()

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun performSearch(query: String) {
        if (currentMode == "recent" && lastRecentSearchQueryForSortReset != query) {
            currentSortMode = "date"
            isAscending = false
            lastRecentSearchQueryForSortReset = query
        }

        searchJob?.cancel()
        if (query.isEmpty()) {
            lifecycleScope.launch {
                val rawFiles = when (currentMode) {
                    "image" -> repository.getAllImages()
                    "video" -> repository.getAllVideos()
                    "audio" -> repository.getAllAudio()
                    "document" -> repository.getAllDocuments()
                    "app" -> repository.getAllApps()
                    "download" -> repository.getDownloads()
                    "recent" -> repository.getRecentFiles(maxAgeDays = null)
                    "large", "duplicate", "low_usage_large" -> when (currentMode) {
                        "duplicate" -> repository.getDuplicateFiles()
                        "low_usage_large" -> repository.getLowUsageLargeFiles()
                        else -> repository.getLargeFiles()
                    }
                    "smart_shared" -> smartSelectionRepository.getFrequentlySharedFiles()
                    "smart_documents" -> smartWorkDocumentRepository.getWorkDocuments(typeFilter = currentWorkDocTypeFilter)
                    "messenger" -> repository.getMessengerFiles(sourceApp = messengerAppFilter)
                    "event_cluster" -> loadEventClusterFiles()
                    else -> repository.getFilesByPath(currentPath)
                }
                applySortAndSubmit(applyStorageScopeFilter(rawFiles), isSearchResult = true)
            }
            return
        }

        searchJob = lifecycleScope.launch {
            val results = when (currentMode) {
                "image" -> repository.getAllImages(query)
                "video" -> repository.getAllVideos(query)
                "audio" -> repository.getAllAudio(query)
                "document" -> repository.getAllDocuments(query)
                "app" -> repository.getAllApps(query)
                "recent" -> repository.getRecentFiles(query = query, maxAgeDays = null)
                "large", "duplicate", "low_usage_large" -> when (currentMode) {
                    "duplicate" -> repository.getDuplicateFiles(query = query)
                    "low_usage_large" -> repository.getLowUsageLargeFiles(query = query)
                    else -> repository.getLargeFiles(query = query)
                }
                "smart_shared" -> smartSelectionRepository.getFrequentlySharedFiles(query = query)
                "smart_documents" -> smartWorkDocumentRepository.getWorkDocuments(query = query, typeFilter = currentWorkDocTypeFilter)
                "messenger" -> repository.getMessengerFiles(query = query, sourceApp = messengerAppFilter)
                "event_cluster" -> loadEventClusterFiles(query)
                "download" -> {
                    val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                    repository.searchRecursive(downloadPath, query)
                }
                "folder" -> repository.searchRecursive(currentPath, query)
                else -> repository.searchRecursive(currentPath, query)
            }
            applySortAndSubmit(applyStorageScopeFilter(results), isSearchResult = true)
        }
    }

    private fun closeSearchMode() {
        isSearchMode = false
        loadSavedSortSettings()

        val headerNormal: LinearLayout = findViewById(R.id.headerNormal)
        val headerSearch: LinearLayout = findViewById(R.id.headerSearch)
        val etSearch: EditText = findViewById(R.id.etSearch)

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)

        etSearch.setText("")
        headerSearch.visibility = View.GONE
        headerNormal.visibility = View.VISIBLE

        loadData(currentMode, currentPath)
    }

    private fun showSortMenu(view: View) {
        if (currentMode == "smart_documents") {
            showSmartDocumentsSortMenu(view)
            return
        }

        if ((currentMode == "recent" && !isSearchMode) || currentMode == "large" || currentMode == "duplicate" || currentMode == "low_usage_large" || currentMode == "smart_shared" || currentMode == "messenger" || currentMode == "event_cluster") return

        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.menu_sort, popup.menu)

        when (currentSortMode) {
            "name" -> popup.menu.findItem(R.id.sort_name).isChecked = true
            "size" -> popup.menu.findItem(R.id.sort_size).isChecked = true
            else -> popup.menu.findItem(R.id.sort_date).isChecked = true
        }
        if (isAscending) popup.menu.findItem(R.id.order_asc).isChecked = true
        else popup.menu.findItem(R.id.order_desc).isChecked = true

        popup.setOnMenuItemClickListener { menuItem ->
            menuItem.isChecked = true
            when (menuItem.itemId) {
                R.id.sort_date -> currentSortMode = "date"
                R.id.sort_name -> currentSortMode = "name"
                R.id.sort_size -> currentSortMode = "size"
                R.id.order_asc -> isAscending = true
                R.id.order_desc -> isAscending = false
                else -> return@setOnMenuItemClickListener false
            }

            if (!isSearchMode) {
                if (currentMode != "recent") {
                    saveSortSettings()
                }
                loadData(currentMode, currentPath)
            } else {
                performSearch(currentSearchQuery)
            }
            true
        }
        popup.show()
    }
    private fun showSmartDocumentsSortMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.menu_sort, popup.menu)

        val menu = popup.menu
        menu.findItem(R.id.sort_name)?.title = getString(R.string.smart_sort_related)
        menu.findItem(R.id.sort_size)?.isVisible = false

        when (currentSmartDocumentsSort) {
            "date" -> menu.findItem(R.id.sort_date)?.isChecked = true
            else -> menu.findItem(R.id.sort_name)?.isChecked = true
        }
        if (currentSmartDocumentsAscending) {
            menu.findItem(R.id.order_asc)?.isChecked = true
        } else {
            menu.findItem(R.id.order_desc)?.isChecked = true
        }

        popup.setOnMenuItemClickListener { menuItem ->
            menuItem.isChecked = true
            when (menuItem.itemId) {
                R.id.sort_name -> currentSmartDocumentsSort = "related"
                R.id.sort_date -> currentSmartDocumentsSort = "date"
                R.id.order_asc -> currentSmartDocumentsAscending = true
                R.id.order_desc -> currentSmartDocumentsAscending = false
                else -> return@setOnMenuItemClickListener false
            }
            if (isSearchMode) {
                performSearch(currentSearchQuery)
            } else {
                loadData(currentMode, currentPath)
            }
            true
        }
        popup.show()
    }

    private fun applySortAndSubmit(files: List<FileItem>, isSearchResult: Boolean = false) {
        val sortedFiles = if (currentMode == "duplicate" || currentMode == "large" || currentMode == "low_usage_large" || currentMode == "smart_shared" || currentMode == "event_cluster") {
            files
        } else if (currentMode == "smart_documents") {
            val baseComparator = if (currentSmartDocumentsSort == "date") {
                compareBy<FileItem> { it.dateModified }.thenBy { it.smartScore }.thenBy { it.path.lowercase() }
            } else {
                compareBy<FileItem> { it.smartScore }.thenBy { it.dateModified }.thenBy { it.path.lowercase() }
            }
            val comparator = if (currentSmartDocumentsAscending) baseComparator else baseComparator.reversed()
            files.sortedWith(comparator)
        } else if (currentMode == "messenger") {
            files.sortedWith(compareBy<FileItem> { MessengerPathMatcher.detectSourceName(it.path) }.thenByDescending { it.dateModified }.thenBy { it.path.lowercase() })
        } else {
            files.sortedWith(Comparator { o1, o2 ->
                if (o1.isDirectory != o2.isDirectory) {
                    return@Comparator if (o1.isDirectory) -1 else 1
                }
                if (currentMode == "recent" && !isSearchMode) {
                    val recentCmp = o2.dateModified.compareTo(o1.dateModified)
                    if (recentCmp != 0) return@Comparator recentCmp
                    return@Comparator o1.path.lowercase().compareTo(o2.path.lowercase())
                }
                val result = when (currentSortMode) {
                    "name" -> o1.name.lowercase().compareTo(o2.name.lowercase())
                    "size" -> o1.size.compareTo(o2.size)
                    else -> o1.dateModified.compareTo(o2.dateModified)
                }
                val ordered = if (isAscending) result else -result
                if (ordered != 0) ordered else o1.path.lowercase().compareTo(o2.path.lowercase())
            })
        }
        val rvFiles = findViewById<RecyclerView>(R.id.rvFiles)
        val layoutEmpty = findViewById<LinearLayout>(R.id.layoutEmpty)
        val tvFileCount = findViewById<TextView>(R.id.tvFileCount)

        val tvEmptyTitle = findViewById<TextView>(R.id.tvEmptyTitle)
        val tvEmptyMessage = findViewById<TextView>(R.id.tvEmptyMessage)
        val ivEmptyIcon = findViewById<ImageView>(R.id.ivEmptyIcon)

        if (sortedFiles.isEmpty()) {
            rvFiles.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE

            if (isSearchResult) {
                tvEmptyTitle.text = getString(R.string.empty_search_title)
                tvEmptyMessage.text = getString(R.string.empty_search_message)
                ivEmptyIcon.setImageResource(R.drawable.ic_search)
            } else if (currentMode == "smart_documents") {
                tvEmptyTitle.text = getString(R.string.smart_documents_empty_title)
                tvEmptyMessage.text = getString(R.string.smart_documents_empty_desc)
                ivEmptyIcon.setImageResource(R.drawable.ic_file)
            } else {
                tvEmptyTitle.text = getString(R.string.empty_folder_title)
                tvEmptyMessage.text = getString(R.string.empty_folder_message)
                ivEmptyIcon.setImageResource(R.drawable.ic_folder_solid)
            }
        } else {
            rvFiles.visibility = View.VISIBLE
            layoutEmpty.visibility = View.GONE
            val supportsDateSections = currentMode == "recent" ||
                currentMode == "image" ||
                currentMode == "video" ||
                currentMode == "audio" ||
                currentMode == "document" ||
                currentMode == "app" ||
                currentMode == "download"
            adapter.showDateHeaders = supportsDateSections && currentSortMode == "date"
            adapter.showDuplicateHeaders = currentMode == "duplicate"
            adapter.showLowUsageHeaders = currentMode == "low_usage_large"

            val filesToSubmit = if ((currentMode == "duplicate" || currentMode == "large" || currentMode == "low_usage_large") && isSelectionMode) {
                val selectedPaths = adapter.currentList.asSequence()
                    .filter { it.isSelected }
                    .map { it.path }
                    .toHashSet()
                sortedFiles.onEach { it.isSelected = it.path in selectedPaths }
            } else {
                sortedFiles
            }

            adapter.submitList(filesToSubmit)
            if (currentMode == "duplicate" || currentMode == "large" || currentMode == "low_usage_large") ensureDuplicateSelectionMode()
        }

        if (isSearchResult) {
            tvFileCount.text = getString(R.string.file_count_search_format, sortedFiles.size)
        } else {
            if (currentMode == "event_cluster") {
                val totalBytes = sortedFiles.sumOf { it.size }
                tvFileCount.text = getString(
                    R.string.smart_event_item_meta_format,
                    sortedFiles.size,
                    Formatter.formatFileSize(this, totalBytes)
                )
                return
            }
            if (currentMode == "smart_shared") {
                tvFileCount.text = getString(R.string.file_count_format, sortedFiles.size) + " / " + getString(R.string.smart_share_source_note)
                return
            }
            if (currentMode == "recent" && isRecentCountLoading) {
                tvFileCount.text = getString(R.string.loading_label)
                return
            }
            if (currentMode == "folder") {
                val folderCount = sortedFiles.count { it.isDirectory }
                val fileCount = sortedFiles.count { !it.isDirectory }

                val textParts = mutableListOf<String>()
                if (folderCount > 0) textParts.add(getString(R.string.folder_count_format, folderCount))
                if (fileCount > 0) textParts.add(getString(R.string.file_count_format, fileCount))

                if (textParts.isEmpty()) {
                    tvFileCount.text = getString(R.string.item_count_zero)
                } else {
                    tvFileCount.text = textParts.joinToString(getString(R.string.count_join_separator))
                }
            } else {
                tvFileCount.text = getString(R.string.file_count_format, sortedFiles.size)
            }
        }
    }

    // loadData: 경로 ?�시 �?붙여?�기 �?UI 갱신 ?�출
    private fun loadData(mode: String, path: String) {
        // ���� �ε� �۾� ���
        loadJob?.cancel()

        currentMode = mode
        currentPath = path
        adapter.showParentPathLine = mode == "duplicate" || mode == "large" || mode == "low_usage_large" || mode == "image" || mode == "video" || mode == "audio" || mode == "document" || mode == "download" || mode == "app" || mode == "smart_shared" || mode == "messenger" || mode == "smart_documents" || mode == "event_cluster"
        adapter.preferStaticIcons = false
        pasteTargetPath = if (mode == "folder") path else rootPath
        updateStorageTabsForMode(mode)
        updateWorkDocumentChipVisibility(mode)
        updateWorkDocumentChipSelection()

        val tvTitle = findViewById<TextView>(R.id.tvPageTitle)
        val btnNewFolder = findViewById<ImageView>(R.id.btnNewFolder)
        val btnSort = findViewById<ImageView>(R.id.btnSort)
        val btnRecentMore = findViewById<ImageView>(R.id.btnRecentMore)
        val btnSearch = findViewById<ImageView>(R.id.btnSearch)
        val btnSearchSort = findViewById<ImageView>(R.id.btnSearchSort)

        val scrollViewPath = findViewById<HorizontalScrollView>(R.id.scrollViewPath)
        val tvPathIndicator = findViewById<TextView>(R.id.tvPathIndicator)

        // ��뷮/�ߺ� ȭ�鿡���� ��� ���� ��ư�� ��ü�������� ���
        if (mode == "duplicate" || mode == "large" || mode == "low_usage_large") {
            btnSearch.setImageResource(R.drawable.ic_select_all)
            btnSearch.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#673AB7"))
        } else {
            btnSearch.setImageResource(R.drawable.ic_search)
            btnSearch.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#111111"))
        }

        if (mode == "folder") {
            btnNewFolder.visibility = View.VISIBLE
            btnSort.visibility = View.VISIBLE
            btnRecentMore.visibility = View.GONE
            btnSearchSort.visibility = if (mode == "large" || mode == "duplicate" || mode == "low_usage_large") View.GONE else View.VISIBLE

            if (path == rootPath) {
                tvTitle.text = rootTitle
                scrollViewPath?.visibility = View.GONE
            } else {
                tvTitle.text = File(path).name

                if (scrollViewPath != null && tvPathIndicator != null) {
                    scrollViewPath.visibility = View.VISIBLE
                    val relativePath = path.removePrefix(rootPath)
                    val displayPath = rootTitle + relativePath.replace("/", " > ")
                    tvPathIndicator.text = displayPath
                    scrollViewPath.post { scrollViewPath.fullScroll(View.FOCUS_RIGHT) }
                }
            }
        } else {
            tvTitle.text = rootTitle
            btnNewFolder.visibility = View.GONE
            if (mode == "recent") {
                btnSort.visibility = View.GONE
                btnRecentMore.visibility = View.VISIBLE
                btnSearchSort.visibility = View.VISIBLE
            } else if (mode == "large" || mode == "duplicate" || mode == "low_usage_large" || mode == "smart_shared" || mode == "messenger" || mode == "event_cluster") {
                btnSort.visibility = View.GONE
                btnRecentMore.visibility = View.GONE
                btnSearchSort.visibility = View.GONE
            } else if (mode == "smart_documents") {
                btnSort.visibility = View.VISIBLE
                btnRecentMore.visibility = View.GONE
                btnSearchSort.visibility = View.VISIBLE
            } else {
                btnSort.visibility = View.VISIBLE
                btnRecentMore.visibility = View.GONE
                btnSearchSort.visibility = View.VISIBLE
            }
            scrollViewPath?.visibility = View.GONE
        }

        updatePasteBarUI()

        // [추�?] ?�더 ?�이�?갱신 (?�버�?<-> ?�로가�?
        updateHeaderIcon()

        // ?�로??로딩 ?�업 ?�작
        loadJob = lifecycleScope.launch {
            if (mode == "recent") {
                val initialFiles = repository.getRecentFiles(limit = RECENT_INITIAL_BATCH, maxAgeDays = null)
                if (initialFiles.isEmpty()) {
                    val fullFallback = repository.getRecentFiles(maxAgeDays = null)
                    isRecentCountLoading = false
                    applySortAndSubmit(applyStorageScopeFilter(fullFallback), isSearchResult = false)
                    return@launch
                }

                val shouldLoadFull = initialFiles.size >= RECENT_INITIAL_BATCH
                isRecentCountLoading = shouldLoadFull
                applySortAndSubmit(applyStorageScopeFilter(initialFiles), isSearchResult = false)

                if (shouldLoadFull) {
                    val fullFiles = repository.getRecentFiles(maxAgeDays = null)
                    isRecentCountLoading = false
                    applySortAndSubmit(applyStorageScopeFilter(fullFiles), isSearchResult = false)
                }
                return@launch
            }
            if (mode == "duplicate") {
                val finalFiles = repository.getDuplicateFilesProgressive { sizeOnlyFiles ->
                    applySortAndSubmit(applyStorageScopeFilter(sizeOnlyFiles), isSearchResult = false)
                }
                applySortAndSubmit(applyStorageScopeFilter(finalFiles), isSearchResult = false)
                return@launch
            }
            isRecentCountLoading = false

            val rawFiles = when (mode) {
                "image" -> repository.getAllImages()
                "video" -> repository.getAllVideos()
                "audio" -> repository.getAllAudio()
                "document" -> repository.getAllDocuments()
                "app" -> repository.getAllApps()
                "download" -> repository.getDownloads()
                "large" -> repository.getLargeFiles()
                "low_usage_large" -> repository.getLowUsageLargeFiles()
                "smart_shared" -> smartSelectionRepository.getFrequentlySharedFiles()
                "smart_documents" -> smartWorkDocumentRepository.getWorkDocuments(typeFilter = currentWorkDocTypeFilter)
                "messenger" -> repository.getMessengerFiles(sourceApp = messengerAppFilter)
                "event_cluster" -> loadEventClusterFiles()
                else -> repository.getFilesByPath(path)
            }
            if (mode == "smart_shared") {
                Log.d("SmartSelection", "FileListActivity rawFiles=${rawFiles.size} scope=$storageScope hasSd=$hasSdCard")
            }
            applySortAndSubmit(applyStorageScopeFilter(rawFiles), isSearchResult = false)
        }
    }

    // [?�정?? 개별 ?�일 메뉴 ?�션 ?�들??
    private fun showFileOptionMenu(view: View, fileItem: FileItem) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.menu_file_item, popup.menu)

        // 즐겨찾기 메뉴 ?�정
        val favItem = popup.menu.findItem(R.id.action_favorite)
        val isFav = FavoritesManager.isFavorite(this, fileItem)

        // [변�? ?�더/?�일 구분 ?�이 즐겨찾기 메뉴 ?�성??
        favItem.isVisible = true
        favItem.title = if (isFav) getString(R.string.favorite_remove) else getString(R.string.menu_favorite_add)

        if (currentMode == "recent") {
            popup.menu.add(
                0,
                MENU_EXCLUDE_RECENT_FOLDER,
                10_000,
                getString(R.string.menu_exclude_folder_from_recent)
            )
        }

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_favorite -> {
                    toggleFavorite(fileItem)
                    true
                }
                R.id.action_copy -> { copyOrMoveFileItem(fileItem, isMove = false); true }
                R.id.action_move -> { copyOrMoveFileItem(fileItem, isMove = true); true }
                R.id.action_share -> { shareFile(fileItem); true }
                R.id.action_zip -> { showZipDialog(listOf(fileItem)); true }
                R.id.action_rename -> { showRenameDialog(fileItem); true }
                R.id.action_delete -> { showDeleteDialog(fileItem); true }
                R.id.action_details -> { showFileDetailsDialog(fileItem); true }
                MENU_EXCLUDE_RECENT_FOLDER -> {
                    excludeParentFolderFromRecent(fileItem)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRecentHeaderMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add(
            0,
            MENU_RECENT_MANAGE_EXCLUDED,
            0,
            getString(R.string.recent_exclusion_title)
        )
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_RECENT_MANAGE_EXCLUDED -> {
                    startActivity(Intent(this, RecentExclusionActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun excludeParentFolderFromRecent(fileItem: FileItem) {
        val parentPath = File(fileItem.path).parent ?: run {
            Toast.makeText(this, getString(R.string.error_cannot_exclude_folder), Toast.LENGTH_SHORT).show()
            return
        }
        val added = RecentExclusionManager.addFolder(this, parentPath)
        val folderName = File(parentPath).name.ifBlank { parentPath }
        val message = if (added) {
            getString(R.string.recent_excluded_folder_added, folderName)
        } else {
            getString(R.string.recent_excluded_folder_exists, folderName)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        loadData("recent", rootPath)
    }

    private fun toggleFavorite(fileItem: FileItem) {
        if (FavoritesManager.isFavorite(this, fileItem)) {
            FavoritesManager.remove(this, fileItem)
            Toast.makeText(this, getString(R.string.favorite_removed), Toast.LENGTH_SHORT).show()
        } else {
            FavoritesManager.add(this, fileItem)
            Toast.makeText(this, getString(R.string.favorite_added), Toast.LENGTH_SHORT).show()
        }
        updateDrawerMenu()
    }

    private fun copyOrMoveFileItem(fileItem: FileItem, isMove: Boolean) {
        FileClipboard.files = listOf(File(fileItem.path))
        FileClipboard.isMove = isMove
        updatePasteBarUI()
        if (currentMode != "folder") {
            rootTitle = getString(R.string.internal_storage)
            loadData("folder", rootPath)
        }
        val message = if (isMove) getString(R.string.clipboard_ready_move) else getString(R.string.clipboard_ready_copy)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showFileDetailsDialog(fileItem: FileItem) {
        val view = layoutInflater.inflate(R.layout.dialog_file_details, null)
        val file = File(fileItem.path)

        val tvName: TextView = view.findViewById(R.id.tvDetailName)
        val tvType: TextView = view.findViewById(R.id.tvDetailType)
        val tvSize: TextView = view.findViewById(R.id.tvDetailSize)
        val tvDate: TextView = view.findViewById(R.id.tvDetailDate)
        val tvPath: TextView = view.findViewById(R.id.tvDetailPath)
        val tvStorage: TextView = view.findViewById(R.id.tvDetailStorage)

        tvName.text = file.name
        tvPath.text = file.absolutePath
        val roots = StorageVolumeHelper.getStorageRoots(this)
        tvStorage.text = when (StorageVolumeHelper.detectVolume(file.absolutePath, roots)) {
            StorageVolumeType.INTERNAL -> getString(R.string.internal_storage)
            StorageVolumeType.SD_CARD -> getString(R.string.sd_card)
            StorageVolumeType.OTHER -> getString(R.string.storage_other)
        }

        val dateFormat = SimpleDateFormat("yyyy. MM. dd. HH:mm", Locale.getDefault())
        tvDate.text = dateFormat.format(Date(file.lastModified()))

        if (file.isDirectory) {
            tvType.text = getString(R.string.details_type_folder_label)
            val items = file.list()?.size ?: 0
            tvSize.text = getString(R.string.details_items_count_format, items)
        } else {
            val extension = file.extension.uppercase()
            tvType.text = if (extension.isNotEmpty()) {
                getString(R.string.details_file_type_format, extension)
            } else {
                getString(R.string.details_file_type_generic)
            }
            tvSize.text = Formatter.formatFileSize(this, file.length())
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.details_title))
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showRenameDialog(fileItem: FileItem) {
        val editText = EditText(this)
        editText.setText(fileItem.name)
        editText.setSingleLine()
        val dotIndex = fileItem.name.lastIndexOf('.')
        if (dotIndex > 0) editText.setSelection(0, dotIndex) else editText.selectAll()

        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.leftMargin = 50; params.rightMargin = 50
        editText.layoutParams = params
        container.addView(editText)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_title))
            .setView(container)
            .setPositiveButton(getString(R.string.action_change)) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != fileItem.name) renameFile(fileItem, newName)
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun renameFile(fileItem: FileItem, newName: String) {
        val oldFile = File(fileItem.path)
        val newFile = File(oldFile.parent, newName)
        if (newFile.exists()) {
            Toast.makeText(this, getString(R.string.rename_exists_error), Toast.LENGTH_SHORT).show()
            return
        }
        if (oldFile.renameTo(newFile)) {
            Toast.makeText(this, getString(R.string.rename_success), Toast.LENGTH_SHORT).show()
            MediaScannerConnection.scanFile(this, arrayOf(oldFile.absolutePath, newFile.absolutePath), null, null)
            FavoritesManager.onPathRenamed(
                context = this,
                oldPath = oldFile.absolutePath,
                newPath = newFile.absolutePath,
                isDirectory = oldFile.isDirectory
            )
            updateDrawerMenu()
            if (isSelectionMode) {
                closeSelectionMode()
            }
            loadData(currentMode, currentPath)
        } else {
            Toast.makeText(this, getString(R.string.rename_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(fileItem: FileItem) {
        try {
            val file = File(fileItem.path)
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = fileItem.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.menu_share)))
            ShareEventLogger.recordShareAsync(applicationContext, listOf(fileItem))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_cannot_share), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteDialog(fileItem: FileItem) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_delete))
            .setMessage(getString(R.string.confirm_delete_file_message, fileItem.name))
            .setPositiveButton(getString(R.string.menu_delete)) { _, _ -> deleteFile(fileItem) }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun deleteFile(fileItem: FileItem) {
        val file = File(fileItem.path)
        if (file.exists()) {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
            val deleted = !file.exists()
            if (deleted) {
                Toast.makeText(this, getString(R.string.msg_deleted), Toast.LENGTH_SHORT).show()
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
                loadData(currentMode, currentPath)
            } else {
                Toast.makeText(this, getString(R.string.error_delete_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleBackAction() {
        if (currentMode == "folder" && currentPath != rootPath) {
            val parentFile = File(currentPath).parentFile
            if (parentFile != null) loadData("folder", parentFile.absolutePath) else finish()
        } else { finish() }
    }

    private suspend fun loadEventClusterFiles(query: String? = null): List<FileItem> {
        if (currentEventStartMs <= 0L || currentEventEndMs <= 0L) return emptyList()
        val files = eventPhotoBundleRepository.getClusterPhotos(currentEventStartMs, currentEventEndMs)
        if (query.isNullOrBlank()) return files
        val q = query.lowercase()
        return files.filter { item ->
            item.name.lowercase().contains(q) || item.path.lowercase().contains(q)
        }
    }

    private fun formatEventClusterTitle(startMs: Long, endMs: Long): String {
        val locale = Locale.getDefault()
        val startCal = Calendar.getInstance().apply { timeInMillis = startMs }
        val endCal = Calendar.getInstance().apply { timeInMillis = endMs }
        val sameDay = startCal.get(Calendar.YEAR) == endCal.get(Calendar.YEAR) &&
            startCal.get(Calendar.DAY_OF_YEAR) == endCal.get(Calendar.DAY_OF_YEAR)
        val base = if (sameDay) {
            if (locale.language == Locale.KOREAN.language) {
                SimpleDateFormat("M�� d��", locale).format(startMs)
            } else {
                SimpleDateFormat("MMM d", locale).format(startMs)
            }
        } else {
            val startText = SimpleDateFormat("yyyy.MM.dd", locale).format(startMs)
            val endText = SimpleDateFormat("yyyy.MM.dd", locale).format(endMs)
            "$startText - $endText"
        }
        return if (locale.language == Locale.KOREAN.language) {
            "$base �Կ� ����"
        } else {
            "$base Photo bundle"
        }
    }

    private fun showCreateFolderDialog() {
        val editText = EditText(this)
        editText.hint = getString(R.string.new_folder_hint)
        editText.setSingleLine()

        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.leftMargin = 50; params.rightMargin = 50
        editText.layoutParams = params
        container.addView(editText)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_folder_title))
            .setView(container)
            .setPositiveButton(getString(R.string.action_create)) { _, _ ->
                val folderName = editText.text.toString().trim()
                if (folderName.isNotEmpty()) createFolder(folderName)
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun createFolder(name: String) {
        val newFolder = File(currentPath, name)
        if (newFolder.exists()) {
            Toast.makeText(this, getString(R.string.folder_exists_error), Toast.LENGTH_SHORT).show()
            return
        }
        if (newFolder.mkdirs()) {
            Toast.makeText(this, getString(R.string.folder_created), Toast.LENGTH_SHORT).show()
            MediaScannerConnection.scanFile(this, arrayOf(newFolder.absolutePath), null, null)
            loadData(currentMode, currentPath)
        } else {
            Toast.makeText(this, getString(R.string.folder_create_failed), Toast.LENGTH_SHORT).show()
        }
    }
}


