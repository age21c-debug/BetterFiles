package com.example.betterfiles

import android.graphics.Bitmap
import android.text.format.DateUtils
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class RecentFileAdapter(
    private val onOpen: (FileItem) -> Unit,
    private val onInfo: (FileItem) -> Unit,
    private val onShare: (FileItem) -> Unit,
    private val onGoToLocation: (FileItem) -> Unit,
    private val onDelete: (FileItem) -> Unit
) : ListAdapter<FileItem, RecentFileAdapter.RecentViewHolder>(DiffCallback) {

    private val pdfThumbCache = LruCache<String, Bitmap>(24)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recent_file, parent, false)
        return RecentViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecentViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RecentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivRecentThumb)
        private val ivType: ImageView = itemView.findViewById(R.id.ivRecentType)
        private val tvName: TextView = itemView.findViewById(R.id.tvRecentName)
        private val tvTime: TextView = itemView.findViewById(R.id.tvRecentTime)

        fun bind(item: FileItem) {
            val file = File(item.path)
            tvName.text = item.name
            tvTime.text = DateUtils.getRelativeTimeSpanString(
                item.dateModified * 1000,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )

            FileVisualRules.bindThumbnail(
                context = itemView.context,
                target = ivThumb,
                fileItem = item,
                file = file,
                pdfCache = pdfThumbCache,
                pdfThumbWidth = 320,
                overlayView = ivType,
                clearTargetFirst = false,
                usePlaceholder = false
            )

            itemView.setOnClickListener { onOpen(item) }
            itemView.setOnLongClickListener {
                showOptions(it, item)
                true
            }
        }

        private fun showOptions(anchor: View, item: FileItem) {
            val popup = PopupMenu(anchor.context, anchor)
            popup.menu.add(0, 1, 0, anchor.context.getString(R.string.action_info))
            popup.menu.add(0, 2, 1, anchor.context.getString(R.string.menu_share))
            popup.menu.add(0, 3, 2, anchor.context.getString(R.string.action_location))
            popup.menu.add(0, 4, 3, anchor.context.getString(R.string.menu_delete))
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> onInfo(item)
                    2 -> onShare(item)
                    3 -> onGoToLocation(item)
                    4 -> onDelete(item)
                }
                true
            }
            popup.show()
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<FileItem>() {
            override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
                return oldItem.path == newItem.path
            }

            override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
                return oldItem.name == newItem.name &&
                    oldItem.path == newItem.path &&
                    oldItem.size == newItem.size &&
                    oldItem.dateModified == newItem.dateModified &&
                    oldItem.mimeType == newItem.mimeType &&
                    oldItem.isDirectory == newItem.isDirectory
            }
        }
    }
}
