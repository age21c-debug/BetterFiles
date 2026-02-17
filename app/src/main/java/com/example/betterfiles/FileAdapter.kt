package com.example.betterfiles

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.format.Formatter
import android.text.style.ReplacementSpan
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
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    private var files: List<FileItem> = emptyList()
    private val pdfThumbCache = LruCache<String, Bitmap>(48)
    private var internalRootPath: String? = null
    private var duplicateMinModifiedByGroup: Map<String, Long> = emptyMap()

    var isSelectionMode = false
    var isPasteMode = false
    var showDateHeaders = false
    var showDuplicateHeaders = false
    var showMessengerHeaders = false
    var showParentPathLine = false

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
                    oldItem.duplicateGroupKey == newItem.duplicateGroupKey &&
                    oldItem.duplicateGroupCount == newItem.duplicateGroupCount &&
                    oldItem.duplicateGroupSavingsBytes == newItem.duplicateGroupSavingsBytes
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
        private val tvSize: TextView = itemView.findViewById(R.id.tvSize)
        private val tvDateHeader: TextView = itemView.findViewById(R.id.tvDateHeader)
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

            FileVisualRules.bindThumbnail(
                context = itemView.context,
                target = ivIcon,
                fileItem = item,
                file = file,
                pdfCache = pdfThumbCache,
                pdfThumbWidth = 180
            )

            val dateStr = getFormattedDate(item.dateModified * 1000)
            if (item.isDirectory) {
                tvSize.text = dateStr
            } else {
                val sizeStr = Formatter.formatFileSize(itemView.context, item.size)
                tvSize.text = buildSizeDateText(item, sizeStr, dateStr)
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
            if (showMessengerHeaders) {
                val currentSource = MessengerPathMatcher.detectSourceName(item.path)
                val previousSource = if (position > 0) MessengerPathMatcher.detectSourceName(files[position - 1].path) else null
                val shouldShow = position == 0 || currentSource != previousSource
                if (shouldShow) {
                    tvDateHeader.visibility = View.VISIBLE
                    tvDateHeader.text = toMessengerDisplayName(currentSource)
                } else {
                    tvDateHeader.visibility = View.GONE
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
                    tvDateHeader.visibility = View.GONE
                    return
                }
                tvDateHeader.visibility = View.VISIBLE
                val context = itemView.context
                val savings = Formatter.formatFileSize(context, item.duplicateGroupSavingsBytes)
                tvDateHeader.text = context.getString(
                    R.string.duplicate_group_header_format,
                    savings,
                    item.duplicateGroupCount
                )
                return
            }

            if (!showDateHeaders) {
                tvDateHeader.visibility = View.GONE
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
                tvDateHeader.visibility = View.VISIBLE
                tvDateHeader.text = formatHeaderDate(currentMillis)
            } else {
                tvDateHeader.visibility = View.GONE
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

        private fun buildSizeDateText(item: FileItem, sizeStr: String, dateStr: String): CharSequence {
            val base = "$sizeStr • $dateStr"
            if (!showDuplicateHeaders) return base

            val groupKey = item.duplicateGroupKey ?: return base
            val minModified = duplicateMinModifiedByGroup[groupKey] ?: return base
            if (item.dateModified != minModified) return base

            val originalLabel = itemView.context.getString(R.string.label_original)
            val start = base.length + 1
            return SpannableStringBuilder(base)
                .append(" ")
                .append(originalLabel)
                .apply {
                    setSpan(
                        PillTagSpan(
                            backgroundColor = Color.parseColor("#1E88E5"),
                            textColor = Color.parseColor("#FFFFFF"),
                            textScale = 0.90f,
                            horizontalPaddingPx = (itemView.resources.displayMetrics.density * 6f).toInt(),
                            verticalInsetPx = (itemView.resources.displayMetrics.density * 1f).toInt()
                        ),
                        start,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
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

    private class PillTagSpan(
        private val backgroundColor: Int,
        private val textColor: Int,
        private val textScale: Float,
        private val horizontalPaddingPx: Int,
        private val verticalInsetPx: Int
    ) : ReplacementSpan() {
        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            val originalTextSize = paint.textSize
            paint.textSize = originalTextSize * textScale
            val width = paint.measureText(text, start, end)
            paint.textSize = originalTextSize
            return (width + horizontalPaddingPx * 2).toInt()
        }

        override fun draw(
            canvas: android.graphics.Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val originalColor = paint.color
            val originalTextSize = paint.textSize
            val originalBold = paint.isFakeBoldText

            paint.textSize = originalTextSize * textScale
            val textWidth = paint.measureText(text, start, end)
            val rectLeft = x
            val rectRight = x + textWidth + horizontalPaddingPx * 2
            val rectTop = top.toFloat() + verticalInsetPx
            val rectBottom = bottom.toFloat() - verticalInsetPx
            val radius = (rectBottom - rectTop) / 2f

            paint.color = backgroundColor
            paint.isFakeBoldText = false
            canvas.drawRoundRect(RectF(rectLeft, rectTop, rectRight, rectBottom), radius, radius, paint)

            paint.color = textColor
            paint.isFakeBoldText = false
            canvas.drawText(text, start, end, x + horizontalPaddingPx, y.toFloat(), paint)

            paint.color = originalColor
            paint.textSize = originalTextSize
            paint.isFakeBoldText = originalBold
        }
    }
}
