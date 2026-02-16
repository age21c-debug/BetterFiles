package com.example.betterfiles

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class RecentExclusionActivity : AppCompatActivity() {
    private lateinit var adapter: RecentExclusionAdapter
    private lateinit var rvFolders: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recent_exclusion)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        rvFolders = findViewById(R.id.rvExcludedFolders)
        tvEmpty = findViewById(R.id.tvExcludedEmpty)

        adapter = RecentExclusionAdapter(
            onRemoveClick = { folderPath ->
                showRemoveDialog(folderPath)
            }
        )
        rvFolders.layoutManager = LinearLayoutManager(this)
        rvFolders.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val items = RecentExclusionManager.getAll(this).toList().sortedBy { it.lowercase() }
        adapter.submitList(items)
        val isEmpty = items.isEmpty()
        tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rvFolders.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun showRemoveDialog(folderPath: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.recent_exclusion_remove_title))
            .setMessage(getString(R.string.recent_exclusion_remove_message, folderPath))
            .setPositiveButton(getString(R.string.action_remove)) { _, _ ->
                val removed = RecentExclusionManager.removeFolder(this, folderPath)
                if (removed) {
                    Toast.makeText(this, getString(R.string.recent_exclusion_removed), Toast.LENGTH_SHORT).show()
                    refreshList()
                } else {
                    Toast.makeText(this, getString(R.string.operation_failed), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }
}
