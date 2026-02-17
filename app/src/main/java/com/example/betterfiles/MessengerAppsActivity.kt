package com.example.betterfiles

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MessengerAppsActivity : AppCompatActivity() {
    private lateinit var summaryRepository: SmartCategorySummaryRepository
    private lateinit var cardContainer: LinearLayout
    private val cardMap = linkedMapOf<String, AppCardRefs>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messenger_apps)
        summaryRepository = SmartCategorySummaryRepository(this)
        cardContainer = findViewById(R.id.layoutMessengerAppCards)

        findViewById<ImageView>(R.id.btnBackMessengerApps).setOnClickListener { finish() }
        loadSummary()
    }

    private fun buildAppCards(orderedSources: List<MessengerSource>) {
        cardContainer.removeAllViews()
        cardMap.clear()
        val inflater = LayoutInflater.from(this)

        orderedSources.forEachIndexed { index, source ->
            val cardView = inflater.inflate(R.layout.item_messenger_app_card, cardContainer, false) as CardView
            if (index == 0) {
                val params = cardView.layoutParams as LinearLayout.LayoutParams
                params.topMargin = (resources.displayMetrics.density * 6f).toInt()
                cardView.layoutParams = params
            }

            val icon = cardView.findViewById<ImageView>(R.id.ivMessengerAppIcon)
            val title = cardView.findViewById<TextView>(R.id.tvMessengerAppName)
            val count = cardView.findViewById<TextView>(R.id.tvMessengerAppCount)
            val recent = cardView.findViewById<TextView>(R.id.tvMessengerAppRecent)

            val displayName = displayNameForSource(source.appName)
            title.text = displayName
            icon.setColorFilter(colorForSource(source.appName))
            count.text = getString(
                R.string.smart_messenger_app_count_size_format,
                0,
                Formatter.formatFileSize(this, 0)
            )
            recent.text = getString(R.string.smart_recent_received_format, getString(R.string.smart_unknown))
            cardView.contentDescription = displayName
            cardView.setOnClickListener {
                startActivity(
                    Intent(this, FileListActivity::class.java).apply {
                        putExtra("mode", "messenger")
                        putExtra("messengerApp", source.appName)
                        putExtra("title", displayName)
                        putExtra("path", StorageVolumeHelper.getStorageRoots(this@MessengerAppsActivity).internalRoot)
                    }
                )
            }

            cardContainer.addView(cardView)
            cardMap[source.appName] = AppCardRefs(cardView, count, recent)
        }
    }

    private fun loadSummary() {
        lifecycleScope.launch {
            val summaries = withContext(Dispatchers.IO) { summaryRepository.getMessengerAppSummaries() }
            val summaryByName = summaries.associateBy { it.appName }
            val orderedSources = MessengerPathMatcher.sources
                .filter { (summaryByName[it.appName]?.itemCount ?: 0) > 0 }
                .sortedWith(
                compareByDescending<MessengerSource> { summaryByName[it.appName]?.totalBytes ?: 0L }
                    .thenBy { displayNameForSource(it.appName).lowercase() }
                )
            buildAppCards(orderedSources)

            val totalCount = summaries.sumOf { it.itemCount }
            val totalBytes = summaries.sumOf { it.totalBytes }
            val latest = summaries.mapNotNull { it.lastReceivedAtMs }.maxOrNull()

            findViewById<TextView>(R.id.tvMessengerAppsTotal).text =
                getString(R.string.smart_messenger_headline_format, totalCount)
            findViewById<TextView>(R.id.tvMessengerAppsLatest).text =
                getString(
                    R.string.smart_recent_received_format,
                    latest?.let { formatRelative(it) } ?: getString(R.string.smart_unknown)
                )
            findViewById<TextView>(R.id.tvMessengerAppsSize).text =
                getString(R.string.smart_total_size_format, Formatter.formatFileSize(this@MessengerAppsActivity, totalBytes))

            summaries.forEach { summary ->
                val refs = cardMap[summary.appName] ?: return@forEach
                refs.count.text = getString(
                    R.string.smart_messenger_app_count_size_format,
                    summary.itemCount,
                    Formatter.formatFileSize(this@MessengerAppsActivity, summary.totalBytes)
                )
                refs.recent.text = getString(
                    R.string.smart_recent_received_format,
                    summary.lastReceivedAtMs?.let { formatRelative(it) } ?: getString(R.string.smart_unknown)
                )
                refs.card.alpha = if (summary.itemCount > 0) 1f else 0.6f
                refs.card.isEnabled = summary.itemCount > 0
                refs.card.isClickable = summary.itemCount > 0
                refs.card.foreground = if (summary.itemCount > 0) {
                    ContextCompat.getDrawable(this@MessengerAppsActivity, android.R.drawable.list_selector_background)
                } else {
                    null
                }
            }
        }
    }

    private fun formatRelative(timeMs: Long): String {
        return DateUtils.getRelativeTimeSpanString(
            timeMs,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }

    private fun displayNameForSource(source: String): String {
        return when (source) {
            "Messenger" -> getString(R.string.messenger_app_messenger)
            "KakaoTalk" -> getString(R.string.messenger_app_kakaotalk)
            "Telegram" -> getString(R.string.messenger_app_telegram)
            "WhatsApp" -> getString(R.string.messenger_app_whatsapp)
            "LINE" -> getString(R.string.messenger_app_line)
            "Discord" -> getString(R.string.messenger_app_discord)
            "Snapchat" -> getString(R.string.messenger_app_snapchat)
            "Viber" -> getString(R.string.messenger_app_viber)
            "Signal" -> getString(R.string.messenger_app_signal)
            "Facebook" -> getString(R.string.messenger_app_facebook)
            "TikTok" -> getString(R.string.messenger_app_tiktok)
            "Threads" -> getString(R.string.messenger_app_threads)
            "X" -> getString(R.string.messenger_app_x)
            "Zalo" -> getString(R.string.messenger_app_zalo)
            "Slack" -> getString(R.string.messenger_app_slack)
            else -> source
        }
    }

    private fun colorForSource(source: String): Int {
        return when (source) {
            "KakaoTalk" -> Color.parseColor("#F9A825")
            "Telegram" -> Color.parseColor("#039BE5")
            "WhatsApp" -> Color.parseColor("#43A047")
            "Messenger" -> Color.parseColor("#1E88E5")
            "LINE" -> Color.parseColor("#00B900")
            "Discord" -> Color.parseColor("#5865F2")
            "Snapchat" -> Color.parseColor("#FFD600")
            "Viber" -> Color.parseColor("#7C4DFF")
            "Signal" -> Color.parseColor("#1E88E5")
            "Facebook" -> Color.parseColor("#1877F2")
            "TikTok" -> Color.parseColor("#111111")
            "Threads" -> Color.parseColor("#111111")
            "X" -> Color.parseColor("#111111")
            "Zalo" -> Color.parseColor("#1E88E5")
            "Slack" -> Color.parseColor("#4A154B")
            else -> Color.parseColor("#546E7A")
        }
    }

    private data class AppCardRefs(
        val card: CardView,
        val count: TextView,
        val recent: TextView
    )
}
