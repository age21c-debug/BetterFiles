package com.example.betterfiles

import android.graphics.Bitmap
import android.graphics.Color
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.LruCache
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class FileAdapter(
    private val onClick: (FileItem) -> Unit,
    private val onMoreClick: (View, FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Unit,
    private val onSelectionChanged: () -> Unit,
    private val onLowUsageHeaderClick: ((Boolean) -> Unit)? = null
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    private var files: List<FileItem> = emptyList()
    private val pdfThumbCache = LruCache<String, Bitmap>(160)
    private var internalRootPath: String? = null
    private var duplicateMinModifiedByGroup: Map<String, Long> = emptyMap()
    private var lowUsageStrongTotalBytes: Long = 0L
    private var lowUsageReviewTotalBytes: Long = 0L

    var isSelectionMode = false
    var isPasteMode = false
    var showDateHeaders = false
    var showDuplicateHeaders = false
    var showLowUsageHeaders = false
    var showMessengerHeaders = false
    var showParentPathLine = false
    var preferStaticIcons = false

    val currentList: List<FileItem>
        get() = files

    init {
        setHasStableIds(true)
    }

    fun submitList(newFiles: List<FileItem>) {
        val oldFiles = files
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldFiles.size
            override fun getNewListSize(): Int = newFiles.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldFiles[oldItemPosition]
                val newItem = newFiles[newItemPosition]
                return oldItem.path == newItem.path
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldFiles[oldItemPosition]
                val newItem = newFiles[newItemPosition]
                return oldItem.name == newItem.name &&
                    oldItem.size == newItem.size &&
                    oldItem.dateModified == newItem.dateModified &&
                    oldItem.mimeType == newItem.mimeType &&
                    oldItem.isDirectory == newItem.isDirectory &&
                    oldItem.isSelected == newItem.isSelected &&
                    oldItem.smartScore == newItem.smartScore &&
                    oldItem.duplicateGroupKey == newItem.duplicateGroupKey &&
                    oldItem.duplicateGroupCount == newItem.duplicateGroupCount &&
                    oldItem.duplicateGroupSavingsBytes == newItem.duplicateGroupSavingsBytes &&
                    oldItem.shareCount60d == newItem.shareCount60d &&
                    oldItem.lastSharedAtMs == newItem.lastSharedAtMs
            }
        }, true)
        duplicateMinModifiedByGroup = newFiles
            .asSequence()
            .mapNotNull { item ->
                val key = item.duplicateGroupKey
                if (key.isNullOrBlank()) null else key to item.dateModified
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.minOrNull() ?: 0L }
        lowUsageStrongTotalBytes = newFiles
            .asSequence()
            .filter { isLowUsageStrongItem(it) }
            .sumOf { if (it.isDirectory) 0L else it.size }
        lowUsageReviewTotalBytes = newFiles
            .asSequence()
            .filter { !isLowUsageStrongItem(it) }
            .sumOf { if (it.isDirectory) 0L else it.size }
        files = newFiles
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemId(position: Int): Long {
        return files[position].path.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(files[position], position)
    }

    override fun getItemCount() = files.size

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvPath: TextView = itemView.findViewById(R.id.tvPath)
        private val tvMetaDate: TextView = itemView.findViewById(R.id.tvMetaDate)
        private val tvSize: TextView = itemView.findViewById(R.id.tvSize)
        private val tvOriginalBadge: TextView = itemView.findViewById(R.id.tvOriginalBadge)
        private val layoutSectionHeader: LinearLayout = itemView.findViewById(R.id.layoutSectionHeader)
        private val tvDateHeader: TextView = itemView.findViewById(R.id.tvDateHeader)
        private val ivHeaderSelectAll: ImageView = itemView.findViewById(R.id.ivHeaderSelectAll)
        private val layoutRowContent: LinearLayout = itemView.findViewById(R.id.layoutRowContent)
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivIcon)
        private val btnMore: ImageView = itemView.findViewById(R.id.btnMore)
        private val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)

        private val rippleResId: Int = with(TypedValue()) {
            itemView.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true)
            resourceId
        }

        fun bind(item: FileItem, position: Int) {
            val file = File(item.path)
            tvName.text = item.name
            bindParentPathLine(file)
            bindDateHeader(item, position)

            Glide.with(itemView.context).clear(ivIcon)
            ivIcon.clearColorFilter()
            ivIcon.setImageDrawable(null)
            ivIcon.tag = item.path

            FileVisualRules.bindThumbnail(
                context = itemView.context,
                target = ivIcon,
                fileItem = item,
                file = file,
                pdfCache = pdfThumbCache,
                pdfThumbWidth = 180,
                allowHeavyThumbnail = !preferStaticIcons
            )

            val dateStr = getFormattedDate(item.dateModified * 1000)
            if (item.isDirectory) {
                tvMetaDate.text = dateStr
                tvSize.text = ""
                tvSize.visibility = View.GONE
                tvOriginalBadge.visibility = View.GONE
            } else {
                val sizeStr = Formatter.formatFileSize(itemView.context, item.size)
                tvMetaDate.text = if (item.shareCount60d > 0 && item.lastSharedAtMs > 0L) {
                    itemView.context.getString(
                        R.string.smart_shared_list_meta_format,
                        item.shareCount60d,
                        getFormattedDate(item.lastSharedAtMs)
                    )
                } else {
                    dateStr
                }
                tvSize.text = sizeStr
                tvSize.visibility = View.VISIBLE
                tvOriginalBadge.visibility = if (shouldShowOriginalBadge(item)) View.VISIBLE else View.GONE
            }

            if (isSelectionMode) {
                btnMore.visibility = View.GONE
                cbSelect.visibility = View.VISIBLE
                cbSelect.isChecked = item.isSelected
                if (item.isSelected) {
                    layoutRowContent.setBackgroundColor(Color.parseColor("#E3F2FD"))
                } else {
                    layoutRowContent.setBackgroundResource(rippleResId)
                }
            } else {
                cbSelect.visibility = View.GONE
                cbSelect.isChecked = false
                layoutRowContent.setBackgroundResource(rippleResId)
                btnMore.visibility = if (isPasteMode) View.GONE else View.VISIBLE
            }

            itemView.setOnClickListener {
                if (isSelectionMode) {
                    val pos = adapterPosition
                    val target = when {
                        pos != RecyclerView.NO_POSITION && pos < files.size -> files[pos]
                        else -> files.firstOrNull { it.path == item.path }
                    }
                    if (target == null) {
                        return@setOnClickListener
                    }
                    val before = target.isSelected
                    target.isSelected = !target.isSelected
                    item.isSelected = target.isSelected
                    if (pos != RecyclerView.NO_POSITION) {
                        notifyItemChanged(pos)
                    } else {
                        notifyDataSetChanged()
                    }
                    onSelectionChanged()
                } else {
                    onClick(item)
                }
            }

            itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    onLongClick(item)
                    true
                } else {
                    false
                }
            }

            btnMore.setOnClickListener { view -> onMoreClick(view, item) }
        }

        private fun getFormattedDate(time: Long): String {
            val date = Date(time)
            val format = SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())
            return format.format(date)
        }

        private fun bindDateHeader(item: FileItem, position: Int) {
            tvDateHeader.setOnClickListener(null)
            tvDateHeader.isClickable = false
            ivHeaderSelectAll.visibility = View.GONE
            ivHeaderSelectAll.setOnClickListener(null)
            layoutSectionHeader.visibility = View.GONE

            if (showMessengerHeaders) {
                val currentSource = MessengerPathMatcher.detectSourceName(item.path)
                val previousSource = if (position > 0) MessengerPathMatcher.detectSourceName(files[position - 1].path) else null
                val shouldShow = position == 0 || currentSource != previousSource
                if (shouldShow) {
                    layoutSectionHeader.visibility = View.VISIBLE
                    tvDateHeader.text = toMessengerDisplayName(currentSource)
                } else {
                    layoutSectionHeader.visibility = View.GONE
                }
                return
            }

            if (showDuplicateHeaders) {
                val currentGroupKey = item.duplicateGroupKey
                if (currentGroupKey.isNullOrBlank()) {
                    tvDateHeader.visibility = View.GONE
                    return
                }
                val previousGroupKey = if (position > 0) files[position - 1].duplicateGroupKey else null
                val shouldShow = position == 0 || currentGroupKey != previousGroupKey
                if (!shouldShow) {
                    layoutSectionHeader.visibility = View.GONE
                    return
                }
                layoutSectionHeader.visibility = View.VISIBLE
                val context = itemView.context
                val savings = Formatter.formatFileSize(context, item.duplicateGroupSavingsBytes)
                tvDateHeader.text = context.getString(
                    R.string.duplicate_group_header_format,
                    savings,
                    item.duplicateGroupCount
                )
                return
            }

            if (showLowUsageHeaders) {
                val currentStrong = isLowUsageStrong(item)
                val previousStrong = if (position > 0) isLowUsageStrong(files[position - 1]) else currentStrong
                val shouldShow = position == 0 || currentStrong != previousStrong
                if (shouldShow) {
                    layoutSectionHeader.visibility = View.VISIBLE
                    val context = itemView.context
                    val totalSizeText = Formatter.formatFileSize(
                        context,
                        if (currentStrong) lowUsageStrongTotalBytes else lowUsageReviewTotalBytes
                    )
                    tvDateHeader.text = if (currentStrong) {
                        context.getString(R.string.low_usage_header_strong_format, totalSizeText)
                    } else {
                        context.getString(R.string.low_usage_header_review_format, totalSizeText)
                    }
                    ivHeaderSelectAll.visibility = View.VISIBLE
                    val sectionAllSelected = files
                        .asSequence()
                        .filter { isLowUsageStrong(it) == currentStrong }
                        .all { it.isSelected }
                    val tintColor = if (sectionAllSelected) {
                        Color.parseColor("#673AB7")
                    } else {
                        Color.parseColor("#9AA0A6")
                    }
                    ivHeaderSelectAll.setColorFilter(tintColor)
                    ivHeaderSelectAll.contentDescription = context.getString(
                        if (currentStrong) {
                            R.string.low_usage_header_select_all_strong
                        } else {
                            R.string.low_usage_header_select_all_review
                        }
                    )
                    ivHeaderSelectAll.setOnClickListener { onLowUsageHeaderClick?.invoke(currentStrong) }
                } else {
                    layoutSectionHeader.visibility = View.GONE
                }
                return
            }

            if (!showDateHeaders) {
                layoutSectionHeader.visibility = View.GONE
                return
            }

            val currentMillis = item.dateModified * 1000
            val shouldShow = if (position == 0) {
                true
            } else {
                val prevMillis = files[position - 1].dateModified * 1000
                !isSameDay(currentMillis, prevMillis)
            }

            if (shouldShow) {
                layoutSectionHeader.visibility = View.VISIBLE
                tvDateHeader.text = formatHeaderDate(currentMillis)
            } else {
                layoutSectionHeader.visibility = View.GONE
            }
        }

        private fun toMessengerDisplayName(source: String): String {
            return when (source) {
                "Messenger" -> itemView.context.getString(R.string.messenger_app_messenger)
                "KakaoTalk" -> itemView.context.getString(R.string.messenger_app_kakaotalk)
                "Telegram" -> itemView.context.getString(R.string.messenger_app_telegram)
                "WhatsApp" -> itemView.context.getString(R.string.messenger_app_whatsapp)
                "LINE" -> itemView.context.getString(R.string.messenger_app_line)
                "Discord" -> itemView.context.getString(R.string.messenger_app_discord)
                "Snapchat" -> itemView.context.getString(R.string.messenger_app_snapchat)
                "Viber" -> itemView.context.getString(R.string.messenger_app_viber)
                "Signal" -> itemView.context.getString(R.string.messenger_app_signal)
                "Facebook" -> itemView.context.getString(R.string.messenger_app_facebook)
                "TikTok" -> itemView.context.getString(R.string.messenger_app_tiktok)
                "Threads" -> itemView.context.getString(R.string.messenger_app_threads)
                "X" -> itemView.context.getString(R.string.messenger_app_x)
                "Zalo" -> itemView.context.getString(R.string.messenger_app_zalo)
                "Slack" -> itemView.context.getString(R.string.messenger_app_slack)
                else -> source
            }
        }

        private fun bindParentPathLine(file: File) {
            if (!showParentPathLine) {
                tvPath.visibility = View.GONE
                return
            }

            val context = itemView.context
            val root = internalRootPath ?: StorageVolumeHelper.getStorageRoots(context).internalRoot.also {
                internalRootPath = it
            }
            val parent = file.parent ?: run {
                tvPath.visibility = View.GONE
                return
            }

            if (!parent.startsWith(root, ignoreCase = true)) {
                tvPath.visibility = View.GONE
                return
            }

            val relative = parent.removePrefix(root).trimStart(File.separatorChar)
            tvPath.visibility = View.VISIBLE
            tvPath.text = if (relative.isBlank()) "/" else relative
            bindSmallPathIcon()
        }

        private fun shouldShowOriginalBadge(item: FileItem): Boolean {
            if (!showDuplicateHeaders) return false
            val groupKey = item.duplicateGroupKey ?: return false
            val minModified = duplicateMinModifiedByGroup[groupKey] ?: return false
            return item.dateModified == minModified
        }

        private fun isLowUsageStrong(item: FileItem): Boolean {
            return isLowUsageStrongItem(item)
        }

        private fun bindSmallPathIcon() {
            val icon = AppCompatResources.getDrawable(itemView.context, R.drawable.ic_folder_solid) ?: return
            val sizePx = (itemView.resources.displayMetrics.density * 14f).toInt()
            icon.setBounds(0, 0, sizePx, sizePx)
            tvPath.setCompoundDrawables(icon, null, null, null)
            tvPath.compoundDrawablePadding = (itemView.resources.displayMetrics.density * 4f).toInt()
        }

        private fun formatHeaderDate(timeMillis: Long): String {
            val context = itemView.context
            return when {
                DateUtils.isToday(timeMillis) -> context.getString(R.string.date_header_today)
                DateUtils.isToday(timeMillis + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.date_header_yesterday)
                else -> getFormattedDate(timeMillis)
            }
        }

        private fun isSameDay(aMillis: Long, bMillis: Long): Boolean {
            val calA = Calendar.getInstance().apply { timeInMillis = aMillis }
            val calB = Calendar.getInstance().apply { timeInMillis = bMillis }
            return calA.get(Calendar.YEAR) == calB.get(Calendar.YEAR) &&
                calA.get(Calendar.DAY_OF_YEAR) == calB.get(Calendar.DAY_OF_YEAR)
        }
    }

    private fun isLowUsageStrongItem(item: FileItem): Boolean {
        return item.smartScore >= 100
    }
}



