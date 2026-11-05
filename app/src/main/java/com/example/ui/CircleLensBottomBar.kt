package com.example.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.ShareTarget

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CircleLensBottomBar(
    modifier: Modifier = Modifier,
    isReady: Boolean = true,
    isOcrRunning: Boolean = false,
    lastShareTarget: ShareTarget?,
    recentTargets: List<ShareTarget> = emptyList(),
    onSelectAllText: () -> Unit,
    onShareEntireScreenGeneral: () -> Unit,
    onShareEntireScreenDirect: (ShareTarget) -> Unit,
    onOpenShareSheet: () -> Unit = {}
) {
    // State for showing recent apps vertical slider
    var showRecentSlider by remember { mutableStateOf(false) }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter
    ) {
        // Vertical slider for recent apps (slides up from the recent app button)
        RecentShareTargetsSlider(
            modifier = Modifier
                .padding(bottom = 60.dp)
                .align(Alignment.BottomEnd),
            visible = showRecentSlider,
            recentTargets = recentTargets,
            isImage = true,
            onTargetSelected = { target ->
                showRecentSlider = false
                onShareEntireScreenDirect(target)
            },
            onOpenShareSheet = {
                showRecentSlider = false
                onOpenShareSheet()
            },
            onDismiss = {
                showRecentSlider = false
            }
        )

        Surface(
            modifier = Modifier
                .shadow(elevation = 14.dp, shape = CircleShape)
                .border(
                    width = 1.dp,
                    color = Color(0x33000000),
                    shape = CircleShape
                ),
            shape = CircleShape,
            color = Color(0xCCFFFFFF),
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. "すべての文字を選択" Icon Button
                if (isOcrRunning) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0x0F000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color(0xFF0F172A),
                            strokeWidth = 2.dp
                        )
                    }
                } else {
                    IconButton(
                        onClick = onSelectAllText,
                        enabled = isReady,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0x0F000000))
                            .alpha(if (isReady) 1f else 0.4f)
                            .testTag("select_all_text_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SelectAll,
                            contentDescription = "すべての文字を選択",
                            tint = Color(0xFF0F172A),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // 2. "画面全体を送る" Icon Button
                IconButton(
                    onClick = onShareEntireScreenGeneral,
                    enabled = isReady,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0x0F000000))
                        .alpha(if (isReady) 1f else 0.4f)
                        .testTag("share_entire_screen_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Screenshot,
                        contentDescription = "画面全体を送る",
                        tint = Color(0xFF0F172A),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // 3. 前回共有したアプリのアイコン (直接送信 / 上スライド・長押しで履歴スライダー展開)
                if (lastShareTarget != null) {
                    var totalDragY by remember { mutableFloatStateOf(0f) }

                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0x0F000000))
                            .border(1.dp, if (showRecentSlider) Color(0xFF0F172A) else Color(0x1F000000), CircleShape)
                            .alpha(if (isReady) 1f else 0.4f)
                            .pointerInput(isReady) {
                                if (!isReady) return@pointerInput
                                detectDragGestures(
                                    onDragStart = { totalDragY = 0f },
                                    onDragEnd = {
                                        if (totalDragY < -20f) {
                                            showRecentSlider = true
                                        }
                                        totalDragY = 0f
                                    },
                                    onDragCancel = { totalDragY = 0f }
                                ) { change, dragAmount ->
                                    change.consume()
                                    totalDragY += dragAmount.y
                                    if (totalDragY < -25f) {
                                        showRecentSlider = true
                                    }
                                }
                            }
                            .combinedClickable(
                                enabled = isReady,
                                onClick = {
                                    if (showRecentSlider) {
                                        showRecentSlider = false
                                    } else {
                                        onShareEntireScreenDirect(lastShareTarget)
                                    }
                                },
                                onLongClick = {
                                    showRecentSlider = !showRecentSlider
                                }
                            )
                            .testTag("direct_share_last_app_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (lastShareTarget.iconBitmap != null) {
                            Image(
                                bitmap = lastShareTarget.iconBitmap,
                                contentDescription = "${lastShareTarget.appName} に画面全体を送る（上にスライドで履歴表示）",
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Screenshot,
                                contentDescription = "${lastShareTarget.appName} に画面全体を送る（上にスライドで履歴表示）",
                                tint = Color(0xFF0F172A),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
