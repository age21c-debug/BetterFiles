package com.example.betterfiles

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale

class EventPhotoBundleActivity : AppCompatActivity() {
    private lateinit var adapter: EventPhotoBundleAdapter
    private lateinit var repository: EventPhotoBundleRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_photo_bundle)

        repository = EventPhotoBundleRepository(this)
        adapter = EventPhotoBundleAdapter { cluster ->
            startActivity(
                Intent(this, FileListActivity::class.java).apply {
                    putExtra("mode", "event_cluster")
                    putExtra("title", formatRangeTitle(cluster.startMs, cluster.endMs))
                    putExtra("startMs", cluster.startMs)
                    putExtra("endMs", cluster.endMs)
                }
            )
        }

        findViewById<ImageView>(R.id.btnBackEventBundle).setOnClickListener { finish() }
        val rv = findViewById<RecyclerView>(R.id.rvEventBundle)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        load()
    }

    private fun load() {
        val tvCount = findViewById<TextView>(R.id.tvEventBundleCount)
        val tvEmpty = findViewById<TextView>(R.id.tvEventBundleEmpty)
        lifecycleScope.launch {
            val clusters = withContext(Dispatchers.IO) { repository.getRecentEventClusters() }
            if (clusters.isEmpty()) {
                tvCount.text = getString(R.string.smart_event_list_count_format, 0)
                tvEmpty.visibility = View.VISIBLE
                adapter.submitList(emptyList())
            } else {
                tvCount.text = getString(R.string.smart_event_list_manage_format, clusters.size)
                tvEmpty.visibility = View.GONE
                adapter.submitList(clusters)
            }
        }
    }

    private fun formatRangeTitle(startMs: Long, endMs: Long): String {
        val locale = Locale.getDefault()
        val startCal = Calendar.getInstance().apply { timeInMillis = startMs }
        val endCal = Calendar.getInstance().apply { timeInMillis = endMs }
        val sameDay = startCal.get(Calendar.YEAR) == endCal.get(Calendar.YEAR) &&
            startCal.get(Calendar.DAY_OF_YEAR) == endCal.get(Calendar.DAY_OF_YEAR)
        val base = if (sameDay) {
            if (locale.language == Locale.KOREAN.language) {
                SimpleDateFormat("M월 d일", locale).format(startMs)
            } else {
                SimpleDateFormat("MMM d", locale).format(startMs)
            }
        } else {
            val start = SimpleDateFormat("yyyy.MM.dd", locale).format(startMs)
            val end = SimpleDateFormat("yyyy.MM.dd", locale).format(endMs)
            "$start - $end"
        }
        return if (locale.language == Locale.KOREAN.language) {
            "$base 촬영 묶음"
        } else {
            "$base Photo bundle"
        }
    }
}
