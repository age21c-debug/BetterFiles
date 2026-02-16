package com.example.betterfiles

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class RecentExclusionAdapter(
    private val onRemoveClick: (String) -> Unit
) : RecyclerView.Adapter<RecentExclusionAdapter.ViewHolder>() {

    private var folders: List<String> = emptyList()

    fun submitList(newFolders: List<String>) {
        folders = newFolders
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_excluded_folder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(folders[position])
    }

    override fun getItemCount(): Int = folders.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvFolderName: TextView = itemView.findViewById(R.id.tvExcludedFolderName)
        private val tvFolderPath: TextView = itemView.findViewById(R.id.tvExcludedFolderPath)
        private val tvFolderVolume: TextView = itemView.findViewById(R.id.tvExcludedFolderVolume)
        private val btnRemove: ImageView = itemView.findViewById(R.id.btnRemoveExcluded)

        fun bind(folderPath: String) {
            val name = File(folderPath).name.ifBlank { folderPath }
            tvFolderName.text = name
            tvFolderPath.text = folderPath
            val roots = StorageVolumeHelper.getStorageRoots(itemView.context)
            val volume = when (StorageVolumeHelper.detectVolume(folderPath, roots)) {
                StorageVolumeType.INTERNAL -> itemView.context.getString(R.string.internal_storage)
                StorageVolumeType.SD_CARD -> itemView.context.getString(R.string.sd_card)
                else -> itemView.context.getString(R.string.storage_other)
            }
            tvFolderVolume.text = volume
            btnRemove.setOnClickListener { onRemoveClick(folderPath) }
        }
    }
}
