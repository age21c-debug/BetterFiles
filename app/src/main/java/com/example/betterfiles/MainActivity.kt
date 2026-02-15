package com.example.betterfiles

import android.app.AlertDialog
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var repository: FileRepository
    private lateinit var recentAdapter: RecentFileAdapter

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

        val btnInternal: View = findViewById(R.id.btnInternalStorage)
        val btnImages: View = findViewById(R.id.btnImages)
        val btnVideos: View = findViewById(R.id.btnVideos)
        val btnAudio: View = findViewById(R.id.btnAudio)
        val btnDownloads: View = findViewById(R.id.btnDownloads)
        val tvRecentSeeAll: TextView = findViewById(R.id.tvRecentSeeAll)

        setupRecentSection()

        btnInternal.setOnClickListener {
            openActivity(
                mode = "folder",
                path = Environment.getExternalStorageDirectory().absolutePath,
                title = getString(R.string.internal_storage)
            )
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

        btnDownloads.setOnClickListener {
            openActivity(mode = "download", title = getString(R.string.downloads))
        }

        tvRecentSeeAll.setOnClickListener {
            openActivity(
                mode = "recent",
                title = getString(R.string.recent_files),
                path = Environment.getExternalStorageDirectory().absolutePath
            )
        }
    }

    override fun onResume() {
        super.onResume()
        loadRecentFiles()
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

    private fun openActivity(mode: String, title: String, path: String = "") {
        val intent = Intent(this, FileListActivity::class.java)
        intent.putExtra("mode", mode)
        intent.putExtra("title", title)
        intent.putExtra("path", path)
        startActivity(intent)
    }
}
