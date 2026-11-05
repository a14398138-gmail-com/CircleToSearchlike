package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
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

    companion object {
        /** Crops smaller than this (in px, longest side) are upscaled before re-OCR. */
        private const val CROP_OCR_TARGET_MAX_DIM = 1600

        /** Upper bound for the crop upscale factor. */
        private const val CROP_OCR_MAX_UPSCALE = 2.5f
    }

    private val ocrManager = OcrManager()
    val shareManager = ShareManager.getInstance(application)

    private val _uiState = MutableStateFlow(CircleLensUiState())
    val uiState: StateFlow<CircleLensUiState> = _uiState.asStateFlow()

    val closeAppEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 2)

    private var activeLoadJob: Job? = null
    private var isRealBitmapLoaded: Boolean = false

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
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(cr, uri)
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
        val currentPoints = _uiState.value.activeStrokePoints + point
        val newTrails = (_uiState.value.trailPoints + TrailPoint(position = point)).takeLast(40)

        // Only update trajectory points and trail while drawing to prevent accidental text selection
        _uiState.value = _uiState.value.copy(
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
        val transform: (Rect) -> RectF = { bBox ->
            val scaleX = imageRectOnDisplay.width() / bitmapWidth
            val scaleY = imageRectOnDisplay.height() / bitmapHeight
            RectF(
                imageRectOnDisplay.left + bBox.left * scaleX,
                imageRectOnDisplay.top + bBox.top * scaleY,
                imageRectOnDisplay.left + bBox.right * scaleX,
                imageRectOnDisplay.top + bBox.bottom * scaleY
            )
        }

        val range = GestureProcessor.findTokensInStroke(points, state.allTokens, transform)
        val finalSelection = if (range != null) {
            buildTextSelection(range.first, range.second, imageRectOnDisplay, bitmapWidth, bitmapHeight)
        } else if (points.size <= 8) {
            // Tap check on single word - only if tapped closely on the word (within ~24px proximity)
            val tapPt = points.last()
            val closest = GestureProcessor.findClosestToken(tapPt, state.allTokens, transform)
            if (closest != null) {
                val box = transform(closest.boundingBox)
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

        val selected = all.filter { it.globalIndex in minIdx..maxIdx }
        if (selected.isEmpty()) return null

        val scaleX = imageRectOnDisplay.width() / bitmapWidth
        val scaleY = imageRectOnDisplay.height() / bitmapHeight

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

        val joinedText = joinSelectedText(selected)

        return TextSelectionState(
            startTokenIndex = minIdx,
            endTokenIndex = maxIdx,
            selectedTokens = selected,
            fullText = joinedText,
            startPinPoint = Offset(startDisplayBox.left, startDisplayBox.top),
            endPinPoint = Offset(endDisplayBox.right, endDisplayBox.bottom)
        )
    }

    /**
     * Concatenates tokens into selection text: newline between lines, no space
     * around Japanese/CJK tokens, and for Latin/digit tokens a space only when
     * the physical gap between the token boxes is wide enough to be a real word
     * space.
     *
     * The previous rule inserted a space between *every* pair of non-CJK
     * tokens, which shredded symbol-split sequences into things like
     * "2 0 2 5 / 0 4 / 2 1" and "m y way" whenever ML Kit returned a Latin word
     * or a number as individual character symbols.
     */
    private fun joinSelectedText(selected: List<OcrToken>): String {
        val sb = StringBuilder()
        var lastLineIdx = -1
        for (i in selected.indices) {
            val token = selected[i]
            if (i > 0) {
                if (token.lineIndex != lastLineIdx) {
                    sb.append("\n")
                } else {
                    val prev = selected[i - 1]
                    if (!prev.isJapaneseOrCjk && !token.isJapaneseOrCjk && shouldInsertSpace(prev, token)) {
                        sb.append(" ")
                    }
                }
            }
            sb.append(token.text)
            lastLineIdx = token.lineIndex
        }
        return sb.toString()
    }

    private fun shouldInsertSpace(prev: OcrToken, cur: OcrToken): Boolean {
        // Closing punctuation binds to the previous token: "410 ," -> "410,"
        if (cur.text.isNotEmpty() && cur.text.all { it in ",.!?;:%/)]}\u3001\u3002" }) return false
        // Nothing separates a token from a preceding opening bracket
        if (prev.text.isNotEmpty() && prev.text.all { it in "([{" }) return false

        val gap = cur.boundingBox.left - prev.boundingBox.right
        if (gap <= 0) return false
        val refHeight = min(prev.boundingBox.height(), cur.boundingBox.height()).coerceAtLeast(1)
        // Intra-word symbol gaps are a small fraction of the glyph height,
        // while a real word space is roughly a quarter of it or more.
        return gap > refHeight * 0.25f
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
        val transform: (Rect) -> RectF = { bBox ->
            val scaleX = imageRectOnDisplay.width() / bitmapWidth
            val scaleY = imageRectOnDisplay.height() / bitmapHeight
            RectF(
                imageRectOnDisplay.left + bBox.left * scaleX,
                imageRectOnDisplay.top + bBox.top * scaleY,
                imageRectOnDisplay.left + bBox.right * scaleX,
                imageRectOnDisplay.top + bBox.bottom * scaleY
            )
        }

        val closest = GestureProcessor.findClosestToken(touchPoint, _uiState.value.allTokens, transform)
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
        val transform: (Rect) -> RectF = { bBox ->
            val scaleX = imageRectOnDisplay.width() / bitmapWidth
            val scaleY = imageRectOnDisplay.height() / bitmapHeight
            RectF(
                imageRectOnDisplay.left + bBox.left * scaleX,
                imageRectOnDisplay.top + bBox.top * scaleY,
                imageRectOnDisplay.left + bBox.right * scaleX,
                imageRectOnDisplay.top + bBox.bottom * scaleY
            )
        }

        val closest = GestureProcessor.findClosestToken(touchPoint, _uiState.value.allTokens, transform)
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
        val cropped = extractCropBitmap(newRect, imageRectDisplay, state.currentBitmap)
        _uiState.value = state.copy(
            activeCropSelection = CropSelection(rect = newRect, croppedBitmap = cropped)
        )
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
        val cropBmp = _uiState.value.activeCropSelection?.croppedBitmap
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
        val cropBmp = _uiState.value.activeCropSelection?.croppedBitmap
        if (cropBmp != null) {
            viewModelScope.launch {
                shareManager.copyImageToClipboard(cropBmp)
                _uiState.value = _uiState.value.copy(feedbackMessage = "画像をクリップボードにコピーしました")
            }
        }
    }

    /**
     * Re-runs OCR on just the circled region instead of reusing the full-screen
     * result. The crop is cut from the original bitmap, upscaled when small so
     * glyphs get more pixels, and recognized on its own — the region the user
     * explicitly cared about gets a second, better look, which fixes many
     * one-character errors the full-screen pass made.
     *
     * If the re-run finds nothing, it falls back to the previous behaviour of
     * selecting the full-screen tokens inside the frame.
     */
    fun ocrInsideCrop(imageRectDisplay: RectF, bitmapWidth: Int, bitmapHeight: Int) {
        val crop = _uiState.value.activeCropSelection ?: return
        val source = _uiState.value.currentBitmap ?: return
        if (_uiState.value.isOcrRunning) return
        if (imageRectDisplay.width() <= 0f || imageRectDisplay.height() <= 0f) return

        val scaleX = bitmapWidth / imageRectDisplay.width()
        val scaleY = bitmapHeight / imageRectDisplay.height()

        val padPx = 12
        val srcLeft = (((crop.rect.left - imageRectDisplay.left) * scaleX).toInt() - padPx).coerceIn(0, bitmapWidth - 1)
        val srcTop = (((crop.rect.top - imageRectDisplay.top) * scaleY).toInt() - padPx).coerceIn(0, bitmapHeight - 1)
        val srcRight = (((crop.rect.right - imageRectDisplay.left) * scaleX).toInt() + padPx).coerceIn(srcLeft + 1, bitmapWidth)
        val srcBottom = (((crop.rect.bottom - imageRectDisplay.top) * scaleY).toInt() + padPx).coerceIn(srcTop + 1, bitmapHeight)
        val srcW = srcRight - srcLeft
        val srcH = srcBottom - srcTop
        if (srcW < 16 || srcH < 16) return

        // Full-screen tokens inside the frame, kept for fallback and for
        // approximating the selection index range used by the pin handles.
        val insideTokens = _uiState.value.allTokens.filter { token ->
            val box = token.boundingBox
            box.centerX() in srcLeft..srcRight && box.centerY() in srcTop..srcBottom
        }
        val fallbackStartIdx = insideTokens.minOfOrNull { it.globalIndex } ?: 0
        val fallbackEndIdx = insideTokens.maxOfOrNull { it.globalIndex } ?: 0

        _uiState.value = _uiState.value.copy(
            isOcrRunning = true,
            feedbackMessage = "枠内を再OCR中..."
        )

        viewModelScope.launch {
            var cropBmp: Bitmap? = null
            var ocrInput: Bitmap? = null
            try {
                cropBmp = Bitmap.createBitmap(source, srcLeft, srcTop, srcW, srcH)

                // Upscale small crops so characters reach a comfortable pixel size
                val maxDim = max(srcW, srcH)
                val upscale = if (maxDim < CROP_OCR_TARGET_MAX_DIM) {
                    min(CROP_OCR_MAX_UPSCALE, CROP_OCR_TARGET_MAX_DIM.toFloat() / maxDim)
                } else {
                    1f
                }
                ocrInput = if (upscale > 1f) {
                    Bitmap.createScaledBitmap(
                        cropBmp,
                        (srcW * upscale).toInt().coerceAtLeast(1),
                        (srcH * upscale).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    cropBmp
                }

                val items = ocrManager.recognizeText(ocrInput)

                // Map token boxes from OCR-input coordinates back to original bitmap coordinates
                val mappedTokens = mutableListOf<OcrToken>()
                var globalIdx = 0
                for (item in items) {
                    for (token in item.tokens) {
                        val b = token.boundingBox
                        val mappedBox = Rect(
                            srcLeft + (b.left / upscale).toInt(),
                            srcTop + (b.top / upscale).toInt(),
                            srcLeft + (b.right / upscale).toInt(),
                            srcTop + (b.bottom / upscale).toInt()
                        )
                        mappedTokens.add(
                            token.copy(
                                id = "crop_${token.id}",
                                boundingBox = mappedBox,
                                globalIndex = globalIdx++
                            )
                        )
                    }
                }

                if (mappedTokens.isNotEmpty()) {
                    val dispScaleX = imageRectDisplay.width() / bitmapWidth
                    val dispScaleY = imageRectDisplay.height() / bitmapHeight
                    fun toDisplay(box: Rect) = RectF(
                        imageRectDisplay.left + box.left * dispScaleX,
                        imageRectDisplay.top + box.top * dispScaleY,
                        imageRectDisplay.left + box.right * dispScaleX,
                        imageRectDisplay.top + box.bottom * dispScaleY
                    )
                    val startBox = toDisplay(mappedTokens.first().boundingBox)
                    val endBox = toDisplay(mappedTokens.last().boundingBox)

                    _uiState.value = _uiState.value.copy(
                        textSelection = TextSelectionState(
                            startTokenIndex = fallbackStartIdx,
                            endTokenIndex = fallbackEndIdx,
                            selectedTokens = mappedTokens,
                            fullText = joinSelectedText(mappedTokens),
                            startPinPoint = Offset(startBox.left, startBox.top),
                            endPinPoint = Offset(endBox.right, endBox.bottom)
                        ),
                        activeCropSelection = null,
                        isOcrRunning = false,
                        feedbackMessage = "枠内テキストを再OCRで抽出しました (${mappedTokens.size}要素)"
                    )
                } else if (insideTokens.isNotEmpty()) {
                    // Re-OCR found nothing: fall back to the full-screen tokens in the frame
                    val sel = buildTextSelection(fallbackStartIdx, fallbackEndIdx, imageRectDisplay, bitmapWidth, bitmapHeight)
                    _uiState.value = _uiState.value.copy(
                        textSelection = sel,
                        activeCropSelection = null,
                        isOcrRunning = false,
                        feedbackMessage = "枠内のテキストを抽出しました (${insideTokens.size}要素)"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isOcrRunning = false,
                        feedbackMessage = "枠内にテキストが見つかりませんでした"
                    )
                }
            } catch (e: Throwable) {
                Log.e("CircleLensVM", "Crop re-OCR failed", e)
                _uiState.value = _uiState.value.copy(
                    isOcrRunning = false,
                    feedbackMessage = "枠内OCRに失敗しました"
                )
            } finally {
                if (ocrInput != null && ocrInput != cropBmp) {
                    try { ocrInput.recycle() } catch (_: Throwable) {}
                }
                if (cropBmp != null && cropBmp != source) {
                    try { cropBmp.recycle() } catch (_: Throwable) {}
                }
            }
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
