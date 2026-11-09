package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
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
import com.example.model.TextJoiner
import com.example.model.TrailPoint
import com.example.ocr.OcrManager
import com.example.translate.TranslateController
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
    val feedbackMessage: String? = null,
    // Translation session state lives in the ViewModel (not `remember`) so
    // rotation / Activity recreation no longer loses the source text, the
    // translation, or the language pair while OCR state survives.
    val translateSourceText: String? = null,
    val translateResult: String? = null,
    val translateSrcLang: String = "ja",
    val translateTgtLang: String = "en"
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

    // The last share targets come from ShareManager's cheap snapshot, so the
    // direct-share button is laid out in the first frame instead of popping in
    // once ShareManager has loaded in the background.
    private val _uiState = MutableStateFlow(
        ShareManager.quickSnapshot(application).let { snapshot ->
            CircleLensUiState(
                lastTextShareTarget = snapshot.lastTextTarget,
                lastImageShareTarget = snapshot.lastImageTarget,
                recentTextTargets = snapshot.recentTextTargets,
                recentImageTargets = snapshot.recentImageTargets
            )
        }
    )
    val uiState: StateFlow<CircleLensUiState> = _uiState.asStateFlow()

    val closeAppEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 2)

    private var activeLoadJob: Job? = null
    private var isRealBitmapLoaded: Boolean = false

    /**
     * Original file of [CircleLensUiState.currentBitmap] when that bitmap was
     * decoded reduced (very large shared images), so crops can be saved and
     * shared at the file's full resolution. Null for screenshots, which are
     * always kept at their native resolution.
     */
    private var fullResolutionSource: FullResolutionSource? = null

    private class FullResolutionSource(val bitmap: Bitmap, val uri: Uri, val width: Int, val height: Int)

    /** Bitmap whose OCR is running or whose results are in [uiState]. */
    private var ocrBitmap: Bitmap? = null

    init {
        // Share targets: one IO initialisation inside ShareManager (its
        // constructor does no PackageManager/icon work). Its flows are only
        // collected once it is initialized: before that they are still empty
        // and would overwrite the snapshot shown in the first frame.
        // All state writes use _uiState.update {}: OCR tile results and these
        // collectors run on other threads than the UI callbacks.
        viewModelScope.launch {
            shareManager.initialize()
            _uiState.update {
                it.copy(
                    availableTextTargets = shareManager.cachedAvailableTargets(isImage = false),
                    availableImageTargets = shareManager.cachedAvailableTargets(isImage = true)
                )
            }
            launch {
                shareManager.lastTextShareTarget.collect { target ->
                    _uiState.update { it.copy(lastTextShareTarget = target) }
                }
            }
            launch {
                shareManager.lastImageShareTarget.collect { target ->
                    _uiState.update { it.copy(lastImageShareTarget = target) }
                }
            }
            launch {
                shareManager.recentTextShareTargets.collect { list ->
                    _uiState.update { it.copy(recentTextTargets = list) }
                }
            }
            launch {
                shareManager.recentImageShareTargets.collect { list ->
                    _uiState.update { it.copy(recentImageTargets = list) }
                }
            }
        }

        // Pre-warm OCR engine in background to eliminate first-use cold start latency
        viewModelScope.launch(Dispatchers.Default) {
            ocrManager.warmUp()
        }

        // Single screenshot consumer path: only this method ever loads a
        // pending screenshot, and each screenshot id is loaded at most once.
        loadPendingScreenshotIfNew()
    }

    private var lastLoadedScreenshotId: Long = 0L

    /**
     * The one and only entry point for assistant screenshots. Lifecycle
     * callbacks (MainActivity.onResume / intent handling) call this instead
     * of consuming the holder themselves, so the same bitmap can never be
     * loaded (and OCR'd) twice.
     */
    fun loadPendingScreenshotIfNew() {
        val pending = CircleLensScreenshotHolder.peekPending() ?: return
        val (id, bitmap) = pending
        if (id == lastLoadedScreenshotId) return
        lastLoadedScreenshotId = id
        CircleLensScreenshotHolder.clearIf(id)
        loadBitmapDirect(bitmap, null)
    }

    /**
     * Full list of share targets (every app's label + icon). Only the target
     * picker needs it, so it is queried on demand (see [showTargetPicker]).
     */
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
        if (CircleLensScreenshotHolder.hasPendingScreenshot()) {
            loadPendingScreenshotIfNew()
            return
        }

        activeLoadJob?.cancel()
        activeLoadJob = viewModelScope.launch {
            // Check if latest device screenshot is available from system
            val recent = ScreenshotHelper.getLatestDeviceScreenshot(getApplication())
            if (recent != null) {
                loadBitmapDirect(recent, null)
            } else if (_uiState.value.currentBitmap == null) {
                _uiState.update { it.copy(
                    feedbackMessage = "画面を取得中..."
                ) }
            }
        }
    }

    fun loadBitmapDirect(bitmap: Bitmap, message: String? = null) {
        // Same bitmap already recognized or being recognized: nothing to do.
        if (bitmap === ocrBitmap && (activeLoadJob?.isActive == true || !_uiState.value.isOcrRunning)) {
            return
        }

        activeLoadJob?.cancel()
        isRealBitmapLoaded = true
        ocrBitmap = bitmap
        if (fullResolutionSource?.bitmap !== bitmap) fullResolutionSource = null

        // ⚡ INSTANT RENDERING: Display bitmap immediately in 0ms without waiting for OCR
        // A new capture starts a fresh translation session. OCR boxes of a
        // previous screenshot are dropped so they are not drawn (or hit-tested)
        // on top of the new one while recognition runs.
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
                feedbackMessage = message,
                translateSourceText = null,
                translateResult = null
            )
        }

        activeLoadJob = viewModelScope.launch {
            try {
                // Progressive: finished tiles are published as they complete
                // (from a background thread, hence update {} and the bitmap check).
                val ocrResults = ocrManager.recognizeText(bitmap) { partial ->
                    _uiState.update {
                        if (it.currentBitmap !== bitmap) it else it.copy(
                            ocrItems = partial,
                            allTokens = partial.flatMap { item -> item.tokens }
                        )
                    }
                }
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
                // Superseded by a newer load, which owns the state now. (A
                // catch-all here used to revert currentBitmap to this stale bitmap.)
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
        fullResolutionSource = null
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
            val decoded = withContext(Dispatchers.IO) {
                try {
                    // Only very large images are subsampled (see decodeSampledImage).
                    ScreenshotHelper.decodeSampledImage(getApplication(), uri)
                } catch (e: Throwable) {
                    Log.e("CircleLensVM", "Failed to load image from URI", e)
                    null
                }
            }
            val bitmap = decoded?.bitmap

            if (bitmap != null) {
                ocrBitmap = bitmap
                if (decoded.isSubsampled) {
                    fullResolutionSource = FullResolutionSource(bitmap, uri, decoded.sourceWidth, decoded.sourceHeight)
                }
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

    // Stroke trajectory is held in a plain local buffer, not rebuilt into
    // uiState on every pointer event (the old `activeStrokePoints + point`
    // copied the whole list per event = O(n^2) over a long stroke). The
    // Canvas also keeps its own local copy for drawing; this buffer is the
    // authoritative list consumed at stroke end.
    private val strokeBuffer = mutableListOf<Offset>()

    fun onStrokeStart(point: Offset) {
        strokeBuffer.clear()
        strokeBuffer.add(point)
        _uiState.update { it.copy(
            activeStrokePoints = strokeBuffer.toList(),
            trailPoints = listOf(TrailPoint(position = point))
        ) }
    }

    fun onStrokeMove(
        point: Offset,
        imageRectOnDisplay: RectF,
        bitmapWidth: Int,
        bitmapHeight: Int
    ) {
        strokeBuffer.add(point)
        // One snapshot copy per event (for Compose state), appended to the
        // local buffer above; the trail keeps only its last 40 points.
        _uiState.update { it.copy(
            activeStrokePoints = strokeBuffer.toList(),
            trailPoints = (it.trailPoints + TrailPoint(position = point)).takeLast(40)
        ) }
    }

    fun onStrokeEnd(
        imageRectOnDisplay: RectF,
        bitmapWidth: Int,
        bitmapHeight: Int
    ) {
        val points = strokeBuffer.toList()
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

                _uiState.update { it.copy(
                    // Pixels are cut out lazily when the crop is shared/copied.
                    activeCropSelection = CropSelection(rect = cropFrame, imageRect = RectF(imageRectOnDisplay)),
                    textSelection = null,
                    activeStrokePoints = emptyList(),
                    trailPoints = emptyList(),
                    feedbackMessage = "画像を切り抜きました"
                ) }
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

        _uiState.update { it.copy(
            activeStrokePoints = emptyList(),
            trailPoints = emptyList(),
            textSelection = finalSelection,
            activeCropSelection = newCropSelection
        ) }
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

        // globalIndex is assigned sequentially (allTokens[i].globalIndex == i),
        // so the selection is a contiguous slice: no full scan per drag event.
        val selected = if (all[minIdx].globalIndex == minIdx && all[maxIdx].globalIndex == maxIdx) {
            all.subList(minIdx, maxIdx + 1)
        } else {
            all.filter { it.globalIndex in minIdx..maxIdx }
        }
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
    private fun joinSelectedText(selected: List<OcrToken>): String = TextJoiner.join(selected)

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
        fullResolutionCrop(crop.rect, imageRect, source)?.let { return it }
        return withContext(Dispatchers.Default) { extractCropBitmap(crop.rect, imageRect, source) }
    }

    /**
     * The crop re-read from the original file at its full resolution, for an
     * image that is displayed reduced (see [fullResolutionSource]). Null when
     * the current image is not reduced or the region cannot be decoded; the
     * caller then cuts the crop from the displayed bitmap.
     */
    private suspend fun fullResolutionCrop(cropRectDisplay: RectF, imageRectDisplay: RectF, source: Bitmap): Bitmap? {
        val full = fullResolutionSource?.takeIf { it.bitmap === source } ?: return null
        if (imageRectDisplay.width() <= 0f || imageRectDisplay.height() <= 0f) return null
        val scaleX = full.width / imageRectDisplay.width()
        val scaleY = full.height / imageRectDisplay.height()
        val region = Rect(
            ((cropRectDisplay.left - imageRectDisplay.left) * scaleX).toInt().coerceIn(0, full.width),
            ((cropRectDisplay.top - imageRectDisplay.top) * scaleY).toInt().coerceIn(0, full.height),
            ((cropRectDisplay.right - imageRectDisplay.left) * scaleX).toInt().coerceIn(0, full.width),
            ((cropRectDisplay.bottom - imageRectDisplay.top) * scaleY).toInt().coerceIn(0, full.height)
        )
        if (region.width() <= 10 || region.height() <= 10) return null
        return withContext(Dispatchers.IO) {
            ScreenshotHelper.decodeFullResolutionRegion(getApplication(), full.uri, region)
        }
    }

    fun updateCropRect(newRect: RectF, imageRectDisplay: RectF) {
        _uiState.update {
            it.copy(activeCropSelection = CropSelection(rect = newRect, imageRect = imageRectDisplay))
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(
            textSelection = null,
            activeCropSelection = null,
            activeStrokePoints = emptyList(),
            trailPoints = emptyList(),
            feedbackMessage = null
        ) }
    }

    fun selectAllText(imageRectDisplay: RectF, bitmapWidth: Int, bitmapHeight: Int) {
        val all = _uiState.value.allTokens
        if (all.isNotEmpty()) {
            val sel = buildTextSelection(0, all.size - 1, imageRectDisplay, bitmapWidth, bitmapHeight)
            _uiState.update { it.copy(
                textSelection = sel,
                activeCropSelection = null,
                feedbackMessage = "全テキストを選択しました (${sel?.selectedTokens?.size}要素)"
            ) }
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

    /**
     * Saves the crop straight to the Download folder (no app switch, unlike
     * sharing to Files by Google) and then closes the overlay like a share does.
     */
    fun saveCroppedImage() {
        val crop = _uiState.value.activeCropSelection ?: return
        viewModelScope.launch {
            val cropBmp = cropBitmapFor(crop) ?: return@launch
            if (shareManager.saveImageToDownloads(cropBmp)) {
                closeAppEvent.emit(Unit)
            }
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

        _uiState.update { it.copy(
            isOcrRunning = true,
            feedbackMessage = "枠内を再OCR中..."
        ) }

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
                    // Index-basis fix: start/end indices now use the SAME
                    // basis as selectedTokens (the crop-local globalIndex
                    // assigned above, 0..size-1). The old code mixed in
                    // full-screen fallback indices, so a later pin drag
                    // interpreted the range against unrelated tokens.
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

                    _uiState.update { it.copy(
                        textSelection = TextSelectionState(
                            startTokenIndex = mappedTokens.first().globalIndex,
                            endTokenIndex = mappedTokens.last().globalIndex,
                            selectedTokens = mappedTokens,
                            fullText = joinSelectedText(mappedTokens),
                            startPinPoint = Offset(startBox.left, startBox.top),
                            endPinPoint = Offset(endBox.right, endBox.bottom)
                        ),
                        activeCropSelection = null,
                        isOcrRunning = false,
                        feedbackMessage = "枠内テキストを再OCRで抽出しました (${mappedTokens.size}要素)"
                    ) }
                } else if (insideTokens.isNotEmpty()) {
                    // Re-OCR found nothing: fall back to the full-screen tokens in the frame
                    val sel = buildTextSelection(fallbackStartIdx, fallbackEndIdx, imageRectDisplay, bitmapWidth, bitmapHeight)
                    _uiState.update { it.copy(
                        textSelection = sel,
                        activeCropSelection = null,
                        isOcrRunning = false,
                        feedbackMessage = "枠内のテキストを抽出しました (${insideTokens.size}要素)"
                    ) }
                } else {
                    _uiState.update { it.copy(
                        isOcrRunning = false,
                        feedbackMessage = "枠内にテキストが見つかりませんでした"
                    ) }
                }
            } catch (e: Throwable) {
                Log.e("CircleLensVM", "Crop re-OCR failed", e)
                _uiState.update { it.copy(
                    isOcrRunning = false,
                    feedbackMessage = "枠内OCRに失敗しました"
                ) }
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
            _uiState.update { it.copy(
                feedbackMessage = "画面キャプチャを準備中です。少々お待ちください..."
            ) }
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
        // Loaded on demand: only the picker lists every share target.
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

    // ---- Translation session (state in ViewModel; see CircleLensUiState) ----

    private val translateController by lazy { TranslateController(getApplication()) }

    fun openTranslate(text: String) {
        if (text.isBlank()) return
        val src = TranslateController.detectLang(text)
        _uiState.update { it.copy(
            translateSourceText = text,
            translateResult = null,
            translateSrcLang = src,
            translateTgtLang = if (src == "ja") "en" else "ja"
        ) }
    }

    fun updateTranslateResult(text: String?) {
        _uiState.update { it.copy(translateResult = text) }
    }

    fun swapTranslateLangs() {
        _uiState.update { st ->
            st.copy(
                translateSrcLang = st.translateTgtLang,
                translateTgtLang = st.translateSrcLang,
                translateResult = null
            )
        }
    }

    fun closeTranslate() {
        _uiState.update { it.copy(
            translateSourceText = null,
            translateResult = null
        ) }
    }

    /** Low-memory eviction: free the ~550MB native model; it reloads on demand. */
    fun onLowMemory() {
        viewModelScope.launch {
            try { translateController.releaseModel() } catch (_: Throwable) {}
        }
    }

    fun dismissFeedback() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }
}
