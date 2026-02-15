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

    var isSelectionMode = false
    var isPasteMode = false
    var showDateHeaders = false

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
        holder.bind(files[position], position)
    }

    override fun getItemCount() = files.size

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
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

            val dateStr = getFormattedDate(file.lastModified())
            if (item.isDirectory) {
                tvSize.text = dateStr
            } else {
                val sizeStr = Formatter.formatFileSize(itemView.context, item.size)
                tvSize.text = "$sizeStr • $dateStr"
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
}
