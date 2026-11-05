package com.example.ui

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.translate.TranslateController
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun CircleLensScreen(
    viewModel: CircleLensViewModel,
    onCloseApp: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var displayImageRect by remember { mutableStateOf(RectF()) }

    // On-device translation (PLaMo) for the selected text
    val appContext = LocalContext.current.applicationContext
    val translateController = remember { TranslateController(appContext) }
    val screenScope = rememberCoroutineScope()
    var translateText by remember { mutableStateOf<String?>(null) }
    // A new capture starts a fresh session: never reopen with the translate bar up.
    androidx.compose.runtime.LaunchedEffect(uiState.currentBitmap) {
        translateText = null
    }
    // Warm up the translation model as soon as a capture arrives, so the load
    // time hides behind the user's selection gestures instead of the translate tap.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (translateController.modelExists() && translateController.handle == 0L) {
            screenScope.launch { translateController.loadModel() }
        }
    }
    var modelPickTick by remember { mutableStateOf(0) }
    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            screenScope.launch {
                translateController.importModel(uri)
                modelPickTick++
            }
        }
    }

    BackHandler(enabled = true) {
        onCloseApp()
    }

    // Fullscreen edge-to-edge container without Scaffold insets shrinkage
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val density = LocalDensity.current
        val screenWidthPx = constraints.maxWidth.toFloat()
        val screenHeightPx = constraints.maxHeight.toFloat()
        val popupHeightPx = with(density) { 52.dp.toPx() }
        val topMarginPx = with(density) { 14.dp.toPx() }
        val safeTopPx = with(density) { 56.dp.toPx() }
        val safeBottomPx = with(density) { 96.dp.toPx() }
        // Measured sizes of the selection popups: their content grows upward when
        // the share-history slider opens, so placement must use the real size.
        var textPopupSize by remember { mutableStateOf(IntSize.Zero) }
        var cropPopupSize by remember { mutableStateOf(IntSize.Zero) }

        // Main Interactive Canvas (Full bleed 100% display)
        CircleLensOverlayCanvas(
            modifier = Modifier.fillMaxSize(),
            bitmap = uiState.currentBitmap,
            ocrItems = uiState.ocrItems,
            textSelection = uiState.textSelection,
            cropSelection = uiState.activeCropSelection,
            activeStrokePoints = uiState.activeStrokePoints,
            trailPoints = uiState.trailPoints,
            onStrokeStart = { startOffset ->
                viewModel.onStrokeStart(startOffset)
            },
            onStrokeMove = { point, imgRect, w, h ->
                viewModel.onStrokeMove(point, imgRect, w, h)
            },
            onStrokeEnd = { imgRect, w, h ->
                viewModel.onStrokeEnd(imgRect, w, h)
            },
            onDragStartPin = { pt, imgRect, w, h ->
                viewModel.onDragStartPin(pt, imgRect, w, h)
            },
            onDragEndPin = { pt, imgRect, w, h ->
                viewModel.onDragEndPin(pt, imgRect, w, h)
            },
            onCropFrameUpdated = { newRect, imgRect ->
                viewModel.updateCropRect(newRect, imgRect)
            },
            onImageRectCalculated = { rect ->
                displayImageRect = rect
            }
        )

        // Calculate top anchor and horizontal center for text selection popup
        val (textAnchorX, textAnchorY, textBottomY) = remember(uiState.textSelection, displayImageRect) {
            val sel = uiState.textSelection
            if (sel != null && sel.selectedTokens.isNotEmpty()) {
                val bmpW = uiState.currentBitmap?.width ?: 1080
                val bmpH = uiState.currentBitmap?.height ?: 2200
                val scaleX = if (bmpW > 0) displayImageRect.width() / bmpW else 1f
                val scaleY = if (bmpH > 0) displayImageRect.height() / bmpH else 1f

                var minX = Float.MAX_VALUE
                var maxX = Float.MIN_VALUE
                var minY = Float.MAX_VALUE
                var maxY = Float.MIN_VALUE

                for (token in sel.selectedTokens) {
                    val box = token.boundingBox
                    val left = displayImageRect.left + box.left * scaleX
                    val top = displayImageRect.top + box.top * scaleY
                    val right = displayImageRect.left + box.right * scaleX
                    val bottom = displayImageRect.top + box.bottom * scaleY

                    minX = min(minX, left)
                    maxX = max(maxX, right)
                    minY = min(minY, top)
                    maxY = max(maxY, bottom)
                }
                minY = min(minY, min(sel.startPinPoint.y, sel.endPinPoint.y))
                Triple((minX + maxX) / 2f, minY, maxY)
            } else {
                Triple(0f, 0f, 0f)
            }
        }

        // Calculate top anchor and horizontal center for crop selection popup
        val (cropAnchorX, cropAnchorY, cropBottomY) = remember(uiState.activeCropSelection) {
            val r = uiState.activeCropSelection?.rect
            if (r != null) {
                Triple((r.left + r.right) / 2f, r.top, r.bottom)
            } else {
                Triple(0f, 0f, 0f)
            }
        }

        // Text Selection Popup (Appears directly above selected text)
        if (uiState.textSelection != null && uiState.textSelection!!.selectedTokens.isNotEmpty()) {
            val preferredY = textAnchorY - popupHeightPx - topMarginPx
            val targetY = if (preferredY >= safeTopPx) {
                preferredY
            } else {
                (textBottomY + topMarginPx).coerceAtMost(screenHeightPx - popupHeightPx - safeBottomPx)
            }
            // Anchor by bottom-center: targetY + popupHeightPx is the bottom edge
            // intended for the toolbar alone. When the share-history slider opens,
            // the popup grows taller/wider than the toolbar; using the measured
            // size keeps the whole popup on screen instead of overflowing the
            // bottom edge (the slider used to be cut off).
            val measuredTextW = if (textPopupSize.width > 0) textPopupSize.width.toFloat()
                else with(density) { 170.dp.toPx() }
            val measuredTextH = if (textPopupSize.height > 0) textPopupSize.height.toFloat()
                else popupHeightPx
            val textActualY = (targetY + popupHeightPx - measuredTextH).coerceAtLeast(safeTopPx)
            val textActualX = (textAnchorX - measuredTextW / 2f)
                .coerceIn(16f, (screenWidthPx - measuredTextW - 16f).coerceAtLeast(16f))

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            textActualX.roundToInt(),
                            textActualY.roundToInt()
                        )
                    }
                    .onSizeChanged { textPopupSize = it }
            ) {
                TextSelectionPopup(
                    textSelection = uiState.textSelection,
                    lastShareTarget = uiState.lastTextShareTarget,
                    recentTargets = uiState.recentTextTargets,
                    onCopy = { viewModel.copySelectedText() },
                    onShareGeneral = {
                        viewModel.shareSelectedText(null)
                    },
                    onShareDirect = { target ->
                        viewModel.shareSelectedText(target)
                    },
                    onOpenShareSheet = {
                        viewModel.shareSelectedText(null)
                    },
                    onTranslate = {
                        val text = viewModel.getSelectedTextString()
                        if (text.isNotBlank()) {
                            translateText = text
                        }
                    }
                )
            }
        }

        // Crop Selection Popup (Appears directly above crop frame)
        if (uiState.activeCropSelection != null) {
            val preferredY = cropAnchorY - popupHeightPx - topMarginPx
            val targetY = if (preferredY >= safeTopPx) {
                preferredY
            } else {
                (cropBottomY + topMarginPx).coerceAtMost(screenHeightPx - popupHeightPx - safeBottomPx)
            }
            // Same bottom-center anchoring as the text popup (see above).
            val measuredCropW = if (cropPopupSize.width > 0) cropPopupSize.width.toFloat()
                else with(density) { 156.dp.toPx() }
            val measuredCropH = if (cropPopupSize.height > 0) cropPopupSize.height.toFloat()
                else popupHeightPx
            val cropActualY = (targetY + popupHeightPx - measuredCropH).coerceAtLeast(safeTopPx)
            val cropActualX = (cropAnchorX - measuredCropW / 2f)
                .coerceIn(16f, (screenWidthPx - measuredCropW - 16f).coerceAtLeast(16f))

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            cropActualX.roundToInt(),
                            cropActualY.roundToInt()
                        )
                    }
                    .onSizeChanged { cropPopupSize = it }
            ) {
                CropSelectionPopup(
                    cropSelection = uiState.activeCropSelection,
                    lastShareTarget = uiState.lastImageShareTarget,
                    recentTargets = uiState.recentImageTargets,
                    onShareImageGeneral = {
                        viewModel.shareCroppedImage(null)
                    },
                    onShareImageDirect = { target ->
                        viewModel.shareCroppedImage(target)
                    },
                    onOpenShareSheet = {
                        viewModel.shareCroppedImage(null)
                    },
                    onOcrCrop = {
                        val bmp = uiState.currentBitmap
                        if (bmp != null) {
                            viewModel.ocrInsideCrop(displayImageRect, bmp.width, bmp.height)
                        }
                    }
                )
            }
        }

        // Bottom area: translate bar (when translating) or the main toolbar
        val currentTranslateText = translateText
        if (currentTranslateText != null) {
            TranslateBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                controller = translateController,
                sourceText = currentTranslateText,
                refreshKey = modelPickTick,
                onPickModelFile = { modelPicker.launch(arrayOf("*/*")) },
                onClose = { translateText = null }
            )
        } else {
            // Minimal Icon-Only Bottom Toolbar (White frosted glass + Black only, no drag handle)
            CircleLensBottomBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                isReady = uiState.currentBitmap != null,
                isOcrRunning = uiState.isOcrRunning,
                lastShareTarget = uiState.lastImageShareTarget ?: uiState.lastTextShareTarget,
                recentTargets = uiState.recentImageTargets,
                onSelectAllText = {
                    val bmpW = uiState.currentBitmap?.width ?: 1080
                    val bmpH = uiState.currentBitmap?.height ?: 2200
                    viewModel.selectAllText(displayImageRect, bmpW, bmpH)
                },
                onShareEntireScreenGeneral = {
                    viewModel.shareEntireScreen(null)
                },
                onShareEntireScreenDirect = { target ->
                    viewModel.shareEntireScreen(target)
                },
                onOpenShareSheet = {
                    viewModel.shareEntireScreen(null)
                }
            )
        }

    }

    // Setup Wizard Help Dialog (Shown only when opened from launcher icon)
    if (uiState.showAssistHelpSheet) {
        AssistHelpDialog(
            onDismiss = {
                viewModel.showAssistHelp(false)
            }
        )
    }
}
