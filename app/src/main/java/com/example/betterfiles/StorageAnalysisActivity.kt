package com.example.betterfiles

import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import android.content.Intent

class StorageAnalysisActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage_analysis)

        findViewById<ImageView>(R.id.btnBackStorageAnalysis).setOnClickListener { finish() }

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
    }
}
