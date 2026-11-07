package com.example.gesture

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import com.example.model.OcrToken
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object GestureProcessor {

    /**
     * Determines whether a series of points forms a circle / loop gesture.
     * Uses cumulative winding angle, path closure, and bounding box geometry.
     */
    fun detectCircleGesture(
        points: List<Offset>,
        minSizePx: Float = 45f
    ): RectF? {
        if (points.size < 8) return null

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (pt in points) {
            minX = min(minX, pt.x)
            minY = min(minY, pt.y)
            maxX = max(maxX, pt.x)
            maxY = max(maxY, pt.y)
        }

        val width = maxX - minX
        val height = maxY - minY

        // Bounding box must have meaningful 2D extent
        if (width < minSizePx || height < minSizePx) return null

        val start = points.first()
        val end = points.last()
        val startEndDist = hypot((start.x - end.x).toDouble(), (start.y - end.y).toDouble()).toFloat()
        val diagonal = hypot(width.toDouble(), height.toDouble()).toFloat()

        // 1. Calculate total path length
        var pathLength = 0f
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            pathLength += hypot((p2.x - p1.x).toDouble(), (p2.y - p1.y).toDouble()).toFloat()
        }

        // 2. Calculate cumulative winding angle (sum of directional angle changes)
        var totalWindingAngle = 0.0
        var prevAngle = 0.0
        var hasPrevAngle = false

        val step = max(1, points.size / 30)
        val sampled = mutableListOf<Offset>()
        for (i in points.indices step step) {
            sampled.add(points[i])
        }
        if (sampled.last() != points.last()) {
            sampled.add(points.last())
        }

        for (i in 0 until sampled.size - 1) {
            val p1 = sampled[i]
            val p2 = sampled[i + 1]
            val dx = (p2.x - p1.x).toDouble()
            val dy = (p2.y - p1.y).toDouble()
            if (hypot(dx, dy) > 3.0) {
                val currentAngle = atan2(dy, dx)
                if (hasPrevAngle) {
                    var diff = currentAngle - prevAngle
                    while (diff > PI) diff -= 2 * PI
                    while (diff < -PI) diff += 2 * PI
                    totalWindingAngle += diff
                }
                prevAngle = currentAngle
                hasPrevAngle = true
            }
        }

        val totalRotationDegrees = abs(Math.toDegrees(totalWindingAngle))
        val perimeter = 2 * (width + height)

        // Circle Criteria:
        // A. Total rotation >= 160 degrees (curving around in a loop / U-turn / oval)
        // B. Start and end points are relatively close
        // C. Path length is sufficient to enclose an area
        val aspectRatio = if (height > 0f) width / height else 1f
        val isNotExtremelyFlat = aspectRatio in 0.12f..8.0f
        val isClosedLoop = startEndDist < diagonal * 0.85f || startEndDist < pathLength * 0.45f
        val hasLoopRotation = totalRotationDegrees >= 160.0

        if ((hasLoopRotation || isClosedLoop) && pathLength > perimeter * 0.35f && isNotExtremelyFlat) {
            val marginX = width * 0.04f
            val marginY = height * 0.04f
            return RectF(
                max(0f, minX - marginX),
                max(0f, minY - marginY),
                maxX + marginX,
                maxY + marginY
            )
        }

        return null
    }

    /**
     * Finds token range (startGlobalIdx to endGlobalIdx) covered by user stroke.
     * Accepts precomputed display-space boxes to avoid per-token RectF allocation on every call.
     * Tokens whose box cannot possibly intersect the stroke bounding box are skipped in O(1).
     */
    fun findTokensInStroke(
        strokePoints: List<Offset>,
        allTokens: List<OcrToken>,
        displayBoxes: List<RectF>
    ): Pair<Int, Int>? {
        if (strokePoints.isEmpty() || allTokens.isEmpty()) return null
        if (displayBoxes.size != allTokens.size) return null

        val tolerance = 32f // Touch tolerance for finger strokes

        // Precompute the stroke bounding box so far-away tokens are rejected in O(1)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (pt in strokePoints) {
            if (pt.x < minX) minX = pt.x
            if (pt.y < minY) minY = pt.y
            if (pt.x > maxX) maxX = pt.x
            if (pt.y > maxY) maxY = pt.y
        }
        val strokeLeft = minX - tolerance
        val strokeTop = minY - tolerance
        val strokeRight = maxX + tolerance
        val strokeBottom = maxY + tolerance

        var minIdx = Int.MAX_VALUE
        var maxIdx = Int.MIN_VALUE

        for (i in allTokens.indices) {
            val displayBox = displayBoxes[i]
            if (displayBox.right < strokeLeft || displayBox.left > strokeRight ||
                displayBox.bottom < strokeTop || displayBox.top > strokeBottom
            ) {
                continue
            }
            val expanded = RectF(
                displayBox.left - tolerance,
                displayBox.top - tolerance,
                displayBox.right + tolerance,
                displayBox.bottom + tolerance
            )
            for (pt in strokePoints) {
                if (expanded.contains(pt.x, pt.y)) {
                    val idx = allTokens[i].globalIndex
                    if (idx < minIdx) minIdx = idx
                    if (idx > maxIdx) maxIdx = idx
                    break
                }
            }
        }

        if (minIdx == Int.MAX_VALUE) return null
        return Pair(minIdx, maxIdx)
    }

    /**
     * Finds the token closest to a given display point with continuous 2D distance mapping
     * to provide fluid pin dragging across arbitrary characters, words, and lines.
     */
    fun findClosestToken(
        point: Offset,
        allTokens: List<OcrToken>,
        displayBoxes: List<RectF>
    ): OcrToken? {
        if (allTokens.isEmpty()) return null
        if (displayBoxes.size != allTokens.size) return null

        var bestToken: OcrToken? = null
        var minScore = Float.MAX_VALUE

        for (i in allTokens.indices) {
            val displayBox = displayBoxes[i]

            // Vertical distance with comfortable line snap band
            val vDist = when {
                point.y in (displayBox.top - 8f)..(displayBox.bottom + 8f) -> 0f
                point.y < displayBox.top -> displayBox.top - point.y
                else -> point.y - displayBox.bottom
            }

            // distScore >= vDist * 2.2, so skip full scoring when vertical distance alone loses
            if (vDist * 2.2f >= minScore) continue

            // Horizontal distance to box edge
            val hDist = when {
                point.x in displayBox.left..displayBox.right -> 0f
                point.x < displayBox.left -> displayBox.left - point.x
                else -> point.x - displayBox.right
            }

            // Strongly favor the line the finger is currently on (vertical weight 2.2x)
            val distScore = hypot(hDist.toDouble(), (vDist * 2.2).toDouble()).toFloat()

            if (distScore < minScore) {
                minScore = distScore
                bestToken = allTokens[i]
            }
        }

        return bestToken
    }
}

