package com.example.betterfiles

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

object ShareEventLogger {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val CLEANUP_INTERVAL_MS = 12L * 60L * 60L * 1000L
    @Volatile
    private var lastCleanupAt: Long = 0L

    fun recordShareAsync(context: Context, files: List<FileItem>) {
        if (files.isEmpty()) return
        val keys = files.mapNotNull { resolveFileKey(it) }.distinctBy { "${it.type}|${it.key}" }
        if (keys.isEmpty()) return

        scope.launch {
            val now = System.currentTimeMillis()
            val store = SmartShareHistoryStore.get(context)
            store.recordShare(keys, now, UUID.randomUUID().toString())
            maybeCleanup(store, now)
        }
    }

    private fun maybeCleanup(store: SmartShareHistoryStore, now: Long) {
        if (now - lastCleanupAt < CLEANUP_INTERVAL_MS) return
        lastCleanupAt = now
        val cutoff = now - 180L * 24L * 60L * 60L * 1000L
        store.pruneOldEvents(cutoff)
    }

    private fun resolveFileKey(item: FileItem): SmartShareFileKey? {
        val uri = item.contentUri
        if (uri != null && uri.scheme == "content") {
            resolveMediaIdKey(uri)?.let { return it }
            if (!uri.authority.isNullOrBlank() && !uri.authority!!.contains(".provider")) {
                return SmartShareFileKey(
                    type = SmartShareKeyType.DOC_URI,
                    key = uri.toString()
                )
            }
        }

        val normalized = normalizePath(item.path) ?: return null
        return SmartShareFileKey(
            type = SmartShareKeyType.PATH,
            key = normalized
        )
    }

    private fun resolveMediaIdKey(uri: Uri): SmartShareFileKey? {
        if (uri.authority != "media") return null
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return null
        val path = uri.path ?: ""
        val collection = when {
            path.contains("/images/", ignoreCase = true) -> "image"
            path.contains("/video/", ignoreCase = true) -> "video"
            path.contains("/audio/", ignoreCase = true) -> "audio"
            path.contains("/file/", ignoreCase = true) -> "file"
            else -> "file"
        }
        return SmartShareFileKey(
            type = SmartShareKeyType.MEDIA_ID,
            key = "media:$collection:$id"
        )
    }

    private fun normalizePath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }
    }
}

