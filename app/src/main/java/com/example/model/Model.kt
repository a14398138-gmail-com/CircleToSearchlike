package com.example.model

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Represents fine-grained token (character for Japanese/CJK, word for Latin/English).
 */
data class OcrToken(
    val id: String,
    val text: String,
    val boundingBox: Rect, // In original Bitmap coords
    val lineIndex: Int = 0,
    val tokenIndex: Int = 0,
    val globalIndex: Int = 0,
    val isJapaneseOrCjk: Boolean = false
)

/**
 * Represents a line of text recognized by OCR on the screen.
 */
data class OcrTextItem(
    val id: String,
    val text: String,
    val boundingBox: Rect, // In original Bitmap coordinate space
    val lineIndex: Int = 0,
    val blockIndex: Int = 0,
    val tokens: List<OcrToken> = emptyList(),
    val isSelected: Boolean = false
)

/**
 * Represents active text selection range with start/end pin handles.
 */
data class TextSelectionState(
    val startTokenIndex: Int = -1,
    val endTokenIndex: Int = -1,
    val selectedTokens: List<OcrToken> = emptyList(),
    val fullText: String = "",
    val startPinPoint: Offset = Offset.Zero,
    val endPinPoint: Offset = Offset.Zero
)

/**
 * Represents active crop frame selected on the screen.
 */
data class CropSelection(
    val rect: RectF, // In display coordinate space
    val isConfirmed: Boolean = true,
    val croppedBitmap: Bitmap? = null,
    /**
     * Display rect of the screenshot when [rect] was set. The cropped pixels are
     * cut out lazily from it at share/copy time instead of on every drag event.
     */
    val imageRect: RectF? = null
)

/**
 * Represents the last used share target application.
 */
data class ShareTarget(
    val packageName: String,
    val activityName: String? = null,
    val appName: String,
    val iconBitmap: ImageBitmap? = null,
    val lastUsedTimestamp: Long = System.currentTimeMillis()
)

/**
 * Gesture trail point for Circle-to-Search glow animation.
 */
data class TrailPoint(
    val position: Offset,
    val timestamp: Long = System.currentTimeMillis(),
    val alpha: Float = 1.0f,
    val colorIndex: Int = 0
)
