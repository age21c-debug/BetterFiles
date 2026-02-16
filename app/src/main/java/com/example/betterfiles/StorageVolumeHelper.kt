package com.example.betterfiles

import android.content.Context
import android.os.Environment
import java.io.File

enum class StorageVolumeType {
    INTERNAL,
    SD_CARD,
    OTHER
}

data class StorageRoots(
    val internalRoot: String,
    val sdRoots: List<String>
)

object StorageVolumeHelper {
    // Debug-only switch for validating SD UI flows on devices without physical SD.
    private const val DEBUG_FORCE_SD = false
    private const val DEBUG_FAKE_SD_ROOT = "/storage/FAKE-SD"

    fun getStorageRoots(context: Context): StorageRoots {
        val internalRoot = normalize(Environment.getExternalStorageDirectory().absolutePath)
        if (DEBUG_FORCE_SD) {
            return StorageRoots(internalRoot = internalRoot, sdRoots = listOf(DEBUG_FAKE_SD_ROOT))
        }
        val externalDirs = context.getExternalFilesDirs(null).orEmpty()
        val sdRoots = externalDirs
            .drop(1)
            .mapNotNull { dir ->
                val path = dir?.absolutePath ?: return@mapNotNull null
                val marker = "${File.separator}Android${File.separator}"
                val markerIndex = path.indexOf(marker, ignoreCase = true)
                val root = if (markerIndex > 0) path.substring(0, markerIndex) else path
                normalize(root)
            }
            .distinct()
            .filter { it.isNotBlank() }
        return StorageRoots(internalRoot = internalRoot, sdRoots = sdRoots)
    }

    fun hasSdCard(context: Context): Boolean = getStorageRoots(context).sdRoots.isNotEmpty()

    fun primarySdRoot(context: Context): String? = getStorageRoots(context).sdRoots.firstOrNull()

    fun detectVolume(path: String, roots: StorageRoots): StorageVolumeType {
        val normalizedPath = normalize(path)
        if (normalizedPath.startsWith(roots.internalRoot, ignoreCase = true)) {
            return StorageVolumeType.INTERNAL
        }
        if (roots.sdRoots.any { normalizedPath.startsWith(it, ignoreCase = true) }) {
            return StorageVolumeType.SD_CARD
        }
        return StorageVolumeType.OTHER
    }

    private fun normalize(path: String): String {
        return path
            .replace('\\', File.separatorChar)
            .trimEnd(File.separatorChar)
    }
}
