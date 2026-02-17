package com.example.betterfiles

import android.content.Context
import java.io.File

object RecentExclusionManager {
    private const val PREFS_NAME = "BetterFilesPrefs"
    private const val KEY_EXCLUDED_FOLDERS = "recent_excluded_folders_v1"
    private const val KEY_EXCLUDED_FILES = "recent_excluded_files_v1"
    private const val KEY_EXCLUDED_EXTENSIONS = "recent_excluded_extensions_v1"

    data class Rules(
        val folders: Set<String>,
        val files: Set<String>,
        val extensions: Set<String>
    )

    fun getAll(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_EXCLUDED_FOLDERS, emptySet())
            ?.mapNotNull { normalize(it) }
            ?.toSet()
            ?: emptySet()
    }

    fun getExcludedFiles(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_EXCLUDED_FILES, emptySet())
            ?.mapNotNull { normalize(it) }
            ?.toSet()
            ?: emptySet()
    }

    fun getExcludedExtensions(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_EXCLUDED_EXTENSIONS, emptySet())
            ?.mapNotNull { normalizeExtension(it) }
            ?.toSet()
            ?: emptySet()
    }

    fun getRules(context: Context): Rules {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val folders = prefs.getStringSet(KEY_EXCLUDED_FOLDERS, emptySet())
            ?.mapNotNull { normalize(it) }
            ?.toSet()
            ?: emptySet()
        val files = prefs.getStringSet(KEY_EXCLUDED_FILES, emptySet())
            ?.mapNotNull { normalize(it) }
            ?.toSet()
            ?: emptySet()
        val extensions = prefs.getStringSet(KEY_EXCLUDED_EXTENSIONS, emptySet())
            ?.mapNotNull { normalizeExtension(it) }
            ?.toSet()
            ?: emptySet()
        return Rules(folders = folders, files = files, extensions = extensions)
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

    fun addFile(context: Context, filePath: String): Boolean {
        val normalized = normalize(filePath) ?: return false
        val current = getRules(context).files.toMutableSet()
        val added = current.add(normalized)
        if (added) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_EXCLUDED_FILES, current).apply()
        }
        return added
    }

    fun addExtension(context: Context, extension: String): Boolean {
        val normalized = normalizeExtension(extension) ?: return false
        val current = getRules(context).extensions.toMutableSet()
        val added = current.add(normalized)
        if (added) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_EXCLUDED_EXTENSIONS, current).apply()
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

    fun removeFile(context: Context, filePath: String): Boolean {
        val normalized = normalize(filePath) ?: return false
        val current = getExcludedFiles(context).toMutableSet()
        val removed = current.remove(normalized)
        if (removed) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_EXCLUDED_FILES, current).apply()
        }
        return removed
    }

    fun removeExtension(context: Context, extension: String): Boolean {
        val normalized = normalizeExtension(extension) ?: return false
        val current = getExcludedExtensions(context).toMutableSet()
        val removed = current.remove(normalized)
        if (removed) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_EXCLUDED_EXTENSIONS, current).apply()
        }
        return removed
    }

    fun isExcluded(filePath: String, excludedFolders: Set<String>): Boolean {
        val normalizedFile = normalize(filePath) ?: return false
        return excludedFolders.any { folder ->
            normalizedFile == folder || normalizedFile.startsWith("$folder${File.separator}")
        }
    }

    fun isExcluded(filePath: String, rules: Rules): Boolean {
        val normalizedFile = normalize(filePath) ?: return false
        if (rules.files.contains(normalizedFile)) {
            return true
        }
        if (rules.folders.any { folder ->
                normalizedFile == folder || normalizedFile.startsWith("$folder${File.separator}")
            }) {
            return true
        }

        val extension = File(normalizedFile).extension
        val normalizedExtension = normalizeExtension(extension)
        return normalizedExtension != null && rules.extensions.contains(normalizedExtension)
    }

    private fun normalize(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return path
            .replace('\\', File.separatorChar)
            .trimEnd(File.separatorChar)
    }

    private fun normalizeExtension(extension: String?): String? {
        if (extension.isNullOrBlank()) return null
        return extension.trim().trimStart('.').lowercase()
    }
}
