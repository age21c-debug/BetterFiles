package com.example.betterfiles

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

class SmartCategoryActivity : AppCompatActivity() {
    private lateinit var summaryRepository: SmartCategorySummaryRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smart_category)
        summaryRepository = SmartCategorySummaryRepository(this)

        findViewById<ImageView>(R.id.btnBackSmartCategory).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.ivSmartInfo).setOnClickListener {
            Toast.makeText(this, getString(R.string.smart_share_source_note), Toast.LENGTH_SHORT).show()
        }
        findViewById<TextView>(R.id.tvSmartBadge).visibility = View.GONE

        findViewById<CardView>(R.id.cardSmartEntry1).setOnClickListener {
            startActivity(
                Intent(this, FileListActivity::class.java).apply {
                    putExtra("mode", "smart_shared")
                    putExtra("title", getString(R.string.smart_frequent_shared_title))
                    putExtra("path", StorageVolumeHelper.getStorageRoots(this@SmartCategoryActivity).internalRoot)
                }
            )
        }
        findViewById<CardView>(R.id.cardSmartEntry2).setOnClickListener {
            startActivity(Intent(this, MessengerAppsActivity::class.java))
        }
        findViewById<CardView>(R.id.cardSmartEntry3).setOnClickListener {
            startActivity(
                Intent(this, FileListActivity::class.java).apply {
                    putExtra("mode", "smart_documents")
                    putExtra("title", getString(R.string.smart_entry_3))
                    putExtra("path", StorageVolumeHelper.getStorageRoots(this@SmartCategoryActivity).internalRoot)
                }
            )
        }
        findViewById<CardView>(R.id.cardSmartEntry4).setOnClickListener {
            startActivity(Intent(this, EventPhotoBundleActivity::class.java))
        }

        loadCardSummaries()
    }

    private fun loadCardSummaries() {
        val card1: CardView = findViewById(R.id.cardSmartEntry1)
        val card2: CardView = findViewById(R.id.cardSmartEntry2)
        val card3: CardView = findViewById(R.id.cardSmartEntry3)
        val card4: CardView = findViewById(R.id.cardSmartEntry4)

        lifecycleScope.launch {
            val totalStartedAt = System.currentTimeMillis()
            val sharedDeferred = lifecycleScope.async(Dispatchers.IO) { summaryRepository.getFrequentlySharedSummary() }
            val messengerDeferred = lifecycleScope.async(Dispatchers.IO) { summaryRepository.getMessengerFilesSummary() }
            val documentsDeferred = lifecycleScope.async(Dispatchers.IO) { summaryRepository.getWorkDocumentsSummary() }
            val eventDeferred = lifecycleScope.async(Dispatchers.IO) { summaryRepository.getEventPhotoSummary() }

            val jobs = listOf(
                launch {
                    resolveWithTimeout("shared", sharedDeferred) { shared ->
                        if (shared.itemCount == 0) {
                                bindEmpty(
                                    card = card1,
                                    iconRes = android.R.drawable.ic_menu_share,
                                    iconTint = android.R.color.holo_blue_dark,
                                    title = getString(R.string.smart_entry_1),
                                    emptyTitle = getString(R.string.smart_shared_empty_title),
                                    emptyDesc = getString(R.string.smart_shared_empty_desc)
                                )
                        } else {
                            bindSummary(
                                card = card1,
                                iconRes = android.R.drawable.ic_menu_share,
                                iconTint = android.R.color.holo_blue_dark,
                                title = getString(R.string.smart_entry_1),
                                headline = getString(R.string.smart_shared_count_format, shared.itemCount),
                                sub0 = null,
                                sub1 = getString(R.string.smart_recent_shared_format, formatRelative(shared.lastSharedAt)),
                                sub2 = getString(R.string.smart_total_size_format, Formatter.formatFileSize(this@SmartCategoryActivity, shared.totalBytes))
                            )
                        }
                    }
                },
                launch {
                    resolveWithTimeout("messenger", messengerDeferred) { messenger ->
                        if (messenger.itemCount == 0) {
                            bindEmpty(
                                card = card2,
                                iconRes = R.drawable.ic_download,
                                iconTint = android.R.color.holo_green_dark,
                                title = getString(R.string.smart_entry_2),
                                emptyTitle = getString(R.string.smart_messenger_empty_title),
                                emptyDesc = getString(R.string.smart_messenger_empty_desc)
                            )
                        } else {
                            bindSummary(
                                card = card2,
                                iconRes = R.drawable.ic_download,
                                iconTint = android.R.color.holo_green_dark,
                                title = getString(R.string.smart_entry_2),
                                headline = getString(R.string.smart_messenger_headline_format, messenger.itemCount),
                                sub0 = null,
                                sub1 = getString(R.string.smart_recent_received_format, formatRelative(messenger.lastReceivedAtMs)),
                                sub2 = getString(R.string.smart_total_size_format, Formatter.formatFileSize(this@SmartCategoryActivity, messenger.totalBytes))
                            )
                        }
                    }
                },
                launch {
                    resolveWithTimeout("documents", documentsDeferred) { documents ->
                        if (documents.itemCount == 0) {
                            bindEmpty(
                                card = card3,
                                iconRes = R.drawable.ic_file,
                                iconTint = android.R.color.holo_blue_light,
                                title = getString(R.string.smart_entry_3),
                                emptyTitle = getString(R.string.smart_documents_empty_title),
                                emptyDesc = getString(R.string.smart_documents_empty_desc)
                            )
                        } else {
                            bindSummary(
                                card = card3,
                                iconRes = R.drawable.ic_file,
                                iconTint = android.R.color.holo_blue_light,
                                title = getString(R.string.smart_entry_3),
                                headline = getString(R.string.smart_documents_headline_format, documents.itemCount),
                                sub0 = null,
                                sub1 = getString(R.string.smart_recent_updated_format, formatRelative(documents.lastModifiedAtMs)),
                                sub2 = getString(R.string.smart_total_size_format, Formatter.formatFileSize(this@SmartCategoryActivity, documents.totalBytes))
                            )
                        }
                    }
                },
                launch {
                    resolveWithTimeout("event", eventDeferred) { event ->
                        if (event.itemCount == 0) {
                            bindEmpty(
                                card = card4,
                                iconRes = android.R.drawable.ic_menu_camera,
                                iconTint = android.R.color.black,
                                title = getString(R.string.smart_entry_4),
                                emptyTitle = getString(R.string.smart_event_empty_title),
                                emptyDesc = getString(R.string.smart_event_empty_desc)
                            )
                        } else {
                            bindSummary(
                                card = card4,
                                iconRes = android.R.drawable.ic_menu_camera,
                                iconTint = android.R.color.black,
                                title = getString(R.string.smart_entry_4),
                                headline = getString(R.string.smart_event_cluster_count_format, event.itemCount),
                                sub0 = null,
                                sub1 = getString(R.string.smart_event_rule_desc),
                                sub2 = getString(R.string.smart_total_size_format, Formatter.formatFileSize(this@SmartCategoryActivity, event.totalBytes))
                            )
                        }
                    }
                }
            )

            jobs.joinAll()
            Log.d("EventPerf", "smart_card totalMs=${System.currentTimeMillis() - totalStartedAt}")
        }
    }

    private suspend fun <T> resolveWithTimeout(
        tag: String,
        deferred: Deferred<T>,
        onResult: (T) -> Unit
    ) {
        val started = System.currentTimeMillis()
        val immediate = withTimeoutOrNull(CARD_TIMEOUT_MS) { deferred.await() }
        if (immediate != null) {
            Log.d("EventPerf", "smart_card ${tag}Ms=${System.currentTimeMillis() - started}")
            onResult(immediate)
            return
        }

        Log.d("EventPerf", "smart_card ${tag}Ms=${System.currentTimeMillis() - started} timeout=true")
        bindPendingTag(tag)

        val lateStarted = System.currentTimeMillis()
        val late = deferred.await()
        Log.d("EventPerf", "smart_card ${tag}LateMs=${System.currentTimeMillis() - lateStarted}")
        onResult(late)
    }

    private fun bindPendingTag(tag: String) {
        when (tag) {
            "shared" -> bindPending(
                card = findViewById(R.id.cardSmartEntry1),
                iconRes = android.R.drawable.ic_menu_share,
                iconTint = android.R.color.holo_blue_dark,
                title = getString(R.string.smart_entry_1)
            )
            "messenger" -> bindPending(
                card = findViewById(R.id.cardSmartEntry2),
                iconRes = R.drawable.ic_download,
                iconTint = android.R.color.holo_green_dark,
                title = getString(R.string.smart_entry_2)
            )
            "documents" -> bindPending(
                card = findViewById(R.id.cardSmartEntry3),
                iconRes = R.drawable.ic_file,
                iconTint = android.R.color.holo_blue_light,
                title = getString(R.string.smart_entry_3)
            )
            "event" -> bindPending(
                card = findViewById(R.id.cardSmartEntry4),
                iconRes = android.R.drawable.ic_menu_camera,
                iconTint = android.R.color.black,
                title = getString(R.string.smart_entry_4)
            )
        }
    }

    private fun bindSummary(
        card: CardView,
        iconRes: Int,
        iconTint: Int,
        title: String,
        headline: String,
        sub0: String?,
        sub1: String,
        sub2: String,
        preview: String? = null
    ) {
        val icon = card.findViewById<ImageView>(R.id.ivSmartIcon)
        val tvTitle = card.findViewById<TextView>(R.id.tvSmartTitle)
        val tvHeadline = card.findViewById<TextView>(R.id.tvSmartHeadline)
        val tvSub0 = card.findViewById<TextView>(R.id.tvSmartSub0)
        val tvSub1 = card.findViewById<TextView>(R.id.tvSmartSub1)
        val tvSub2 = card.findViewById<TextView>(R.id.tvSmartSub2)
        val tvPreview = card.findViewById<TextView>(R.id.tvSmartPreview)
        val tvEmptyTitle = card.findViewById<TextView>(R.id.tvSmartEmptyTitle)
        val tvEmptyDesc = card.findViewById<TextView>(R.id.tvSmartEmptyDesc)
        icon.setImageResource(iconRes)
        icon.imageTintList = ContextCompat.getColorStateList(this, iconTint)
        tvTitle.text = title
        tvHeadline.text = headline
        if (sub0.isNullOrBlank()) {
            tvSub0.visibility = View.GONE
        } else {
            tvSub0.visibility = View.VISIBLE
            tvSub0.text = sub0
        }
        tvSub1.text = sub1
        tvSub2.text = sub2
        tvHeadline.visibility = View.VISIBLE
        tvSub1.visibility = View.VISIBLE
        tvSub2.visibility = View.VISIBLE
        if (preview.isNullOrBlank()) {
            tvPreview.visibility = View.GONE
        } else {
            tvPreview.visibility = View.VISIBLE
            tvPreview.text = preview
        }
        tvEmptyTitle.visibility = View.GONE
        tvEmptyDesc.visibility = View.GONE
    }

    private fun bindEmpty(
        card: CardView,
        iconRes: Int,
        iconTint: Int,
        title: String,
        emptyTitle: String,
        emptyDesc: String
    ) {
        val icon = card.findViewById<ImageView>(R.id.ivSmartIcon)
        val tvTitle = card.findViewById<TextView>(R.id.tvSmartTitle)
        val tvHeadline = card.findViewById<TextView>(R.id.tvSmartHeadline)
        val tvSub0 = card.findViewById<TextView>(R.id.tvSmartSub0)
        val tvSub1 = card.findViewById<TextView>(R.id.tvSmartSub1)
        val tvSub2 = card.findViewById<TextView>(R.id.tvSmartSub2)
        val tvPreview = card.findViewById<TextView>(R.id.tvSmartPreview)
        val tvEmptyTitle = card.findViewById<TextView>(R.id.tvSmartEmptyTitle)
        val tvEmptyDesc = card.findViewById<TextView>(R.id.tvSmartEmptyDesc)
        icon.setImageResource(iconRes)
        icon.imageTintList = ContextCompat.getColorStateList(this, iconTint)
        tvTitle.text = title
        tvHeadline.visibility = View.GONE
        tvSub0.visibility = View.GONE
        tvSub1.visibility = View.GONE
        tvSub2.visibility = View.GONE
        tvPreview.visibility = View.GONE
        tvEmptyTitle.visibility = View.VISIBLE
        tvEmptyDesc.visibility = View.VISIBLE
        tvEmptyTitle.text = emptyTitle
        tvEmptyDesc.text = emptyDesc
    }

    private fun bindPending(
        card: CardView,
        iconRes: Int,
        iconTint: Int,
        title: String
    ) {
        bindEmpty(
            card = card,
            iconRes = iconRes,
            iconTint = iconTint,
            title = title,
            emptyTitle = getString(R.string.loading_label),
            emptyDesc = getString(R.string.smart_card_pending_desc)
        )
    }

    private fun formatRelative(timeMs: Long?): String {
        if (timeMs == null) return getString(R.string.smart_unknown)
        return DateUtils.getRelativeTimeSpanString(
            timeMs,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }

    companion object {
        private const val CARD_TIMEOUT_MS = 300L
    }
}
