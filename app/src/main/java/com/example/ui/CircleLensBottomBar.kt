package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.ShareTarget

private val RailShape = RoundedCornerShape(34.dp)

/** Two 50dp icon columns + 4dp gap + 8dp side padding each. */
private val RAIL_WIDTH = 120.dp

/**
 * Edge rail on the first screen: the apps the screen was shared to before (newest on top,
 * scrollable) and a "+" at the end that opens the system share sheet to pick a new app.
 * Modelled after the OS side panel: pale frosted pill, round app icons.
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
    // One icon row (50dp + 10dp gap) shorter than the previous single-column rail.
    val maxRailHeight = (LocalConfiguration.current.screenHeightDp * 0.6f - 60f).dp

    Box(
        modifier = modifier
            .width(RAIL_WIDTH)
            .heightIn(max = maxRailHeight)
            .shadow(elevation = 14.dp, shape = RailShape)
            .clip(RailShape)
            .background(
                Brush.verticalGradient(
                    0f to Color(0xF2D9D6D6),
                    0.5f to Color(0xF7F5F3F8),
                    1f to Color(0xF2D9D6D6)
                )
            )
            .border(1.dp, Color(0x1F000000), RailShape)
            .alpha(if (isReady) 1f else 0.5f)
            .testTag("share_rail")
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.width(RAIL_WIDTH)
        ) {
            if (isOcrRunning) {
                item(key = "ocr_progress", span = { GridItemSpan(2) }) {
                    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFF0F172A),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            items(recentTargets, key = { "${it.packageName}/${it.activityName}" }) { target ->
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = isReady) { onShareEntireScreenDirect(target) }
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

            item(key = "pick_new_app") {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x2E000000))
                        .clickable(enabled = isReady) { onShareEntireScreenGeneral() }
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
    }
}
