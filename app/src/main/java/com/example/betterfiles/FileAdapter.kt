package com.example.betterfiles

import android.content.pm.PackageManager
import android.graphics.Color
import android.text.format.Formatter
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
    private val onMoreClick: (View, FileItem) -> Unit, // 더보기 버튼 클릭 시
    private val onLongClick: (FileItem) -> Unit,       // 롱클릭 시 (선택 모드 진입)
    private val onSelectionChanged: () -> Unit         // 선택 상태 변경 알림
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    private var files: List<FileItem> = emptyList()

    // 선택 모드 상태 변수
    var isSelectionMode = false

    // 외부에서 현재 리스트에 접근할 수 있도록 프로퍼티 추가
    val currentList: List<FileItem>
        get() = files

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
        private val btnMore: ImageView = itemView.findViewById(R.id.btnMore)
        private val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)

        // ▼▼▼ [수정] 배경 리소스 ID를 안전하게 미리 가져옴 (Crash 해결 핵심) ▼▼▼
        private val rippleResId: Int = with(TypedValue()) {
            itemView.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true)
            resourceId
        }

        fun bind(item: FileItem) {
            val file = File(item.path)
            tvName.text = item.name

            // 1. 아이콘 상태 초기화
            Glide.with(itemView.context).clear(ivIcon)
            ivIcon.clearColorFilter()
            ivIcon.setImageDrawable(null)

            // ------------------------------------------------------------
            // 기존 아이콘 결정 로직
            // ------------------------------------------------------------
            if (item.isDirectory) {
                ivIcon.setImageResource(R.drawable.ic_folder_solid)
            } else {
                if (isImageFile(item.name) || isVideoFile(item.name)) {
                    Glide.with(itemView.context)
                        .load(file)
                        .centerCrop()
                        .placeholder(R.drawable.ic_file)
                        .into(ivIcon)
                    ivIcon.clearColorFilter()

                } else if (isApkFile(item.name)) {
                    val pm = itemView.context.packageManager
                    val packageInfo = pm.getPackageArchiveInfo(item.path, 0)

                    if (packageInfo != null) {
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
                        ivIcon.setImageResource(R.drawable.ic_android)
                        ivIcon.setColorFilter(Color.parseColor("#3DDC84"))
                    }

                } else if (isPdfFile(item.name)) {
                    ivIcon.setImageResource(R.drawable.ic_pdf)
                    if (itemView.resources.getIdentifier("ic_pdf", "drawable", itemView.context.packageName) == 0) {
                        ivIcon.setImageResource(R.drawable.ic_file)
                    }
                    ivIcon.setColorFilter(Color.parseColor("#F44336"))

                } else if (isVoiceFile(item.name)) {
                    ivIcon.setImageResource(R.drawable.ic_mic)
                    if (itemView.resources.getIdentifier("ic_mic", "drawable", itemView.context.packageName) == 0) {
                        ivIcon.setImageResource(R.drawable.ic_file)
                    }
                    ivIcon.setColorFilter(Color.parseColor("#009688"))

                } else if (isAudioFile(item.name)) {
                    ivIcon.setImageResource(R.drawable.ic_music_note)
                    if (itemView.resources.getIdentifier("ic_music_note", "drawable", itemView.context.packageName) == 0) {
                        ivIcon.setImageResource(R.drawable.ic_file)
                    }
                    ivIcon.setColorFilter(Color.parseColor("#9C27B0"))

                } else {
                    ivIcon.setImageResource(R.drawable.ic_file)
                    ivIcon.setColorFilter(Color.parseColor("#5F6368"))
                }
            }

            // ------------------------------------------------------------
            // 텍스트 설정
            // ------------------------------------------------------------
            val dateStr = getFormattedDate(file.lastModified())
            if (item.isDirectory) {
                tvSize.text = dateStr
            } else {
                val sizeStr = Formatter.formatFileSize(itemView.context, item.size)
                tvSize.text = "$sizeStr • $dateStr"
            }

            // ------------------------------------------------------------
            // ▼▼▼ [수정] 선택 모드 UI 로직 (배경 설정 안전하게 변경) ▼▼▼
            // ------------------------------------------------------------
            if (isSelectionMode) {
                btnMore.visibility = View.GONE
                cbSelect.visibility = View.VISIBLE
                cbSelect.isChecked = item.isSelected

                if (item.isSelected) {
                    itemView.setBackgroundColor(Color.parseColor("#E3F2FD")) // 선택됨: 파란색
                } else {
                    itemView.setBackgroundResource(rippleResId) // 선택안됨: 기본 물결 (에러 수정됨)
                }
            } else {
                btnMore.visibility = View.VISIBLE
                cbSelect.visibility = View.GONE
                cbSelect.isChecked = false
                itemView.setBackgroundResource(rippleResId) // 일반 모드: 기본 물결 (에러 수정됨)
            }

            // ------------------------------------------------------------
            // 클릭 이벤트 설정
            // ------------------------------------------------------------
            itemView.setOnClickListener {
                if (isSelectionMode) {
                    item.isSelected = !item.isSelected
                    notifyItemChanged(adapterPosition)
                    onSelectionChanged()
                } else {
                    onClick(item)
                }
            }

            itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    onLongClick(item)
                    return@setOnLongClickListener true
                }
                false
            }

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