package com.example.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import com.example.model.OcrTextItem
import com.example.model.OcrToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

class OcrManager {

    private companion object {
        /** Long-side cap for inference. Native phone screenshots (<= 2400px typ.) are not downscaled. */
        const val MAX_INFERENCE_DIM = 2560f
        /** Upscale factor for re-recognizing a circled region; helps small text. */
        const val REGION_UPSCALE = 2f
        const val REGION_PADDING_PX = 12
        const val JAPANESE_TIMEOUT_MS = 4000L
        const val LATIN_TIMEOUT_MS = 2500L
    }

    private val japaneseRecognizer by lazy {
        try {
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        } catch (e: Throwable) {
            Log.w("OcrManager", "Japanese recognizer init failed, falling back to Latin", e)
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Pre-warms both ML Kit OCR engines by processing a tiny dummy image in background.
     * This loads the neural network weights, TFLite delegates, and native libraries into memory
     * so that the user's first actual screen OCR executes with zero cold-start delay.
     */
    suspend fun warmUp() = withContext(Dispatchers.Default) {
        try {
            val dummyBmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val dummyImage = InputImage.fromBitmap(dummyBmp, 0)
            coroutineScope {
                async {
                    try {
                        japaneseRecognizer.process(dummyImage)
                    } catch (e: Throwable) {
                        Log.w("OcrManager", "Japanese warm-up warning", e)
                    }
                }
                async {
                    try {
                        latinRecognizer.process(dummyImage)
                    } catch (e: Throwable) {
                        Log.w("OcrManager", "Latin warm-up warning", e)
                    }
                }
            }
            Log.d("OcrManager", "OCR engine pre-warmed successfully")
        } catch (e: Throwable) {
            Log.w("OcrManager", "OCR warm-up exception", e)
        }
    }

    /**
     * Full-screen text recognition for mobile screen captures.
     * 1. Safely converts hardware bitmaps if needed.
     * 2. Keeps (near-)native resolution: screenshots are only downscaled when the long side
     *    exceeds [MAX_INFERENCE_DIM]. Downscaling a 1080x2400 capture to 1080px long side
     *    shrinks body text below the ~24px per character that the Japanese model needs.
     * 3. Uses timeouts with automatic Latin recognizer fallback to ensure OCR never hangs.
     * 4. Maps bounding boxes back to the original full bitmap coordinates.
     */
    suspend fun recognizeText(bitmap: Bitmap): List<OcrTextItem> =
        recognizeScaled(bitmap, preferredScale = 1f, offsetX = 0, offsetY = 0)

    /**
     * High-accuracy re-recognition of a sub-region (e.g. the area the user circled).
     * The region is cropped from [source], upscaled by [REGION_UPSCALE] (capped so the long side
     * stays within [MAX_INFERENCE_DIM]) and recognized. Returned boxes are in [source] coordinates.
     */
    suspend fun recognizeRegion(source: Bitmap, region: Rect): List<OcrTextItem> = withContext(Dispatchers.Default) {
        val safeSource = toSoftwareBitmap(source)
        val left = (region.left - REGION_PADDING_PX).coerceIn(0, safeSource.width - 1)
        val top = (region.top - REGION_PADDING_PX).coerceIn(0, safeSource.height - 1)
        val right = (region.right + REGION_PADDING_PX).coerceIn(left + 1, safeSource.width)
        val bottom = (region.bottom + REGION_PADDING_PX).coerceIn(top + 1, safeSource.height)
        val w = right - left
        val h = bottom - top
        if (w < 8 || h < 8) {
            if (safeSource != source) safeSource.recycle()
            return@withContext emptyList()
        }

        val crop = try {
            Bitmap.createBitmap(safeSource, left, top, w, h)
        } catch (e: Throwable) {
            Log.w("OcrManager", "Failed to crop region for re-OCR", e)
            if (safeSource != source) safeSource.recycle()
            return@withContext emptyList()
        }

        try {
            recognizeScaled(crop, preferredScale = REGION_UPSCALE, offsetX = left, offsetY = top)
        } finally {
            if (crop != safeSource) {
                try { crop.recycle() } catch (_: Throwable) {}
            }
            if (safeSource != source) {
                try { safeSource.recycle() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Replaces the lines of [full] that lie inside [region] with [refined] (from [recognizeRegion]),
     * then re-sorts into reading order and re-assigns line/token/global indices so that
     * gesture selection keeps working on the merged list.
     * If [refined] is empty the original result is returned unchanged.
     */
    fun mergeRegionResults(
        full: List<OcrTextItem>,
        region: Rect,
        refined: List<OcrTextItem>
    ): List<OcrTextItem> {
        if (refined.isEmpty()) return full

        val kept = full.filter { item ->
            val b = item.boundingBox
            !region.contains(b.centerX(), b.centerY())
        }
        // Refined lines found only in the padding, or duplicating a kept line that straddles
        // the region edge, are dropped to avoid double text.
        val refinedInside = refined.filter { item ->
            val b = item.boundingBox
            region.contains(b.centerX(), b.centerY()) &&
                kept.none { overlapRatio(it.boundingBox, b) > 0.5f }
        }
        if (refinedInside.isEmpty()) return full
        val merged = sortItemsInReadingOrder(kept + refinedInside)

        var global = 0
        return merged.mapIndexed { lineIdx, item ->
            val tokens = item.tokens.mapIndexed { tokIdx, t ->
                t.copy(
                    id = "t_${lineIdx}_${tokIdx}",
                    lineIndex = lineIdx,
                    tokenIndex = tokIdx,
                    globalIndex = global++
                )
            }
            item.copy(id = "line_${lineIdx}", lineIndex = lineIdx, tokens = tokens)
        }
    }

    private fun overlapRatio(a: Rect, b: Rect): Float {
        val ix = max(0, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0, min(a.bottom, b.bottom) - max(a.top, b.top))
        val smaller = min(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1)
        return (ix.toLong() * iy).toFloat() / smaller
    }

    /** Row clustering identical in spirit to [sortLinesInReadingOrder], on already-mapped items. */
    private fun sortItemsInReadingOrder(items: List<OcrTextItem>): List<OcrTextItem> {
        if (items.size <= 1) return items
        val rows = mutableListOf<MutableList<OcrTextItem>>()
        for (item in items.sortedWith(compareBy<OcrTextItem> { it.boundingBox.top }.thenBy { it.boundingBox.left })) {
            val box = item.boundingBox
            val height = box.height().coerceAtLeast(1)
            val centerY = box.centerY()
            val row = rows.firstOrNull { r ->
                val rTop = r.minOf { it.boundingBox.top }
                val rBottom = r.maxOf { it.boundingBox.bottom }
                val rHeight = (rBottom - rTop).coerceAtLeast(1)
                val overlap = max(0, min(box.bottom, rBottom) - max(box.top, rTop))
                overlap.toFloat() / min(height, rHeight) >= 0.40f || centerY in rTop..rBottom
            }
            if (row != null) row.add(item) else rows.add(mutableListOf(item))
        }
        rows.sortBy { r -> r.sumOf { it.boundingBox.top } / r.size }
        rows.forEach { r -> r.sortBy { it.boundingBox.left } }
        return rows.flatten()
    }

    private fun toSoftwareBitmap(bitmap: Bitmap): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
            try {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
            } catch (e: Throwable) {
                Log.w("OcrManager", "Failed to copy hardware bitmap", e)
                bitmap
            }
        } else {
            bitmap
        }

    /**
     * Scales [bitmap] by [preferredScale] (reduced if needed so the long side fits
     * [MAX_INFERENCE_DIM]), runs OCR and maps boxes back via scale + offset.
     */
    private suspend fun recognizeScaled(
        bitmap: Bitmap,
        preferredScale: Float,
        offsetX: Int,
        offsetY: Int
    ): List<OcrTextItem> = withContext(Dispatchers.Default) {
        val origW = bitmap.width
        val origH = bitmap.height
        if (origW <= 0 || origH <= 0) return@withContext emptyList()

        val safeBitmap = toSoftwareBitmap(bitmap)

        val maxDim = max(origW, origH)
        val scaleRatio = min(preferredScale, MAX_INFERENCE_DIM / maxDim)
        val needsScale = kotlin.math.abs(scaleRatio - 1f) > 0.01f

        val inferenceBitmap = if (needsScale) {
            val targetW = (origW * scaleRatio).toInt().coerceAtLeast(1)
            val targetH = (origH * scaleRatio).toInt().coerceAtLeast(1)
            try {
                Bitmap.createScaledBitmap(safeBitmap, targetW, targetH, true)
            } catch (e: Throwable) {
                safeBitmap
            }
        } else {
            safeBitmap
        }
        val effectiveScale = if (inferenceBitmap == safeBitmap) 1f else scaleRatio

        try {
            val inputImage = try {
                InputImage.fromBitmap(inferenceBitmap, 0)
            } catch (e: Throwable) {
                Log.e("OcrManager", "Failed to create InputImage from bitmap", e)
                return@withContext emptyList()
            }

            val japaneseResult = withTimeoutOrNull(JAPANESE_TIMEOUT_MS) {
                runMlKitRecognition(japaneseRecognizer, inputImage, effectiveScale, offsetX, offsetY)
            }
            if (japaneseResult != null && japaneseResult.isNotEmpty()) {
                return@withContext japaneseResult
            }

            Log.w("OcrManager", "Japanese recognition timed out or returned empty, falling back to Latin recognizer")

            val latinResult = withTimeoutOrNull(LATIN_TIMEOUT_MS) {
                runMlKitRecognition(latinRecognizer, inputImage, effectiveScale, offsetX, offsetY)
            }
            latinResult ?: emptyList()
        } finally {
            if (inferenceBitmap != safeBitmap && inferenceBitmap != bitmap) {
                try { inferenceBitmap.recycle() } catch (_: Throwable) {}
            }
            if (safeBitmap != bitmap) {
                try { safeBitmap.recycle() } catch (_: Throwable) {}
            }
        }
    }

    private suspend fun runMlKitRecognition(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        inputImage: InputImage,
        scaleRatio: Float,
        offsetX: Int = 0,
        offsetY: Int = 0
    ): List<OcrTextItem> = suspendCancellableCoroutine { continuation ->
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                if (continuation.isActive) {
                    try {
                        val result = processVisionText(visionText, scaleRatio, offsetX, offsetY)
                        continuation.resume(result)
                    } catch (e: Throwable) {
                        Log.e("OcrManager", "Error parsing OCR vision text", e)
                        continuation.resume(emptyList())
                    }
                }
            }
            .addOnFailureListener { error ->
                Log.w("OcrManager", "ML Kit process failure", error)
                if (continuation.isActive) {
                    continuation.resume(emptyList())
                }
            }
    }

    private fun processVisionText(visionText: Text, scaleRatio: Float, offsetX: Int, offsetY: Int): List<OcrTextItem> {
        val items = mutableListOf<OcrTextItem>()
        var globalTokenIdx = 0
        var globalLineIdx = 0

        // 1. Gather all non-empty lines from all text blocks
        val rawLines = visionText.textBlocks.flatMap { it.lines }
            .filter { it.text.isNotBlank() }

        // 2. Sort all lines strictly into top-to-bottom reading order
        val sortedLines = sortLinesInReadingOrder(rawLines)

        val invScale = if (scaleRatio > 0f && scaleRatio != 1f) 1f / scaleRatio else 1f

        for (line in sortedLines) {
            val rawLineBox = line.boundingBox ?: Rect(0, 0, 100, 50)
            val lineBox = unscaleRect(rawLineBox, invScale, offsetX, offsetY)
            val currentLineIdx = globalLineIdx++
            val tokens = mutableListOf<OcrToken>()
            var tokenInLineIdx = 0

            val elements = line.elements.sortedBy { it.boundingBox?.left ?: 0 }
            if (elements.isNotEmpty()) {
                for (element in elements) {
                    val rawElemBox = element.boundingBox ?: rawLineBox
                    val elementBox = unscaleRect(rawElemBox, invScale, offsetX, offsetY)
                    val elementText = element.text
                    val symbols = try {
                        element.symbols.sortedBy { it.boundingBox?.left ?: 0 }
                    } catch (e: Throwable) {
                        emptyList<Text.Symbol>()
                    }

                    if (symbols.isNotEmpty() && containsJapaneseOrMixed(elementText)) {
                        // Use ML Kit direct Symbol bounding boxes for highest character precision
                        for (symbol in symbols) {
                            val rawSymBox = symbol.boundingBox ?: rawElemBox
                            val symBox = unscaleRect(rawSymBox, invScale, offsetX, offsetY)
                            val symText = symbol.text
                            tokens.add(
                                OcrToken(
                                    id = "t_${currentLineIdx}_${tokenInLineIdx}",
                                    text = symText,
                                    boundingBox = symBox,
                                    lineIndex = currentLineIdx,
                                    tokenIndex = tokenInLineIdx++,
                                    globalIndex = globalTokenIdx++,
                                    isJapaneseOrCjk = containsJapanese(symText)
                                )
                            )
                        }
                    } else if (containsJapanese(elementText)) {
                        // Japanese / CJK fallback when symbols are not individually provided
                        val charCount = elementText.length
                        val elemW = elementBox.width()

                        for (i in 0 until charCount) {
                            val ch = elementText[i].toString()
                            val left = elementBox.left + (elemW * i / charCount)
                            val right = elementBox.left + (elemW * (i + 1) / charCount)
                            val charBox = Rect(left, elementBox.top, right, elementBox.bottom)

                            tokens.add(
                                OcrToken(
                                    id = "t_${currentLineIdx}_${tokenInLineIdx}",
                                    text = ch,
                                    boundingBox = charBox,
                                    lineIndex = currentLineIdx,
                                    tokenIndex = tokenInLineIdx++,
                                    globalIndex = globalTokenIdx++,
                                    isJapaneseOrCjk = true
                                )
                            )
                        }
                    } else {
                        // Latin / English / Number / Symbol: treat as single word token
                        tokens.add(
                            OcrToken(
                                id = "t_${currentLineIdx}_${tokenInLineIdx}",
                                text = elementText,
                                boundingBox = elementBox,
                                lineIndex = currentLineIdx,
                                tokenIndex = tokenInLineIdx++,
                                globalIndex = globalTokenIdx++,
                                isJapaneseOrCjk = false
                            )
                        )
                    }
                }
            } else {
                // Direct line text fallback
                val lineText = line.text
                if (containsJapanese(lineText)) {
                    val charCount = lineText.length
                    val lineW = lineBox.width()
                    for (i in 0 until charCount) {
                        val ch = lineText[i].toString()
                        val left = lineBox.left + (lineW * i / charCount)
                        val right = lineBox.left + (lineW * (i + 1) / charCount)
                        val charBox = Rect(left, lineBox.top, right, lineBox.bottom)
                        tokens.add(
                            OcrToken(
                                id = "t_${currentLineIdx}_${tokenInLineIdx}",
                                text = ch,
                                boundingBox = charBox,
                                lineIndex = currentLineIdx,
                                tokenIndex = tokenInLineIdx++,
                                globalIndex = globalTokenIdx++,
                                isJapaneseOrCjk = true
                            )
                        )
                    }
                } else {
                    val words = lineText.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    val totalLen = lineText.length.coerceAtLeast(1)
                    var currentOffset = 0
                    for (word in words) {
                        val startIdx = lineText.indexOf(word, currentOffset).coerceAtLeast(0)
                        val endIdx = startIdx + word.length
                        currentOffset = endIdx

                        val left = lineBox.left + (lineBox.width() * startIdx / totalLen)
                        val right = lineBox.left + (lineBox.width() * endIdx / totalLen)
                        val wordBox = Rect(left, lineBox.top, right, lineBox.bottom)

                        tokens.add(
                            OcrToken(
                                id = "t_${currentLineIdx}_${tokenInLineIdx}",
                                text = word,
                                boundingBox = wordBox,
                                lineIndex = currentLineIdx,
                                tokenIndex = tokenInLineIdx++,
                                globalIndex = globalTokenIdx++,
                                isJapaneseOrCjk = false
                            )
                        )
                    }
                }
            }

            if (tokens.isNotEmpty()) {
                items.add(
                    OcrTextItem(
                        id = "line_${currentLineIdx}",
                        text = line.text,
                        boundingBox = lineBox,
                        lineIndex = currentLineIdx,
                        blockIndex = 0,
                        tokens = tokens,
                        isSelected = false
                    )
                )
            }
        }
        return items
    }

    private fun unscaleRect(rect: Rect, invScale: Float, offsetX: Int = 0, offsetY: Int = 0): Rect {
        if (invScale == 1f && offsetX == 0 && offsetY == 0) return rect
        return Rect(
            (rect.left * invScale).toInt() + offsetX,
            (rect.top * invScale).toInt() + offsetY,
            (rect.right * invScale).toInt() + offsetX,
            (rect.bottom * invScale).toInt() + offsetY
        )
    }

    /**
     * Clusters lines into horizontal rows and sorts rows strictly top-to-bottom,
     * and lines within each row left-to-right.
     * Prevents ML Kit block fragmentations from inverting vertical line reading order.
     */
    private fun sortLinesInReadingOrder(lines: List<Text.Line>): List<Text.Line> {
        if (lines.size <= 1) return lines

        // 1. Initial sort by top coordinate, then by left coordinate
        val sortedByTop = lines.sortedWith(
            compareBy<Text.Line> { it.boundingBox?.top ?: 0 }
                .thenBy { it.boundingBox?.left ?: 0 }
        )

        val rows = mutableListOf<MutableList<Text.Line>>()

        for (line in sortedByTop) {
            val box = line.boundingBox ?: Rect(0, 0, 0, 0)
            val top = box.top
            val bottom = box.bottom
            val height = (bottom - top).coerceAtLeast(1)
            val centerY = (top + bottom) / 2

            var matchedRow: MutableList<Text.Line>? = null

            for (row in rows) {
                val rTop = row.minOf { it.boundingBox?.top ?: top }
                val rBottom = row.maxOf { it.boundingBox?.bottom ?: bottom }
                val rHeight = (rBottom - rTop).coerceAtLeast(1)

                val overlapY = max(0, min(bottom, rBottom) - max(top, rTop))
                val overlapRatio = overlapY.toFloat() / min(height, rHeight)

                // Overlap by at least 40% of line height or vertical center inside row span
                if (overlapRatio >= 0.40f || (centerY in rTop..rBottom)) {
                    matchedRow = row
                    break
                }
            }

            if (matchedRow != null) {
                matchedRow.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }

        // 2. Sort rows top-to-bottom by average top coordinate
        rows.sortBy { row ->
            row.sumOf { it.boundingBox?.top ?: 0 } / row.size.coerceAtLeast(1)
        }

        // 3. Sort lines within each row left-to-right
        for (row in rows) {
            row.sortBy { it.boundingBox?.left ?: 0 }
        }

        return rows.flatten()
    }

    private fun containsJapaneseOrMixed(text: String): Boolean {
        for (i in 0 until text.length) {
            val code = text[i].code
            if (code in 0x3040..0x309F || // Hiragana
                code in 0x30A0..0x30FF || // Katakana
                code in 0x4E00..0x9FAF || // Kanji
                code in 0x3400..0x4DBF || // Kanji Extension A
                code in 0xFF00..0xFFEF   // Fullwidth
            ) {
                return true
            }
        }
        return false
    }

    private fun containsJapanese(text: String): Boolean {
        for (i in 0 until text.length) {
            val codePoint = text[i].code
            if (codePoint in 0x3040..0x309F ||
                codePoint in 0x30A0..0x30FF ||
                codePoint in 0x4E00..0x9FAF ||
                codePoint in 0x3400..0x4DBF ||
                codePoint in 0xFF00..0xFFEF
            ) {
                return true
            }
        }
        return false
    }
}
