package com.example.betterfiles

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.View
import android.view.inputmethod.InputMethodManager
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    // 데이터 로딩 작업 관리 (중복 로딩 방지)
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

        // 1. Intent 데이터 수신
        val intentTitle = intent.getStringExtra("title") ?: "파일"
        currentMode = intent.getStringExtra("mode") ?: "folder"
        val intentPath = intent.getStringExtra("path") ?: Environment.getExternalStorageDirectory().absolutePath

        rootTitle = intentTitle
        rootPath = intentPath
        currentPath = intentPath

        // 2. 현재 모드에 맞는 정렬 설정 불러오기
        loadSavedSortSettings()

        val rvFiles: RecyclerView = findViewById(R.id.rvFiles)
        val btnBack: ImageView = findViewById(R.id.btnBack)

        // 3. 어댑터 설정 (롱클릭 및 선택 이벤트 연결)
        adapter = FileAdapter(
            onClick = { fileItem ->
                if (fileItem.isDirectory) {
                    // 검색 모드일 때만 검색 종료 로직 실행 (불필요한 리로드 방지)
                    if (isSearchMode) {
                        closeSearchMode()
                    }
                    loadData("folder", fileItem.path)
                } else {
                    openFile(fileItem)
                }
            },
            onMoreClick = { view, fileItem ->
                showFileOptionMenu(view, fileItem)
            },
            onLongClick = { fileItem ->
                // 복사/이동 중이 아닐 때만 선택 모드 진입
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

        btnBack.setOnClickListener { handleBackAction() }

        setupHeaderEvents()
        setupSelectionEvents()

        // 붙여넣기 바 이벤트 설정
        setupPasteEvents()

        // 뒤로가기 버튼 처리 (우선순위: 선택모드 > 검색모드 > 일반)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSelectionMode) {
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

    // ▼▼▼ 복사/이동/붙여넣기 관련 로직 ▼▼▼

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

            // 1. 텍스트 설정
            if (isMove) {
                tvPasteInfo.text = "$count 개 항목 이동 대기 중..."
                btnPaste.text = "여기로 이동"
            } else {
                tvPasteInfo.text = "$count 개 항목 복사 대기 중..."
                btnPaste.text = "여기에 복사"
            }

            // 2. 같은 폴더 이동 방지 로직
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

                // 이름 중복 처리
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

    // ▲▲▲ 복사/이동/붙여넣기 로직 끝 ▲▲▲

    // ▼▼▼ 선택 모드 관련 로직 ▼▼▼

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

    // [수정됨] 선택 모드 더보기 메뉴 (개수에 따라 상세정보 표시 여부 결정)
    private fun showSelectionMoreMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.menu_selection_more, popup.menu)

        // 선택된 항목 개수 확인
        val selectedItems = adapter.currentList.filter { it.isSelected }

        // 1개일 때만 '상세 정보' 메뉴 보이기
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
                R.id.action_selection_details -> {
                    // 선택된 1개의 항목에 대해 상세 정보 표시
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
                tvFileCount.text = "${folderCount}개 폴더 • ${fileCount}개 파일"
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

    private fun showFileOptionMenu(view: View, fileItem: FileItem) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.menu_file_item, popup.menu)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_share -> { shareFile(fileItem); true }
                R.id.action_rename -> { showRenameDialog(fileItem); true }
                R.id.action_delete -> { showDeleteDialog(fileItem); true }
                R.id.action_details -> { showFileDetailsDialog(fileItem); true }
                else -> false
            }
        }
        popup.show()
    }

    // [수정됨] 상세 정보 다이얼로그 (권한 정보 제거)
    private fun showFileDetailsDialog(fileItem: FileItem) {
        val view = layoutInflater.inflate(R.layout.dialog_file_details, null)
        val file = File(fileItem.path)

        val tvName: TextView = view.findViewById(R.id.tvDetailName)
        val tvType: TextView = view.findViewById(R.id.tvDetailType)
        val tvSize: TextView = view.findViewById(R.id.tvDetailSize)
        val tvDate: TextView = view.findViewById(R.id.tvDetailDate)
        val tvPath: TextView = view.findViewById(R.id.tvDetailPath)

        // 권한 TextView 참조 제거됨

        tvName.text = file.name
        tvPath.text = file.absolutePath

        // 수정 날짜
        val dateFormat = SimpleDateFormat("yyyy. MM. dd. HH:mm", Locale.getDefault())
        tvDate.text = dateFormat.format(Date(file.lastModified()))

        // 종류 및 크기
        if (file.isDirectory) {
            tvType.text = "폴더"
            val items = file.list()?.size ?: 0
            tvSize.text = "$items 항목"
        } else {
            val extension = file.extension.uppercase()
            tvType.text = if (extension.isNotEmpty()) "$extension 파일" else "파일"
            tvSize.text = Formatter.formatFileSize(this, file.length())
        }

        // 권한 정보 설정 로직 제거됨

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