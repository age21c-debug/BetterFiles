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
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class FileListActivity : AppCompatActivity() {

    private lateinit var adapter: FileAdapter
    private lateinit var repository: FileRepository

    // 검색 작업 관리
    private var searchJob: Job? = null
    private var isSearchMode: Boolean = false
    private var currentSearchQuery: String = ""

    // [추가] 선택 모드 관리 변수
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
                    closeSearchMode()
                    loadData("folder", fileItem.path)
                } else {
                    openFile(fileItem)
                }
            },
            onMoreClick = { view, fileItem ->
                showFileOptionMenu(view, fileItem)
            },
            onLongClick = { fileItem ->
                // [추가] 롱클릭 시 선택 모드 시작
                startSelectionMode(fileItem)
            },
            onSelectionChanged = {
                // [추가] 선택 개수 변경 시 UI 업데이트
                updateSelectionUI()
            }
        )
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = adapter

        btnBack.setOnClickListener { handleBackAction() }

        setupHeaderEvents()

        // [추가] 선택 모드 헤더 버튼들 이벤트 연결
        setupSelectionEvents()

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

    // ▼▼▼ [수정] 선택 모드 관련 로직 (전체 선택 추가됨) ▼▼▼

    private fun setupSelectionEvents() {
        val btnCloseSelection: ImageView = findViewById(R.id.btnCloseSelection)
        val btnSelectAll: ImageView = findViewById(R.id.btnSelectAll) // [추가] 전체선택 버튼 ID 연결
        val btnShareSelection: ImageView = findViewById(R.id.btnShareSelection)
        val btnDeleteSelection: ImageView = findViewById(R.id.btnDeleteSelection)

        btnCloseSelection.setOnClickListener { closeSelectionMode() }
        btnSelectAll.setOnClickListener { toggleSelectAll() } // [추가] 클릭 리스너 연결
        btnShareSelection.setOnClickListener { shareSelectedFiles() }
        btnDeleteSelection.setOnClickListener { showDeleteSelectionDialog() }
    }

    // [추가] 전체 선택 / 해제 토글 로직
    private fun toggleSelectAll() {
        val currentList = adapter.currentList
        if (currentList.isEmpty()) return

        // 1. 현재 모든 항목이 선택되어 있는지 확인
        val isAllSelected = currentList.all { it.isSelected }

        // 2. 상태 반전 (모두 선택됨 -> 모두 해제 / 아니면 -> 모두 선택)
        val newState = !isAllSelected

        // 3. 리스트의 모든 항목 상태 변경
        currentList.forEach { it.isSelected = newState }

        // 4. 화면 갱신
        adapter.notifyDataSetChanged()
        updateSelectionUI()
    }

    private fun startSelectionMode(initialItem: FileItem) {
        if (isSelectionMode) return
        isSelectionMode = true

        // 롱클릭한 아이템을 먼저 선택 상태로 만듦
        initialItem.isSelected = true

        // 어댑터 모드 변경 및 갱신 (체크박스 보이기)
        adapter.isSelectionMode = true
        adapter.notifyDataSetChanged()

        // 헤더 교체 (일반/검색 -> 선택 헤더)
        findViewById<LinearLayout>(R.id.headerNormal).visibility = View.GONE
        findViewById<LinearLayout>(R.id.headerSearch).visibility = View.GONE
        findViewById<LinearLayout>(R.id.headerSelection).visibility = View.VISIBLE

        updateSelectionUI()
    }

    private fun closeSelectionMode() {
        isSelectionMode = false

        // 모든 항목 선택 해제
        adapter.currentList.forEach { it.isSelected = false }
        adapter.isSelectionMode = false
        adapter.notifyDataSetChanged()

        // 헤더 복구
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

        // MIME 타입 결정 (모두 이미지면 image/*, 아니면 */*)
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
    // ▲▲▲ 선택 모드 관련 로직 끝 ▲▲▲

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
            tvFileCount.text = "${sortedFiles.size}개 파일"
        }
    }

    private fun loadData(mode: String, path: String) {
        currentMode = mode
        currentPath = path
        val tvTitle = findViewById<TextView>(R.id.tvPageTitle)
        val btnNewFolder = findViewById<ImageView>(R.id.btnNewFolder)

        if (mode == "folder") {
            if (path == rootPath) tvTitle.text = rootTitle else tvTitle.text = File(path).name
            btnNewFolder.visibility = View.VISIBLE
        } else {
            tvTitle.text = rootTitle
            btnNewFolder.visibility = View.GONE
        }

        lifecycleScope.launch {
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
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenameDialog(fileItem: FileItem) {
        val editText = android.widget.EditText(this)
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
        val editText = android.widget.EditText(this)
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