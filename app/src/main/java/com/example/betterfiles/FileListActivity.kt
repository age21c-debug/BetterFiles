package com.example.betterfiles

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.view.View
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
import kotlinx.coroutines.launch
import java.io.File

class FileListActivity : AppCompatActivity() {

    private lateinit var adapter: FileAdapter
    private lateinit var repository: FileRepository

    // 네비게이션 변수
    private var currentPath: String = ""
    private var currentMode: String = "folder"
    private lateinit var rootPath: String
    private lateinit var rootTitle: String

    // 정렬 설정 변수 (SharedPreferences)
    private lateinit var prefs: SharedPreferences
    private var currentSortMode = "name" // 기본값: 이름순
    private var isAscending = true       // 기본값: 오름차순 (true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_list)

        repository = FileRepository(this)

        // 1. 저장된 정렬 설정 불러오기
        prefs = getSharedPreferences("BetterFilesPrefs", Context.MODE_PRIVATE)
        currentSortMode = prefs.getString("sort_mode", "name") ?: "name"
        isAscending = prefs.getBoolean("is_ascending", true)

        // Intent 데이터 수신
        val intentTitle = intent.getStringExtra("title") ?: "파일"
        currentMode = intent.getStringExtra("mode") ?: "folder"
        val intentPath = intent.getStringExtra("path") ?: Environment.getExternalStorageDirectory().absolutePath

        rootTitle = intentTitle
        rootPath = intentPath
        currentPath = intentPath

        val rvFiles: RecyclerView = findViewById(R.id.rvFiles)
        val btnBack: ImageView = findViewById(R.id.btnBack)
        val btnSort: ImageView = findViewById(R.id.btnSort)

        adapter = FileAdapter(
            onClick = { fileItem ->
                if (fileItem.isDirectory) {
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

        // 정렬 버튼 클릭
        btnSort.setOnClickListener { view -> showSortMenu(view) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBackAction() }
        })

        loadData(currentMode, rootPath)
    }

    // ▼▼▼ 정렬 메뉴 및 저장 로직 ▼▼▼
    private fun showSortMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.menu_sort, popup.menu)

        // 1. 현재 설정값에 따라 체크박스 상태 설정
        when (currentSortMode) {
            "name" -> popup.menu.findItem(R.id.sort_name).isChecked = true
            "size" -> popup.menu.findItem(R.id.sort_size).isChecked = true
            else -> popup.menu.findItem(R.id.sort_date).isChecked = true
        }

        if (isAscending) {
            popup.menu.findItem(R.id.order_asc).isChecked = true
        } else {
            popup.menu.findItem(R.id.order_desc).isChecked = true
        }

        // 2. 메뉴 클릭 이벤트 처리
        popup.setOnMenuItemClickListener { menuItem ->
            val editor = prefs.edit()
            menuItem.isChecked = true

            when (menuItem.itemId) {
                // 기준 변경
                R.id.sort_date -> {
                    currentSortMode = "date"
                    editor.putString("sort_mode", "date")
                }
                R.id.sort_name -> {
                    currentSortMode = "name"
                    editor.putString("sort_mode", "name")
                }
                R.id.sort_size -> {
                    currentSortMode = "size"
                    editor.putString("sort_mode", "size")
                }
                // 순서 변경
                R.id.order_asc -> {
                    isAscending = true
                    editor.putBoolean("is_ascending", true)
                }
                R.id.order_desc -> {
                    isAscending = false
                    editor.putBoolean("is_ascending", false)
                }
                else -> return@setOnMenuItemClickListener false
            }

            editor.apply()
            loadData(currentMode, currentPath)
            true
        }
        popup.show()
    }

    private fun loadData(mode: String, path: String) {
        currentMode = mode
        currentPath = path
        val tvTitle = findViewById<TextView>(R.id.tvPageTitle)

        if (mode == "folder") {
            if (path == rootPath) tvTitle.text = rootTitle else tvTitle.text = File(path).name
        } else { tvTitle.text = rootTitle }

        lifecycleScope.launch {
            val rawFiles = when (mode) {
                "image" -> repository.getAllImages()
                "video" -> repository.getAllVideos()
                "audio" -> repository.getAllAudio()
                "download" -> repository.getDownloads()
                else -> repository.getFilesByPath(path)
            }

            // 정렬 로직 (폴더 우선 + 사용자 설정)
            val sortedFiles = rawFiles.sortedWith(Comparator { o1, o2 ->
                // 1. 폴더 여부 비교 (폴더는 무조건 위로)
                if (o1.isDirectory != o2.isDirectory) {
                    return@Comparator if (o1.isDirectory) -1 else 1
                }

                // 2. 정렬 기준 비교
                val result = when (currentSortMode) {
                    "name" -> o1.name.lowercase().compareTo(o2.name.lowercase())
                    "size" -> o1.size.compareTo(o2.size)
                    else -> o1.dateModified.compareTo(o2.dateModified)
                }

                // 3. 오름차순/내림차순 적용
                if (isAscending) result else -result
            })

            val rvFiles = findViewById<RecyclerView>(R.id.rvFiles)
            val layoutEmpty = findViewById<LinearLayout>(R.id.layoutEmpty)
            val tvFileCount = findViewById<TextView>(R.id.tvFileCount)

            if (sortedFiles.isEmpty()) {
                rvFiles.visibility = View.GONE
                layoutEmpty.visibility = View.VISIBLE
            } else {
                rvFiles.visibility = View.VISIBLE
                layoutEmpty.visibility = View.GONE
                adapter.submitList(sortedFiles)
            }
            tvFileCount.text = "${sortedFiles.size}개 파일"
        }
    }

    // ▼▼▼ 팝업 메뉴 (이름 변경 포함) ▼▼▼
    private fun showFileOptionMenu(view: View, fileItem: FileItem) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.menu_file_item, popup.menu)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_share -> {
                    shareFile(fileItem)
                    true
                }
                // [추가] 이름 변경
                R.id.action_rename -> {
                    showRenameDialog(fileItem)
                    true
                }
                R.id.action_delete -> {
                    showDeleteDialog(fileItem)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    // ▼▼▼ [추가] 이름 변경 다이얼로그 ▼▼▼
    private fun showRenameDialog(fileItem: FileItem) {
        val editText = android.widget.EditText(this)
        editText.setText(fileItem.name)
        editText.setSingleLine()

        // 확장자 제외하고 이름만 선택(드래그) 상태로 만들기
        val dotIndex = fileItem.name.lastIndexOf('.')
        if (dotIndex > 0) {
            editText.setSelection(0, dotIndex)
        } else {
            editText.selectAll()
        }

        // 입력창 여백 설정
        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.leftMargin = 50 // px
        params.rightMargin = 50 // px
        editText.layoutParams = params
        container.addView(editText)

        AlertDialog.Builder(this)
            .setTitle("이름 변경")
            .setView(container)
            .setPositiveButton("변경") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != fileItem.name) {
                    renameFile(fileItem, newName)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ▼▼▼ [추가] 실제 파일 이름 변경 로직 ▼▼▼
    private fun renameFile(fileItem: FileItem, newName: String) {
        val oldFile = File(fileItem.path)
        val newFile = File(oldFile.parent, newName) // 같은 폴더 내 새 이름

        if (newFile.exists()) {
            Toast.makeText(this, "이미 같은 이름의 파일이 존재합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        if (oldFile.renameTo(newFile)) {
            Toast.makeText(this, "이름이 변경되었습니다.", Toast.LENGTH_SHORT).show()

            // 미디어 스캔 (갤러리 갱신: 옛날 건 지우고, 새 건 등록)
            MediaScannerConnection.scanFile(
                this,
                arrayOf(oldFile.absolutePath, newFile.absolutePath),
                null,
                null
            )

            // 목록 새로고침
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
}