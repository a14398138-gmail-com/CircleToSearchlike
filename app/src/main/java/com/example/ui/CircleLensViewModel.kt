package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gesture.GestureProcessor
import com.example.model.CropSelection
import com.example.model.OcrTextItem
import com.example.model.OcrToken
import com.example.model.ShareTarget
import com.example.model.TextSelectionState
import com.example.model.TrailPoint
import com.example.ocr.OcrManager
import com.example.share.ShareManager
import com.example.util.CircleLensScreenshotHolder
import com.example.util.ScreenshotHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

data class CircleLensUiState(
    val currentBitmap: Bitmap? = null,
    val isOcrRunning: Boolean = false,
    val ocrItems: List<OcrTextItem> = emptyList(),
    val allTokens: List<OcrToken> = emptyList(),
    val textSelection: TextSelectionState? = null,
    val activeCropSelection: CropSelection? = null,
    val activeStrokePoints: List<Offset> = emptyList(),
    val trailPoints: List<TrailPoint> = emptyList(),
    val lastTextShareTarget: ShareTarget? = null,
    val lastImageShareTarget: ShareTarget? = null,
    val recentTextTargets: List<ShareTarget> = emptyList(),
    val recentImageTargets: List<ShareTarget> = emptyList(),
    val availableTextTargets: List<ShareTarget> = emptyList(),
    val availableImageTargets: List<ShareTarget> = emptyList(),
    val showTargetPickerSheet: Boolean = false,
    val targetPickerIsImage: Boolean = false,
    val showAssistHelpSheet: Boolean = false,
    val feedbackMessage: String? = null
)

class CircleLensViewModel(application: Application) : AndroidViewModel(application) {

    private val ocrManager = OcrManager()
    val shareManager = ShareManager.getInstance(application)

    private val _uiState = MutableStateFlow(CircleLensUiState())
    val uiState: StateFlow<CircleLensUiState> = _uiState.asStateFlow()

    val closeAppEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 2)

    private var activeLoadJob: Job? = null
    private var isRealBitmapLoaded: Boolean = false
    private var cropExtractJob: Job? = null
    private var lastImageRect: RectF = RectF()

    /**
     * Cache of OCR tokens projected into display coordinates.
     * Rebuilt only when the token list or the display transform changes, so gesture
     * handling (60-120Hz pointer events) never re-allocates a RectF per token.
     */
    private class TokenDisplayIndex(
        val tokens: List<OcrToken>,
        val boxes: List<RectF>,
        val imageRect: RectF,
        val bitmapWidth: Int,
        val bitmapHeight: Int
    )

    private var tokenDisplayIndex: TokenDisplayIndex? = null

    private fun tokenDisplayIndex(imageRect: RectF, bitmapWidth: Int, bitmapHeight: Int): TokenDisplayIndex {
        val tokens = _uiState.value.allTokens
        val existing = tokenDisplayIndex
        if (existing != null && existing.tokens === tokens &&
            existing.bitmapWidth == bitmapWidth && existing.bitmapHeight == bitmapHeight &&
            existing.imageRect.left == imageRect.left && existing.imageRect.top == imageRect.top &&
            existing.imageRect.right == imageRect.right && existing.imageRect.bottom == imageRect.bottom
        ) {
            return existing
        }
        val scaleX = if (bitmapWidth > 0) imageRect.width() / bitmapWidth else 1f
        val scaleY = if (bitmapHeight > 0) imageRect.height() / bitmapHeight else 1f
        val boxes = ArrayList<RectF>(tokens.size)
        for (token in tokens) {
            val b = token.boundingBox
            boxes.add(
                RectF(
                    imageRect.left + b.left * scaleX,
                    imageRect.top + b.top * scaleY,
                    imageRect.left + b.right * scaleX,
                    imageRect.top + b.bottom * scaleY
                )
            )
        }
        return TokenDisplayIndex(
            tokens = tokens,
            boxes = boxes,
            imageRect = RectF(imageRect),
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight
        ).also { tokenDisplayIndex = it }
    }

    override fun onCleared() {
        super.onCleared()
        activeLoadJob?.cancel()
        cropExtractJob?.cancel()
        ocrManager.close()
    }

    init {
        // Observe share targets and recent history
        viewModelScope.launch {
            shareManager.lastTextShareTarget.collect { target ->
                _uiState.value = _uiState.value.copy(lastTextShareTarget = target)
            }
        }
        viewModelScope.launch {
            shareManager.lastImageShareTarget.collect { target ->
                _uiState.value = _uiState.value.copy(lastImageShareTarget = target)
            }
        }
        viewModelScope.launch {
            shareManager.recentTextShareTargets.collect { list ->
                _uiState.value = _uiState.value.copy(recentTextTargets = list)
            }
        }
        viewModelScope.launch {
            shareManager.recentImageShareTargets.collect { list ->
                _uiState.value = _uiState.value.copy(recentImageTargets = list)
            }
        }

        // Observe incoming assistant screenshots in real time
        viewModelScope.launch {
            CircleLensScreenshotHolder.screenshotFlow.collect { bitmap ->
                loadBitmapDirect(bitmap, null)
            }
        }

        // Pre-warm OCR engine in background to eliminate first-use cold start latency
        viewModelScope.launch(Dispatchers.Default) {
            ocrManager.warmUp()
        }

        refreshAvailableTargets()

        // If there is already a captured screenshot pending, load it immediately
        val captured = CircleLensScreenshotHolder.consumeScreenshot()
        if (captured != null) {
            loadBitmapDirect(captured, null)
        }
    }

    fun refreshAvailableTargets() {
        viewModelScope.launch(Dispatchers.IO) {
            val textTargets = shareManager.getAvailableShareTargets("text/plain")
            val imageTargets = shareManager.getAvailableShareTargets("image/*")
            _uiState.value = _uiState.value.copy(
                availableTextTargets = textTargets,
                availableImageTargets = imageTargets
            )
        }
    }

    /**
     * Called when invoked via Assist / Home long-press
     */
    fun onAssistLaunched() {
        val captured = CircleLensScreenshotHolder.consumeScreenshot()
        if (captured != null) {
            loadBitmapDirect(captured, null)
            return
        }

        activeLoadJob?.cancel()
        activeLoadJob = viewModelScope.launch {
            // Check if latest device screenshot is available from system
            val recent = ScreenshotHelper.getLatestDeviceScreenshot(getApplication())
            if (recent != null) {
                loadBitmapDirect(recent, null)
            } else if (_uiState.value.currentBitmap == null) {
                _uiState.value = _uiState.value.copy(
                    feedbackMessage = "画面を取得中..."
                )
            }
        }
    }

    fun loadBitmapDirect(bitmap: Bitmap, message: String? = null) {
        activeLoadJob?.cancel()
        isRealBitmapLoaded = true

        // ⚡ INSTANT RENDERING: Display bitmap immediately in 0ms without waiting for OCR
        _uiState.value = _uiState.value.copy(
            currentBitmap = bitmap,
            isOcrRunning = true,
            textSelection = null,
            activeCropSelection = null,
            activeStrokePoints = emptyList(),
            feedbackMessage = message
        )

        activeLoadJob = viewModelScope.launch {
            try {
                val ocrResults = ocrManager.recognizeText(bitmap)
                val flatTokens = ocrResults.flatMap { it.tokens }

                _uiState.value = _uiState.value.copy(
                    currentBitmap = bitmap,
                    ocrItems = ocrResults,
                    allTokens = flatTokens,
                    isOcrRunning = false,
                    feedbackMessage = null
                )
            } catch (e: Throwable) {
                Log.e("CircleLensVM", "OCR execution error", e)
                _uiState.value = _uiState.value.copy(
                    currentBitmap = bitmap,
                    isOcrRunning = false,
                    feedbackMessage = null
                )
            }
        }
    }

    fun loadBitmapFromUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isOcrRunning = true,
                textSelection = null,
                activeCropSelection = null,
                activeStrokePoints = emptyList(),
                feedbackMessage = "画像をOCR解析中..."
            )

            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val cr = getApplication<Application>().contentResolver
                    // Decode bounds first, then sample down huge images (e.g. 4K photos)
                    // instead of allocating the full-resolution bitmap in memory.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sampleSize = 1
                    val maxDim = max(bounds.outWidth, bounds.outHeight)
                    while (maxDim / sampleSize > 2048) sampleSize *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                } catch (e: Throwable) {
                    Log.e("CircleLensVM", "Failed to load image from URI", e)
                    null
                }
            }

            if (bitmap != null) {
                try {
                    val ocrResults = ocrManager.recognizeText(bitmap)
                    val flatTokens = ocrResults.flatMap { it.tokens }
                    _uiState.value = _uiState.value.copy(
                        currentBitmap = bitmap,
                        ocrItems = ocrResults,
                        allTokens = flatTokens,
                        isOcrRunning = false,
                        feedbackMessage = "OCR解析が完了しました (${flatTokens.size}要素 検出)"
                    )
                } catch (e: Throwable) {
                    Log.e("CircleLensVM", "OCR error for URI image", e)
                    _uiState.value = _uiState.value.copy(
                        currentBitmap = bitmap,
                        isOcrRunning = false,
                        feedbackMessage = null
                    )
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    isOcrRunning = false,
                    feedbackMessage = "画像の読み込みに失敗しました"
                )
            }
        }
    }

    fun onStrokeStart(point: Offset) {
        _uiState.value = _uiState.value.copy(
            activeStrokePoints = listOf(point),
            trailPoints = listOf(TrailPoint(position = point))
        )
    }

    fun onStrokeMove(
        point: Offset,
        imageRectOnDisplay: RectF,
        bitmapWidth: Int,
        bitmapHeight: Int
    ) {
        val state = _uiState.value
        val last = state.activeStrokePoints.lastOrNull()
        // Throttle micro-movements below ~4px to cut state churn during 120Hz drags
        if (last != null) {
            val dx = point.x - last.x
            val dy = point.y - last.y
            if (dx * dx + dy * dy < 16f) return
        }

        val currentPoints = state.activeStrokePoints + point
        val newTrails = (state.trailPoints + TrailPoint(position = point)).takeLast(40)

        // Only update trajectory points and trail while drawing to prevent accidental text selection
        _uiState.value = state.copy(
            activeStrokePoints = currentPoints,
            trailPoints = newTrails
        )
    }

    fun onStrokeEnd(
        imageRectOnDisplay: RectF,
        bitmapWidth: Int,
        bitmapHeight: Int
    ) {
        val points = _uiState.value.activeStrokePoints
        val state = _uiState.value

        if (points.isEmpty()) return
        lastImageRect = RectF(imageRectOnDisplay)

        // 1. FIRST PRIORITY: Circle / Loop gesture detection on completed stroke
        val detectedCropRect = GestureProcessor.detectCircleGesture(points)
        if (detectedCropRect != null) {
            val clampedLeft = max(imageRectOnDisplay.left, detectedCropRect.left)
            val clampedTop = max(imageRectOnDisplay.top, detectedCropRect.top)
            val clampedRight = min(imageRectOnDisplay.right, detectedCropRect.right)
            val clampedBottom = min(imageRectOnDisplay.bottom, detectedCropRect.bottom)

            if (clampedRight > clampedLeft + 30f && clampedBottom > clampedTop + 30f) {
                val cropFrame = RectF(clampedLeft, clampedTop, clampedRight, clampedBottom)
                val croppedBitmap = extractCropBitmap(cropFrame, imageRectOnDisplay, state.currentBitmap)

                _uiState.value = _uiState.value.copy(
                    activeCropSelection = CropSelection(rect = cropFrame, croppedBitmap = croppedBitmap),
                    textSelection = null,
                    activeStrokePoints = emptyList(),
                    trailPoints = emptyList(),
                    feedbackMessage = "画像を切り抜きました"
                )
                return
            }
        }

        // 2. SECOND PRIORITY: If clearly not a circle, process as text swipe or tap
        val index = tokenDisplayIndex(imageRectOnDisplay, bitmapWidth, bitmapHeight)

        val range = GestureProcessor.findTokensInStroke(points, index.tokens, index.boxes)
        val finalSelection = if (range != null) {
            buildTextSelection(range.first, range.second, imageRectOnDisplay, bitmapWidth, bitmapHeight)
        } else if (points.size <= 8) {
            // Tap check on single word - only if tapped closely on the word (within ~24px proximity)
            val tapPt = points.last()
            val closest = GestureProcessor.findClosestToken(tapPt, index.tokens, index.boxes)
            if (closest != null && index.tokens.getOrNull(closest.globalIndex) === closest) {
                val box = index.boxes[closest.globalIndex]
                val isNear = (tapPt.x in (box.left - 24f)..(box.right + 24f)) && (tapPt.y in (box.top - 16f)..(box.bottom + 16f))
                if (isNear) {
                    buildTextSelection(closest.globalIndex, closest.globalIndex, imageRectOnDisplay, bitmapWidth, bitmapHeight)
                } else {
                    null // Tapped away from text -> clear selection
                }
            } else {
                null
            }
        } else {
            null
        }

        // Tap outside crop rect clears the active crop
        val tapPt = points.last()
        val tappedInsideCrop = state.activeCropSelection?.rect?.let { r ->
            tapPt.x in (r.left - 10f)..(r.right + 10f) && tapPt.y in (r.top - 10f)..(r.bottom + 10f)
        } ?: false

        val newCropSelection = if (finalSelection != null || !tappedInsideCrop) {
            null
        } else {
            state.activeCropSelection
        }

        _uiState.value = _uiState.value.copy(
            activeStrokePoints = emptyList(),
            trailPoints = emptyList(),
            textSelection = finalSelection,
            activeCropSelection = newCropSelection
        )
    }

    private fun buildTextSelection(
        startGlobalIdx: Int,
        endGlobalIdx: Int,
        imageRectOnDisplay: RectF,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): TextSelectionState? {
        val all = _uiState.value.allTokens
        if (all.isEmpty()) return null

        val minIdx = max(0, min(startGlobalIdx, endGlobalIdx))
        val maxIdx = min(all.size - 1, max(startGlobalIdx, endGlobalIdx))
        if (minIdx > maxIdx) return null

        // globalIndex is assigned sequentially, so the list position equals the global index.
        // Slice directly instead of filtering the whole token list on every pointer event.
        val selected = if (all.getOrNull(minIdx)?.globalIndex == minIdx &&
            all.getOrNull(maxIdx)?.globalIndex == maxIdx
        ) {
            if (minIdx == 0 && maxIdx == all.size - 1) all else all.subList(minIdx, maxIdx + 1)
        } else {
            all.filter { it.globalIndex in minIdx..maxIdx }
        }

        return buildTextSelectionFromTokens(selected, minIdx, maxIdx, imageRectOnDisplay, bitmapWidth, bitmapHeight)
    }

    private fun buildTextSelectionFromTokens(
        selected: List<OcrToken>,
        startTokenIndex: Int,
        endTokenIndex: Int,
        imageRectOnDisplay: RectF,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): TextSelectionState? {
        if (selected.isEmpty()) return null

        val scaleX = if (bitmapWidth > 0) imageRectOnDisplay.width() / bitmapWidth else 1f
        val scaleY = if (bitmapHeight > 0) imageRectOnDisplay.height() / bitmapHeight else 1f

        val firstToken = selected.first()
        val lastToken = selected.last()

        val startDisplayBox = RectF(
            imageRectOnDisplay.left + firstToken.boundingBox.left * scaleX,
            imageRectOnDisplay.top + firstToken.boundingBox.top * scaleY,
            imageRectOnDisplay.left + firstToken.boundingBox.right * scaleX,
            imageRectOnDisplay.top + firstToken.boundingBox.bottom * scaleY
        )

        val endDisplayBox = RectF(
            imageRectOnDisplay.left + lastToken.boundingBox.left * scaleX,
            imageRectOnDisplay.top + lastToken.boundingBox.top * scaleY,
            imageRectOnDisplay.left + lastToken.boundingBox.right * scaleX,
            imageRectOnDisplay.top + lastToken.boundingBox.bottom * scaleY
        )

        // Concatenate text intelligently (add newline between lines, space between English words, no space for Japanese chars)
        val sb = StringBuilder()
        var lastLineIdx = -1
        for (i in selected.indices) {
            val token = selected[i]
            if (lastLineIdx != -1 && token.lineIndex != lastLineIdx) {
                sb.append("\n")
            } else if (i > 0 && !selected[i - 1].isJapaneseOrCjk && !token.isJapaneseOrCjk) {
                sb.append(" ")
            }
            sb.append(token.text)
            lastLineIdx = token.lineIndex
        }

        return TextSelectionState(
            startTokenIndex = startTokenIndex,
            endTokenIndex = endTokenIndex,
            selectedTokens = selected,
            fullText = sb.toString(),
            startPinPoint = Offset(startDisplayBox.left, startDisplayBox.top),
            endPinPoint = Offset(endDisplayBox.right, endDisplayBox.bottom)
        )
    }

    /**
     * Dragging start handle (pin) to update selection start.
     * Allows smoothly selecting arbitrary number of characters, words, and lines.
     */
    fun onDragStartPin(
        touchPoint: Offset,
        imageRectOnDisplay: RectF,
        bitmapWidth: Int,
        bitmapHeight: Int
    ) {
        val currentSel = _uiState.value.textSelection ?: return
        lastImageRect = RectF(imageRectOnDisplay)
        val index = tokenDisplayIndex(imageRectOnDisplay, bitmapWidth, bitmapHeight)

        val closest = GestureProcessor.findClosestToken(touchPoint, index.tokens, index.boxes)
        if (closest != null) {
            val targetIdx = closest.globalIndex
            val updated = buildTextSelection(targetIdx, currentSel.endTokenIndex, imageRectOnDisplay, bitmapWidth, bitmapHeight)
            if (updated != null) {
                _uiState.value = _uiState.value.copy(textSelection = updated)
            }
        }
    }

    /**
     * Dragging end handle (pin) to update selection end.
     * Allows smoothly selecting arbitrary number of characters, words, and lines.
     */
    fun onDragEndPin(
        touchPoint: Offset,
        imageRectOnDisplay: RectF,
        bitmapWidth: Int,
        bitmapHeight: Int
    ) {
        val currentSel = _uiState.value.textSelection ?: return
        lastImageRect = RectF(imageRectOnDisplay)
        val index = tokenDisplayIndex(imageRectOnDisplay, bitmapWidth, bitmapHeight)

        val closest = GestureProcessor.findClosestToken(touchPoint, index.tokens, index.boxes)
        if (closest != null) {
            val targetIdx = closest.globalIndex
            val updated = buildTextSelection(currentSel.startTokenIndex, targetIdx, imageRectOnDisplay, bitmapWidth, bitmapHeight)
            if (updated != null) {
                _uiState.value = _uiState.value.copy(textSelection = updated)
            }
        }
    }

    private fun extractCropBitmap(
        cropRectDisplay: RectF,
        imageRectDisplay: RectF,
        sourceBitmap: Bitmap?
    ): Bitmap? {
        if (sourceBitmap == null || imageRectDisplay.width() <= 0 || imageRectDisplay.height() <= 0) return null
        return try {
            val scaleX = sourceBitmap.width / imageRectDisplay.width()
            val scaleY = sourceBitmap.height / imageRectDisplay.height()

            val srcX = max(0, ((cropRectDisplay.left - imageRectDisplay.left) * scaleX).toInt())
            val srcY = max(0, ((cropRectDisplay.top - imageRectDisplay.top) * scaleY).toInt())
            val srcW = min(sourceBitmap.width - srcX, ((cropRectDisplay.width()) * scaleX).toInt())
            val srcH = min(sourceBitmap.height - srcY, ((cropRectDisplay.height()) * scaleY).toInt())

            if (srcW > 10 && srcH > 10) {
                Bitmap.createBitmap(sourceBitmap, srcX, srcY, srcW, srcH)
            } else {
                null
            }
        } catch (e: Throwable) {
            Log.e("CircleLensVM", "Failed to extract crop bitmap", e)
            null
        }
    }

    fun updateCropRect(newRect: RectF, imageRectDisplay: RectF) {
        val state = _uiState.value
        lastImageRect = RectF(imageRectDisplay)
        // Keep the previous bitmap while dragging: only the rect updates at pointer rate.
        _uiState.value = state.copy(
            activeCropSelection = CropSelection(rect = newRect, croppedBitmap = state.activeCropSelection?.croppedBitmap)
        )

        // Debounce the expensive bitmap re-crop so handle drags do not allocate per event
        cropExtractJob?.cancel()
        cropExtractJob = viewModelScope.launch {
            delay(120)
            val cur = _uiState.value
            val crop = cur.activeCropSelection ?: return@launch
            if (crop.rect != newRect) return@launch
            val cropped = extractCropBitmap(newRect, imageRectDisplay, cur.currentBitmap)
            if (cropped != null) {
                _uiState.value = _uiState.value.copy(
                    activeCropSelection = crop.copy(croppedBitmap = cropped)
                )
            }
        }
    }

    /**
     * Returns the cropped bitmap for the active crop, extracting it synchronously
     * from the full screenshot when the debounced extraction has not run yet.
     */
    private fun currentCroppedBitmap(): Bitmap? {
        val state = _uiState.value
        val crop = state.activeCropSelection ?: return null
        crop.croppedBitmap?.let { return it }
        return extractCropBitmap(crop.rect, lastImageRect, state.currentBitmap)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            textSelection = null,
            activeCropSelection = null,
            activeStrokePoints = emptyList(),
            trailPoints = emptyList(),
            feedbackMessage = null
        )
    }

    fun selectAllText(imageRectDisplay: RectF, bitmapWidth: Int, bitmapHeight: Int) {
        val all = _uiState.value.allTokens
        if (all.isNotEmpty()) {
            val sel = buildTextSelection(0, all.size - 1, imageRectDisplay, bitmapWidth, bitmapHeight)
            _uiState.value = _uiState.value.copy(
                textSelection = sel,
                activeCropSelection = null,
                feedbackMessage = "全テキストを選択しました (${sel?.selectedTokens?.size}要素)"
            )
        }
    }

    fun getSelectedTextString(): String {
        return _uiState.value.textSelection?.fullText ?: ""
    }

    fun copySelectedText() {
        val text = getSelectedTextString()
        if (text.isNotBlank()) {
            shareManager.copyTextToClipboard(text)
            _uiState.value = _uiState.value.copy(feedbackMessage = "テキストをクリップボードにコピーしました")
        }
    }

    fun shareSelectedText(target: ShareTarget? = null) {
        val text = getSelectedTextString()
        if (text.isNotBlank()) {
            if (target != null) {
                shareManager.shareTextDirect(text, target)
            } else {
                shareManager.shareTextGeneral(text)
            }
            viewModelScope.launch {
                closeAppEvent.emit(Unit)
            }
        }
    }

    fun shareCroppedImage(target: ShareTarget? = null) {
        val cropBmp = currentCroppedBitmap()
        if (cropBmp != null) {
            viewModelScope.launch {
                if (target != null) {
                    shareManager.shareImageDirect(cropBmp, target)
                } else {
                    shareManager.shareImageGeneral(cropBmp, "切り抜き画像を共有")
                }
                closeAppEvent.emit(Unit)
            }
        }
    }

    fun copyCroppedImage() {
        val cropBmp = currentCroppedBitmap()
        if (cropBmp != null) {
            viewModelScope.launch {
                shareManager.copyImageToClipboard(cropBmp)
                _uiState.value = _uiState.value.copy(feedbackMessage = "画像をクリップボードにコピーしました")
            }
        }
    }

    fun ocrInsideCrop(imageRectDisplay: RectF, bitmapWidth: Int, bitmapHeight: Int) {
        val crop = _uiState.value.activeCropSelection ?: return
        val all = _uiState.value.allTokens
        lastImageRect = RectF(imageRectDisplay)

        val scaleX = bitmapWidth / imageRectDisplay.width()
        val scaleY = bitmapHeight / imageRectDisplay.height()

        val srcLeft = ((crop.rect.left - imageRectDisplay.left) * scaleX).toInt()
        val srcTop = ((crop.rect.top - imageRectDisplay.top) * scaleY).toInt()
        val srcRight = ((crop.rect.right - imageRectDisplay.left) * scaleX).toInt()
        val srcBottom = ((crop.rect.bottom - imageRectDisplay.top) * scaleY).toInt()

        if (all.isEmpty()) {
            // Full-screen OCR has not produced tokens yet: run OCR on the cropped
            // region only, which is far smaller than the entire screenshot.
            viewModelScope.launch {
                val regionBitmap = crop.croppedBitmap
                    ?: extractCropBitmap(crop.rect, imageRectDisplay, _uiState.value.currentBitmap)
                if (regionBitmap == null) {
                    _uiState.value = _uiState.value.copy(
                        feedbackMessage = "枠内にテキストが見つかりませんでした"
                    )
                    return@launch
                }
                val results = try {
                    ocrManager.recognizeText(regionBitmap)
                } catch (e: Throwable) {
                    Log.e("CircleLensVM", "Region OCR failed", e)
                    emptyList<OcrTextItem>()
                }
                // Map region tokens back into full-bitmap coordinates
                val regionTokens = results.flatMap { it.tokens }.map { token ->
                    token.copy(boundingBox = Rect(token.boundingBox).offset(srcLeft, srcTop))
                }
                val sel = buildTextSelectionFromTokens(
                    regionTokens, 0, regionTokens.size - 1, imageRectDisplay, bitmapWidth, bitmapHeight
                )
                _uiState.value = _uiState.value.copy(
                    textSelection = sel,
                    activeCropSelection = null,
                    feedbackMessage = if (sel != null) {
                        "枠内のテキストを抽出しました (${regionTokens.size}要素)"
                    } else {
                        "枠内にテキストが見つかりませんでした"
                    }
                )
            }
            return
        }

        val insideTokens = all.filter { token ->
            val box = token.boundingBox
            box.centerX() in srcLeft..srcRight && box.centerY() in srcTop..srcBottom
        }

        if (insideTokens.isNotEmpty()) {
            val minIdx = insideTokens.minOf { it.globalIndex }
            val maxIdx = insideTokens.maxOf { it.globalIndex }
            val sel = buildTextSelection(minIdx, maxIdx, imageRectDisplay, bitmapWidth, bitmapHeight)
            _uiState.value = _uiState.value.copy(
                textSelection = sel,
                activeCropSelection = null,
                feedbackMessage = "枠内のテキストを抽出しました (${insideTokens.size}要素)"
            )
        } else {
            _uiState.value = _uiState.value.copy(
                feedbackMessage = "枠内にテキストが見つかりませんでした"
            )
        }
    }

    fun shareEntireScreen(target: ShareTarget? = null) {
        val fullBmp = _uiState.value.currentBitmap
        if (fullBmp == null) {
            _uiState.value = _uiState.value.copy(
                feedbackMessage = "画面キャプチャを準備中です。少々お待ちください..."
            )
            return
        }
        viewModelScope.launch {
            if (target != null) {
                shareManager.shareImageDirect(fullBmp, target)
            } else {
                shareManager.shareImageGeneral(fullBmp, "画面全体を共有")
            }
            closeAppEvent.emit(Unit)
        }
    }

    fun showTargetPicker(show: Boolean, isImage: Boolean) {
        _uiState.value = _uiState.value.copy(
            showTargetPickerSheet = show,
            targetPickerIsImage = isImage
        )
    }

    fun selectTargetFromPicker(target: ShareTarget, isImage: Boolean) {
        shareManager.updateLastShareTarget(target, isImage)
        _uiState.value = if (isImage) {
            _uiState.value.copy(lastImageShareTarget = target, showTargetPickerSheet = false)
        } else {
            _uiState.value.copy(lastTextShareTarget = target, showTargetPickerSheet = false)
        }
    }

    fun showAssistHelp(show: Boolean) {
        _uiState.value = _uiState.value.copy(showAssistHelpSheet = show)
    }

    fun dismissFeedback() {
        _uiState.value = _uiState.value.copy(feedbackMessage = null)
    }
}
