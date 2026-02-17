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
import com.google.android.material.tabs.TabLayout
import java.io.File

class RecentExclusionActivity : AppCompatActivity() {
    private enum class ExclusionTab {
        FILE,
        FOLDER,
        EXTENSION
    }

    private lateinit var adapter: RecentExclusionAdapter
    private lateinit var rvItems: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tabLayout: TabLayout
    private var currentTab: ExclusionTab = ExclusionTab.FILE
    private var hasChanges: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recent_exclusion)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        rvItems = findViewById(R.id.rvExcludedItems)
        tvEmpty = findViewById(R.id.tvExcludedEmpty)
        tvSubtitle = findViewById(R.id.tvExcludedSubtitle)
        tabLayout = findViewById(R.id.tabExclusionType)

        adapter = RecentExclusionAdapter(
            onRemoveClick = { entry ->
                showRemoveDialog(entry)
            }
        )
        rvItems.layoutManager = LinearLayoutManager(this)
        rvItems.adapter = adapter

        setupTabs()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun setupTabs() {
        tabLayout.removeAllTabs()
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.recent_exclusion_tab_file)))
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.recent_exclusion_tab_folder)))
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.recent_exclusion_tab_extension)))
        tabLayout.getTabAt(0)?.select()
        currentTab = ExclusionTab.FILE
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = when (tab?.position ?: 0) {
                    1 -> ExclusionTab.FOLDER
                    2 -> ExclusionTab.EXTENSION
                    else -> ExclusionTab.FILE
                }
                refreshList()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })
    }

    private fun refreshList() {
        tvSubtitle.text = when (currentTab) {
            ExclusionTab.FILE -> getString(R.string.recent_exclusion_subtitle_file)
            ExclusionTab.FOLDER -> getString(R.string.recent_exclusion_subtitle_folder)
            ExclusionTab.EXTENSION -> getString(R.string.recent_exclusion_subtitle_extension)
        }

        val items = when (currentTab) {
            ExclusionTab.FILE -> buildFileEntries()
            ExclusionTab.FOLDER -> buildFolderEntries()
            ExclusionTab.EXTENSION -> buildExtensionEntries()
        }
        adapter.submitList(items)
        val isEmpty = items.isEmpty()
        tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rvItems.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun buildFileEntries(): List<RecentExclusionAdapter.Entry> {
        return RecentExclusionManager.getExcludedFiles(this)
            .toList()
            .sortedBy { it.lowercase() }
            .map { filePath ->
                val name = File(filePath).name.ifBlank { filePath }
                RecentExclusionAdapter.Entry(
                    key = filePath,
                    title = name,
                    subtitle = filePath,
                    meta = detectVolumeText(filePath),
                    type = RecentExclusionAdapter.Type.FILE
                )
            }
    }

    private fun buildFolderEntries(): List<RecentExclusionAdapter.Entry> {
        return RecentExclusionManager.getAll(this)
            .toList()
            .sortedBy { it.lowercase() }
            .map { folderPath ->
                val name = File(folderPath).name.ifBlank { folderPath }
                RecentExclusionAdapter.Entry(
                    key = folderPath,
                    title = name,
                    subtitle = folderPath,
                    meta = detectVolumeText(folderPath),
                    type = RecentExclusionAdapter.Type.FOLDER
                )
            }
    }

    private fun buildExtensionEntries(): List<RecentExclusionAdapter.Entry> {
        return RecentExclusionManager.getExcludedExtensions(this)
            .toList()
            .sortedBy { it.lowercase() }
            .map { extension ->
                val display = ".$extension"
                RecentExclusionAdapter.Entry(
                    key = extension,
                    title = display,
                    subtitle = getString(R.string.recent_exclusion_extension_subtitle, display),
                    meta = getString(R.string.recent_exclusion_meta_extension),
                    type = RecentExclusionAdapter.Type.EXTENSION
                )
            }
    }

    private fun detectVolumeText(path: String): String {
        val roots = StorageVolumeHelper.getStorageRoots(this)
        return when (StorageVolumeHelper.detectVolume(path, roots)) {
            StorageVolumeType.INTERNAL -> getString(R.string.internal_storage)
            StorageVolumeType.SD_CARD -> getString(R.string.sd_card)
            else -> getString(R.string.storage_other)
        }
    }

    private fun showRemoveDialog(entry: RecentExclusionAdapter.Entry) {
        val message = when (entry.type) {
            RecentExclusionAdapter.Type.FILE ->
                getString(R.string.recent_exclusion_remove_message_file, entry.subtitle)
            RecentExclusionAdapter.Type.FOLDER ->
                getString(R.string.recent_exclusion_remove_message_folder, entry.subtitle)
            RecentExclusionAdapter.Type.EXTENSION ->
                getString(R.string.recent_exclusion_remove_message_extension, entry.title)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.recent_exclusion_remove_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.action_remove)) { _, _ ->
                val removed = when (entry.type) {
                    RecentExclusionAdapter.Type.FILE -> RecentExclusionManager.removeFile(this, entry.key)
                    RecentExclusionAdapter.Type.FOLDER -> RecentExclusionManager.removeFolder(this, entry.key)
                    RecentExclusionAdapter.Type.EXTENSION -> RecentExclusionManager.removeExtension(this, entry.key)
                }
                if (removed) {
                    hasChanges = true
                    Toast.makeText(this, getString(R.string.recent_exclusion_removed), Toast.LENGTH_SHORT).show()
                    refreshList()
                } else {
                    Toast.makeText(this, getString(R.string.operation_failed), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    override fun finish() {
        if (hasChanges) {
            setResult(RESULT_OK)
        }
        super.finish()
    }
}
