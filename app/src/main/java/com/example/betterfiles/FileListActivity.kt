package com.example.betterfiles

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaScannerConnection // [蹂듦뎄?? ??以꾩씠 鍮좎졇???먮윭媛 ?ъ뒿?덈떎
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.Size
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
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileListActivity : AppCompatActivity() {
    companion object {
        private const val RECENT_INITIAL_BATCH = 120
    }

    private lateinit var adapter: FileAdapter
    private lateinit var repository: FileRepository

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_list)

        repository = FileRepository(this)
        prefs = getSharedPreferences("BetterFilesPrefs", Context.MODE_PRIVATE)

        drawerLayout = findViewById(R.id.drawerLayout)
        navView = findViewById(R.id.navView)
        val rvFiles: RecyclerView = findViewById(R.id.rvFiles)
        val btnBack: ImageView = findViewById(R.id.btnBack)

        val intentTitle = intent.getStringExtra("title") ?: getString(R.string.default_page_title)
        currentMode = intent.getStringExtra("mode") ?: "folder"
        val intentPathExtra = intent.getStringExtra("path")
        val intentPath = if (intentPathExtra.isNullOrBlank()) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            intentPathExtra
        }
        finishOnSearchBack = intent.getBooleanExtra("startSearch", false)

        rootTitle = intentTitle
        rootPath = intentPath
        currentPath = intentPath
        pasteTargetPath = intentPath

        loadSavedSortSettings()

        // 2. ?대뙌???ㅼ젙
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
            }
        )
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = adapter

        // 3. ?쒕줈???ㅼ젙
        setupDrawer()

        // 4. ?대깽???ㅼ젙
        btnBack.setOnClickListener { handleHeaderNavigationClick() }
        setupHeaderEvents()
        setupSelectionEvents()
        setupPasteEvents()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
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

    // ?쇄뼹???쒕줈??利먭꺼李얘린) 愿??濡쒖쭅 ?쇄뼹??
    private fun setupDrawer() {
        // 1. 湲곕낯 ?댄듃(?됱긽 ??뼱?곌린) ?쒓굅 -> ?곕━媛 ?먰븯?????몃??? ?몃꽕???????쒖떆?섍린 ?꾪븿
        navView.itemIconTintList = null

        // 2. ?곷떒 怨좎젙 硫붾돱(?댁옣 硫붾え由? ?ㅼ슫濡쒕뱶) ?꾩씠肄섏쓣 ?뚯깋?쇰줈 ?섎룞 ?ㅼ젙
        val menu = navView.menu
        val greyColor = Color.parseColor("#757575") // 湲곕낯 ?뚯깋

        val internalItem = menu.findItem(R.id.nav_internal_storage)
        internalItem?.icon?.mutate()?.setTint(greyColor)

        val documentItem = menu.findItem(R.id.nav_document)
        documentItem?.icon?.mutate()?.setTint(greyColor)

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

        // 3. ?대┃ 由ъ뒪??(湲곗〈 肄붾뱶? ?숈씪)
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_internal_storage -> {
                    rootTitle = getString(R.string.internal_storage)
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
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
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
                val item = favoritesGroup.add(0, index + 100, 0, title)

                if (entry.isDirectory) {
                    // Folder favorite icon tint
                    val drawable = getDrawable(R.drawable.ic_folder_solid)?.mutate()
                    drawable?.setTint(Color.parseColor("#FFC107")) // ?몃????곸슜
                    item.icon = drawable

                    item.setOnMenuItemClickListener {
                        loadData("folder", entry.path)
                        drawerLayout.closeDrawer(GravityCompat.START)
                        true
                    }
                } else {
                    // [?뚯씪] ?곗꽑 湲곕낯 ?꾩씠肄?諛??됱긽 ?ㅼ젙
                    val iconRes = getFileIconResource(file.name)
                    val iconColor = getFileIconColor(file.name) ?: Color.GRAY
                    val drawable = getDrawable(iconRes)?.mutate()
                    drawable?.setTint(iconColor)
                    item.icon = drawable

                    // [異붽??? ?몃꽕?? ?대?吏/鍮꾨뵒?ㅻ뒗 鍮꾨룞湲곕줈 濡쒕뵫?섏뿬 ?꾩씠肄?援먯껜
                    if (isImageFile(file.name) || isVideoFile(file.name)) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val thumbnail = loadThumbnail(file) // ?꾨옒??異붽????⑥닔 ?몄텧
                            if (thumbnail != null) {
                                withContext(Dispatchers.Main) {
                                    // ?κ렐 紐⑥꽌由??몃꽕???앹꽦
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

    // [異붽?] ?몃꽕??濡쒕뵫 ?⑥닔
    // [異붽?] ?몃꽕??濡쒕뵫 ?ы띁 ?⑥닔
    private fun loadThumbnail(file: File): Bitmap? {
        return try {
            val size = Size(144, 144) // 硫붾돱 ?꾩씠肄섏뿉 ?곷떦???ш린
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createImageThumbnail(file, size, null)
            } else {
                // 援щ쾭???명솚 (媛꾨떒??鍮꾪듃留??붿퐫??
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
    // ?꿎뼯???쒕줈??濡쒖쭅 ???꿎뼯??

    private fun handleFileClick(fileItem: FileItem) {
        val file = File(fileItem.path)
        if (file.extension.equals("zip", ignoreCase = true)) {
            showUnzipDialog(file)
        } else {
            openFile(fileItem)
        }
    }

    // [?⑥닔 1] 硫붿씤 由ъ뒪?몄뿉???대┃ ??(FileItem ?ъ슜)
    private fun openFile(fileItem: FileItem) {
        try {
            val file = File(fileItem.path)
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, fileItem.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_no_app_to_open), Toast.LENGTH_SHORT).show()
        }
    }

    // [?⑥닔 2] 利먭꺼李얘린 ?깆뿉???뚯씪 寃쎈줈留뚯쑝濡??ㅽ뻾 (File ?ъ슜)
    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val mimeType = getMimeType(file)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_no_app_to_open), Toast.LENGTH_SHORT).show()
        }
    }

    // [?꾩닔] ?뚯씪 ?뺤옣?먮줈 MIME Type 李얘린
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

        btnCloseSelection.setOnClickListener { closeSelectionMode() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }

        btnSelectionMore.setOnClickListener { view ->
            showSelectionMoreMenu(view)
        }

        btnShareSelection.setOnClickListener { shareSelectedFiles() }
        btnDeleteSelection.setOnClickListener { showDeleteSelectionDialog() }
    }

    private fun showSelectionMoreMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.menu_selection_more, popup.menu)

        val selectedItems = adapter.currentList.filter { it.isSelected }
        val detailsItem = popup.menu.findItem(R.id.action_selection_details)
        val favoriteItem = popup.menu.findItem(R.id.action_selection_favorite)
        val renameItem = popup.menu.findItem(R.id.action_selection_rename)
        detailsItem.isVisible = selectedItems.size == 1
        favoriteItem.isVisible = selectedItems.size == 1
        renameItem.isVisible = selectedItems.size == 1
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
        val isAllSelected = currentList.all { it.isSelected }
        val newState = !isAllSelected
        currentList.forEach { it.isSelected = newState }
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

        updateSelectionUI()
    }

    private fun closeSelectionMode() {
        isSelectionMode = false
        adapter.currentList.forEach { it.isSelected = false }
        adapter.isSelectionMode = false
        adapter.notifyDataSetChanged()

        findViewById<LinearLayout>(R.id.headerSelection).visibility = View.GONE
        if (isSearchMode) {
            findViewById<LinearLayout>(R.id.headerSearch).visibility = View.VISIBLE
        } else {
            findViewById<LinearLayout>(R.id.headerNormal).visibility = View.VISIBLE
        }
    }

    private fun updateSelectionUI() {
        val count = adapter.currentList.count { it.isSelected }
        val tvSelectionCount: TextView = findViewById(R.id.tvSelectionCount)
        tvSelectionCount.text = getString(R.string.selection_count_format, count)
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
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_cannot_share), Toast.LENGTH_SHORT).show()
        }
    }

    // --- ?뺣젹, 寃?? ?곗씠??濡쒕뱶 ---
    private fun loadSavedSortSettings() {
        if (currentMode == "recent") {
            currentSortMode = "date"
            isAscending = false
            return
        }

        val sortKey = "sort_mode_$currentMode"
        val ascKey = "is_ascending_$currentMode"
        val defaultSortMode = if (currentMode == "folder") "name" else "date"
        val defaultIsAscending = currentMode == "folder"

        currentSortMode = prefs.getString(sortKey, defaultSortMode) ?: defaultSortMode
        isAscending = prefs.getBoolean(ascKey, defaultIsAscending)
    }

    private fun saveSortSettings() {
        if (currentMode == "recent") return

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
        val btnCloseSearch: ImageView = findViewById(R.id.btnCloseSearch)
        val etSearch: EditText = findViewById(R.id.etSearch)
        val headerNormal: LinearLayout = findViewById(R.id.headerNormal)
        val headerSearch: LinearLayout = findViewById(R.id.headerSearch)

        val btnSearchSort: ImageView = findViewById(R.id.btnSearchSort)
        btnSearchSort.setOnClickListener { view -> showSortMenu(view) }

        btnSort.setOnClickListener { view -> showSortMenu(view) }
        btnNewFolder.setOnClickListener { showCreateFolderDialog() }

        btnSearch.setOnClickListener {
            enterSearchMode()
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

    private fun enterSearchMode() {
        val etSearch: EditText = findViewById(R.id.etSearch)
        val headerNormal: LinearLayout = findViewById(R.id.headerNormal)
        val headerSearch: LinearLayout = findViewById(R.id.headerSearch)
        val btnSearchSort: ImageView = findViewById(R.id.btnSearchSort)

        isSearchMode = true
        if (currentMode == "recent") {
            currentSortMode = "date"
            isAscending = false
        } else {
            currentSortMode = "name"
            isAscending = true
        }
        btnSearchSort.visibility = View.VISIBLE

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
                    else -> repository.getFilesByPath(currentPath)
                }
                applySortAndSubmit(rawFiles, isSearchResult = true)
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
                "download" -> {
                    val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                    repository.searchRecursive(downloadPath, query)
                }
                "folder" -> repository.searchRecursive(currentPath, query)
                else -> repository.searchRecursive(currentPath, query)
            }
            applySortAndSubmit(results, isSearchResult = true)
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
        if (currentMode == "recent" && !isSearchMode) return

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

    private fun applySortAndSubmit(files: List<FileItem>, isSearchResult: Boolean = false) {
        val sortedFiles = files.sortedWith(Comparator { o1, o2 ->
            if (o1.isDirectory != o2.isDirectory) {
                return@Comparator if (o1.isDirectory) -1 else 1
            }
            if (currentMode == "recent" && !isSearchMode) {
                return@Comparator o2.dateModified.compareTo(o1.dateModified)
            }
            val result = when (currentSortMode) {
                "name" -> o1.name.lowercase().compareTo(o2.name.lowercase())
                "size" -> o1.size.compareTo(o2.size)
                else -> o1.dateModified.compareTo(o2.dateModified)
            }
            if (isAscending) result else -result
        })

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
            adapter.submitList(sortedFiles)
        }

        if (isSearchResult) {
            tvFileCount.text = getString(R.string.file_count_search_format, sortedFiles.size)
        } else {
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

    // loadData: 寃쎈줈 ?쒖떆 諛?遺숈뿬?ｊ린 諛?UI 媛깆떊 ?몄텧
    private fun loadData(mode: String, path: String) {
        // ?댁쟾 濡쒕뵫 ?묒뾽 痍⑥냼
        loadJob?.cancel()

        currentMode = mode
        currentPath = path
        pasteTargetPath = if (mode == "folder") path else rootPath

        val tvTitle = findViewById<TextView>(R.id.tvPageTitle)
        val btnNewFolder = findViewById<ImageView>(R.id.btnNewFolder)
        val btnSort = findViewById<ImageView>(R.id.btnSort)
        val btnSearchSort = findViewById<ImageView>(R.id.btnSearchSort)

        val scrollViewPath = findViewById<HorizontalScrollView>(R.id.scrollViewPath)
        val tvPathIndicator = findViewById<TextView>(R.id.tvPathIndicator)

        if (mode == "folder") {
            btnNewFolder.visibility = View.VISIBLE
            btnSort.visibility = View.VISIBLE
            btnSearchSort.visibility = View.VISIBLE

            if (path == rootPath) {
                tvTitle.text = rootTitle
                if (scrollViewPath != null) scrollViewPath.visibility = View.GONE
            } else {
                tvTitle.text = File(path).name

                if (scrollViewPath != null && tvPathIndicator != null) {
                    scrollViewPath.visibility = View.VISIBLE

                    val relativePath = path.removePrefix(rootPath)
                    val displayPath = getString(R.string.internal_storage) + relativePath.replace("/", " > ")
                    tvPathIndicator.text = displayPath

                    scrollViewPath.post {
                        scrollViewPath.fullScroll(View.FOCUS_RIGHT)
                    }
                }
            }
        } else {
            tvTitle.text = rootTitle
            btnNewFolder.visibility = View.GONE
            if (mode == "recent") {
                btnSort.visibility = View.GONE
            } else {
                btnSort.visibility = View.VISIBLE
                btnSearchSort.visibility = View.VISIBLE
            }
            if (scrollViewPath != null) scrollViewPath.visibility = View.GONE
        }

        updatePasteBarUI()

        // [異붽?] ?ㅻ뜑 ?꾩씠肄?媛깆떊 (?꾨쾭嫄?<-> ?ㅻ줈媛湲?
        updateHeaderIcon()

        // ?덈줈??濡쒕뵫 ?묒뾽 ?쒖옉
        loadJob = lifecycleScope.launch {
            if (mode == "recent") {
                val initialFiles = repository.getRecentFiles(limit = RECENT_INITIAL_BATCH, maxAgeDays = null)
                if (initialFiles.isEmpty()) {
                    val fullFallback = repository.getRecentFiles(maxAgeDays = null)
                    isRecentCountLoading = false
                    applySortAndSubmit(fullFallback, isSearchResult = false)
                    return@launch
                }

                val shouldLoadFull = initialFiles.size >= RECENT_INITIAL_BATCH
                isRecentCountLoading = shouldLoadFull
                applySortAndSubmit(initialFiles, isSearchResult = false)

                if (shouldLoadFull) {
                    val fullFiles = repository.getRecentFiles(maxAgeDays = null)
                    isRecentCountLoading = false
                    applySortAndSubmit(fullFiles, isSearchResult = false)
                }
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
                else -> repository.getFilesByPath(path)
            }
            applySortAndSubmit(rawFiles, isSearchResult = false)
        }
    }

    // [?섏젙?? 媛쒕퀎 ?뚯씪 硫붾돱 ?듭뀡 ?몃뱾??
    private fun showFileOptionMenu(view: View, fileItem: FileItem) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.menu_file_item, popup.menu)

        // 利먭꺼李얘린 硫붾돱 ?ㅼ젙
        val favItem = popup.menu.findItem(R.id.action_favorite)
        val isFav = FavoritesManager.isFavorite(this, fileItem)

        // [蹂寃? ?대뜑/?뚯씪 援щ텇 ?놁씠 利먭꺼李얘린 硫붾돱 ?쒖꽦??
        favItem.isVisible = true
        favItem.title = if (isFav) getString(R.string.favorite_remove) else getString(R.string.menu_favorite_add)

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
                else -> false
            }
        }
        popup.show()
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

        tvName.text = file.name
        tvPath.text = file.absolutePath

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

