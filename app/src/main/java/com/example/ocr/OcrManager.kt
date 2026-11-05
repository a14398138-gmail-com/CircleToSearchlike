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

    companion object {
        /**
         * Height of one inference tile in original-image pixels.
         * Tiles are cut from the *unscaled* bitmap: recognition always runs at
         * native resolution so small on-screen text keeps enough pixels
         * (ML Kit guidance: at least ~16x16 px per character, ~24x24 for CJK).
         */
        private const val TILE_HEIGHT_PX = 1200

        /** Vertical overlap between neighbouring tiles so lines on a seam are seen whole by at least one tile. */
        private const val TILE_OVERLAP_PX = 200

        /**
         * Per-tile timeout. A tile that times out simply contributes nothing;
         * it no longer discards the whole Japanese result or triggers a Latin
         * fallback (the Latin recognizer cannot read Japanese at all).
         */
        private const val TILE_TIMEOUT_MS = 10_000L
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
     * Text recognition for mobile screen captures.
     *
     * Accuracy-first pipeline (revised):
     * 1. Safely converts hardware bitmaps if needed.
     * 2. NEVER downscales the screenshot. The previous implementation capped the
     *    longest side at 1080 px, which shrank small text on a full-screen capture
     *    to roughly 9 px per character — far below ML Kit's minimum — and destroyed
     *    recognition of dense Japanese text.
     * 3. Splits tall screenshots into overlapping horizontal tiles at native
     *    resolution, recognizes each tile with the Japanese recognizer, then
     *    de-duplicates seam lines and renumbers lines in reading order.
     * 4. Falls back to the Latin recognizer only when the Japanese pass produced
     *    nothing at all (e.g. a purely Latin screen the Japanese model rejected),
     *    never on a per-tile timeout.
     * All returned bounding boxes are in the original full bitmap coordinates.
     */
    suspend fun recognizeText(bitmap: Bitmap): List<OcrTextItem> = withContext(Dispatchers.Default) {
        val origW = bitmap.width
        val origH = bitmap.height
        if (origW <= 0 || origH <= 0) return@withContext emptyList()

        // 1. Ensure bitmap is software-accessible (in case system delivers HARDWARE bitmap)
        val safeBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
            try {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
            } catch (e: Throwable) {
                Log.w("OcrManager", "Failed to copy hardware bitmap", e)
                bitmap
            }
        } else {
            bitmap
        }

        try {
            val japaneseResult = recognizeInTiles(japaneseRecognizer, safeBitmap)
            if (japaneseResult.isNotEmpty()) {
                return@withContext japaneseResult
            }

            Log.w("OcrManager", "Japanese recognition returned empty, falling back to Latin recognizer")
            recognizeInTiles(latinRecognizer, safeBitmap)
        } finally {
            if (safeBitmap != bitmap) {
                try { safeBitmap.recycle() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Runs [recognizer] over horizontal tiles of [bitmap] cut at native resolution
     * and merges the per-tile lines into one de-duplicated, reading-order list
     * whose boxes are in full-bitmap coordinates.
     */
    private suspend fun recognizeInTiles(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: Bitmap
    ): List<OcrTextItem> {
        val width = bitmap.width
        val height = bitmap.height

        // Tile start offsets: 0, then step by (TILE_HEIGHT_PX - TILE_OVERLAP_PX)
        // until the bottom of the image is covered.
        val starts = mutableListOf<Int>()
        if (height <= TILE_HEIGHT_PX) {
            starts.add(0)
        } else {
            var start = 0
            while (true) {
                starts.add(start)
                if (start + TILE_HEIGHT_PX >= height) break
                start += TILE_HEIGHT_PX - TILE_OVERLAP_PX
            }
        }

        val allItems = mutableListOf<OcrTextItem>()
        for (top in starts) {
            val tileHeight = min(TILE_HEIGHT_PX, height - top)
            if (tileHeight <= 0) continue

            val isFullImage = top == 0 && tileHeight == height
            val tileBitmap = if (isFullImage) {
                bitmap
            } else {
                try {
                    Bitmap.createBitmap(bitmap, 0, top, width, tileHeight)
                } catch (e: Throwable) {
                    Log.e("OcrManager", "Failed to create tile bitmap at y=$top", e)
                    continue
                }
            }

            try {
                val inputImage = try {
                    InputImage.fromBitmap(tileBitmap, 0)
                } catch (e: Throwable) {
                    Log.e("OcrManager", "Failed to create InputImage for tile at y=$top", e)
                    null
                }
                if (inputImage != null) {
                    val tileItems = withTimeoutOrNull(TILE_TIMEOUT_MS) {
                        runMlKitRecognition(recognizer, inputImage, yOffset = top)
                    }
                    if (tileItems == null) {
                        Log.w("OcrManager", "Tile at y=$top timed out after ${TILE_TIMEOUT_MS}ms, skipping tile")
                    } else {
                        allItems.addAll(tileItems)
                    }
                }
            } finally {
                if (tileBitmap != bitmap) {
                    try { tileBitmap.recycle() } catch (_: Throwable) {}
                }
            }
        }

        return mergeTileResults(allItems)
    }

    /**
     * De-duplicates lines detected twice in tile overlap zones, sorts the lines
     * into reading order, and renumbers line/token indices so downstream
     * selection logic sees one consistent sequence.
     *
     * Duplicate rule: two lines whose boxes overlap by at least half of the
     * smaller box are the same physical line. The longer text wins, because a
     * line clipped by a tile seam can come back truncated/garbled from one tile
     * and whole from the next.
     */
    private fun mergeTileResults(items: List<OcrTextItem>): List<OcrTextItem> {
        if (items.isEmpty()) return emptyList()

        val kept = mutableListOf<OcrTextItem>()
        for (item in items.sortedWith(compareBy<OcrTextItem> { it.boundingBox.top }.thenBy { it.boundingBox.left })) {
            var duplicateIndex = -1
            for ((index, existing) in kept.withIndex()) {
                if (boxesMostlyOverlap(existing.boundingBox, item.boundingBox)) {
                    duplicateIndex = index
                    break
                }
            }
            if (duplicateIndex < 0) {
                kept.add(item)
            } else if (item.text.length > kept[duplicateIndex].text.length) {
                kept[duplicateIndex] = item
            }
        }

        val ordered = sortItemsInReadingOrder(kept)

        var globalTokenIdx = 0
        return ordered.mapIndexed { lineIdx, item ->
            val renumberedTokens = item.tokens.map { token ->
                token.copy(
                    id = "t_${lineIdx}_${token.tokenIndex}",
                    lineIndex = lineIdx,
                    globalIndex = globalTokenIdx++
                )
            }
            item.copy(
                id = "line_$lineIdx",
                lineIndex = lineIdx,
                blockIndex = 0,
                tokens = renumberedTokens
            )
        }
    }

    private fun boxesMostlyOverlap(a: Rect, b: Rect): Boolean {
        val overlapW = min(a.right, b.right) - max(a.left, b.left)
        val overlapH = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (overlapW <= 0 || overlapH <= 0) return false
        val overlapArea = overlapW.toLong() * overlapH.toLong()
        val minArea = min(a.width(), b.width()).toLong() * min(a.height(), b.height()).toLong()
        if (minArea <= 0L) return false
        return overlapArea * 2 >= minArea
    }

    /** Same row-clustering strategy as [sortLinesInReadingOrder], applied to merged items. */
    private fun sortItemsInReadingOrder(items: List<OcrTextItem>): List<OcrTextItem> {
        if (items.size <= 1) return items

        val sortedByTop = items.sortedWith(
            compareBy<OcrTextItem> { it.boundingBox.top }.thenBy { it.boundingBox.left }
        )

        val rows = mutableListOf<MutableList<OcrTextItem>>()
        for (item in sortedByTop) {
            val box = item.boundingBox
            val top = box.top
            val bottom = box.bottom
            val height = (bottom - top).coerceAtLeast(1)
            val centerY = (top + bottom) / 2

            var matchedRow: MutableList<OcrTextItem>? = null
            for (row in rows) {
                val rTop = row.minOf { it.boundingBox.top }
                val rBottom = row.maxOf { it.boundingBox.bottom }
                val rHeight = (rBottom - rTop).coerceAtLeast(1)
                val overlapY = max(0, min(bottom, rBottom) - max(top, rTop))
                val overlapRatio = overlapY.toFloat() / min(height, rHeight)
                if (overlapRatio >= 0.40f || (centerY in rTop..rBottom)) {
                    matchedRow = row
                    break
                }
            }
            if (matchedRow != null) {
                matchedRow.add(item)
            } else {
                rows.add(mutableListOf(item))
            }
        }

        rows.sortBy { row -> row.sumOf { it.boundingBox.top } / row.size.coerceAtLeast(1) }
        for (row in rows) {
            row.sortBy { it.boundingBox.left }
        }
        return rows.flatten()
    }

    private suspend fun runMlKitRecognition(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        inputImage: InputImage,
        yOffset: Int = 0
    ): List<OcrTextItem> = suspendCancellableCoroutine { continuation ->
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                if (continuation.isActive) {
                    try {
                        val result = processVisionText(visionText, yOffset)
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

    private fun processVisionText(visionText: Text, yOffset: Int = 0): List<OcrTextItem> {
        val items = mutableListOf<OcrTextItem>()
        var globalTokenIdx = 0
        var globalLineIdx = 0

        // 1. Gather all non-empty lines from all text blocks
        val rawLines = visionText.textBlocks.flatMap { it.lines }
            .filter { it.text.isNotBlank() }

        // 2. Sort all lines strictly into top-to-bottom reading order
        val sortedLines = sortLinesInReadingOrder(rawLines)

        for (line in sortedLines) {
            val rawLineBox = line.boundingBox ?: Rect(0, 0, 100, 50)
            val lineBox = offsetRect(rawLineBox, yOffset)
            val currentLineIdx = globalLineIdx++
            val tokens = mutableListOf<OcrToken>()
            var tokenInLineIdx = 0

            val elements = line.elements.sortedBy { it.boundingBox?.left ?: 0 }
            if (elements.isNotEmpty()) {
                for (element in elements) {
                    val rawElemBox = element.boundingBox ?: rawLineBox
                    val elementBox = offsetRect(rawElemBox, yOffset)
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
                            val symBox = offsetRect(rawSymBox, yOffset)
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

    private fun offsetRect(rect: Rect, yOffset: Int): Rect {
        if (yOffset == 0) return rect
        return Rect(rect.left, rect.top + yOffset, rect.right, rect.bottom + yOffset)
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
