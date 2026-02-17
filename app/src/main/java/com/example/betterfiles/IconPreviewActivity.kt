package com.example.betterfiles

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class IconPreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_icon_preview)

        val tvBack = findViewById<TextView>(R.id.tvPreviewBack)
        val rv = findViewById<RecyclerView>(R.id.rvIconPreview)
        tvBack.setOnClickListener { finish() }

        rv.layoutManager = GridLayoutManager(this, 2)
        rv.adapter = IconPreviewAdapter(
            listOf(
                PreviewItem("Smart Folder Sparkle", "smart category", R.drawable.ic_smart_folder_sparkle, null),
                PreviewItem("Smart Manage", "android.R.drawable.ic_menu_manage", android.R.drawable.ic_menu_manage, Color.parseColor("#111111")),
                PreviewItem("Smart Sort", "android.R.drawable.ic_menu_sort_by_size", android.R.drawable.ic_menu_sort_by_size, Color.parseColor("#111111")),
                PreviewItem("Smart Agenda", "android.R.drawable.ic_menu_agenda", android.R.drawable.ic_menu_agenda, Color.parseColor("#111111")),
                PreviewItem("Smart View", "android.R.drawable.ic_menu_view", android.R.drawable.ic_menu_view, Color.parseColor("#111111")),
                PreviewItem("Smart Info", "android.R.drawable.ic_menu_info_details", android.R.drawable.ic_menu_info_details, Color.parseColor("#111111")),
                PreviewItem("Smart Camera", "android.R.drawable.ic_menu_camera", android.R.drawable.ic_menu_camera, Color.parseColor("#111111")),
                PreviewItem("PDF", "pdf", R.drawable.ic_pdf, Color.parseColor("#F44336")),
                PreviewItem("Word", "doc, docx", R.drawable.ic_doc_word, null),
                PreviewItem("Excel", "xls, xlsx, csv", R.drawable.ic_doc_excel, null),
                PreviewItem("PowerPoint", "ppt, pptx", R.drawable.ic_doc_powerpoint, null),
                PreviewItem("Text", "txt, log, md", R.drawable.ic_file, Color.parseColor("#546E7A")),
                PreviewItem("HWP", "hwp, hwpx", R.drawable.ic_doc_hwp, null),
                PreviewItem("OpenDocument", "odt, ods, odp", R.drawable.ic_file, Color.parseColor("#8E24AA")),
                PreviewItem("Markup/Data", "json, xml, yaml", R.drawable.ic_file, Color.parseColor("#00897B"))
            )
        )
    }

    data class PreviewItem(
        val title: String,
        val exts: String,
        val iconRes: Int,
        val tint: Int?
    )

    class IconPreviewAdapter(
        private val items: List<PreviewItem>
    ) : RecyclerView.Adapter<IconPreviewAdapter.PreviewViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_icon_preview, parent, false)
            return PreviewViewHolder(view)
        }

        override fun onBindViewHolder(holder: PreviewViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class PreviewViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivIcon: ImageView = itemView.findViewById(R.id.ivPreviewIcon)
            private val tvTitle: TextView = itemView.findViewById(R.id.tvPreviewName)
            private val tvExt: TextView = itemView.findViewById(R.id.tvPreviewExt)

            fun bind(item: PreviewItem) {
                ivIcon.setImageResource(item.iconRes)
                if (item.tint != null) {
                    ivIcon.setColorFilter(item.tint)
                } else {
                    ivIcon.clearColorFilter()
                }
                tvTitle.text = item.title
                tvExt.text = item.exts
            }
        }
    }
}
