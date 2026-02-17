package com.example.betterfiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.location.Location
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class EventPhotoCluster(
    val startMs: Long,
    val endMs: Long,
    val photoCount: Int,
    val totalBytes: Long,
    val previewPath: String?
)

class EventPhotoBundleRepository(private val context: Context) {
    private val hashStore = EventHashCacheStore.get(context)

    suspend fun getRecentEventClusters(): List<EventPhotoCluster> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val cached = cachedEvents
            if (cached != null && now - cachedAtMs <= CACHE_TTL_MS) {
                return@withContext cached
            }
        }

        val startedAt = now
        val stats = PerfStats()
        val cutoffSec = (now - YEAR_MS) / 1000L

        val queryStartedAt = System.currentTimeMillis()
        val queryResult = queryImageRows(cutoffSec)
        val rows = queryResult.rows
            .filter { it.sizeBytes > MIN_SIZE_BYTES }
            .sortedBy { it.timestampMs }
        val queryElapsed = System.currentTimeMillis() - queryStartedAt
        if (rows.isEmpty()) {
            synchronized(cacheLock) {
                cachedEvents = emptyList()
                cachedAtMs = System.currentTimeMillis()
            }
            return@withContext emptyList()
        }

        val timeClusters = buildTimeClusters(rows)
        val events = mutableListOf<EventPhotoCluster>()
        var budgetHit = false
        var processedTimeClusters = 0
        var excludedNoCamera = 0
        var totalSubClusters = 0
        var excludedSubSmall = 0
        var excludedNoDensity = 0
        var hashAppliedSubs = 0
        var hashSkippedSubs = 0

        for (timeCluster in timeClusters) {
            processedTimeClusters += 1
            if (System.currentTimeMillis() - startedAt >= EVENT_PROCESS_BUDGET_MS) {
                budgetHit = true
                break
            }

            val sourceGroups = timeCluster.groupBy { it.source }
            val cameraRows = sourceGroups[EventSource.CAMERA]
                .orEmpty()
                .sortedBy { it.timestampMs }
            if (cameraRows.size < MIN_PHOTO_COUNT) {
                excludedNoCamera += 1
                continue
            }

            val pass1 = splitByTimeAndGps(cameraRows, stats)
            for (sub in pass1.subClusters) {
                totalSubClusters += 1
                if (sub.size < MIN_PHOTO_COUNT) {
                    excludedSubSmall += 1
                    continue
                }

                // Density first: if it already fails event criteria, skip hash entirely.
                if (!hasDensityPeak(sub)) {
                    excludedNoDensity += 1
                    hashSkippedSubs += 1
                    continue
                }

                val shouldUseHash = shouldApplyHash(sub, pass1.cutCount)
                val finalSubs = if (shouldUseHash) {
                    hashAppliedSubs += 1
                    val clusterQuota = computeClusterHashQuota(sub.size, stats.hashComputed)
                    splitByHashAdaptive(sub, stats, clusterQuota)
                } else {
                    hashSkippedSubs += 1
                    listOf(sub)
                }

                for (finalSub in finalSubs) {
                    if (finalSub.size < MIN_PHOTO_COUNT) {
                        excludedSubSmall += 1
                        continue
                    }
                    if (!hasDensityPeak(finalSub)) {
                        excludedNoDensity += 1
                        continue
                    }

                    events += EventPhotoCluster(
                        startMs = finalSub.first().timestampMs,
                        endMs = finalSub.last().timestampMs,
                        photoCount = finalSub.size,
                        totalBytes = finalSub.sumOf { it.sizeBytes },
                        previewPath = chooseRepresentativePreviewPath(finalSub)
                    )
                }
            }
        }

        val finalEvents = events
            .sortedByDescending { it.endMs }
            .take(MAX_EVENTS)

        synchronized(cacheLock) {
            cachedEvents = finalEvents
            cachedAtMs = System.currentTimeMillis()
        }

        val totalElapsed = System.currentTimeMillis() - startedAt
        Log.d(
            TAG,
            "event_clusters totalMs=$totalElapsed queryMs=$queryElapsed rows=${rows.size} timeClusters=${timeClusters.size} " +
                "events=${finalEvents.size} hashCalls=${stats.hashCalls} hashMemHits=${stats.hashMemHits} hashDbHits=${stats.hashDbHits} hashComputed=${stats.hashComputed} budgetHit=$budgetHit " +
                "processedTimeClusters=$processedTimeClusters excludedNoCamera=$excludedNoCamera totalSubClusters=$totalSubClusters excludedSubSmall=$excludedSubSmall excludedNoDensity=$excludedNoDensity " +
                "hashAppliedSubs=$hashAppliedSubs hashSkippedSubs=$hashSkippedSubs " +
                "queryProviderMs=${nsToMs(queryResult.stats.providerNs)} queryIterMs=${nsToMs(queryResult.stats.iterNs)} queryFileCheckMs=${nsToMs(queryResult.stats.fileCheckNs)} querySourceMs=${nsToMs(queryResult.stats.sourceDetectNs)} queryVisited=${queryResult.stats.visitedRows} queryAccepted=${queryResult.stats.acceptedRows} " +
                "splitLoopMs=${nsToMs(stats.splitLoopNs)} timeRuleMs=${nsToMs(stats.timeRuleNs)} gpsRuleMs=${nsToMs(stats.gpsRuleNs)} hashRuleMs=${nsToMs(stats.hashRuleNs)} " +
                "timeCuts=${stats.timeCuts} gpsCuts=${stats.gpsCuts} hashCuts=${stats.hashCuts} hashLookupMs=${nsToMs(stats.hashLookupNs)} hashComputeMs=${nsToMs(stats.hashComputeNs)}"
        )

        finalEvents
    }

    suspend fun getClusterPhotos(startMs: Long, endMs: Long): List<FileItem> = withContext(Dispatchers.IO) {
        val startSec = (startMs / 1000L).toString()
        val endSec = (endMs / 1000L).toString()
        val rows = queryImageRowsInRange(startSec, endSec)
            .asSequence()
            .filter { it.source == EventSource.CAMERA }
            .filter { it.sizeBytes > MIN_SIZE_BYTES }
            .sortedByDescending { it.timestampMs }
            .toList()

        rows.mapIndexed { index, row ->
            FileItem(
                id = index.toLong(),
                name = File(row.path).name,
                path = row.path,
                size = row.sizeBytes,
                dateModified = row.timestampMs / 1000L,
                mimeType = "image/*",
                isDirectory = false,
                contentUri = null
            )
        }
    }

    private data class ImageRow(
        val path: String,
        val sizeBytes: Long,
        val timestampMs: Long,
        val source: EventSource,
        val latitude: Double?,
        val longitude: Double?,
        val fileMtimeMs: Long,
        val width: Int,
        val height: Int
    )

    private data class SplitResult(
        val subClusters: List<List<ImageRow>>,
        val cutCount: Int
    )

    private data class QueryRowsResult(
        val rows: List<ImageRow>,
        val stats: QueryStats
    )

    private data class QueryStats(
        var providerNs: Long = 0L,
        var iterNs: Long = 0L,
        var fileCheckNs: Long = 0L,
        var sourceDetectNs: Long = 0L,
        var visitedRows: Int = 0,
        var acceptedRows: Int = 0
    )

    private enum class EventSource {
        CAMERA,
        SCREENSHOT,
        DOWNLOAD,
        MESSENGER,
        OTHER
    }

    private data class PerfStats(
        var hashCalls: Int = 0,
        var hashMemHits: Int = 0,
        var hashDbHits: Int = 0,
        var hashComputed: Int = 0,
        var splitLoopNs: Long = 0L,
        var timeRuleNs: Long = 0L,
        var gpsRuleNs: Long = 0L,
        var hashRuleNs: Long = 0L,
        var timeCuts: Int = 0,
        var gpsCuts: Int = 0,
        var hashCuts: Int = 0,
        var hashLookupNs: Long = 0L,
        var hashComputeNs: Long = 0L
    )

    private fun shouldApplyHash(cameraRows: List<ImageRow>, timeGpsCutCount: Int): Boolean {
        val durationMs = (cameraRows.lastOrNull()?.timestampMs ?: 0L) - (cameraRows.firstOrNull()?.timestampMs ?: 0L)

        // If time/GPS already created enough cuts, skip hash for normal-sized clusters.
        if (timeGpsCutCount >= HASH_SKIP_CUTS_THRESHOLD && cameraRows.size < HASH_FORCE_SIZE_THRESHOLD) {
            return false
        }

        // Hash only on big/ambiguous clusters to avoid burning compute budget globally.
        if (cameraRows.size < HASH_MIN_CANDIDATE_SIZE && durationMs < HASH_MIN_CANDIDATE_DURATION_MS) {
            return false
        }

        val largeAndAmbiguous = cameraRows.size >= (MIN_PHOTO_COUNT * 2) && timeGpsCutCount <= LOW_CUT_COUNT_THRESHOLD
        val longDuration = durationMs >= HASH_ENABLE_DURATION_MS && cameraRows.size >= MIN_PHOTO_COUNT
        return largeAndAmbiguous || longDuration
    }

    private fun computeClusterHashQuota(clusterSize: Int, currentGlobalComputed: Int): Int {
        val bySize = (clusterSize / HASH_QUOTA_DIVISOR).coerceIn(HASH_QUOTA_MIN, HASH_QUOTA_MAX)
        val remainingGlobal = (HASH_MAX_COMPUTE - currentGlobalComputed).coerceAtLeast(0)
        return minOf(bySize, remainingGlobal)
    }

    private fun buildTimeClusters(rows: List<ImageRow>): List<List<ImageRow>> {
        if (rows.isEmpty()) return emptyList()
        val clusters = mutableListOf<MutableList<ImageRow>>()
        var current = mutableListOf(rows.first())

        for (index in 1 until rows.size) {
            val row = rows[index]
            val last = current.last()
            if (row.timestampMs - last.timestampMs <= GAP_MS) {
                current += row
            } else {
                clusters += current
                current = mutableListOf(row)
            }
        }
        clusters += current
        return clusters
    }

    private fun splitByTimeAndGps(cluster: List<ImageRow>, stats: PerfStats): SplitResult {
        if (cluster.size <= 1) return SplitResult(listOf(cluster), 0)

        val gpsCount = cluster.count { it.latitude != null && it.longitude != null }
        val gpsRatio = gpsCount.toDouble() / cluster.size.toDouble()
        val useGpsSplit = gpsRatio >= GPS_REQUIRED_RATIO

        val result = mutableListOf<MutableList<ImageRow>>()
        var current = mutableListOf(cluster.first())
        var cutCount = 0

        for (i in 1 until cluster.size) {
            val loopStart = System.nanoTime()
            val prev = cluster[i - 1]
            val curr = cluster[i]
            var cut = false
            var reason = 0

            val timeStart = System.nanoTime()
            if (curr.timestampMs - prev.timestampMs >= SPLIT_GAP_MS) {
                cut = true
                reason = 1
            }
            stats.timeRuleNs += (System.nanoTime() - timeStart)

            if (!cut && useGpsSplit) {
                val gpsStart = System.nanoTime()
                val dist = distanceMeters(prev, curr)
                if (dist != null && dist >= GPS_SPLIT_METERS) {
                    cut = true
                    reason = 2
                }
                stats.gpsRuleNs += (System.nanoTime() - gpsStart)
            }

            if (cut) {
                result += current
                current = mutableListOf(curr)
                cutCount += 1
                if (reason == 1) stats.timeCuts += 1 else if (reason == 2) stats.gpsCuts += 1
            } else {
                current += curr
            }
            stats.splitLoopNs += (System.nanoTime() - loopStart)
        }

        result += current
        return SplitResult(result, cutCount)
    }

    private fun splitByHashAdaptive(
        cluster: List<ImageRow>,
        stats: PerfStats,
        clusterComputeQuota: Int
    ): List<List<ImageRow>> {
        if (cluster.size <= 1 || cluster.size < HASH_MIN_CLUSTER_SIZE) return listOf(cluster)
        if (stats.hashComputed >= HASH_MAX_COMPUTE || clusterComputeQuota <= 0) return listOf(cluster)

        val computedStart = stats.hashComputed

        val stride = maxOf(HASH_MIN_SAMPLE_STEP, cluster.size / HASH_CLUSTER_DIVISOR)
        val suspects = mutableListOf<Int>()

        val coarseStart = stride
        var i = coarseStart
        while (
            i < cluster.size &&
            stats.hashComputed < HASH_MAX_COMPUTE &&
            (stats.hashComputed - computedStart) < clusterComputeQuota
        ) {
            val hashStart = System.nanoTime()
            val prev = cluster[i - stride]
            val curr = cluster[i]
            val hPrev = getDHash64(prev, stats)
            val hCurr = getDHash64(curr, stats)
            if (hPrev != null && hCurr != null) {
                val hamming = java.lang.Long.bitCount(hPrev xor hCurr)
                if (hamming >= HASH_SPLIT_THRESHOLD) {
                    suspects += i
                }
            }
            stats.hashRuleNs += (System.nanoTime() - hashStart)
            i += stride
        }

        if (suspects.isEmpty()) return listOf(cluster)

        val cutIndices = linkedSetOf<Int>()
        for (suspect in suspects) {
            val from = maxOf(1, suspect - HASH_REFINE_RADIUS)
            val to = minOf(cluster.lastIndex, suspect + HASH_REFINE_RADIUS)
            for (j in from..to) {
                if (
                    stats.hashComputed >= HASH_MAX_COMPUTE ||
                    (stats.hashComputed - computedStart) >= clusterComputeQuota
                ) break
                val hashStart = System.nanoTime()
                val hPrev = getDHash64(cluster[j - 1], stats)
                val hCurr = getDHash64(cluster[j], stats)
                if (hPrev != null && hCurr != null) {
                    val hamming = java.lang.Long.bitCount(hPrev xor hCurr)
                    if (hamming >= HASH_SPLIT_THRESHOLD) {
                        cutIndices += j
                        stats.hashCuts += 1
                    }
                }
                stats.hashRuleNs += (System.nanoTime() - hashStart)
            }
        }

        if (cutIndices.isEmpty()) return listOf(cluster)

        val sortedCuts = cutIndices.toList().sorted()
        val result = mutableListOf<List<ImageRow>>()
        var start = 0
        for (cut in sortedCuts) {
            if (cut > start) {
                result += cluster.subList(start, cut)
            }
            start = cut
        }
        if (start < cluster.size) {
            result += cluster.subList(start, cluster.size)
        }
        return result
    }

    private fun hasDensityPeak(rows: List<ImageRow>): Boolean {
        if (rows.size < DENSITY_MIN_PHOTOS) return false
        var i = 0
        for (j in rows.indices) {
            while (rows[j].timestampMs - rows[i].timestampMs > DENSITY_WINDOW_MS) {
                i += 1
            }
            val windowCount = j - i + 1
            if (windowCount >= DENSITY_MIN_PHOTOS) {
                return true
            }
        }
        return false
    }

    private fun detectSource(path: String): EventSource? {
        val p = path.replace('\\', '/').lowercase(Locale.ROOT)

        if (p.contains("/cache/") || p.contains("/temp/") || p.contains("thumbnails")) return null

        if (
            p.contains("screenshots") ||
            p.contains("screen_shot") ||
            p.contains("screenshot") ||
            p.contains("스크린샷")
        ) return EventSource.SCREENSHOT

        if (p.contains("/dcim/camera") || isCameraPath(p)) return EventSource.CAMERA

        if (
            p.contains("whatsapp") ||
            p.contains("org.telegram") ||
            p.contains("telegram") ||
            p.contains("kakaotalk") ||
            p.contains("com.kakao.talk") ||
            p.contains("jp.naver.line") ||
            p.contains("/line/") ||
            p.contains("discord") ||
            p.contains("viber") ||
            p.contains("signal") ||
            p.contains("zalo") ||
            p.contains("slack")
        ) return EventSource.MESSENGER

        if (p.contains("/download/") || p.contains("downloads")) return EventSource.DOWNLOAD
        return EventSource.OTHER
    }

    private fun isCameraPath(path: String): Boolean {
        if (path.contains("/dcim/camera")) return true
        return CAMERA_SEGMENT_HINTS.any { seg -> path.contains("/dcim/$seg/") }
    }

    private fun containsAny(path: String, hints: List<String>): Boolean {
        return hints.any { path.contains(it) }
    }

    private fun distanceMeters(a: ImageRow, b: ImageRow): Float? {
        val latA = a.latitude ?: return null
        val lonA = a.longitude ?: return null
        val latB = b.latitude ?: return null
        val lonB = b.longitude ?: return null

        val result = FloatArray(1)
        Location.distanceBetween(latA, lonA, latB, lonB, result)
        return result[0]
    }

    private val hashCache = object : LinkedHashMap<String, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 400
        }
    }

    private fun hashKey(row: ImageRow): String = "${row.path}|${row.sizeBytes}|${row.fileMtimeMs}"

    private fun getDHash64(row: ImageRow, stats: PerfStats): Long? {
        stats.hashCalls += 1
        val key = hashKey(row)

        val lookupStarted = System.nanoTime()
        synchronized(hashCache) {
            hashCache[key]?.let {
                stats.hashMemHits += 1
                stats.hashLookupNs += (System.nanoTime() - lookupStarted)
                return it
            }
        }

        val persisted = hashStore.get(row.path, row.sizeBytes, row.fileMtimeMs)
        if (persisted != null) {
            synchronized(hashCache) { hashCache[key] = persisted }
            stats.hashDbHits += 1
            stats.hashLookupNs += (System.nanoTime() - lookupStarted)
            return persisted
        }
        stats.hashLookupNs += (System.nanoTime() - lookupStarted)

        val computeStarted = System.nanoTime()
        val hash = computeDHash64(row.path) ?: return null
        stats.hashComputeNs += (System.nanoTime() - computeStarted)

        synchronized(hashCache) {
            hashCache[key] = hash
        }
        hashStore.put(row.path, row.sizeBytes, row.fileMtimeMs, hash)
        stats.hashComputed += 1
        return hash
    }

    private fun computeDHash64(path: String): Long? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 64, 64)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null
        val scaled = Bitmap.createScaledBitmap(decoded, 9, 8, true)
        if (scaled != decoded) decoded.recycle()

        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = luminance(scaled.getPixel(x, y))
                val right = luminance(scaled.getPixel(x + 1, y))
                if (left > right) {
                    hash = hash or (1L shl bit)
                }
                bit += 1
            }
        }
        scaled.recycle()
        return hash
    }

    private fun luminance(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        var w = width
        var h = height
        while (w / 2 >= reqWidth && h / 2 >= reqHeight) {
            w /= 2
            h /= 2
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun chooseRepresentativePreviewPath(rows: List<ImageRow>): String? {
        if (rows.isEmpty()) return null
        val last = rows.last()
        if (isValidPreviewCandidate(last)) return last.path

        // Backup candidates from tail side, max 5 files only.
        val limit = minOf(rows.size, PREVIEW_MAX_CANDIDATES)
        val n = rows.lastIndex
        val special = if (rows.size >= 5) (n - 4) else (rows.size / 2)
        val candidates = linkedSetOf(n - 1, n - 2, special, n - 3, n - 4)
        for (idx in candidates) {
            if (idx in (rows.size - limit) until rows.size) {
                val row = rows[idx]
                if (isValidPreviewCandidate(row)) return row.path
            }
        }
        return last.path
    }

    private fun isValidPreviewCandidate(row: ImageRow): Boolean {
        if (row.sizeBytes < PREVIEW_MIN_BYTES) return false
        val w = row.width
        val h = row.height
        if (w <= 0 || h <= 0) return true
        val ratio = w.toFloat() / h.toFloat()
        return ratio in PREVIEW_MIN_ASPECT..PREVIEW_MAX_ASPECT
    }

    private fun nsToMs(ns: Long): Long = ns / 1_000_000L

    private fun queryImageRows(cutoffSec: Long): QueryRowsResult {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.ImageColumns.LATITUDE,
            MediaStore.Images.ImageColumns.LONGITUDE
        )
        val selection = "(${MediaStore.Images.Media.DATE_MODIFIED} >= ? OR ${MediaStore.Images.Media.DATE_ADDED} >= ? OR ${MediaStore.Images.Media.DATE_TAKEN} >= ?)"
        val args = arrayOf(cutoffSec.toString(), cutoffSec.toString(), (cutoffSec * 1000L).toString())

        val stats = QueryStats()
        val result = mutableListOf<ImageRow>()
        val providerStart = System.nanoTime()
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            stats.providerNs += (System.nanoTime() - providerStart)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
            val relCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val addCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            val latCol = cursor.getColumnIndex(MediaStore.Images.ImageColumns.LATITUDE)
            val lonCol = cursor.getColumnIndex(MediaStore.Images.ImageColumns.LONGITUDE)

            while (cursor.moveToNext()) {
                val iterStart = System.nanoTime()
                try {
                    stats.visitedRows += 1
                    val mime = cursor.getString(mimeCol)?.lowercase(Locale.ROOT) ?: continue
                    if (!mime.startsWith("image/")) continue
                    val name = cursor.getString(nameCol) ?: continue
                    val dataPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                    val relPath = if (relCol >= 0) cursor.getString(relCol) else null
                    val resolvedPath = when {
                        !dataPath.isNullOrBlank() -> dataPath
                        !relPath.isNullOrBlank() -> File(Environment.getExternalStorageDirectory(), relPath).resolve(name).absolutePath
                        else -> continue
                    }
                    val fileCheckStart = System.nanoTime()
                    // Skip File.exists()/isDirectory checks here to avoid per-row disk I/O.
                    // MediaStore already provides indexed media rows; stale rows are handled lazily later.
                    stats.fileCheckNs += (System.nanoTime() - fileCheckStart)

                    val sourceStart = System.nanoTime()
                    val source = detectSource(resolvedPath) ?: continue
                    stats.sourceDetectNs += (System.nanoTime() - sourceStart)
                    val modifiedMs = cursor.getLong(modCol) * 1000L
                    val addedMs = cursor.getLong(addCol) * 1000L
                    val takenMs = if (takenCol >= 0) cursor.getLong(takenCol) else 0L
                    val timestampMs = when {
                        takenMs > 0L -> takenMs
                        addedMs > 0L -> addedMs
                        else -> modifiedMs
                    }

                    val latitude = if (latCol >= 0 && !cursor.isNull(latCol)) cursor.getDouble(latCol) else null
                    val longitude = if (lonCol >= 0 && !cursor.isNull(lonCol)) cursor.getDouble(lonCol) else null
                    val validLat = latitude?.takeIf { it != 0.0 }
                    val validLon = longitude?.takeIf { it != 0.0 }

                    result += ImageRow(
                        path = resolvedPath,
                        sizeBytes = cursor.getLong(sizeCol),
                        timestampMs = timestampMs,
                        source = source,
                        latitude = validLat,
                        longitude = validLon,
                        fileMtimeMs = modifiedMs,
                        width = if (widthCol >= 0 && !cursor.isNull(widthCol)) cursor.getInt(widthCol) else 0,
                        height = if (heightCol >= 0 && !cursor.isNull(heightCol)) cursor.getInt(heightCol) else 0
                    )
                    stats.acceptedRows += 1
                } finally {
                    stats.iterNs += (System.nanoTime() - iterStart)
                }
            }
        }
        if (stats.providerNs == 0L) {
            stats.providerNs = System.nanoTime() - providerStart
        }
        return QueryRowsResult(result, stats)
    }

    private fun queryImageRowsInRange(startSec: String, endSec: String): List<ImageRow> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.ImageColumns.LATITUDE,
            MediaStore.Images.ImageColumns.LONGITUDE
        )
        val startMs = (startSec.toLongOrNull() ?: 0L) * 1000L
        val endMs = (endSec.toLongOrNull() ?: 0L) * 1000L
        val selection = "(((${MediaStore.Images.Media.DATE_MODIFIED} BETWEEN ? AND ?) OR (${MediaStore.Images.Media.DATE_ADDED} BETWEEN ? AND ?)) OR (${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ?))"
        val args = arrayOf(startSec, endSec, startSec, endSec, startMs.toString(), endMs.toString())

        val result = mutableListOf<ImageRow>()
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
            val relCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val addCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            val latCol = cursor.getColumnIndex(MediaStore.Images.ImageColumns.LATITUDE)
            val lonCol = cursor.getColumnIndex(MediaStore.Images.ImageColumns.LONGITUDE)

            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeCol)?.lowercase(Locale.ROOT) ?: continue
                if (!mime.startsWith("image/")) continue
                val name = cursor.getString(nameCol) ?: continue
                val dataPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                val relPath = if (relCol >= 0) cursor.getString(relCol) else null
                val resolvedPath = when {
                    !dataPath.isNullOrBlank() -> dataPath
                    !relPath.isNullOrBlank() -> File(Environment.getExternalStorageDirectory(), relPath).resolve(name).absolutePath
                    else -> continue
                }
                val file = File(resolvedPath)
                if (!file.exists() || file.isDirectory) continue

                val source = detectSource(resolvedPath) ?: continue
                val modifiedMs = cursor.getLong(modCol) * 1000L
                val addedMs = cursor.getLong(addCol) * 1000L
                val takenMs = if (takenCol >= 0) cursor.getLong(takenCol) else 0L
                val timestampMs = when {
                    takenMs > 0L -> takenMs
                    addedMs > 0L -> addedMs
                    else -> modifiedMs
                }

                val latitude = if (latCol >= 0 && !cursor.isNull(latCol)) cursor.getDouble(latCol) else null
                val longitude = if (lonCol >= 0 && !cursor.isNull(lonCol)) cursor.getDouble(lonCol) else null
                val validLat = latitude?.takeIf { it != 0.0 }
                val validLon = longitude?.takeIf { it != 0.0 }

                result += ImageRow(
                    path = resolvedPath,
                    sizeBytes = cursor.getLong(sizeCol),
                    timestampMs = timestampMs,
                    source = source,
                    latitude = validLat,
                    longitude = validLon,
                    fileMtimeMs = file.lastModified(),
                    width = if (widthCol >= 0 && !cursor.isNull(widthCol)) cursor.getInt(widthCol) else 0,
                    height = if (heightCol >= 0 && !cursor.isNull(heightCol)) cursor.getInt(heightCol) else 0
                )
            }
        }
        return result
    }

    companion object {
        private const val TAG = "EventPerf"

        private const val GAP_MS = 24L * 60L * 60L * 1000L
        private const val MIN_PHOTO_COUNT = 15
        private const val DENSITY_WINDOW_MS = 7L * 60L * 1000L
        private const val DENSITY_MIN_PHOTOS = 5
        private const val MAX_EVENTS = 50
        private const val YEAR_MS = 365L * 24L * 60L * 60L * 1000L
        private const val MIN_SIZE_BYTES = 20L * 1024L

        private const val SPLIT_GAP_MS = 120L * 60L * 1000L
        private const val GPS_SPLIT_METERS = 800f
        private const val GPS_REQUIRED_RATIO = 0.4

        private const val HASH_SPLIT_THRESHOLD = 18
        private const val HASH_SAMPLE_STRIDE = 1
        private const val HASH_MIN_CLUSTER_SIZE = 30
        private const val HASH_MAX_COMPUTE = 220
        private const val HASH_CLUSTER_DIVISOR = 15
        private const val HASH_MIN_SAMPLE_STEP = 8
        private const val HASH_REFINE_RADIUS = 2
        private const val LOW_CUT_COUNT_THRESHOLD = 1
        private const val HASH_ENABLE_DURATION_MS = 8L * 60L * 60L * 1000L
        private const val HASH_MIN_CANDIDATE_SIZE = 30
        private const val HASH_MIN_CANDIDATE_DURATION_MS = 6L * 60L * 60L * 1000L
        private const val HASH_SKIP_CUTS_THRESHOLD = 2
        private const val HASH_FORCE_SIZE_THRESHOLD = 80
        private const val HASH_QUOTA_DIVISOR = 5
        private const val HASH_QUOTA_MIN = 12
        private const val HASH_QUOTA_MAX = 60
        private const val PREVIEW_MAX_CANDIDATES = 5
        private const val PREVIEW_MIN_BYTES = 60L * 1024L
        private const val PREVIEW_MIN_ASPECT = 0.5f
        private const val PREVIEW_MAX_ASPECT = 2.0f

        private const val EVENT_PROCESS_BUDGET_MS = 90_000L
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        private val CACHE_HINTS = listOf("/cache/", "/temp/", "thumbnails")
        private val SCREENSHOT_HINTS = listOf("screenshots", "screen_shot", "screenshot", "스크린샷")
        private val DOWNLOAD_HINTS = listOf("/download/", "downloads")
        private val MESSENGER_HINTS = listOf(
            "com.whatsapp", "whatsapp",
            "org.telegram", "telegram",
            "kakaotalk", "com.kakao.talk",
            "jp.naver.line", "/line/",
            "discord",
            "viber", "com.viber",
            "signal",
            "zalo",
            "slack"
        )
        private val CAMERA_SEGMENT_HINTS = listOf(
            "camera",
            "100andro",
            "100media",
            "opencamera",
            "gcam",
            "dcimcamera",
            "oneplus",
            "redmicamera",
            "sgcamera",
            "snap"
        )

        private val cacheLock = Any()
        @Volatile private var cachedAtMs: Long = 0L
        @Volatile private var cachedEvents: List<EventPhotoCluster>? = null
    }
}
