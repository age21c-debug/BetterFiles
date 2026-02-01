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

        // 권한 체크 로직 (그대로 유지)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }

        // 버튼 연결
        val btnInternal: View = findViewById(R.id.btnInternalStorage)
        val btnImages: View = findViewById(R.id.btnImages)
        val btnVideos: View = findViewById(R.id.btnVideos)
        val btnAudio: View = findViewById(R.id.btnAudio)
        val btnDownloads: View = findViewById(R.id.btnDownloads)

        // 1. 내장 저장공간 (폴더 탐색 모드)
        btnInternal.setOnClickListener {
            openActivity(
                mode = "folder",
                path = Environment.getExternalStorageDirectory().absolutePath,
                title = "내장 저장공간"
            )
        }

        // 2. 이미지 (전체 이미지 모드 - MediaStore)
        btnImages.setOnClickListener {
            openActivity(mode = "image", title = "이미지")
        }

        // 3. 동영상 (전체 동영상 모드 - MediaStore)
        btnVideos.setOnClickListener {
            openActivity(mode = "video", title = "동영상")
        }

        // 4. 오디오 (전체 오디오 모드 - MediaStore)
        btnAudio.setOnClickListener {
            openActivity(mode = "audio", title = "오디오")
        }

        // 5. 다운로드
        btnDownloads.setOnClickListener {
            openActivity(mode = "download", title = "다운로드")
        }
    }

    private fun openActivity(mode: String, title: String, path: String = "") {
        val intent = Intent(this, FileListActivity::class.java)
        intent.putExtra("mode", mode)   // 핵심: "image", "folder" 등 모드 전달
        intent.putExtra("title", title) // 제목 전달
        intent.putExtra("path", path)   // 경로 전달 (folder 모드일 때만 씀)
        startActivity(intent)
    }
}