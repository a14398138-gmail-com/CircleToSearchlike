package com.example.util

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-path screenshot handoff.
 *
 * Previously [setScreenshot] both stashed a pending bitmap AND emitted into a
 * SharedFlow, while three different consumers (ViewModel init collect,
 * ViewModel init consume, MainActivity.onResume, MainActivity intent
 * handling) raced to consume it — the same bitmap could be OCR'd twice or
 * cancel/replace an in-flight load.
 *
 * Now there is exactly one channel: a pending bitmap tagged with a
 * monotonically increasing id. The only consumer is
 * CircleLensViewModel.loadPendingScreenshotIfNew(), which loads a given id
 * at most once. Producers only call [setScreenshot]; lifecycle callbacks
 * only *ask* the ViewModel to check for a new pending screenshot.
 */
object CircleLensScreenshotHolder {
    private var pendingScreenshot: Bitmap? = null
    private var pendingId: Long = 0L
    private val idSeq = AtomicLong(0L)

    @Synchronized
    fun setScreenshot(bitmap: Bitmap) {
        pendingScreenshot = bitmap
        pendingId = idSeq.incrementAndGet()
    }

    /** Returns (id, bitmap) of the pending screenshot without consuming it. */
    @Synchronized
    fun peekPending(): Pair<Long, Bitmap>? {
        val bmp = pendingScreenshot ?: return null
        return pendingId to bmp
    }

    @Synchronized
    fun clearIf(id: Long) {
        if (pendingId == id) {
            pendingScreenshot = null
        }
    }

    @Synchronized
    fun hasPendingScreenshot(): Boolean = pendingScreenshot != null

    @Synchronized
    fun clear() {
        pendingScreenshot = null
    }
}

object ScreenshotHelper {

    /**
     * Last-resort fallback: query MediaStore for a *recent screenshot*.
     *
     * Strict matching (the previous `isRecent || isScreenshotName` adopted
     * years-old screenshots by name alone, or any ordinary photo taken
     * within 5 minutes): a candidate must satisfy ALL of
     *  - added within the last 120 seconds, AND
     *  - file name contains "screenshot", AND
     *  - (on Android 10+) its relative path contains "screenshot" when the
     *    column is available.
     * The first few rows are scanned so a just-taken ordinary photo in row 0
     * no longer hides a real screenshot in row 1.
     */
    suspend fun getLatestDeviceScreenshot(context: Context): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val projection = mutableListOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                projection.add(MediaStore.Images.Media.RELATIVE_PATH)
            }

            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            context.contentResolver.query(uri, projection.toTypedArray(), null, null, sortOrder)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    -1
                }

                val nowSec = System.currentTimeMillis() / 1000
                var rowsChecked = 0
                while (cursor.moveToNext() && rowsChecked < 5) {
                    rowsChecked++
                    val name = cursor.getString(nameColumn) ?: ""
                    val dateAddedSec = cursor.getLong(dateColumn)
                    val relPath = if (pathColumn >= 0) cursor.getString(pathColumn) ?: "" else ""

                    val isRecent = (nowSec - dateAddedSec) in 0..120
                    val isScreenshotName = name.lowercase().contains("screenshot")
                    val isScreenshotPath = relPath.isEmpty() || relPath.lowercase().contains("screenshot")

                    if (isRecent && isScreenshotName && isScreenshotPath) {
                        val id = cursor.getLong(idColumn)
                        val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        return@withContext decodeSampledBitmap(context, contentUri)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w("ScreenshotHelper", "Could not query recent screenshot from MediaStore", e)
        }
        null
    }

    /** A decoded image and the pixel size of the original file it came from. */
    class DecodedImage(val bitmap: Bitmap, val sourceWidth: Int, val sourceHeight: Int) {
        /** True when [bitmap] holds fewer pixels than the file (it was subsampled). */
        val isSubsampled: Boolean
            get() = sourceWidth > bitmap.width || sourceHeight > bitmap.height
    }

    /**
     * Decodes [uri] subsampled (power-of-two inSampleSize) only when it is
     * larger than MIN_PIXEL_BUDGET (or twice the display, if larger). OCR runs
     * at native resolution for accuracy, so ordinary photos (up to ~16MP) are
     * kept as they are; only very large images (e.g. 50MP, 200MB decoded) are
     * reduced. Crops of a reduced image are re-read at full resolution with
     * [decodeFullResolutionRegion]. Blocking; call on IO.
     */
    fun decodeSampledImage(context: Context, uri: Uri): DecodedImage? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        val metrics = context.resources.displayMetrics
        val pixelBudget = (metrics.widthPixels.toLong() * metrics.heightPixels.toLong() * 2L)
            .coerceAtLeast(MIN_PIXEL_BUDGET)
        var sampleSize = 1
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
            while (pixels / (sampleSize.toLong() * sampleSize) > pixelBudget) {
                sampleSize *= 2
            }
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        // Formats whose bounds pass did not report a size are treated as not reduced.
        val width = if (bounds.outWidth > 0) bounds.outWidth else bitmap.width
        val height = if (bounds.outHeight > 0) bounds.outHeight else bitmap.height
        return DecodedImage(bitmap, width, height)
    }

    fun decodeSampledBitmap(context: Context, uri: Uri): Bitmap? = decodeSampledImage(context, uri)?.bitmap

    /**
     * Decodes only [region] (in the file's own pixels) of [uri] at full
     * resolution. Returns null when the format has no region decoder (e.g.
     * GIF), the region is too large, or reading fails. Blocking; call on IO.
     */
    fun decodeFullResolutionRegion(context: Context, uri: Uri, region: Rect): Bitmap? {
        if (region.isEmpty || region.width().toLong() * region.height() > MAX_FULL_RES_REGION_PIXELS) return null
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    BitmapRegionDecoder.newInstance(input)
                } else {
                    @Suppress("DEPRECATION")
                    BitmapRegionDecoder.newInstance(input, false)
                } ?: return@use null
                try {
                    decoder.decodeRegion(region, null)
                } finally {
                    decoder.recycle()
                }
            }
        } catch (e: Throwable) {
            Log.w("ScreenshotHelper", "Full-resolution region decode failed", e)
            null
        }
    }

    private const val MIN_PIXEL_BUDGET = 16_000_000L

    /** 50MP (200MB as ARGB_8888): larger full-resolution crops fall back to the reduced image. */
    private const val MAX_FULL_RES_REGION_PIXELS = 50_000_000L
}
