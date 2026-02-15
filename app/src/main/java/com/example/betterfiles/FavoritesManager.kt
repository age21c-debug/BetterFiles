package com.example.betterfiles

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

object FavoritesManager {
    private const val PREF_NAME = "BetterFilesFavorites"
    private const val KEY_FAVORITES_LEGACY = "favorite_paths"
    private const val KEY_FAVORITES_V2 = "favorites_v2"
    private const val SEP = "\u001F"

    data class FavoriteEntry(
        val path: String,
        val isDirectory: Boolean,
        val mediaId: Long? = null,
        val contentUri: String? = null,
        val name: String? = null
    )

    fun getAll(context: Context): List<FavoriteEntry> {
        migrateIfNeeded(context)
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val encoded = prefs.getStringSet(KEY_FAVORITES_V2, emptySet()) ?: emptySet()
        val entries = encoded.mapNotNull { decodeEntry(it) }.mapNotNull { resolveEntry(context, it) }
        return entries.sortedBy { (it.name ?: File(it.path).name).lowercase(Locale.getDefault()) }
    }

    fun add(context: Context, fileItem: FileItem) {
        migrateIfNeeded(context)
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val current = (prefs.getStringSet(KEY_FAVORITES_V2, emptySet()) ?: emptySet())
            .mapNotNull { decodeEntry(it) }
            .toMutableList()

        val newEntry = FavoriteEntry(
            path = fileItem.path,
            isDirectory = fileItem.isDirectory,
            mediaId = if (fileItem.isDirectory || fileItem.id <= 0L) null else fileItem.id,
            contentUri = if (fileItem.isDirectory) null else fileItem.contentUri?.toString(),
            name = fileItem.name
        )

        val exists = current.any { isSameFavorite(it, newEntry) }
        if (!exists) {
            current += newEntry
            save(context, current)
        }
    }

    fun add(context: Context, path: String) {
        val file = File(path)
        val item = FileItem(
            id = if (file.exists()) file.hashCode().toLong() else -1L,
            name = file.name.ifBlank { path },
            path = path,
            size = if (file.exists() && file.isFile) file.length() else 0L,
            dateModified = if (file.exists()) file.lastModified() / 1000 else 0L,
            mimeType = if (file.isDirectory) "resource/folder" else "*/*",
            isDirectory = file.isDirectory,
            contentUri = null
        )
        add(context, item)
    }

    fun remove(context: Context, fileItem: FileItem) {
        migrateIfNeeded(context)
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val current = (prefs.getStringSet(KEY_FAVORITES_V2, emptySet()) ?: emptySet())
            .mapNotNull { decodeEntry(it) }
            .toMutableList()
        val target = FavoriteEntry(
            path = fileItem.path,
            isDirectory = fileItem.isDirectory,
            mediaId = if (fileItem.isDirectory || fileItem.id <= 0L) null else fileItem.id,
            contentUri = if (fileItem.isDirectory) null else fileItem.contentUri?.toString(),
            name = fileItem.name
        )
        val filtered = current.filterNot { isSameFavorite(it, target) }
        save(context, filtered)
    }

    fun remove(context: Context, path: String) {
        migrateIfNeeded(context)
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val current = (prefs.getStringSet(KEY_FAVORITES_V2, emptySet()) ?: emptySet())
            .mapNotNull { decodeEntry(it) }
        val filtered = current.filterNot { it.path == path }
        save(context, filtered)
    }

    fun isFavorite(context: Context, fileItem: FileItem): Boolean {
        val favorites = getAll(context)
        val target = FavoriteEntry(
            path = fileItem.path,
            isDirectory = fileItem.isDirectory,
            mediaId = if (fileItem.isDirectory || fileItem.id <= 0L) null else fileItem.id,
            contentUri = if (fileItem.isDirectory) null else fileItem.contentUri?.toString(),
            name = fileItem.name
        )
        return favorites.any { isSameFavorite(it, target) }
    }

    fun isFavorite(context: Context, path: String): Boolean {
        val favorites = getAll(context)
        return favorites.any { it.path == path }
    }

    fun onPathRenamed(context: Context, oldPath: String, newPath: String, isDirectory: Boolean) {
        migrateIfNeeded(context)
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val current = (prefs.getStringSet(KEY_FAVORITES_V2, emptySet()) ?: emptySet())
            .mapNotNull { decodeEntry(it) }

        if (current.isEmpty()) return

        val oldPrefix = if (oldPath.endsWith(File.separator)) oldPath else "$oldPath${File.separator}"
        val updated = current.map { entry ->
            when {
                entry.path == oldPath -> {
                    val updatedName = File(newPath).name
                    entry.copy(path = newPath, name = updatedName)
                }
                isDirectory && entry.path.startsWith(oldPrefix) -> {
                    val suffix = entry.path.removePrefix(oldPrefix)
                    val replacedPath = File(newPath, suffix).absolutePath
                    entry.copy(path = replacedPath, name = File(replacedPath).name)
                }
                else -> entry
            }
        }
        save(context, updated)
    }

    private fun migrateIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_FAVORITES_V2)) return

        val legacy = prefs.getStringSet(KEY_FAVORITES_LEGACY, emptySet()) ?: emptySet()
        val migrated = legacy.map { path ->
            val file = File(path)
            FavoriteEntry(
                path = path,
                isDirectory = file.isDirectory,
                name = file.name
            )
        }
        save(context, migrated)
    }

    private fun save(context: Context, entries: List<FavoriteEntry>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val set = entries.map { encodeEntry(it) }.toSet()
        prefs.edit().putStringSet(KEY_FAVORITES_V2, set).apply()
    }

    private fun resolveEntry(context: Context, entry: FavoriteEntry): FavoriteEntry? {
        if (entry.isDirectory) {
            val file = File(entry.path)
            if (!file.exists() || !file.isDirectory) return null
            return entry.copy(name = file.name)
        }

        val resolvedFromUri = resolveFileFromUri(context, entry)
        if (resolvedFromUri != null) return resolvedFromUri

        val fallback = File(entry.path)
        if (!fallback.exists() || fallback.isDirectory) return null
        return entry.copy(name = fallback.name)
    }

    private fun resolveFileFromUri(context: Context, entry: FavoriteEntry): FavoriteEntry? {
        val uriString = entry.contentUri ?: return null
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val relativeCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)

                val resolvedId = if (idCol >= 0) cursor.getLong(idCol) else entry.mediaId
                val resolvedName = if (nameCol >= 0) cursor.getString(nameCol) else entry.name
                val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                val relativePath = if (relativeCol >= 0) cursor.getString(relativeCol) else null

                val resolvedPath = when {
                    !dataPath.isNullOrBlank() -> dataPath
                    !relativePath.isNullOrBlank() && !resolvedName.isNullOrBlank() -> {
                        File(Environment.getExternalStorageDirectory(), relativePath).resolve(resolvedName).absolutePath
                    }
                    else -> entry.path
                }

                val file = File(resolvedPath)
                if (!file.exists() || file.isDirectory) return null
                entry.copy(
                    path = resolvedPath,
                    mediaId = resolvedId,
                    name = resolvedName ?: file.name
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isSameFavorite(a: FavoriteEntry, b: FavoriteEntry): Boolean {
        if (a.isDirectory != b.isDirectory) return false
        if (a.isDirectory) return a.path == b.path
        if (!a.contentUri.isNullOrBlank() && !b.contentUri.isNullOrBlank()) {
            return a.contentUri == b.contentUri
        }
        if (a.mediaId != null && b.mediaId != null) {
            return a.mediaId == b.mediaId
        }
        return a.path == b.path
    }

    private fun encodeEntry(entry: FavoriteEntry): String {
        fun enc(value: String?): String = Uri.encode(value ?: "")
        return listOf(
            enc(entry.path),
            if (entry.isDirectory) "1" else "0",
            entry.mediaId?.toString() ?: "",
            enc(entry.contentUri),
            enc(entry.name)
        ).joinToString(SEP)
    }

    private fun decodeEntry(encoded: String): FavoriteEntry? {
        val parts = encoded.split(SEP)
        if (parts.size < 5) return null
        fun dec(value: String): String = Uri.decode(value)
        val path = dec(parts[0])
        if (path.isBlank()) return null
        val isDirectory = parts[1] == "1"
        val mediaId = parts[2].toLongOrNull()
        val contentUri = dec(parts[3]).ifBlank { null }
        val name = dec(parts[4]).ifBlank { null }
        return FavoriteEntry(path, isDirectory, mediaId, contentUri, name)
    }
}
