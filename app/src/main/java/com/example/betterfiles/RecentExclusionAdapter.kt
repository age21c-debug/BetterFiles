package com.example.betterfiles

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RecentExclusionAdapter(
    private val onRemoveClick: (Entry) -> Unit
) : RecyclerView.Adapter<RecentExclusionAdapter.ViewHolder>() {

    data class Entry(
        val key: String,
        val title: String,
        val subtitle: String,
        val meta: String,
        val type: Type
    )

    enum class Type {
        FILE,
        FOLDER,
        EXTENSION
    }

    private var items: List<Entry> = emptyList()

    fun submitList(newItems: List<Entry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_excluded_folder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivExcludedTypeIcon)
        private val tvFolderName: TextView = itemView.findViewById(R.id.tvExcludedFolderName)
        private val tvFolderPath: TextView = itemView.findViewById(R.id.tvExcludedFolderPath)
        private val tvFolderVolume: TextView = itemView.findViewById(R.id.tvExcludedFolderVolume)
        private val btnRemove: ImageView = itemView.findViewById(R.id.btnRemoveExcluded)

        fun bind(entry: Entry) {
            tvFolderName.text = entry.title
            tvFolderPath.text = entry.subtitle
            tvFolderVolume.text = entry.meta

            when (entry.type) {
                Type.FOLDER -> {
                    ivIcon.setImageResource(R.drawable.ic_folder_solid)
                    ivIcon.setColorFilter(0xFFFFA000.toInt())
                }
                Type.FILE -> {
                    ivIcon.setImageResource(R.drawable.ic_file)
                    ivIcon.setColorFilter(0xFF4E79A7.toInt())
                }
                Type.EXTENSION -> {
                    ivIcon.setImageResource(R.drawable.ic_file)
                    ivIcon.setColorFilter(0xFF7D5BA6.toInt())
                }
            }

            btnRemove.setOnClickListener { onRemoveClick(entry) }
        }
    }
}
