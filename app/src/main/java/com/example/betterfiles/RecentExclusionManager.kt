package com.example.betterfiles

import android.content.Context
import java.io.File

object RecentExclusionManager {
    private const val PREFS_NAME = "BetterFilesPrefs"
    private const val KEY_EXCLUDED_FOLDERS = "recent_excluded_folders_v1"

    fun getAll(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_EXCLUDED_FOLDERS, emptySet())
            ?.mapNotNull { normalize(it) }
            ?.toSet()
            ?: emptySet()
    }

    fun addFolder(context: Context, folderPath: String): Boolean {
        val normalized = normalize(folderPath) ?: return false
        val current = getAll(context).toMutableSet()
        val added = current.add(normalized)
        if (added) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_EXCLUDED_FOLDERS, current).apply()
        }
        return added
    }

    fun removeFolder(context: Context, folderPath: String): Boolean {
        val normalized = normalize(folderPath) ?: return false
        val current = getAll(context).toMutableSet()
        val removed = current.remove(normalized)
        if (removed) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_EXCLUDED_FOLDERS, current).apply()
        }
        return removed
    }

    fun isExcluded(filePath: String, excludedFolders: Set<String>): Boolean {
        val normalizedFile = normalize(filePath) ?: return false
        return excludedFolders.any { folder ->
            normalizedFile == folder || normalizedFile.startsWith("$folder${File.separator}")
        }
    }

    private fun normalize(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return path
            .replace('\\', File.separatorChar)
            .trimEnd(File.separatorChar)
    }
}
