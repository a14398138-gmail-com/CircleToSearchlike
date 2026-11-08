package com.example.util

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.InputStream

object CircleLensScreenshotHolder {
    private var pendingScreenshot: Bitmap? = null
    private val _screenshotFlow = MutableSharedFlow<Bitmap>(replay = 0, extraBufferCapacity = 4)
    val screenshotFlow: SharedFlow<Bitmap> = _screenshotFlow.asSharedFlow()

    @Synchronized
    fun setScreenshot(bitmap: Bitmap) {
        pendingScreenshot = bitmap
        _screenshotFlow.tryEmit(bitmap)
    }

    @Synchronized
    fun consumeScreenshot(): Bitmap? {
        val bmp = pendingScreenshot
        pendingScreenshot = null
        return bmp
    }

    @Synchronized
    fun peekScreenshot(): Bitmap? = pendingScreenshot

    @Synchronized
    fun hasPendingScreenshot(): Boolean = pendingScreenshot != null

    @Synchronized
    fun clear() {
        pendingScreenshot = null
    }
}

object ScreenshotHelper {

    /**
     * Attempts to query the most recently saved screenshot from MediaStore
     * (e.g., if taken recently within the last 60 seconds).
     */
    suspend fun getLatestDeviceScreenshot(context: Context): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED
            )

            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: ""
                    val dateAddedSec = cursor.getLong(dateColumn)
                    val nowSec = System.currentTimeMillis() / 1000

                    // If it was added recently (within 5 minutes) or has "screenshot" in name
                    val isRecent = (nowSec - dateAddedSec) < 300
                    val isScreenshotName = name.lowercase().contains("screenshot") || name.lowercase().contains("screen")

                    if (isRecent || isScreenshotName) {
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

    /**
     * Decodes [uri] subsampled (power-of-two inSampleSize) so the bitmap holds at
     * most about twice the display's pixel count. Gallery images and shared
     * photos are often 12-50MP: decoding them at full size cost hundreds of
     * milliseconds and 50-200MB, although the image is only shown fitted to
     * the screen and OCR downscales to 1080px anyway. Blocking; call on IO.
     */
    fun decodeSampledBitmap(context: Context, uri: Uri): Bitmap? {
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
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private const val MIN_PIXEL_BUDGET = 4_000_000L
}
