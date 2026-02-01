package com.example.betterfiles

import android.content.pm.PackageManager
import android.graphics.Color
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileAdapter(
    private val onClick: (FileItem) -> Unit,           // 파일 클릭 시 (열기)
    private val onMoreClick: (View, FileItem) -> Unit  // [추가] 더보기 버튼 클릭 시 (팝업 메뉴)
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    private var files: List<FileItem> = emptyList()

    fun submitList(newFiles: List<FileItem>) {
        files = newFiles
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(files[position])
    }

    override fun getItemCount() = files.size

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvSize: TextView = itemView.findViewById(R.id.tvSize)
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivIcon)
        private val btnMore: ImageView = itemView.findViewById(R.id.btnMore) // [추가] 점 3개 버튼

        fun bind(item: FileItem) {
            val file = File(item.path)
            tvName.text = item.name

            // 1. 아이콘 상태 초기화 (재사용 문제 방지)
            Glide.with(itemView.context).clear(ivIcon)
            ivIcon.clearColorFilter()

            // ------------------------------------------------------------
            // 아이콘 결정 로직
            // ------------------------------------------------------------
            if (item.isDirectory) {
                // [폴더]
                ivIcon.setImageResource(R.drawable.ic_folder_solid)
            } else {
                // [파일]
                if (isImageFile(item.name) || isVideoFile(item.name)) {
                    // 1) 이미지 & 동영상 -> 썸네일 로딩
                    Glide.with(itemView.context)
                        .load(file)
                        .centerCrop()
                        .placeholder(R.drawable.ic_file)
                        .into(ivIcon)
                    ivIcon.clearColorFilter() // 틴트 제거

                } else if (isApkFile(item.name)) {
                    // 2) APK 파일 -> 실제 앱 아이콘 추출
                    val pm = itemView.context.packageManager
                    val packageInfo = pm.getPackageArchiveInfo(item.path, 0)

                    if (packageInfo != null) {
                        // 중요: 아이콘을 가져오려면 소스 경로를 지정해줘야 함
                        packageInfo.applicationInfo.sourceDir = item.path
                        packageInfo.applicationInfo.publicSourceDir = item.path

                        val apkIcon = packageInfo.applicationInfo.loadIcon(pm)

                        Glide.with(itemView.context)
                            .load(apkIcon)
                            .centerCrop()
                            .placeholder(R.drawable.ic_android)
                            .into(ivIcon)
                        ivIcon.clearColorFilter()
                    } else {
                        // 분석 실패 시 (깨진 APK 등) -> 기본 안드로이드 로봇
                        ivIcon.setImageResource(R.drawable.ic_android)
                        ivIcon.setColorFilter(Color.parseColor("#3DDC84"))
                    }

                } else if (isPdfFile(item.name)) {
                    // 3) PDF 파일 -> 빨간색 아이콘
                    ivIcon.setImageResource(R.drawable.ic_pdf)
                    ivIcon.setColorFilter(Color.parseColor("#F44336"))

                } else if (isVoiceFile(item.name)) {
                    // 4) 음성 녹음(.m4a) -> 청록색 마이크
                    ivIcon.setImageResource(R.drawable.ic_mic)
                    ivIcon.setColorFilter(Color.parseColor("#009688"))

                } else if (isAudioFile(item.name)) {
                    // 5) 일반 음악 -> 보라색 음표
                    ivIcon.setImageResource(R.drawable.ic_music_note)
                    ivIcon.setColorFilter(Color.parseColor("#9C27B0"))

                } else {
                    // 6) 그 외 파일 -> 회색 기본 아이콘
                    ivIcon.setImageResource(R.drawable.ic_file)
                    ivIcon.setColorFilter(Color.parseColor("#5F6368"))
                }
            }

            // ------------------------------------------------------------
            // 텍스트(용량/날짜) 설정
            // ------------------------------------------------------------
            val dateStr = getFormattedDate(file.lastModified())

            if (item.isDirectory) {
                // 폴더는 날짜만 표시
                tvSize.text = dateStr
            } else {
                // 파일은 "용량 • 날짜" 표시
                val sizeStr = Formatter.formatFileSize(itemView.context, item.size)
                tvSize.text = "$sizeStr • $dateStr"
            }

            // ------------------------------------------------------------
            // 클릭 이벤트 설정
            // ------------------------------------------------------------

            // 1. 항목 전체 클릭 -> 파일 열기
            itemView.setOnClickListener {
                onClick(item)
            }

            // 2. 점 3개 버튼 클릭 -> 팝업 메뉴 열기 (Activity로 이벤트 전달)
            btnMore.setOnClickListener { view ->
                onMoreClick(view, item)
            }
        }

        // --- 확장자 판별 헬퍼 함수들 ---
        private fun isImageFile(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                    lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
        }

        private fun isVideoFile(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") ||
                    lower.endsWith(".mov") || lower.endsWith(".wmv") || lower.endsWith(".webm") ||
                    lower.endsWith(".3gp")
        }

        private fun isApkFile(name: String): Boolean {
            return name.lowercase().endsWith(".apk")
        }

        private fun isVoiceFile(name: String): Boolean {
            return name.lowercase().endsWith(".m4a")
        }

        private fun isAudioFile(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg") ||
                    lower.endsWith(".flac") || lower.endsWith(".aac") || lower.endsWith(".wma")
        }

        private fun isPdfFile(name: String): Boolean {
            return name.lowercase().endsWith(".pdf")
        }

        private fun getFormattedDate(time: Long): String {
            val date = Date(time)
            val format = SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())
            return format.format(date)
        }
    }
}