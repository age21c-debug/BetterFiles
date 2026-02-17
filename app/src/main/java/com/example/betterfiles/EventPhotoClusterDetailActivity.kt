package com.example.betterfiles

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.widget.ImageView
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class EventPhotoClusterDetailActivity : AppCompatActivity() {
    private lateinit var repository: EventPhotoBundleRepository
    private lateinit var adapter: FileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_photo_cluster_detail)

        val startMs = intent.getLongExtra("startMs", 0L)
        val endMs = intent.getLongExtra("endMs", 0L)
        if (startMs <= 0L || endMs <= 0L) {
            finish()
            return
        }

        repository = EventPhotoBundleRepository(this)
        adapter = FileAdapter(
            onClick = { openImage(it) },
            onMoreClick = { _, _ -> },
            onLongClick = { },
            onSelectionChanged = { }
        ).apply {
            isPasteMode = true
            showParentPathLine = true
        }

        findViewById<ImageView>(R.id.btnBackEventCluster).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvEventClusterTitle).text = formatDateRange(startMs, endMs)

        val recyclerView = findViewById<RecyclerView>(R.id.rvEventCluster)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        load(startMs, endMs)
    }

    private fun load(startMs: Long, endMs: Long) {
        val tvCount = findViewById<TextView>(R.id.tvEventClusterCount)
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { repository.getClusterPhotos(startMs, endMs) }
            adapter.submitList(files)
            val totalBytes = files.sumOf { it.size }
            tvCount.text = getString(
                R.string.smart_event_item_meta_format,
                files.size,
                Formatter.formatFileSize(this@EventPhotoClusterDetailActivity, totalBytes)
            )
        }
    }

    private fun openImage(fileItem: FileItem) {
        try {
            val file = File(fileItem.path)
            if (!file.exists()) {
                Toast.makeText(this, getString(R.string.error_file_not_found), Toast.LENGTH_SHORT).show()
                return
            }
            val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, fileItem.mimeType.ifBlank { "image/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.error_cannot_open), Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatDateRange(startMs: Long, endMs: Long): String {
        val locale = Locale.getDefault()
        val startCal = Calendar.getInstance().apply { timeInMillis = startMs }
        val endCal = Calendar.getInstance().apply { timeInMillis = endMs }
        val sameYear = startCal.get(Calendar.YEAR) == endCal.get(Calendar.YEAR)
        val sameMonth = sameYear && startCal.get(Calendar.MONTH) == endCal.get(Calendar.MONTH)
        val sameDay = sameMonth && startCal.get(Calendar.DAY_OF_MONTH) == endCal.get(Calendar.DAY_OF_MONTH)
        return if (locale.language == Locale.KOREAN.language) {
            when {
                sameDay -> SimpleDateFormat("M월 d일", locale).format(startMs)
                sameMonth -> {
                    val month = startCal.get(Calendar.MONTH) + 1
                    val startDay = startCal.get(Calendar.DAY_OF_MONTH)
                    val endDay = endCal.get(Calendar.DAY_OF_MONTH)
                    "${month}월 ${startDay}~${endDay}일"
                }
                else -> {
                    val startText = SimpleDateFormat("M월 d일", locale).format(startMs)
                    val endText = SimpleDateFormat("M월 d일", locale).format(endMs)
                    "$startText ~ $endText"
                }
            }
        } else {
            when {
                sameDay -> SimpleDateFormat("MMM d", locale).format(startMs)
                sameMonth -> {
                    val month = SimpleDateFormat("MMM", locale).format(startMs)
                    val startDay = startCal.get(Calendar.DAY_OF_MONTH)
                    val endDay = endCal.get(Calendar.DAY_OF_MONTH)
                    "$month $startDay-$endDay"
                }
                else -> {
                    val startText = SimpleDateFormat("MMM d", locale).format(startMs)
                    val endText = SimpleDateFormat("MMM d", locale).format(endMs)
                    "$startText - $endText"
                }
            }
        }
    }
}
