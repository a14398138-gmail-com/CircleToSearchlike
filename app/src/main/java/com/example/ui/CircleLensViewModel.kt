package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gesture.GestureProcessor
import com.example.gesture.ImageToDisplayMapping
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    // Created lazily, off the main thread (see init): ShareManager's constructor
    // reads SharedPreferences and resolves the recent targets' labels/icons
    // through PackageManager, which used to block the first frame.
    val shareManager: ShareManager by lazy { ShareManager.getInstance(application) }

    private val _uiState = MutableStateFlow(CircleLensUiState())
    val uiState: StateFlow<CircleLensUiState> = _uiState.asStateFlow()

    val closeAppEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 2)

    private var activeLoadJob: Job? = null
    private var isRealBitmapLoaded: Boolean = false

    /** Bitmap whose OCR is running or whose results are in [uiState]. */
    private var ocrBitmap: Bitmap? = null

    init {
        // Observe share targets and recent history. All state writes go through
        // _uiState.update {} because they happen on several threads.
        viewModelScope.launch(Dispatchers.Default) {
            val sm = shareManager
            launch {
                sm.lastTextShareTarget.collect { target ->
                    _uiState.update { it.copy(lastTextShareTarget = target) }
                }
            }
            launch {
                sm.lastImageShareTarget.collect { target ->
                    _uiState.update { it.copy(lastImageShareTarget = target) }
                }
            }
            launch {
                sm.recentTextShareTargets.collect { list ->
                    _uiState.update { it.copy(recentTextTargets = list) }
                }
            }
            launch {
                sm.recentImageShareTargets.collect { list ->
                    _uiState.update { it.copy(recentImageTargets = list) }
                }
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

        // The full list of share targets (every app's label + icon) is only
        // needed by the target picker, so it is loaded on demand in
        // showTargetPicker() instead of on every launch.

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
            _uiState.update {
                it.copy(
                    availableTextTargets = textTargets,
                    availableImageTargets = imageTargets
                )
            }
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
                _uiState.update { it.copy(feedbackMessage = "画面を取得中...") }
            }
        }
    }

    fun loadBitmapDirect(bitmap: Bitmap, message: String? = null) {
        // The same screenshot reaches us through several paths (holder flow,
        // onCreate/onNewIntent, onResume). Restarting OCR for it cancelled the
        // in-flight recognition and queued a second one behind it in ML Kit,
        // roughly doubling time-to-text on warm launches.
        if (bitmap === ocrBitmap && (activeLoadJob?.isActive == true || !_uiState.value.isOcrRunning)) {
            return
        }

        activeLoadJob?.cancel()
        isRealBitmapLoaded = true
        ocrBitmap = bitmap

        // ⚡ INSTANT RENDERING: Display bitmap immediately in 0ms without waiting for OCR.
        // OCR boxes of a previous screenshot are dropped so they are not drawn
        // (or hit-tested) on top of the new one while recognition runs.
        _uiState.update {
            val sameBitmap = it.currentBitmap === bitmap
            it.copy(
                currentBitmap = bitmap,
                isOcrRunning = true,
                ocrItems = if (sameBitmap) it.ocrItems else emptyList(),
                allTokens = if (sameBitmap) it.allTokens else emptyList(),
                textSelection = null,
                activeCropSelection = null,
                activeStrokePoints = emptyList(),
                feedbackMessage = message
            )
        }

        activeLoadJob = viewModelScope.launch {
            try {
                val ocrResults = ocrManager.recognizeText(bitmap)
                val flatTokens = ocrResults.flatMap { it.tokens }

                _uiState.update {
                    if (it.currentBitmap !== bitmap) it else it.copy(
                        ocrItems = ocrResults,
                        allTokens = flatTokens,
                        isOcrRunning = false,
                        feedbackMessage = null
                    )
                }
            } catch (e: CancellationException) {
                // Superseded by a newer load: it owns the state now. (The old
                // catch-all reverted currentBitmap to this stale bitmap.)
                throw e
            } catch (e: Throwable) {
                Log.e("CircleLensVM", "OCR execution error", e)
                _uiState.update {
                    if (it.currentBitmap !== bitmap) it else it.copy(
                        isOcrRunning = false,
                        feedbackMessage = null
                    )
                }
            }
        }
    }

    fun loadBitmapFromUri(uri: Uri) {
        activeLoadJob?.cancel()
        ocrBitmap = null
        _uiState.update {
            it.copy(
                isOcrRunning = true,
                textSelection = null,
                activeCropSelection = null,
                activeStrokePoints = emptyList(),
                feedbackMessage = "画像をOCR解析中..."
            )
        }

        activeLoadJob = viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    // Decoded with subsampling: shared photos can be 12-50MP,
                    // far beyond what is displayed or fed to OCR (max 1080px).
                    ScreenshotHelper.decodeSampledBitmap(getApplication(), uri)
                } catch (e: Throwable) {
                    Log.e("CircleLensVM", "Failed to load image from URI", e)
                    null
                }
            }

            if (bitmap != null) {
                ocrBitmap = bitmap
                try {
                    val ocrResults = ocrManager.recognizeText(bitmap)
                    val flatTokens = ocrResults.flatMap { it.tokens }
                    _uiState.update {
                        it.copy(
                            currentBitmap = bitmap,
                            ocrItems = ocrResults,
                            allTokens = flatTokens,
                            isOcrRunning = false,
                            feedbackMessage = "OCR解析が完了しました (${flatTokens.size}要素 検出)"
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e("CircleLensVM", "OCR error for URI image", e)
                    _uiState.update {
                        it.copy(
                            currentBitmap = bitmap,
                            ocrItems = emptyList(),
                            allTokens = emptyList(),
                            isOcrRunning = false,
                            feedbackMessage = null
                        )
                    }
                }
            } else {
                _uiState.update {
                    it.copy(
                        isOcrRunning = false,
                        feedbackMessage = "画像の読み込みに失敗しました"
                    )
                }
            }
        }
    }

    fun onStrokeStart(point: Offset) {
        _uiState.update {
            it.copy(
                activeStrokePoints = listOf(point),
                trailPoints = listOf(TrailPoint(position = point))
            )
        }
    }

    fun onStrokeMove(
        point: Offset,
        imageRectOnDisplay: RectF,
        bitmapWidth: Int,
        bitmapHeight: Int
    ) {
        // Only update trajectory points and trail while drawing to prevent accidental text selection
        _uiState.update {
            it.copy(
                activeStrokePoints = it.activeStrokePoints + point,
                trailPoints = (it.trailPoints + TrailPoint(position = point)).takeLast(40)
            )
        }
    }

    fun onStrokeEnd(
        imageRectOnDisplay: RectF,
        bitmapWidth: Int,
        bitmapHeight: Int
    ) {
        val state = _uiState.value
        val points = state.activeStrokePoints

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

                _uiState.update {
                    it.copy(
                        // Pixels are cut out lazily when the crop is shared/copied.
                        activeCropSelection = CropSelection(rect = cropFrame, imageRect = RectF(imageRectOnDisplay)),
                        textSelection = null,
                        activeStrokePoints = emptyList(),
                        trailPoints = emptyList(),
                        feedbackMessage = "画像を切り抜きました"
                    )
                }
                return
            }
        }

        // 2. SECOND PRIORITY: If clearly not a circle, process as text swipe or tap
        val mapping = ImageToDisplayMapping.of(imageRectOnDisplay, bitmapWidth, bitmapHeight)

        val range = GestureProcessor.findTokensInStroke(points, state.allTokens, mapping)
        val finalSelection = if (range != null) {
            buildTextSelection(range.first, range.second, imageRectOnDisplay, bitmapWidth, bitmapHeight)
        } else if (points.size <= 8) {
            // Tap check on single word - only if tapped closely on the word (within ~24px proximity)
            val tapPt = points.last()
            val closest = GestureProcessor.findClosestToken(tapPt, state.allTokens, mapping)
            if (closest != null) {
                val box = mapping.toDisplayRect(closest.boundingBox)
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

        _uiState.update {
            it.copy(
                activeStrokePoints = emptyList(),
                trailPoints = emptyList(),
                textSelection = finalSelection,
                activeCropSelection = newCropSelection
            )
        }
    }

    /** Clamped, ordered token range exactly as [buildTextSelection] stores it. */
    private fun normalizedRange(a: Int, b: Int, tokenCount: Int): IntRange =
        max(0, min(a, b))..min(tokenCount - 1, max(a, b))

    private fun buildTextSelection(
        startGlobalIdx: Int,
        endGlobalIdx: Int,
        imageRectOnDisplay: RectF,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): TextSelectionState? {
        val all = _uiState.value.allTokens
        if (all.isEmpty()) return null

        val range = normalizedRange(startGlobalIdx, endGlobalIdx, all.size)
        val minIdx = range.first
        val maxIdx = range.last
        if (minIdx > maxIdx) return null

        // OcrManager assigns globalIndex sequentially, so allTokens[i].globalIndex == i
        // and the selection is a contiguous slice (no full scan per drag event).
        val selected = if (all[minIdx].globalIndex == minIdx && all[maxIdx].globalIndex == maxIdx) {
            all.subList(minIdx, maxIdx + 1)
        } else {
            all.filter { it.globalIndex in minIdx..maxIdx }
        }
        if (selected.isEmpty()) return null

        val mapping = ImageToDisplayMapping.of(imageRectOnDisplay, bitmapWidth, bitmapHeight)

        val firstToken = selected.first()
        val lastToken = selected.last()

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
            startTokenIndex = minIdx,
            endTokenIndex = maxIdx,
            selectedTokens = selected,
            fullText = sb.toString(),
            startPinPoint = Offset(mapping.toDisplayX(firstToken.boundingBox.left), mapping.toDisplayY(firstToken.boundingBox.top)),
            endPinPoint = Offset(mapping.toDisplayX(lastToken.boundingBox.right), mapping.toDisplayY(lastToken.boundingBox.bottom))
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
        val state = _uiState.value
        val currentSel = state.textSelection ?: return
        val mapping = ImageToDisplayMapping.of(imageRectOnDisplay, bitmapWidth, bitmapHeight)

        val closest = GestureProcessor.findClosestToken(touchPoint, state.allTokens, mapping) ?: return
        updateSelectionRange(closest.globalIndex, currentSel.endTokenIndex, currentSel, imageRectOnDisplay, bitmapWidth, bitmapHeight)
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
        val state = _uiState.value
        val currentSel = state.textSelection ?: return
        val mapping = ImageToDisplayMapping.of(imageRectOnDisplay, bitmapWidth, bitmapHeight)

        val closest = GestureProcessor.findClosestToken(touchPoint, state.allTokens, mapping) ?: return
        updateSelectionRange(currentSel.startTokenIndex, closest.globalIndex, currentSel, imageRectOnDisplay, bitmapWidth, bitmapHeight)
    }

    private fun updateSelectionRange(
        a: Int,
        b: Int,
        currentSel: TextSelectionState,
        imageRectOnDisplay: RectF,
        bitmapWidth: Int,
        bitmapHeight: Int
    ) {
        // Pins are dragged at pointer-event rate; most events stay on the same
        // token. Skip rebuilding the selection (and recomposing/redrawing the
        // overlay) when the resulting range is unchanged.
        val range = normalizedRange(a, b, _uiState.value.allTokens.size)
        if (range.first == currentSel.startTokenIndex && range.last == currentSel.endTokenIndex) return

        val updated = buildTextSelection(a, b, imageRectOnDisplay, bitmapWidth, bitmapHeight) ?: return
        _uiState.update { it.copy(textSelection = updated) }
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

    /**
     * Pixels of [crop], cut out of the current screenshot on a background
     * thread. Done on demand: extracting on every crop-handle drag event
     * allocated a multi-megabyte bitmap per pointer move.
     */
    private suspend fun cropBitmapFor(crop: CropSelection): Bitmap? {
        crop.croppedBitmap?.let { return it }
        val imageRect = crop.imageRect ?: return null
        val source = _uiState.value.currentBitmap ?: return null
        return withContext(Dispatchers.Default) { extractCropBitmap(crop.rect, imageRect, source) }
    }

    fun updateCropRect(newRect: RectF, imageRectDisplay: RectF) {
        _uiState.update {
            it.copy(activeCropSelection = CropSelection(rect = newRect, imageRect = imageRectDisplay))
        }
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(
                textSelection = null,
                activeCropSelection = null,
                activeStrokePoints = emptyList(),
                trailPoints = emptyList(),
                feedbackMessage = null
            )
        }
    }

    fun selectAllText(imageRectDisplay: RectF, bitmapWidth: Int, bitmapHeight: Int) {
        val all = _uiState.value.allTokens
        if (all.isNotEmpty()) {
            val sel = buildTextSelection(0, all.size - 1, imageRectDisplay, bitmapWidth, bitmapHeight)
            _uiState.update {
                it.copy(
                    textSelection = sel,
                    activeCropSelection = null,
                    feedbackMessage = "全テキストを選択しました (${sel?.selectedTokens?.size}要素)"
                )
            }
        }
    }

    fun getSelectedTextString(): String {
        return _uiState.value.textSelection?.fullText ?: ""
    }

    fun copySelectedText() {
        val text = getSelectedTextString()
        if (text.isNotBlank()) {
            shareManager.copyTextToClipboard(text)
            _uiState.update { it.copy(feedbackMessage = "テキストをクリップボードにコピーしました") }
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
        val crop = _uiState.value.activeCropSelection ?: return
        viewModelScope.launch {
            val cropBmp = cropBitmapFor(crop) ?: return@launch
            if (target != null) {
                shareManager.shareImageDirect(cropBmp, target)
            } else {
                shareManager.shareImageGeneral(cropBmp, "切り抜き画像を共有")
            }
            closeAppEvent.emit(Unit)
        }
    }

    fun copyCroppedImage() {
        val crop = _uiState.value.activeCropSelection ?: return
        viewModelScope.launch {
            val cropBmp = cropBitmapFor(crop) ?: return@launch
            shareManager.copyImageToClipboard(cropBmp)
            _uiState.update { it.copy(feedbackMessage = "画像をクリップボードにコピーしました") }
        }
    }

    fun ocrInsideCrop(imageRectDisplay: RectF, bitmapWidth: Int, bitmapHeight: Int) {
        val crop = _uiState.value.activeCropSelection ?: return
        val all = _uiState.value.allTokens
        if (all.isEmpty()) return

        val scaleX = bitmapWidth / imageRectDisplay.width()
        val scaleY = bitmapHeight / imageRectDisplay.height()

        val srcLeft = ((crop.rect.left - imageRectDisplay.left) * scaleX).toInt()
        val srcTop = ((crop.rect.top - imageRectDisplay.top) * scaleY).toInt()
        val srcRight = ((crop.rect.right - imageRectDisplay.left) * scaleX).toInt()
        val srcBottom = ((crop.rect.bottom - imageRectDisplay.top) * scaleY).toInt()

        val insideTokens = all.filter { token ->
            val box = token.boundingBox
            box.centerX() in srcLeft..srcRight && box.centerY() in srcTop..srcBottom
        }

        if (insideTokens.isNotEmpty()) {
            val minIdx = insideTokens.minOf { it.globalIndex }
            val maxIdx = insideTokens.maxOf { it.globalIndex }
            val sel = buildTextSelection(minIdx, maxIdx, imageRectDisplay, bitmapWidth, bitmapHeight)
            _uiState.update {
                it.copy(
                    textSelection = sel,
                    activeCropSelection = null,
                    feedbackMessage = "枠内のテキストを抽出しました (${insideTokens.size}要素)"
                )
            }
        } else {
            _uiState.update { it.copy(feedbackMessage = "枠内にテキストが見つかりませんでした") }
        }
    }

    fun shareEntireScreen(target: ShareTarget? = null) {
        val fullBmp = _uiState.value.currentBitmap
        if (fullBmp == null) {
            _uiState.update { it.copy(feedbackMessage = "画面キャプチャを準備中です。少々お待ちください...") }
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
        _uiState.update {
            it.copy(
                showTargetPickerSheet = show,
                targetPickerIsImage = isImage
            )
        }
        // Loaded on demand (see init): only the picker lists every share target.
        if (show) {
            val state = _uiState.value
            val needed = if (isImage) state.availableImageTargets else state.availableTextTargets
            if (needed.isEmpty()) refreshAvailableTargets()
        }
    }

    fun selectTargetFromPicker(target: ShareTarget, isImage: Boolean) {
        shareManager.updateLastShareTarget(target, isImage)
        _uiState.update {
            if (isImage) {
                it.copy(lastImageShareTarget = target, showTargetPickerSheet = false)
            } else {
                it.copy(lastTextShareTarget = target, showTargetPickerSheet = false)
            }
        }
    }

    fun showAssistHelp(show: Boolean) {
        _uiState.update { it.copy(showAssistHelpSheet = show) }
    }

    fun dismissFeedback() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }
}
