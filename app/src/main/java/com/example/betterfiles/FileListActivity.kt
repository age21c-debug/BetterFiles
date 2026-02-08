package com.example.betterfiles

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaScannerConnection
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
    // 검색 모드 상태 확인 변수
    private var isSearchMode: Boolean = false
    private var currentSearchQuery: String = "" // 현재 검색어 저장용

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

        // 1. 초기 설정 불러오기
        prefs = getSharedPreferences("BetterFilesPrefs", Context.MODE_PRIVATE)
        loadSavedSortSettings()

        // Intent 데이터 수신
        val intentTitle = intent.getStringExtra("title") ?: "파일"
        currentMode = intent.getStringExtra("mode") ?: "folder"
        val intentPath = intent.getStringExtra("path") ?: Environment.getExternalStorageDirectory().absolutePath

        rootTitle = intentTitle
        rootPath = intentPath
        currentPath = intentPath

        val rvFiles: RecyclerView = findViewById(R.id.rvFiles)
        val btnBack: ImageView = findViewById(R.id.btnBack)

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
            }
        )
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = adapter

        btnBack.setOnClickListener { handleBackAction() }

        setupHeaderEvents()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSearchMode) {
                    closeSearchMode()
                } else {
                    handleBackAction()
                }
            }
        })

        loadData(currentMode, rootPath)
    }

    private fun loadSavedSortSettings() {
        currentSortMode = prefs.getString("sort_mode", "name") ?: "name"
        isAscending = prefs.getBoolean("is_ascending", true)
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

        // 검색 버튼 클릭
        btnSearch.setOnClickListener {
            isSearchMode = true

            // 검색 진입 시: 정렬을 "이름 오름차순"으로 강제 초기화 (임시)
            currentSortMode = "name"
            isAscending = true

            headerNormal.visibility = View.GONE
            headerSearch.visibility = View.VISIBLE
            etSearch.setText("")
            etSearch.requestFocus()

            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        }

        // 닫기 버튼 클릭
        btnCloseSearch.setOnClickListener {
            closeSearchMode()
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

    // ▼▼▼ [수정] 모드별 검색 로직 분기 처리 ▼▼▼
    private fun performSearch(query: String) {
        searchJob?.cancel()

        if (query.isEmpty()) {
            lifecycleScope.launch {
                // 검색어 없을 때: 모드에 맞는 전체 목록 로드
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
            // ▼ 모드에 따라 검색 방식 결정
            val results = when (currentMode) {
                "image" -> repository.getAllImages(query) // DB 검색
                "video" -> repository.getAllVideos(query) // DB 검색
                "audio" -> repository.getAllAudio(query) // DB 검색
                "download" -> {
                    // 다운로드 폴더는 '재귀 검색'으로 처리 (전략대로)
                    val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                    repository.searchRecursive(downloadPath, query)
                }
                "folder" -> repository.searchRecursive(currentPath, query) // 기존 재귀 검색
                else -> repository.searchRecursive(currentPath, query)
            }

            applySortAndSubmit(results, isSearchResult = true)
        }
    }

    private fun closeSearchMode() {
        isSearchMode = false
        loadSavedSortSettings() // 설정 복구

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
                val editor = prefs.edit()
                editor.putString("sort_mode", currentSortMode)
                editor.putBoolean("is_ascending", isAscending)
                editor.apply()
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

    // --- (이하 나머지 팝업 메뉴, 파일 조작 함수들은 기존과 동일) ---
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