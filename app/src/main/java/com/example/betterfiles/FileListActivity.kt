package com.example.betterfiles

import android.app.AlertDialog
import android.content.Intent
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
    private var currentPath: String = ""
    private var currentMode: String = "folder"
    private lateinit var rootPath: String
    private lateinit var rootTitle: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_list)

        repository = FileRepository(this)

        val intentTitle = intent.getStringExtra("title") ?: "파일"
        currentMode = intent.getStringExtra("mode") ?: "folder"
        val intentPath = intent.getStringExtra("path") ?: Environment.getExternalStorageDirectory().absolutePath

        rootTitle = intentTitle
        rootPath = intentPath
        currentPath = intentPath

        val rvFiles: RecyclerView = findViewById(R.id.rvFiles)
        val btnBack: ImageView = findViewById(R.id.btnBack)

        // ▼▼▼ 어댑터 생성 부분 변경 (onMoreClick 추가됨) ▼▼▼
        adapter = FileAdapter(
            onClick = { fileItem ->
                if (fileItem.isDirectory) {
                    loadData("folder", fileItem.path)
                } else {
                    openFile(fileItem)
                }
            },
            onMoreClick = { view, fileItem ->
                showFileOptionMenu(view, fileItem) // 팝업 메뉴 띄우기
            }
        )
        // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = adapter

        btnBack.setOnClickListener { handleBackAction() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBackAction() }
        })

        loadData(currentMode, rootPath)
    }

    // ▼▼▼ 팝업 메뉴 및 기능 구현 ▼▼▼
    private fun showFileOptionMenu(view: View, fileItem: FileItem) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.menu_file_item, popup.menu)

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_share -> {
                    shareFile(fileItem)
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
            .setPositiveButton("삭제") { _, _ ->
                deleteFile(fileItem)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteFile(fileItem: FileItem) {
        val file = File(fileItem.path)
        if (file.exists()) {
            val deleted = file.delete()
            if (deleted) {
                Toast.makeText(this, "삭제되었습니다.", Toast.LENGTH_SHORT).show()

                // 갤러리/시스템에 삭제 사실 알리기 (미디어 스캔)
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)

                // 목록 새로고침
                loadData(currentMode, currentPath)
            } else {
                Toast.makeText(this, "삭제 실패. 권한을 확인하세요.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

    private fun handleBackAction() {
        if (currentMode == "folder" && currentPath != rootPath) {
            val parentFile = File(currentPath).parentFile
            if (parentFile != null) {
                loadData("folder", parentFile.absolutePath)
            } else { finish() }
        } else { finish() }
    }

    private fun loadData(mode: String, path: String) {
        currentMode = mode
        currentPath = path
        val tvTitle = findViewById<TextView>(R.id.tvPageTitle)
        val tvFileCount = findViewById<TextView>(R.id.tvFileCount)

        if (mode == "folder") {
            if (path == rootPath) tvTitle.text = rootTitle else tvTitle.text = File(path).name
        } else { tvTitle.text = rootTitle }

        lifecycleScope.launch {
            val files = when (mode) {
                "image" -> repository.getAllImages()
                "video" -> repository.getAllVideos()
                "audio" -> repository.getAllAudio()
                "download" -> repository.getDownloads()
                else -> repository.getFilesByPath(path)
            }

            val rvFiles = findViewById<RecyclerView>(R.id.rvFiles)
            val layoutEmpty = findViewById<LinearLayout>(R.id.layoutEmpty)

            if (files.isEmpty()) {
                rvFiles.visibility = View.GONE
                layoutEmpty.visibility = View.VISIBLE
            } else {
                rvFiles.visibility = View.VISIBLE
                layoutEmpty.visibility = View.GONE
                adapter.submitList(files)
            }
            tvFileCount.text = "${files.size}개 파일"
        }
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