package com.example.workspacefilemanager.util

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Extracts and caches a single frame from a video file so the gallery grid can
 * show real thumbnails instead of a generic film icon.
 *
 * Bounded to ~80 entries and decoded at a small size — decoding full-size frames
 * for a couple of hundred videos is what makes galleries janky or OOM.
 */
object VideoThumbs {

    private val cache = LruCache<String, Bitmap?>(80)

    fun cached(file: File): Bitmap? = cache.get(file.absolutePath)

    suspend fun load(file: File): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(file.absolutePath)?.let { return@withContext it }
        var retriever: MediaMetadataRetriever? = null
        val frame = try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(
                1_000_000, // ~1s in, avoids black intro frames
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
        } catch (t: Throwable) {
            null
        } finally {
            try {
                retriever?.release()
            } catch (_: Throwable) {
            }
        }
        val scaled = frame?.let { bmp ->
            val targetW = 320
            if (bmp.width > targetW) {
                val h = (bmp.height * (targetW.toFloat() / bmp.width)).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, targetW, h, true).also {
                    if (it !== bmp) bmp.recycle()
                }
            } else bmp
        }
        cache.put(file.absolutePath, scaled)
        scaled
    }

    fun clear() = cache.evictAll()
}
