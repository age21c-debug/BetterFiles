package com.example.betterfiles

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
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

class EventPhotoBundleAdapter(
    private val onItemClick: (EventPhotoCluster) -> Unit
) : RecyclerView.Adapter<EventPhotoBundleAdapter.BundleViewHolder>() {
    private var clusters: List<EventPhotoCluster> = emptyList()

    fun submitList(newList: List<EventPhotoCluster>) {
        clusters = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BundleViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_event_photo_bundle, parent, false)
        return BundleViewHolder(view)
    }

    override fun onBindViewHolder(holder: BundleViewHolder, position: Int) {
        holder.bind(clusters[position])
    }

    override fun getItemCount(): Int = clusters.size

    inner class BundleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPreview: ImageView = itemView.findViewById(R.id.ivEventPreview)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvEventTitle)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvEventMeta)
        private val tvHint: TextView = itemView.findViewById(R.id.tvEventHint)

        fun bind(item: EventPhotoCluster) {
            itemView.setOnClickListener { onItemClick(item) }
            tvTitle.text = formatDateTitle(item.startMs, item.endMs)
            tvMeta.text = itemView.context.getString(
                R.string.smart_event_item_meta_format,
                item.photoCount,
                Formatter.formatFileSize(itemView.context, item.totalBytes)
            )
            tvHint.text = formatCaptureWindow(item.startMs, item.endMs)

            val previewPath = item.previewPath
            if (previewPath.isNullOrBlank()) {
                ivPreview.setImageResource(R.drawable.ic_image_file)
            } else {
                Glide.with(itemView.context)
                    .load(File(previewPath))
                    .centerCrop()
                    .placeholder(R.drawable.ic_image_file)
                    .error(R.drawable.ic_image_file)
                    .into(ivPreview)
            }
        }

        private fun formatDateTitle(startMs: Long, endMs: Long): String {
            val locale = Locale.getDefault()
            val startCal = Calendar.getInstance().apply { timeInMillis = startMs }
            val endCal = Calendar.getInstance().apply { timeInMillis = endMs }
            val sameYear = startCal.get(Calendar.YEAR) == endCal.get(Calendar.YEAR)
            val sameMonth = sameYear && startCal.get(Calendar.MONTH) == endCal.get(Calendar.MONTH)
            val sameDay = sameMonth && startCal.get(Calendar.DAY_OF_MONTH) == endCal.get(Calendar.DAY_OF_MONTH)

            return if (locale.language == Locale.KOREAN.language) {
                if (sameDay) {
                    val dayText = SimpleDateFormat("M월 d일", locale).format(startMs)
                    val timeRange = SimpleDateFormat("HH:mm", locale).format(startMs) + "-" +
                        SimpleDateFormat("HH:mm", locale).format(endMs)
                    "$dayText · $timeRange"
                } else {
                    val startText = if (sameYear) {
                        SimpleDateFormat("M월 d일", locale).format(startMs)
                    } else {
                        SimpleDateFormat("yyyy.M.d", locale).format(startMs)
                    }
                    val endText = if (sameYear) {
                        SimpleDateFormat("M월 d일", locale).format(endMs)
                    } else {
                        SimpleDateFormat("yyyy.M.d", locale).format(endMs)
                    }
                    "$startText ~ $endText"
                }
            } else {
                if (sameDay) {
                    val dayText = SimpleDateFormat("MMM d", locale).format(startMs)
                    val timeRange = SimpleDateFormat("HH:mm", locale).format(startMs) + "-" +
                        SimpleDateFormat("HH:mm", locale).format(endMs)
                    "$dayText · $timeRange"
                } else {
                    val startText = if (sameYear) {
                        SimpleDateFormat("MMM d", locale).format(startMs)
                    } else {
                        SimpleDateFormat("yyyy.MM.dd", locale).format(startMs)
                    }
                    val endText = if (sameYear) {
                        SimpleDateFormat("MMM d", locale).format(endMs)
                    } else {
                        SimpleDateFormat("yyyy.MM.dd", locale).format(endMs)
                    }
                    "$startText - $endText"
                }
            }
        }

        private fun formatCaptureWindow(startMs: Long, endMs: Long): String {
            val locale = Locale.getDefault()
            val durationMinutes = max(1L, (endMs - startMs) / (60L * 1000L))
            val hours = durationMinutes / 60L
            val mins = durationMinutes % 60L
            return if (locale.language == Locale.KOREAN.language) {
                when {
                    hours > 0 && mins > 0 -> "${hours}시간 ${mins}분 동안 촬영"
                    hours > 0 -> "${hours}시간 동안 촬영"
                    else -> "${durationMinutes}분 동안 촬영"
                }
            } else {
                when {
                    hours > 0 && mins > 0 -> "Captured for ${hours}h ${mins}m"
                    hours > 0 -> "Captured for ${hours}h"
                    else -> "Captured for ${durationMinutes}m"
                }
            }
        }
    }
}
