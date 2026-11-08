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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

class OcrManager {

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val WARM_UP_TIMEOUT_MS = 5_000L
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

    @Volatile
    private var latinWarmUpStarted = false

    /**
     * Pre-warms the primary (Japanese) ML Kit engine by processing a tiny dummy
     * image, so the model weights, TFLite delegates and native libraries are
     * loaded before the user's first real screen OCR.
     *
     * The Latin engine is only a fallback (Japanese timed out / failed). It is
     * no longer warmed up here: loading its model in parallel competed for CPU
     * and I/O with the Japanese model load and the first real recognition on
     * cold start. It is warmed up after the first real recognition instead
     * (see [warmUpLatinInBackground]).
     */
    suspend fun warmUp() = withContext(Dispatchers.Default) {
        try {
            val dummyImage = InputImage.fromBitmap(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888), 0)
            val done = withTimeoutOrNull(WARM_UP_TIMEOUT_MS) {
                runMlKitRecognition(japaneseRecognizer, dummyImage, 1f)
            }
            Log.d("OcrManager", if (done != null) "OCR engine pre-warmed" else "OCR warm-up did not complete (timed out or failed)")
        } catch (e: Throwable) {
            Log.w("OcrManager", "OCR warm-up exception", e)
        }
    }

    /** Fire-and-forget Latin model load, started once after the first real recognition. */
    private fun warmUpLatinInBackground() {
        if (latinWarmUpStarted) return
        latinWarmUpStarted = true
        try {
            val dummyImage = InputImage.fromBitmap(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888), 0)
            latinRecognizer.process(dummyImage)
                .addOnFailureListener { Log.w("OcrManager", "Latin warm-up warning", it) }
        } catch (e: Throwable) {
            Log.w("OcrManager", "Latin warm-up exception", e)
        }
    }

    /**
     * Fast, high-accuracy text recognition optimized for mobile screen captures.
     * 1. Safely converts hardware bitmaps if needed.
     * 2. Auto-scales ultra-high resolution screenshots (e.g. 1440x3120) to optimal inference resolution (max 1080px),
     *    reducing inference latency to ~150ms without losing character accuracy.
     * 3. Uses strict timeouts (2.5s) with automatic Latin recognizer fallback to ensure OCR never hangs.
     * 4. Maps bounding boxes back to the original full bitmap coordinates seamlessly.
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

        val maxDim = max(origW, origH)
        val targetMaxDim = 1080f

        // Compute optimal inference scaling
        val (inferenceBitmap, scaleRatio, isScaled) = if (maxDim > targetMaxDim) {
            val ratio = targetMaxDim / maxDim
            val targetW = (origW * ratio).toInt().coerceAtLeast(1)
            val targetH = (origH * ratio).toInt().coerceAtLeast(1)
            val scaled = try {
                Bitmap.createScaledBitmap(safeBitmap, targetW, targetH, true)
            } catch (e: Throwable) {
                safeBitmap
            }
            Triple(scaled, ratio, scaled != safeBitmap)
        } else {
            Triple(safeBitmap, 1f, false)
        }

        val inputImage = try {
            InputImage.fromBitmap(inferenceBitmap, 0)
        } catch (e: Throwable) {
            Log.e("OcrManager", "Failed to create InputImage from bitmap", e)
            if (isScaled && inferenceBitmap != bitmap && inferenceBitmap != safeBitmap) {
                try { inferenceBitmap.recycle() } catch (_: Throwable) {}
            }
            if (safeBitmap != bitmap) {
                try { safeBitmap.recycle() } catch (_: Throwable) {}
            }
            return@withContext emptyList()
        }

        try {
            // Attempt Japanese recognizer with a strict 2.5-second timeout.
            // null = timed out or failed; an empty list = no text on screen.
            val japaneseResult = withTimeoutOrNull(2500L) {
                runMlKitRecognition(japaneseRecognizer, inputImage, scaleRatio)
            }

            if (japaneseResult != null) {
                // The Japanese model also reads Latin script (same detector), so a
                // successful empty result means there is no text: running the
                // Latin model as well only delayed the "no text" state.
                warmUpLatinInBackground()
                return@withContext japaneseResult
            }

            Log.w("OcrManager", "Japanese recognition timed out or failed, falling back to Latin recognizer")

            // Fallback to Latin recognizer with a 1.5-second timeout
            val latinResult = withTimeoutOrNull(1500L) {
                runMlKitRecognition(latinRecognizer, inputImage, scaleRatio)
            }

            latinResult.orEmpty()
        } finally {
            if (isScaled && inferenceBitmap != bitmap && inferenceBitmap != safeBitmap) {
                try { inferenceBitmap.recycle() } catch (_: Throwable) {}
            }
            if (safeBitmap != bitmap) {
                try { safeBitmap.recycle() } catch (_: Throwable) {}
            }
        }
    }

    /** Recognized lines, or null when ML Kit reported a failure. */
    private suspend fun runMlKitRecognition(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        inputImage: InputImage,
        scaleRatio: Float
    ): List<OcrTextItem>? = suspendCancellableCoroutine { continuation ->
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                if (continuation.isActive) {
                    try {
                        val result = processVisionText(visionText, scaleRatio)
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
                    continuation.resume(null)
                }
            }
    }

    private fun processVisionText(visionText: Text, scaleRatio: Float): List<OcrTextItem> {
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
            val lineBox = unscaleRect(rawLineBox, invScale)
            val currentLineIdx = globalLineIdx++
            val tokens = mutableListOf<OcrToken>()
            var tokenInLineIdx = 0

            val elements = line.elements.sortedBy { it.boundingBox?.left ?: 0 }
            if (elements.isNotEmpty()) {
                for (element in elements) {
                    val rawElemBox = element.boundingBox ?: rawLineBox
                    val elementBox = unscaleRect(rawElemBox, invScale)
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
                            val symBox = unscaleRect(rawSymBox, invScale)
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
                    val words = lineText.split(WHITESPACE).filter { it.isNotEmpty() }
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

    private fun unscaleRect(rect: Rect, invScale: Float): Rect {
        if (invScale == 1f) return rect
        return Rect(
            (rect.left * invScale).toInt(),
            (rect.top * invScale).toInt(),
            (rect.right * invScale).toInt(),
            (rect.bottom * invScale).toInt()
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
        // Running vertical span of each row's boxed lines. The old code recomputed
        // it with minOf/maxOf over the whole row for every candidate line
        // (O(n^2)); a box-less member fell back to the candidate's own top/bottom,
        // which is reproduced through rowHasBoxless below.
        val rowTops = mutableListOf<Int>()
        val rowBottoms = mutableListOf<Int>()
        val rowHasBoxless = mutableListOf<Boolean>()

        for (line in sortedByTop) {
            val box = line.boundingBox ?: Rect(0, 0, 0, 0)
            val top = box.top
            val bottom = box.bottom
            val height = (bottom - top).coerceAtLeast(1)
            val centerY = (top + bottom) / 2

            var matchedRow = -1

            for (r in rows.indices) {
                val rTop = if (rowHasBoxless[r]) min(rowTops[r], top) else rowTops[r]
                val rBottom = if (rowHasBoxless[r]) max(rowBottoms[r], bottom) else rowBottoms[r]
                val rHeight = (rBottom - rTop).coerceAtLeast(1)

                val overlapY = max(0, min(bottom, rBottom) - max(top, rTop))
                val overlapRatio = overlapY.toFloat() / min(height, rHeight)

                // Overlap by at least 40% of line height or vertical center inside row span
                if (overlapRatio >= 0.40f || (centerY in rTop..rBottom)) {
                    matchedRow = r
                    break
                }
            }

            val hasBox = line.boundingBox != null
            if (matchedRow >= 0) {
                rows[matchedRow].add(line)
                if (hasBox) {
                    rowTops[matchedRow] = min(rowTops[matchedRow], top)
                    rowBottoms[matchedRow] = max(rowBottoms[matchedRow], bottom)
                } else {
                    rowHasBoxless[matchedRow] = true
                }
            } else {
                rows.add(mutableListOf(line))
                rowTops.add(if (hasBox) top else Int.MAX_VALUE)
                rowBottoms.add(if (hasBox) bottom else Int.MIN_VALUE)
                rowHasBoxless.add(!hasBox)
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
