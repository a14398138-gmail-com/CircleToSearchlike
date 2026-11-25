package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.ShareTarget

private val BarShape = RoundedCornerShape(34.dp)
private val CELL = 50.dp
private val COL_GAP = 6.dp
private val BAR_PAD_H = 10.dp

/**
 * Bottom bar on the first screen: the apps the screen was shared to before (newest on the
 * left) and a "+" at the right end that opens the system share sheet to pick a new app.
 *
 * Only the icons and "+" take touches. The bar itself has no pointer input (and does not
 * scroll), so drags and taps on its translucent background reach the screenshot behind it.
 */
@Composable
fun CircleLensBottomBar(
    modifier: Modifier = Modifier,
    isReady: Boolean = true,
    isOcrRunning: Boolean = false,
    recentTargets: List<ShareTarget> = emptyList(),
    onShareEntireScreenGeneral: () -> Unit,
    onShareEntireScreenDirect: (ShareTarget) -> Unit
) {
    // One row along the bottom. Fit as many apps as the screen width allows (newest on the
    // left, "+" at the right end); older apps are dropped instead of scrolling.
    val maxWidthDp = LocalConfiguration.current.screenWidthDp - 32f
    val cellWidthDp = CELL.value + COL_GAP.value
    val maxCells = (((maxWidthDp - 2 * BAR_PAD_H.value) + COL_GAP.value) / cellWidthDp).toInt().coerceAtLeast(1)
    val reservedForProgress = if (isOcrRunning) 1 else 0
    val maxApps = (maxCells - reservedForProgress - 1).coerceAtLeast(0) // keep one cell for "+"

    Row(
        modifier = modifier
            .clip(BarShape)
            .background(
                Brush.horizontalGradient(
                    0f to Color(0x47D9D6D6),
                    0.5f to Color(0x66F5F3F8),
                    1f to Color(0x47D9D6D6)
                )
            )
            .border(1.dp, Color(0x33FFFFFF), BarShape)
            .padding(horizontal = BAR_PAD_H, vertical = 8.dp)
            .alpha(if (isReady) 1f else 0.5f)
            .testTag("share_rail"),
        horizontalArrangement = Arrangement.spacedBy(COL_GAP),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isOcrRunning) {
            Box(Modifier.size(CELL), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color(0xFF0F172A),
                    strokeWidth = 2.dp
                )
            }
        }
        recentTargets.take(maxApps).forEach { target ->
            AppCell(target, isReady) { onShareEntireScreenDirect(target) }
        }
        NewAppCell(isReady, onShareEntireScreenGeneral)
    }
}

@Composable
private fun AppCell(target: ShareTarget, isReady: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(CELL)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = isReady, onClick = onClick)
            .testTag("share_rail_app_${target.packageName}"),
        contentAlignment = Alignment.Center
    ) {
        if (target.iconBitmap != null) {
            Image(
                bitmap = target.iconBitmap,
                contentDescription = "${target.appName} に画面全体を送る",
                modifier = Modifier.size(46.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Default.Send,
                contentDescription = "${target.appName} に画面全体を送る",
                tint = Color(0xFF0F172A),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun NewAppCell(isReady: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(CELL),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0x40000000))
                .clickable(enabled = isReady, onClick = onClick)
                .testTag("share_entire_screen_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "新しいアプリを選んで画面全体を送る",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}
