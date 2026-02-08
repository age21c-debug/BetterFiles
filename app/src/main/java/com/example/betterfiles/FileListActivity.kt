package com.example.betterfiles

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaScannerConnection // [복구됨] 이 줄이 빠져서 에러가 났습니다
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

    private lateinit var adapter: FileAdapter
    private lateinit var repository: FileRepository

    // 드로어(사이드 메뉴) 관련 변수
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView

    // 데이터 로딩 작업 관리
    private var loadJob: Job? = null

    // 검색 작업 관리
    private var searchJob: Job? = null
    private var isSearchMode: Boolean = false
    private var currentSearchQuery: String = ""

    // 선택 모드 관리 변수
    private var isSelectionMode: Boolean = false

    // 네비게이션 변수
    private var currentPath: String = ""
    private var currentMode: String = "folder"
    private lateinit var rootPath: String
    private lateinit var rootTitle: String

    // 정렬 설정 변수
    private lateinit var prefs: SharedPreferences
    private var currentSortMode = "name"
    private var isAscending = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_list)

        repository = FileRepository(this)
        prefs = getSharedPreferences("BetterFilesPrefs", Context.MODE_PRIVATE)

        // 1. 뷰 초기화
        drawerLayout = findViewById(R.id.drawerLayout)
        navView = findViewById(R.id.navView)
        val rvFiles: RecyclerView = findViewById(R.id.rvFiles)
        val btnBack: ImageView = findViewById(R.id.btnBack)

        val intentTitle = intent.getStringExtra("title") ?: "파일"
        currentMode = intent.getStringExtra("mode") ?: "folder"
        val intentPath = intent.getStringExtra("path") ?: Environment.getExternalStorageDirectory().absolutePath

        rootTitle = intentTitle
        rootPath = intentPath
        currentPath = intentPath

        loadSavedSortSettings()

        // 2. 어댑터 설정
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

        // 3. 드로어 설정
        setupDrawer()

        // 4. 이벤트 설정
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
                    closeSearchMode()
                } else {
                    handleBackAction()
                }
            }
        })

        loadData(currentMode, rootPath)
    }

    // ▼▼▼ 드로어(즐겨찾기) 관련 로직 ▼▼▼

    private fun setupDrawer() {
        // 1. 기본 틴트(색상 덮어쓰기) 제거 -> 우리가 원하는 색(노란색, 썸네일 등)을 표시하기 위함
        navView.itemIconTintList = null

        // 2. 상단 고정 메뉴(내장 메모리, 다운로드) 아이콘을 회색으로 수동 설정
        val menu = navView.menu
        val greyColor = Color.parseColor("#757575") // 기본 회색

        val internalItem = menu.findItem(R.id.nav_internal_storage)
        internalItem?.icon?.mutate()?.setTint(greyColor)

        val downloadItem = menu.findItem(R.id.nav_download)
        downloadItem?.icon?.mutate()?.setTint(greyColor)

        // 3. 클릭 리스너 (기존 코드와 동일)
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_internal_storage -> {
                    loadData("folder", rootPath)
                }
                R.id.nav_download -> {
                    val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                    loadData("folder", downloadPath)
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
            val item = favoritesGroup.add(0, 0, 0, "(즐겨찾기 없음)")
            item.isEnabled = false
        } else {
            favorites.forEachIndexed { index, path ->
                val file = File(path)
                val item = favoritesGroup.add(0, index + 100, 0, file.name)

                if (file.isDirectory) {
                    // [수정됨] 폴더: 파란색 -> 노란색 (#FFC107) 변경
                    val drawable = getDrawable(R.drawable.ic_folder_solid)?.mutate()
                    drawable?.setTint(Color.parseColor("#FFC107")) // 노란색 적용
                    item.icon = drawable

                    item.setOnMenuItemClickListener {
                        loadData("folder", path)
                        drawerLayout.closeDrawer(GravityCompat.START)
                        true
                    }
                } else {
                    // [파일] 우선 기본 아이콘 및 색상 설정
                    val iconRes = getFileIconResource(file.name)
                    val iconColor = getFileIconColor(file.name) ?: Color.GRAY
                    val drawable = getDrawable(iconRes)?.mutate()
                    drawable?.setTint(iconColor)
                    item.icon = drawable

                    // [추가됨] 썸네일: 이미지/비디오는 비동기로 로딩하여 아이콘 교체
                    if (isImageFile(file.name) || isVideoFile(file.name)) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val thumbnail = loadThumbnail(file) // 아래에 추가할 함수 호출
                            if (thumbnail != null) {
                                withContext(Dispatchers.Main) {
                                    // 둥근 모서리 썸네일 생성
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

    // [추가] 썸네일 로딩 함수
    // [추가] 썸네일 로딩 헬퍼 함수
    private fun loadThumbnail(file: File): Bitmap? {
        return try {
            val size = Size(144, 144) // 메뉴 아이콘에 적당한 크기
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createImageThumbnail(file, size, null)
            } else {
                // 구버전 호환 (간단한 비트맵 디코딩)
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
    // ▲▲▲ 드로어 로직 끝 ▲▲▲


    private fun handleFileClick(fileItem: FileItem) {
        val file = File(fileItem.path)
        if (file.extension.equals("zip", ignoreCase = true)) {
            showUnzipDialog(file)
        } else {
            openFile(fileItem)
        }
    }

    // [함수 1] 메인 리스트에서 클릭 시 (FileItem 사용)
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
            Toast.makeText(this, "이 파일을 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // [함수 2] 즐겨찾기 등에서 파일 경로만으로 실행 (File 사용)
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
            Toast.makeText(this, "이 파일을 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // [필수] 파일 확장자로 MIME Type 찾기
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
        tvSummary.text = "정보 읽는 중..."

        val dialog = AlertDialog.Builder(this)
            .setTitle("압축 해제")
            .setView(view)
            .setPositiveButton("해제") { _, _ ->
                performUnzip(zipFile)
            }
            .setNegativeButton("취소", null)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val info = ZipManager.getZipInfo(zipFile)

                withContext(Dispatchers.Main) {
                    val sizeStr = Formatter.formatFileSize(this@FileListActivity, info.totalSize)
                    tvSummary.text = "총 ${info.fileCount}개 파일 • 해제 시 약 $sizeStr"

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
                        moreView.text = "...외 ${info.fileCount - 10}개 항목"
                        moreView.setPadding(8, 16, 8, 8)
                        moreView.setTextColor(getColor(android.R.color.darker_gray))
                        container.addView(moreView)
                    } else if (info.fileCount == 0) {
                        val emptyView = TextView(this@FileListActivity)
                        emptyView.text = "내용 없음"
                        emptyView.setPadding(8, 16, 8, 8)
                        container.addView(emptyView)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    tvSummary.text = "정보를 읽을 수 없습니다."
                    val errorView = TextView(this@FileListActivity)
                    errorView.text = "Error: ${e.message}"
                    container.addView(errorView)
                }
            }
        }
    }

    private fun getFileIconResource(fileName: String): Int {
        if (fileName.endsWith("/")) return R.drawable.ic_folder_solid

        val lower = fileName.lowercase(Locale.getDefault())
        return when {
            isImageFile(lower) -> R.drawable.ic_image_file
            isApkFile(lower) -> R.drawable.ic_android_file
            isVideoFile(lower) -> R.drawable.ic_video
            isPdfFile(lower) -> R.drawable.ic_pdf
            isVoiceFile(lower) -> R.drawable.ic_mic
            isAudioFile(lower) -> R.drawable.ic_music_note
            isZipFile(lower) -> R.drawable.ic_zip
            else -> R.drawable.ic_file
        }
    }

    private fun getFileIconColor(fileName: String): Int? {
        if (fileName.endsWith("/")) return null

        val lower = fileName.lowercase(Locale.getDefault())
        return when {
            isImageFile(lower) -> Color.parseColor("#FFA000")
            isApkFile(lower) -> Color.parseColor("#3DDC84")
            isVideoFile(lower) -> Color.parseColor("#1565C0")
            isPdfFile(lower) -> Color.parseColor("#F44336")
            isVoiceFile(lower) -> Color.parseColor("#009688")
            isAudioFile(lower) -> Color.parseColor("#9C27B0")
            isZipFile(lower) -> Color.parseColor("#FFC107")
            else -> Color.parseColor("#5F6368")
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
                    Toast.makeText(this@FileListActivity, "압축 해제 완료", Toast.LENGTH_SHORT).show()
                    MediaScannerConnection.scanFile(this@FileListActivity, arrayOf(extractDir.absolutePath), null, null)
                    loadData(currentMode, currentPath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FileListActivity, "압축 해제 실패: ${e.message}", Toast.LENGTH_SHORT).show()
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
                tvPasteInfo.text = "$count 개 항목 이동 대기 중..."
                btnPaste.text = "여기로 이동"
            } else {
                tvPasteInfo.text = "$count 개 항목 복사 대기 중..."
                btnPaste.text = "여기에 복사"
            }

            val sourceParentPath = FileClipboard.files.firstOrNull()?.parent

            if (isMove && sourceParentPath == currentPath) {
                btnPaste.isEnabled = false
                btnPaste.alpha = 0.5f
            } else {
                btnPaste.isEnabled = true
                btnPaste.alpha = 1.0f
            }

        } else {
            layoutPasteBar.visibility = View.GONE
        }
    }

    private fun performPaste() {
        val targetDir = File(currentPath)
        if (!targetDir.canWrite()) {
            Toast.makeText(this, "이 폴더에는 쓸 수 없습니다.", Toast.LENGTH_SHORT).show()
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
                    val msg = if (FileClipboard.isMove) "이동 완료" else "복사 완료"
                    Toast.makeText(this@FileListActivity, "$successCount 개 $msg", Toast.LENGTH_SHORT).show()
                    MediaScannerConnection.scanFile(this@FileListActivity, pathsToScan.toTypedArray(), null, null)
                    FileClipboard.clear()
                    updatePasteBarUI()
                    loadData(currentMode, currentPath)
                } else {
                    Toast.makeText(this@FileListActivity, "작업에 실패했습니다.", Toast.LENGTH_SHORT).show()
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

        val action = if (isMove) "이동" else "복사"
        Toast.makeText(this, "$action 할 위치로 가서 '$action' 버튼을 누르세요.", Toast.LENGTH_SHORT).show()
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
        detailsItem.isVisible = selectedItems.size == 1

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
            File(currentPath).name // 현재 폴더 이름
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
            .setTitle("압축하기")
            .setMessage("${selectedItems.size}개 항목을 압축합니다.")
            .setView(container)
            .setPositiveButton("압축") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    performZip(selectedItems, name)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun performZip(items: List<FileItem>, zipName: String) {
        val targetDir = File(currentPath)
        val finalName = if (zipName.endsWith(".zip", ignoreCase = true)) zipName else "$zipName.zip"
        val zipFile = getUniqueFile(targetDir, finalName)

        val filesToZip = items.map { File(it.path) }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ZipManager.zip(filesToZip, zipFile)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FileListActivity, "압축 완료: ${zipFile.name}", Toast.LENGTH_SHORT).show()
                    MediaScannerConnection.scanFile(this@FileListActivity, arrayOf(zipFile.absolutePath), null, null)

                    closeSelectionMode()
                    loadData(currentMode, currentPath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FileListActivity, "압축 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
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
        tvSelectionCount.text = "${count}개 선택됨"
    }

    private fun showDeleteSelectionDialog() {
        val selectedItems = adapter.currentList.filter { it.isSelected }
        if (selectedItems.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("파일 삭제")
            .setMessage("${selectedItems.size}개의 항목을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ -> deleteSelectedFiles(selectedItems) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteSelectedFiles(items: List<FileItem>) {
        var deletedCount = 0
        val pathsToScan = mutableListOf<String>()

        items.forEach { item ->
            val file = File(item.path)
            if (file.exists() && file.delete()) {
                deletedCount++
                pathsToScan.add(file.absolutePath)
            }
        }

        if (deletedCount > 0) {
            Toast.makeText(this, "${deletedCount}개 삭제되었습니다.", Toast.LENGTH_SHORT).show()
            MediaScannerConnection.scanFile(this, pathsToScan.toTypedArray(), null, null)
            closeSelectionMode()
            loadData(currentMode, currentPath)
        } else {
            Toast.makeText(this, "삭제 실패. 권한을 확인하세요.", Toast.LENGTH_SHORT).show()
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
            startActivity(Intent.createChooser(intent, "파일 공유"))
        } catch (e: Exception) {
            Toast.makeText(this, "공유할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // --- 정렬, 검색, 데이터 로드 ---
    private fun loadSavedSortSettings() {
        val sortKey = "sort_mode_$currentMode"
        val ascKey = "is_ascending_$currentMode"
        val defaultSortMode = if (currentMode == "folder") "name" else "date"
        val defaultIsAscending = currentMode == "folder"

        currentSortMode = prefs.getString(sortKey, defaultSortMode) ?: defaultSortMode
        isAscending = prefs.getBoolean(ascKey, defaultIsAscending)
    }

    private fun saveSortSettings() {
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
            isSearchMode = true
            currentSortMode = "name"
            isAscending = true

            headerNormal.visibility = View.GONE
            headerSearch.visibility = View.VISIBLE
            etSearch.setText("")
            etSearch.requestFocus()

            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        }

        btnCloseSearch.setOnClickListener { closeSearchMode() }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s.toString()
                performSearch(currentSearchQuery)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()
        if (query.isEmpty()) {
            lifecycleScope.launch {
                val rawFiles = when (currentMode) {
                    "image" -> repository.getAllImages()
                    "video" -> repository.getAllVideos()
                    "audio" -> repository.getAllAudio()
                    "download" -> repository.getDownloads()
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
                saveSortSettings()
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
                tvEmptyTitle.text = "검색 결과가 없어요"
                tvEmptyMessage.text = "다른 검색어로 시도해 보세요."
                ivEmptyIcon.setImageResource(R.drawable.ic_search)
            } else {
                tvEmptyTitle.text = "폴더가 비어있어요"
                tvEmptyMessage.text = "파일이 없습니다."
                ivEmptyIcon.setImageResource(R.drawable.ic_folder_solid)
            }
        } else {
            rvFiles.visibility = View.VISIBLE
            layoutEmpty.visibility = View.GONE
            adapter.submitList(sortedFiles)
        }

        if (isSearchResult) {
            tvFileCount.text = "${sortedFiles.size}개 검색됨"
        } else {
            if (currentMode == "folder") {
                val folderCount = sortedFiles.count { it.isDirectory }
                val fileCount = sortedFiles.count { !it.isDirectory }

                val textParts = mutableListOf<String>()
                if (folderCount > 0) textParts.add("${folderCount}개 폴더")
                if (fileCount > 0) textParts.add("${fileCount}개 파일")

                if (textParts.isEmpty()) {
                    tvFileCount.text = "0개 항목"
                } else {
                    tvFileCount.text = textParts.joinToString(" • ")
                }
            } else {
                tvFileCount.text = "${sortedFiles.size}개 파일"
            }
        }
    }

    // loadData: 경로 표시 및 붙여넣기 바 UI 갱신 호출
    private fun loadData(mode: String, path: String) {
        // 이전 로딩 작업 취소
        loadJob?.cancel()

        currentMode = mode
        currentPath = path

        val tvTitle = findViewById<TextView>(R.id.tvPageTitle)
        val btnNewFolder = findViewById<ImageView>(R.id.btnNewFolder)

        val scrollViewPath = findViewById<HorizontalScrollView>(R.id.scrollViewPath)
        val tvPathIndicator = findViewById<TextView>(R.id.tvPathIndicator)

        if (mode == "folder") {
            btnNewFolder.visibility = View.VISIBLE

            if (path == rootPath) {
                tvTitle.text = rootTitle
                if (scrollViewPath != null) scrollViewPath.visibility = View.GONE
            } else {
                tvTitle.text = File(path).name

                if (scrollViewPath != null && tvPathIndicator != null) {
                    scrollViewPath.visibility = View.VISIBLE

                    val relativePath = path.removePrefix(rootPath)
                    val displayPath = "내장 메모리" + relativePath.replace("/", " > ")
                    tvPathIndicator.text = displayPath

                    scrollViewPath.post {
                        scrollViewPath.fullScroll(View.FOCUS_RIGHT)
                    }
                }
            }
        } else {
            tvTitle.text = rootTitle
            btnNewFolder.visibility = View.GONE
            if (scrollViewPath != null) scrollViewPath.visibility = View.GONE
        }

        updatePasteBarUI()

        // [추가] 헤더 아이콘 갱신 (햄버거 <-> 뒤로가기)
        updateHeaderIcon()

        // 새로운 로딩 작업 시작
        loadJob = lifecycleScope.launch {
            val rawFiles = when (mode) {
                "image" -> repository.getAllImages()
                "video" -> repository.getAllVideos()
                "audio" -> repository.getAllAudio()
                "download" -> repository.getDownloads()
                else -> repository.getFilesByPath(path)
            }
            applySortAndSubmit(rawFiles, isSearchResult = false)
        }
    }

    // [수정됨] 개별 파일 메뉴 옵션 핸들러
    private fun showFileOptionMenu(view: View, fileItem: FileItem) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.menu_file_item, popup.menu)

        // 즐겨찾기 메뉴 설정
        val favItem = popup.menu.findItem(R.id.action_favorite)
        val isFav = FavoritesManager.isFavorite(this, fileItem.path)

        // [변경] 폴더/파일 구분 없이 즐겨찾기 메뉴 활성화
        favItem.isVisible = true
        favItem.title = if (isFav) "즐겨찾기 해제" else "즐겨찾기 추가"

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_favorite -> {
                    if (FavoritesManager.isFavorite(this, fileItem.path)) {
                        FavoritesManager.remove(this, fileItem.path)
                        Toast.makeText(this, "즐겨찾기 해제됨", Toast.LENGTH_SHORT).show()
                    } else {
                        FavoritesManager.add(this, fileItem.path)
                        Toast.makeText(this, "즐겨찾기 추가됨", Toast.LENGTH_SHORT).show()
                    }
                    updateDrawerMenu() // 메뉴 갱신
                    true
                }
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
            tvType.text = "폴더"
            val items = file.list()?.size ?: 0
            tvSize.text = "$items 항목"
        } else {
            val extension = file.extension.uppercase()
            tvType.text = if (extension.isNotEmpty()) "$extension 파일" else "파일"
            tvSize.text = Formatter.formatFileSize(this, file.length())
        }

        AlertDialog.Builder(this)
            .setTitle("상세 정보")
            .setView(view)
            .setPositiveButton("확인", null)
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
            .setTitle("이름 변경")
            .setView(container)
            .setPositiveButton("변경") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != fileItem.name) renameFile(fileItem, newName)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun renameFile(fileItem: FileItem, newName: String) {
        val oldFile = File(fileItem.path)
        val newFile = File(oldFile.parent, newName)
        if (newFile.exists()) {
            Toast.makeText(this, "이미 같은 이름의 파일이 존재합니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (oldFile.renameTo(newFile)) {
            Toast.makeText(this, "이름이 변경되었습니다.", Toast.LENGTH_SHORT).show()
            MediaScannerConnection.scanFile(this, arrayOf(oldFile.absolutePath, newFile.absolutePath), null, null)
            loadData(currentMode, currentPath)
        } else {
            Toast.makeText(this, "이름 변경 실패. 권한을 확인하세요.", Toast.LENGTH_SHORT).show()
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
            startActivity(Intent.createChooser(intent, "파일 공유"))
        } catch (e: Exception) {
            Toast.makeText(this, "공유할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteDialog(fileItem: FileItem) {
        AlertDialog.Builder(this)
            .setTitle("파일 삭제")
            .setMessage("'${fileItem.name}'을(를) 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ -> deleteFile(fileItem) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteFile(fileItem: FileItem) {
        val file = File(fileItem.path)
        if (file.exists()) {
            if (file.delete()) {
                Toast.makeText(this, "삭제되었습니다.", Toast.LENGTH_SHORT).show()
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
                loadData(currentMode, currentPath)
            } else {
                Toast.makeText(this, "삭제 실패. 권한을 확인하세요.", Toast.LENGTH_SHORT).show()
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
        editText.hint = "새 폴더 이름"
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
            .setTitle("새 폴더 만들기")
            .setView(container)
            .setPositiveButton("생성") { _, _ ->
                val folderName = editText.text.toString().trim()
                if (folderName.isNotEmpty()) createFolder(folderName)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun createFolder(name: String) {
        val newFolder = File(currentPath, name)
        if (newFolder.exists()) {
            Toast.makeText(this, "이미 존재하는 폴더입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (newFolder.mkdirs()) {
            Toast.makeText(this, "폴더가 생성되었습니다.", Toast.LENGTH_SHORT).show()
            MediaScannerConnection.scanFile(this, arrayOf(newFolder.absolutePath), null, null)
            loadData(currentMode, currentPath)
        } else {
            Toast.makeText(this, "폴더 생성 실패.", Toast.LENGTH_SHORT).show()
        }
    }
}