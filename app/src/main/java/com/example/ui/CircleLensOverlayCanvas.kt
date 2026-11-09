package com.example.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.model.CropSelection
import com.example.model.OcrTextItem
import com.example.model.TextSelectionState
import com.example.model.TrailPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class CropHandleType {
    NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    TOP_EDGE, BOTTOM_EDGE, LEFT_EDGE, RIGHT_EDGE,
    MOVE
}

enum class DragTarget {
    NONE,
    START_PIN,
    END_PIN,
    CROP_HANDLE,
    DRAWING_STROKE
}

@Composable
fun CircleLensOverlayCanvas(
    modifier: Modifier = Modifier,
    bitmap: Bitmap?,
    ocrItems: List<OcrTextItem>,
    textSelection: TextSelectionState?,
    cropSelection: CropSelection?,
    activeStrokePoints: List<Offset>,
    trailPoints: List<TrailPoint>,
    onStrokeStart: (Offset) -> Unit,
    onStrokeMove: (Offset, RectF, Int, Int) -> Unit,
    onStrokeEnd: (RectF, Int, Int) -> Unit,
    onDragStartPin: (Offset, RectF, Int, Int) -> Unit,
    onDragEndPin: (Offset, RectF, Int, Int) -> Unit,
    onCropFrameUpdated: (RectF, RectF) -> Unit,
    onImageRectCalculated: (RectF) -> Unit
) {
    // Aurora animation runs ONLY while the user is interacting (an active
    // stroke, a text selection, or a crop frame); an idle screen does not
    // redraw. The values are passed down as State and only read inside the
    // layers' draw lambdas, so while animating nothing recomposes either.
    val auroraActive = activeStrokePoints.isNotEmpty() || textSelection != null || cropSelection != null
    val phaseAnim = remember { Animatable(0f) }
    val pulseAnim = remember { Animatable(0.88f) }
    LaunchedEffect(auroraActive) {
        if (auroraActive) {
            launchPhases(phaseAnim, pulseAnim)
        } else {
            phaseAnim.snapTo(0f)
            pulseAnim.snapTo(0.88f)
        }
    }
    val phase = phaseAnim.asState()
    val pulsePhase = pulseAnim.asState()

    val currentBitmap by rememberUpdatedState(bitmap)
    val currentTextSelection by rememberUpdatedState(textSelection)
    val currentCropSelection by rememberUpdatedState(cropSelection)
    val currentStrokeStart by rememberUpdatedState(onStrokeStart)
    val currentStrokeMove by rememberUpdatedState(onStrokeMove)
    val currentStrokeEnd by rememberUpdatedState(onStrokeEnd)
    val currentDragStartPin by rememberUpdatedState(onDragStartPin)
    val currentDragEndPin by rememberUpdatedState(onDragEndPin)
    val currentCropFrameUpdated by rememberUpdatedState(onCropFrameUpdated)
    val currentImageRectCalculated by rememberUpdatedState(onImageRectCalculated)

    // The fitted image rect is derived from the layout size instead of being
    // written to state from inside the draw pass on every frame.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val bitmapWidth = bitmap?.width ?: 0
    val bitmapHeight = bitmap?.height ?: 0
    val fittedImageRect: RectF? = remember(canvasSize, bitmapWidth, bitmapHeight) {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || canvasSize.width <= 0 || canvasSize.height <= 0) {
            null
        } else {
            fitImage(canvasSize.width.toFloat(), canvasSize.height.toFloat(), bitmapWidth, bitmapHeight).rect
        }
    }
    val currentDisplayImageRect by rememberUpdatedState(fittedImageRect ?: EMPTY_RECT)
    SideEffect {
        fittedImageRect?.let { currentImageRectCalculated(it) }
    }

    var currentDragTarget by remember { mutableStateOf(DragTarget.NONE) }
    var activeCropHandle by remember { mutableStateOf(CropHandleType.NONE) }
    var dragStartCropRect by remember { mutableStateOf(RectF()) }
    var dragStartTouch by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startOffset = down.position
                    val bmpW = currentBitmap?.width ?: 1080
                    val bmpH = currentBitmap?.height ?: 2200
                    val pinHitRadius = 90f // Generous touch target for pins (~36dp)
                    val handleHitRadius = 90f // Generous touch target for crop handles (~36dp)

                    var dragTarget = DragTarget.NONE
                    var localCropHandle = CropHandleType.NONE
                    var localDragStartCropRect = RectF()
                    var localDragStartTouch = Offset.Zero

                    val activeTextSel = currentTextSelection
                    // 1. Check Text Selection Pins first with generous touch targets and accurate pin centers
                    if (activeTextSel != null && activeTextSel.selectedTokens.isNotEmpty()) {
                        val startPin = activeTextSel.startPinPoint
                        val endPin = activeTextSel.endPinPoint

                        // Actual center coordinates of start and end pin heads
                        val startPinHeadCenter = Offset(startPin.x - 2f, startPin.y - 18f)
                        val endPinHeadCenter = Offset(endPin.x + 2f, endPin.y + 18f)

                        val distToStartPin = min(
                            hypot((startOffset.x - startPinHeadCenter.x).toDouble(), (startOffset.y - startPinHeadCenter.y).toDouble()).toFloat(),
                            hypot((startOffset.x - startPin.x).toDouble(), (startOffset.y - startPin.y).toDouble()).toFloat()
                        )
                        val distToEndPin = min(
                            hypot((startOffset.x - endPinHeadCenter.x).toDouble(), (startOffset.y - endPinHeadCenter.y).toDouble()).toFloat(),
                            hypot((startOffset.x - endPin.x).toDouble(), (startOffset.y - endPin.y).toDouble()).toFloat()
                        )

                        if (distToStartPin < pinHitRadius && distToStartPin <= distToEndPin) {
                            dragTarget = DragTarget.START_PIN
                            currentDragTarget = DragTarget.START_PIN
                            currentDragStartPin(startOffset, currentDisplayImageRect, bmpW, bmpH)
                        } else if (distToEndPin < pinHitRadius) {
                            dragTarget = DragTarget.END_PIN
                            currentDragTarget = DragTarget.END_PIN
                            currentDragEndPin(startOffset, currentDisplayImageRect, bmpW, bmpH)
                        }
                    }

                    // 2. Check Crop Selection Handles or Crop Area
                    if (dragTarget == DragTarget.NONE) {
                        val crop = currentCropSelection?.rect
                        if (crop != null && !crop.isEmpty) {
                            val tl = Offset(crop.left, crop.top)
                            val tr = Offset(crop.right, crop.top)
                            val bl = Offset(crop.left, crop.bottom)
                            val br = Offset(crop.right, crop.bottom)

                            val distTL = hypot((startOffset.x - tl.x).toDouble(), (startOffset.y - tl.y).toDouble()).toFloat()
                            val distTR = hypot((startOffset.x - tr.x).toDouble(), (startOffset.y - tr.y).toDouble()).toFloat()
                            val distBL = hypot((startOffset.x - bl.x).toDouble(), (startOffset.y - bl.y).toDouble()).toFloat()
                            val distBR = hypot((startOffset.x - br.x).toDouble(), (startOffset.y - br.y).toDouble()).toFloat()

                            localCropHandle = when {
                                distTL < handleHitRadius && distTL <= min(distTR, min(distBL, distBR)) -> CropHandleType.TOP_LEFT
                                distTR < handleHitRadius && distTR <= min(distTL, min(distBL, distBR)) -> CropHandleType.TOP_RIGHT
                                distBL < handleHitRadius && distBL <= min(distTL, min(distTR, distBR)) -> CropHandleType.BOTTOM_LEFT
                                distBR < handleHitRadius -> CropHandleType.BOTTOM_RIGHT
                                // Edges with forgiving proximity bands
                                startOffset.x in (crop.left - 24f)..(crop.right + 24f) && abs(startOffset.y - crop.top) < 48f -> CropHandleType.TOP_EDGE
                                startOffset.x in (crop.left - 24f)..(crop.right + 24f) && abs(startOffset.y - crop.bottom) < 48f -> CropHandleType.BOTTOM_EDGE
                                startOffset.y in (crop.top - 24f)..(crop.bottom + 24f) && abs(startOffset.x - crop.left) < 48f -> CropHandleType.LEFT_EDGE
                                startOffset.y in (crop.top - 24f)..(crop.bottom + 24f) && abs(startOffset.x - crop.right) < 48f -> CropHandleType.RIGHT_EDGE
                                crop.contains(startOffset.x, startOffset.y) -> CropHandleType.MOVE
                                else -> CropHandleType.NONE
                            }

                            if (localCropHandle != CropHandleType.NONE) {
                                dragTarget = DragTarget.CROP_HANDLE
                                currentDragTarget = DragTarget.CROP_HANDLE
                                activeCropHandle = localCropHandle
                                localDragStartCropRect = RectF(crop)
                                localDragStartTouch = startOffset
                                dragStartCropRect = RectF(crop)
                                dragStartTouch = startOffset
                            }
                        }
                    }

                    // 3. Otherwise, treat as drawing stroke or tap
                    if (dragTarget == DragTarget.NONE) {
                        dragTarget = DragTarget.DRAWING_STROKE
                        currentDragTarget = DragTarget.DRAWING_STROKE
                        activeCropHandle = CropHandleType.NONE
                        currentStrokeStart(startOffset)
                    }

                    var currentPos = startOffset
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (change.pressed) {
                            currentPos = change.position
                            change.consume()

                            when (dragTarget) {
                                DragTarget.START_PIN -> {
                                    currentDragStartPin(currentPos, currentDisplayImageRect, bmpW, bmpH)
                                }
                                DragTarget.END_PIN -> {
                                    currentDragEndPin(currentPos, currentDisplayImageRect, bmpW, bmpH)
                                }
                                DragTarget.CROP_HANDLE -> {
                                    val dx = currentPos.x - localDragStartTouch.x
                                    val dy = currentPos.y - localDragStartTouch.y
                                    val base = localDragStartCropRect
                                    val minSize = 60f

                                    val newRect = when (localCropHandle) {
                                        CropHandleType.TOP_LEFT -> RectF(
                                            min(base.right - minSize, base.left + dx),
                                            min(base.bottom - minSize, base.top + dy),
                                            base.right,
                                            base.bottom
                                        )
                                        CropHandleType.TOP_RIGHT -> RectF(
                                            base.left,
                                            min(base.bottom - minSize, base.top + dy),
                                            max(base.left + minSize, base.right + dx),
                                            base.bottom
                                        )
                                        CropHandleType.BOTTOM_LEFT -> RectF(
                                            min(base.right - minSize, base.left + dx),
                                            base.top,
                                            base.right,
                                            max(base.top + minSize, base.bottom + dy)
                                        )
                                        CropHandleType.BOTTOM_RIGHT -> RectF(
                                            base.left,
                                            base.top,
                                            max(base.left + minSize, base.right + dx),
                                            max(base.top + minSize, base.bottom + dy)
                                        )
                                        CropHandleType.TOP_EDGE -> RectF(
                                            base.left,
                                            min(base.bottom - minSize, base.top + dy),
                                            base.right,
                                            base.bottom
                                        )
                                        CropHandleType.BOTTOM_EDGE -> RectF(
                                            base.left,
                                            base.top,
                                            base.right,
                                            max(base.top + minSize, base.bottom + dy)
                                        )
                                        CropHandleType.LEFT_EDGE -> RectF(
                                            min(base.right - minSize, base.left + dx),
                                            base.top,
                                            base.right,
                                            base.bottom
                                        )
                                        CropHandleType.RIGHT_EDGE -> RectF(
                                            base.left,
                                            base.top,
                                            max(base.left + minSize, base.right + dx),
                                            base.bottom
                                        )
                                        CropHandleType.MOVE -> {
                                            val w = base.width()
                                            val h = base.height()
                                            val l = base.left + dx
                                            val t = base.top + dy
                                            RectF(l, t, l + w, t + h)
                                        }
                                        else -> base
                                    }

                                    val clamped = RectF(
                                        max(currentDisplayImageRect.left, newRect.left),
                                        max(currentDisplayImageRect.top, newRect.top),
                                        min(currentDisplayImageRect.right, newRect.right),
                                        min(currentDisplayImageRect.bottom, newRect.bottom)
                                    )
                                    currentCropFrameUpdated(clamped, currentDisplayImageRect)
                                }
                                DragTarget.DRAWING_STROKE -> {
                                    currentStrokeMove(currentPos, currentDisplayImageRect, bmpW, bmpH)
                                }
                                DragTarget.NONE -> {}
                            }
                        } else {
                            // Released
                            change.consume()
                            if (dragTarget == DragTarget.DRAWING_STROKE) {
                                currentStrokeEnd(currentDisplayImageRect, bmpW, bmpH)
                            }
                            currentDragTarget = DragTarget.NONE
                            activeCropHandle = CropHandleType.NONE
                            break
                        }
                    }
                }
            }
    ) {
        if (bitmap != null) {
            // Each layer is its own RenderNode (graphicsLayer), so it is only
            // re-recorded when its own inputs change. Previously everything —
            // screenshot, OCR boxes, selection, gradients — was re-recorded on
            // every animation frame (60fps) and on every stroke point.
            ScreenshotLayer(bitmap)
            AuroraLayer(bitmapWidth, bitmapHeight, pulsePhase)
            OcrSelectionLayer(bitmapWidth, bitmapHeight, ocrItems, textSelection)
            if (cropSelection != null) {
                CropLayer(bitmapWidth, bitmapHeight, cropSelection, phase)
            }
            if (activeStrokePoints.size > 1 || trailPoints.isNotEmpty()) {
                StrokeLayer(activeStrokePoints, trailPoints)
            }
        }
    }
}

/** Screenshot fitted (centered, aspect preserved) into the canvas. */
private class FittedImage(val left: Float, val top: Float, val width: Float, val height: Float) {
    val rect: RectF get() = RectF(left, top, left + width, top + height)
}

private fun fitImage(canvasWidth: Float, canvasHeight: Float, bitmapWidth: Int, bitmapHeight: Int): FittedImage {
    val bmpWidth = bitmapWidth.toFloat()
    val bmpHeight = bitmapHeight.toFloat()
    val scale = min(canvasWidth / bmpWidth, canvasHeight / bmpHeight)
    val destWidth = bmpWidth * scale
    val destHeight = bmpHeight * scale
    val destLeft = (canvasWidth - destWidth) / 2f
    val destTop = (canvasHeight - destHeight) / 2f
    return FittedImage(destLeft, destTop, destWidth, destHeight)
}

private val EMPTY_RECT = RectF()

private val ScrimColor = Color(0x38000000)
private val OcrHintColor = Color(0x1560A5FA)
private val CropVignetteColor = Color(0x40000000)
private val TrailColor = Color(0xFF67E8F9)

// Brushes are hoisted: the old code allocated a new gradient brush (and
// shader) per selected token per frame. A horizontalGradient without explicit
// bounds spans the DrawScope width, so a single shared instance is identical.
private val SelectionHighlightBrush = Brush.horizontalGradient(
    listOf(Color(0xB81D4ED8), Color(0xCC2563EB))
)
private val StrokeAuraBrush = Brush.horizontalGradient(
    listOf(Color(0x9938BDF8), Color(0xCC818CF8), Color(0x99F472B6))
)
private val AuroraTopLeftColors = listOf(
    Color(0x8C2563EB), Color(0x6638BDF8), Color(0x3360A5FA), Color(0x1038BDF8), Color(0x002563EB)
)
private val AuroraTopRightColors = listOf(
    Color(0x90F59E0B), Color(0x66EAB308), Color(0x33F97316), Color(0x10F59E0B), Color(0x00F59E0B)
)
private val AuroraBottomRightColors = listOf(
    Color(0x8C10B981), Color(0x6014B8A6), Color(0x30059669), Color(0x1010B981), Color(0x0010B981)
)
private val AuroraBottomLeftColors = listOf(
    Color(0x7506B6D4), Color(0x4D10B981), Color(0x2038BDF8), Color(0x0006B6D4)
)
private val AuroraBorderColors = listOf(
    Color(0xFF38BDF8), Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFF06B6D4), Color(0xFF38BDF8)
)
private val CropBorderColors = listOf(
    Color(0xFF38BDF8), Color(0xFF818CF8), Color(0xFFF472B6), Color(0xFFFBBF24), Color(0xFF38BDF8)
)
private val CropDashIntervals = floatArrayOf(30f, 15f)

/** Static layer: the screenshot plus the dark scrim. Re-recorded only when the bitmap or size changes. */
@Composable
private fun ScreenshotLayer(bitmap: Bitmap) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(modifier = Modifier.fillMaxSize().graphicsLayer {}) {
        val fit = fitImage(size.width, size.height, bitmap.width, bitmap.height)

        // Draw underlying screen bitmap
        drawImage(
            image = imageBitmap,
            dstOffset = IntOffset(fit.left.toInt(), fit.top.toInt()),
            dstSize = IntSize(fit.width.toInt(), fit.height.toInt())
        )

        // Dark translucent scrim for authentic Circle to Search aesthetic
        drawRect(
            color = ScrimColor,
            topLeft = Offset(fit.left, fit.top),
            size = Size(fit.width, fit.height)
        )
    }
}

/**
 * Animated layer: organic misty aurora glow ("もやもや" effect) and border.
 * The gradient brushes are built once per size; the pulsing radius is applied
 * as a scale around each blob's centre (a radial gradient scaled about its
 * centre is the same gradient with a scaled radius), so no brush or shader is
 * allocated per animation frame.
 */
@Composable
private fun AuroraLayer(bitmapWidth: Int, bitmapHeight: Int, pulsePhase: State<Float>) {
    Spacer(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {}
            .drawWithCache {
                val fit = fitImage(size.width, size.height, bitmapWidth, bitmapHeight)
                val baseRadius = max(fit.width, fit.height) * 0.55f

                val tlCenter = Offset(fit.left + fit.width * 0.05f, fit.top + fit.height * 0.08f)
                val trCenter = Offset(fit.left + fit.width * 0.96f, fit.top + fit.height * 0.32f)
                val brCenter = Offset(fit.left + fit.width * 0.88f, fit.top + fit.height * 0.90f)
                val blCenter = Offset(fit.left + fit.width * 0.12f, fit.top + fit.height * 0.88f)

                val tlBrush = Brush.radialGradient(AuroraTopLeftColors, center = tlCenter, radius = baseRadius)
                val trBrush = Brush.radialGradient(AuroraTopRightColors, center = trCenter, radius = baseRadius)
                val brBrush = Brush.radialGradient(AuroraBottomRightColors, center = brCenter, radius = baseRadius)
                val blBrush = Brush.radialGradient(AuroraBottomLeftColors, center = blCenter, radius = baseRadius)

                // Outer border line connecting the aurora colors
                val borderBrush = Brush.sweepGradient(
                    colors = AuroraBorderColors,
                    center = Offset(size.width / 2f, size.height / 2f)
                )
                val borderStroke = Stroke(width = 3.5f)

                onDrawBehind {
                    val pulse = pulsePhase.value
                    // Top-Left Cyan / Sky Blue Misty Blob
                    drawAuroraBlob(tlBrush, tlCenter, pulse, fit)
                    // Right-side & Top-Right Warm Amber / Gold Misty Blob
                    drawAuroraBlob(trBrush, trCenter, 2.1f - pulse, fit)
                    // Bottom-Right / Bottom Emerald Green & Teal Misty Blob
                    drawAuroraBlob(brBrush, brCenter, pulse, fit)
                    // Bottom-Left Cyan / Teal Misty Blob
                    drawAuroraBlob(blBrush, blCenter, 2.05f - pulse, fit)

                    drawRect(
                        brush = borderBrush,
                        topLeft = Offset(fit.left + 1f, fit.top + 1f),
                        size = Size(fit.width - 2f, fit.height - 2f),
                        style = borderStroke
                    )
                }
            }
    )
}

/**
 * Fills the image rect with a radial gradient whose radius is the brush's
 * radius times [radiusScale]: the canvas is scaled about the gradient centre
 * and the rect is drawn in the matching pre-scaled coordinates.
 */
private fun DrawScope.drawAuroraBlob(brush: Brush, center: Offset, radiusScale: Float, fit: FittedImage) {
    if (radiusScale <= 0f) return
    scale(radiusScale, pivot = center) {
        drawRect(
            brush = brush,
            topLeft = Offset(
                center.x + (fit.left - center.x) / radiusScale,
                center.y + (fit.top - center.y) / radiusScale
            ),
            size = Size(fit.width / radiusScale, fit.height / radiusScale)
        )
    }
}

/** OCR hint boxes, selected-token highlights and pins. Re-recorded only when OCR results or the selection change. */
@Composable
private fun OcrSelectionLayer(
    bitmapWidth: Int,
    bitmapHeight: Int,
    ocrItems: List<OcrTextItem>,
    textSelection: TextSelectionState?
) {
    if (ocrItems.isEmpty() && (textSelection == null || textSelection.selectedTokens.isEmpty())) return
    Canvas(modifier = Modifier.fillMaxSize().graphicsLayer {}) {
        val fit = fitImage(size.width, size.height, bitmapWidth, bitmapHeight)
        val destLeft = fit.left
        val destTop = fit.top
        val scaleX = fit.width / bitmapWidth.toFloat()
        val scaleY = fit.height / bitmapHeight.toFloat()

        // 1. Draw subtle hint boxes for all recognized OCR lines
        val hintStroke = Stroke(width = 1f)
        val hintCorner = CornerRadius(4f, 4f)
        for (item in ocrItems) {
            val box = item.boundingBox
            val left = destLeft + box.left * scaleX
            val top = destTop + box.top * scaleY
            val right = destLeft + box.right * scaleX
            val bottom = destTop + box.bottom * scaleY

            drawRoundRect(
                color = OcrHintColor,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = hintCorner,
                style = hintStroke
            )
        }

        // 2. Draw fine-grained selected tokens & Start/End Pins
        if (textSelection != null && textSelection.selectedTokens.isNotEmpty()) {
            val highlightCorner = CornerRadius(6f, 6f)
            for (token in textSelection.selectedTokens) {
                val box = token.boundingBox
                val left = destLeft + box.left * scaleX
                val top = destTop + box.top * scaleY
                val right = destLeft + box.right * scaleX
                val bottom = destTop + box.bottom * scaleY

                // Blue highlight pill
                drawRoundRect(
                    brush = SelectionHighlightBrush,
                    topLeft = Offset(left - 2f, top - 2f),
                    size = Size(right - left + 4f, bottom - top + 4f),
                    cornerRadius = highlightCorner
                )
            }

            // Draw Start Pin (Droplet handle at top-left of first token)
            val firstBox = textSelection.selectedTokens.first().boundingBox
            val startX = destLeft + firstBox.left * scaleX
            val startY = destTop + firstBox.top * scaleY
            drawSelectionPin(
                pinPoint = Offset(startX, startY),
                isStart = true
            )

            // Draw End Pin (Droplet handle at bottom-right of last token)
            val lastBox = textSelection.selectedTokens.last().boundingBox
            val endX = destLeft + lastBox.right * scaleX
            val endY = destTop + lastBox.bottom * scaleY
            drawSelectionPin(
                pinPoint = Offset(endX, endY),
                isStart = false
            )
        }
    }
}

/** Crop frame. Its dashed border is animated, so only this layer redraws per frame while a crop is active. */
@Composable
private fun CropLayer(
    bitmapWidth: Int,
    bitmapHeight: Int,
    cropSelection: CropSelection,
    phase: State<Float>
) {
    Spacer(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {}
            .drawWithCache {
                val fit = fitImage(size.width, size.height, bitmapWidth, bitmapHeight)
                val crop = cropSelection.rect
                val borderBrush = Brush.sweepGradient(
                    colors = CropBorderColors,
                    center = Offset(crop.centerX(), crop.centerY())
                )

                onDrawBehind {
                    // Ambient vignette outside crop
                    drawRect(
                        color = CropVignetteColor,
                        topLeft = Offset(fit.left, fit.top),
                        size = Size(fit.width, fit.height)
                    )

                    // Glowing animated dashed border
                    drawRoundRect(
                        brush = borderBrush,
                        topLeft = Offset(crop.left, crop.top),
                        size = Size(crop.width(), crop.height()),
                        cornerRadius = CornerRadius(16f, 16f),
                        style = Stroke(
                            width = 3.5f,
                            pathEffect = PathEffect.dashPathEffect(CropDashIntervals, phase.value * 45f)
                        )
                    )

                    // Draw Authentic Circle-to-Search Corner L-Brackets ⌜ ⌝ ⌞ ⌟
                    val bracketLen = min(crop.width() * 0.25f, 32f).coerceAtLeast(16f)
                    val bracketStroke = 4.5f
                    val bracketColor = Color.White

                    // Top-Left ⌜
                    drawLine(bracketColor, Offset(crop.left - 2f, crop.top + bracketLen), Offset(crop.left - 2f, crop.top - 2f), bracketStroke, StrokeCap.Round)
                    drawLine(bracketColor, Offset(crop.left - 2f, crop.top - 2f), Offset(crop.left + bracketLen, crop.top - 2f), bracketStroke, StrokeCap.Round)

                    // Top-Right ⌝
                    drawLine(bracketColor, Offset(crop.right + 2f, crop.top + bracketLen), Offset(crop.right + 2f, crop.top - 2f), bracketStroke, StrokeCap.Round)
                    drawLine(bracketColor, Offset(crop.right + 2f, crop.top - 2f), Offset(crop.right - bracketLen, crop.top - 2f), bracketStroke, StrokeCap.Round)

                    // Bottom-Left ⌞
                    drawLine(bracketColor, Offset(crop.left - 2f, crop.bottom - bracketLen), Offset(crop.left - 2f, crop.bottom + 2f), bracketStroke, StrokeCap.Round)
                    drawLine(bracketColor, Offset(crop.left - 2f, crop.bottom + 2f), Offset(crop.left + bracketLen, crop.bottom + 2f), bracketStroke, StrokeCap.Round)

                    // Bottom-Right ⌟
                    drawLine(bracketColor, Offset(crop.right + 2f, crop.bottom - bracketLen), Offset(crop.right + 2f, crop.bottom + 2f), bracketStroke, StrokeCap.Round)
                    drawLine(bracketColor, Offset(crop.right + 2f, crop.bottom + 2f), Offset(crop.right - bracketLen, crop.bottom + 2f), bracketStroke, StrokeCap.Round)

                    // Corner pill handles
                    drawCropCornerHandle(Offset(crop.left, crop.top))
                    drawCropCornerHandle(Offset(crop.right, crop.top))
                    drawCropCornerHandle(Offset(crop.left, crop.bottom))
                    drawCropCornerHandle(Offset(crop.right, crop.bottom))

                    // Midpoint edge drag pills
                    val midTop = Offset(crop.centerX(), crop.top)
                    val midBottom = Offset(crop.centerX(), crop.bottom)
                    val midLeft = Offset(crop.left, crop.centerY())
                    val midRight = Offset(crop.right, crop.centerY())

                    drawRoundRect(Color.White, Offset(midTop.x - 14f, midTop.y - 3.5f), Size(28f, 7f), CornerRadius(4f, 4f))
                    drawRoundRect(Color.White, Offset(midBottom.x - 14f, midBottom.y - 3.5f), Size(28f, 7f), CornerRadius(4f, 4f))
                    drawRoundRect(Color.White, Offset(midLeft.x - 3.5f, midLeft.y - 14f), Size(7f, 28f), CornerRadius(4f, 4f))
                    drawRoundRect(Color.White, Offset(midRight.x - 3.5f, midRight.y - 14f), Size(7f, 28f), CornerRadius(4f, 4f))
                }
            }
    )
}

private fun DrawScope.drawCropCornerHandle(corner: Offset) {
    drawCircle(color = Color(0xFF1E293B), radius = 10f, center = corner)
    drawCircle(color = Color(0xFF38BDF8), radius = 7f, center = corner)
}

/** User gesture stroke (neon laser ribbon) and particle trail. The only layer re-recorded per stroke point. */
@Composable
private fun StrokeLayer(activeStrokePoints: List<Offset>, trailPoints: List<TrailPoint>) {
    Canvas(modifier = Modifier.fillMaxSize().graphicsLayer {}) {
        // 4. Draw User Gesture Stroke (Neon Laser Ribbon)
        if (activeStrokePoints.size > 1) {
            val strokePath = Path()
            strokePath.moveTo(activeStrokePoints.first().x, activeStrokePoints.first().y)
            for (i in 1 until activeStrokePoints.size) {
                val p = activeStrokePoints[i]
                strokePath.lineTo(p.x, p.y)
            }

            // Neon aura
            drawPath(
                path = strokePath,
                brush = StrokeAuraBrush,
                style = Stroke(width = 16f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            // White core
            drawPath(
                path = strokePath,
                color = Color.White,
                style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // 5. Draw Particle Trails
        for ((index, trail) in trailPoints.withIndex()) {
            val age = (trailPoints.size - index).toFloat() / trailPoints.size
            val radius = (1f - age) * 10f + 2f
            val alpha = (1f - age) * 0.9f
            drawCircle(
                color = TrailColor.copy(alpha = alpha),
                radius = radius,
                center = trail.position
            )
        }
    }
}

/**
 * Draws a teardrop selection pin handle (like Android text selection pin / Google Lens).
 */
private fun DrawScope.drawSelectionPin(pinPoint: Offset, isStart: Boolean) {
    val pinColor = Color(0xFF2563EB)
    val pinRadius = 14f

    if (isStart) {
        // Start Pin: teardrop pointing up-right into top-left of text
        val center = Offset(pinPoint.x - 2f, pinPoint.y - 16f)
        // Connecting line
        drawLine(
            color = pinColor,
            start = pinPoint,
            end = center,
            strokeWidth = 4f,
            cap = StrokeCap.Round
        )
        // Pin head
        drawCircle(color = Color.White, radius = pinRadius + 2.5f, center = center)
        drawCircle(color = pinColor, radius = pinRadius, center = center)
        drawCircle(color = Color.White, radius = 5f, center = center)
    } else {
        // End Pin: teardrop pointing down-left out of bottom-right of text
        val center = Offset(pinPoint.x + 2f, pinPoint.y + 16f)
        // Connecting line
        drawLine(
            color = pinColor,
            start = pinPoint,
            end = center,
            strokeWidth = 4f,
            cap = StrokeCap.Round
        )
        // Pin head
        drawCircle(color = Color.White, radius = pinRadius + 2.5f, center = center)
        drawCircle(color = pinColor, radius = pinRadius, center = center)
        drawCircle(color = Color.White, radius = 5f, center = center)
    }
}

/** Drives the aurora phase/pulse animatables while interaction is active. */
private suspend fun launchPhases(phaseAnim: Animatable<Float, *>, pulseAnim: Animatable<Float, *>) {
    kotlinx.coroutines.coroutineScope {
        launch {
            phaseAnim.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        }
        launch {
            pulseAnim.animateTo(
                targetValue = 1.12f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )
        }
    }
}
