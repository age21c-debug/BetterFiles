package com.example.betterfiles

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
    }

    private fun openActivity(mode: String, title: String, path: String = "") {
        val intent = Intent(this, FileListActivity::class.java)
        intent.putExtra("mode", mode)
        intent.putExtra("title", title)
        intent.putExtra("path", path)
        startActivity(intent)
    }
}
