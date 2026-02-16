package com.example.betterfiles

import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView

class StorageAnalysisActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage_analysis)

        findViewById<ImageView>(R.id.btnBackStorageAnalysis).setOnClickListener { finish() }

        findViewById<CardView>(R.id.cardDuplicateCleanup).setOnClickListener {
            Toast.makeText(this, getString(R.string.storage_feature_coming_soon), Toast.LENGTH_SHORT).show()
        }
        findViewById<CardView>(R.id.cardLargeFileCleanup).setOnClickListener {
            Toast.makeText(this, getString(R.string.storage_feature_coming_soon), Toast.LENGTH_SHORT).show()
        }
    }
}

