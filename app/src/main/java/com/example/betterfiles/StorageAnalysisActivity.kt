package com.example.betterfiles

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StorageAnalysisActivity : AppCompatActivity() {
    companion object {
        private const val AUTO_CLEAN_MIN_SHOW_BYTES = 300L * 1024L * 1024L
        private const val AUTO_CLEAN_MIN_SHOW_COUNT = 5
    }

    private lateinit var repository: FileRepository
    private var latestAutoCleanCandidates: List<FileItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage_analysis)
        repository = FileRepository(this)

        findViewById<ImageView>(R.id.btnBackStorageAnalysis).setOnClickListener { finish() }

        val cardAutoClean = findViewById<CardView>(R.id.cardAutoCleanSuggestion)
        val btnAutoCleanReview = findViewById<TextView>(R.id.btnAutoCleanReview)
        btnAutoCleanReview.setOnClickListener {
            if (latestAutoCleanCandidates.isEmpty()) return@setOnClickListener
            startActivity(
                Intent(this, FileListActivity::class.java).apply {
                    putExtra("mode", "auto_clean")
                    putExtra("title", getString(R.string.storage_auto_clean_title))
                    putExtra("path", StorageVolumeHelper.getStorageRoots(this@StorageAnalysisActivity).internalRoot)
                }
            )
        }
        cardAutoClean.setOnClickListener { btnAutoCleanReview.performClick() }

        findViewById<CardView>(R.id.cardDuplicateCleanup).setOnClickListener {
            startActivity(
                Intent(this, FileListActivity::class.java).apply {
                    putExtra("mode", "duplicate")
                    putExtra("title", getString(R.string.storage_duplicate_cleanup_title))
                    putExtra("path", StorageVolumeHelper.getStorageRoots(this@StorageAnalysisActivity).internalRoot)
                }
            )
        }
        findViewById<CardView>(R.id.cardLargeFileCleanup).setOnClickListener {
            startActivity(
                Intent(this, FileListActivity::class.java).apply {
                    putExtra("mode", "large")
                    putExtra("title", getString(R.string.storage_large_cleanup_title))
                    putExtra("path", StorageVolumeHelper.getStorageRoots(this@StorageAnalysisActivity).internalRoot)
                }
            )
        }
        findViewById<CardView>(R.id.cardLowUsageLargeCleanup).setOnClickListener {
            startActivity(
                Intent(this, FileListActivity::class.java).apply {
                    putExtra("mode", "low_usage_large")
                    putExtra("title", getString(R.string.storage_low_usage_large_title))
                    putExtra("path", StorageVolumeHelper.getStorageRoots(this@StorageAnalysisActivity).internalRoot)
                }
            )
        }
        findViewById<CardView>(R.id.cardOldDownloadCleanup).setOnClickListener {
            startActivity(
                Intent(this, FileListActivity::class.java).apply {
                    putExtra("mode", "old_download")
                    putExtra("title", getString(R.string.storage_old_download_title))
                    putExtra("path", StorageVolumeHelper.getStorageRoots(this@StorageAnalysisActivity).internalRoot)
                }
            )
        }
        findViewById<CardView>(R.id.cardOldSharedCleanup).setOnClickListener {
            startActivity(
                Intent(this, FileListActivity::class.java).apply {
                    putExtra("mode", "old_shared")
                    putExtra("title", getString(R.string.storage_old_shared_title))
                    putExtra("path", StorageVolumeHelper.getStorageRoots(this@StorageAnalysisActivity).internalRoot)
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAutoCleanSuggestion()
    }

    private fun refreshAutoCleanSuggestion() {
        val cardAutoClean = findViewById<CardView>(R.id.cardAutoCleanSuggestion)
        val tvAutoCleanSummary = findViewById<TextView>(R.id.tvAutoCleanSummary)

        lifecycleScope.launch {
            val candidates = withContext(Dispatchers.IO) {
                repository.getAutoCleanCandidates()
            }
            val totalBytes = candidates.sumOf { it.size }
            val count = candidates.size
            val shouldShow = count > 0 && (totalBytes >= AUTO_CLEAN_MIN_SHOW_BYTES || count >= AUTO_CLEAN_MIN_SHOW_COUNT)

            latestAutoCleanCandidates = if (shouldShow) candidates else emptyList()
            if (!shouldShow) {
                cardAutoClean.visibility = View.GONE
                return@launch
            }

            val sizeText = Formatter.formatFileSize(this@StorageAnalysisActivity, totalBytes)
            tvAutoCleanSummary.text = getString(R.string.storage_auto_clean_summary_format, sizeText, count)
            cardAutoClean.visibility = View.VISIBLE
        }
    }
}
