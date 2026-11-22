package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.CropSelection
import com.example.model.ShareTarget
import com.example.model.TextSelectionState

/**
 * Clean & minimal floating text action toolbar styled with white frosted glass and monochrome black accents.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TextSelectionPopup(
    modifier: Modifier = Modifier,
    textSelection: TextSelectionState?,
    lastShareTarget: ShareTarget?,
    recentTargets: List<ShareTarget> = emptyList(),
    onCopy: () -> Unit,
    onShareGeneral: () -> Unit,
    onShareDirect: (ShareTarget) -> Unit,
    onOpenShareSheet: () -> Unit = {},
    onTranslate: () -> Unit = {}
) {
    var showRecentSlider by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = textSelection != null && textSelection.selectedTokens.isNotEmpty(),
        enter = fadeIn() + slideInVertically(initialOffsetY = { 20 }, animationSpec = spring()),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { 20 })
    ) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.BottomCenter
        ) {
            RecentShareTargetsSlider(
                modifier = Modifier
                    .padding(bottom = 58.dp)
                    .align(Alignment.BottomCenter),
                visible = showRecentSlider,
                recentTargets = recentTargets,
                isImage = false,
                onTargetSelected = { target ->
                    showRecentSlider = false
                    onShareDirect(target)
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
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Copy Text Button
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0x0F000000))
                            .testTag("copy_text_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "テキストをコピー",
                            tint = Color(0xFF0F172A),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Share Text Button (General)
                    IconButton(
                        onClick = onShareGeneral,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0x0F000000))
                            .testTag("share_text_general_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "テキストを共有",
                            tint = Color(0xFF0F172A),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Translate Button (on-device PLaMo translation bar)
                    IconButton(
                        onClick = onTranslate,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0x0F000000))
                            .testTag("translate_text_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = "翻訳",
                            tint = Color(0xFF0F172A),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Last Direct Share App (Slide up for recent history)
                    if (lastShareTarget != null) {
                        var totalDragY by remember { mutableFloatStateOf(0f) }

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0x0F000000))
                                .border(1.dp, if (showRecentSlider) Color(0xFF0F172A) else Color(0x1F000000), CircleShape)
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { totalDragY = 0f },
                                        onDragEnd = {
                                            if (totalDragY < -20f) showRecentSlider = true
                                            totalDragY = 0f
                                        },
                                        onDragCancel = { totalDragY = 0f }
                                    ) { change, dragAmount ->
                                        change.consume()
                                        totalDragY += dragAmount.y
                                        if (totalDragY < -25f) showRecentSlider = true
                                    }
                                }
                                .combinedClickable(
                                    onClick = {
                                        if (showRecentSlider) {
                                            showRecentSlider = false
                                        } else {
                                            onShareDirect(lastShareTarget)
                                        }
                                    },
                                    onLongClick = {
                                        showRecentSlider = !showRecentSlider
                                    }
                                )
                                .testTag("direct_share_text_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            if (lastShareTarget.iconBitmap != null) {
                                Image(
                                    bitmap = lastShareTarget.iconBitmap,
                                    contentDescription = "${lastShareTarget.appName} に送信（上にスライドで履歴表示）",
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "${lastShareTarget.appName} に送信（上にスライドで履歴表示）",
                                    tint = Color(0xFF0F172A),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clean & minimal floating crop action toolbar styled with white frosted glass and monochrome black accents.
 * Displays "枠内テキストを抽出" (re-OCR), "画像をシェア", "画像を保存" (straight to the
 * Download folder, no app switch) and the adjacent direct share app icon.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CropSelectionPopup(
    modifier: Modifier = Modifier,
    cropSelection: CropSelection?,
    lastShareTarget: ShareTarget?,
    recentTargets: List<ShareTarget> = emptyList(),
    onShareImageGeneral: () -> Unit,
    onShareImageDirect: (ShareTarget) -> Unit,
    onOpenShareSheet: () -> Unit = {},
    onOcrCrop: () -> Unit = {},
    onSaveImage: () -> Unit = {}
) {
    var showRecentSlider by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = cropSelection != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { 20 }, animationSpec = spring()),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { 20 })
    ) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.BottomCenter
        ) {
            RecentShareTargetsSlider(
                modifier = Modifier
                    .padding(bottom = 58.dp)
                    .align(Alignment.BottomCenter),
                visible = showRecentSlider,
                recentTargets = recentTargets,
                isImage = true,
                onTargetSelected = { target ->
                    showRecentSlider = false
                    onShareImageDirect(target)
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
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Re-OCR inside the frame (text extraction)
                    IconButton(
                        onClick = onOcrCrop,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0x0F000000))
                            .testTag("ocr_crop_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.TextFields,
                            contentDescription = "枠内テキストを抽出",
                            tint = Color(0xFF0F172A),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Share Image (General)
                    IconButton(
                        onClick = onShareImageGeneral,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0x0F000000))
                            .testTag("share_crop_general_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "画像を共有",
                            tint = Color(0xFF0F172A),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Save to the Download folder without leaving the app
                    IconButton(
                        onClick = onSaveImage,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0x0F000000))
                            .testTag("save_crop_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "画像をダウンロードに保存",
                            tint = Color(0xFF0F172A),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Last Direct Share App (Slide up for recent history)
                    if (lastShareTarget != null) {
                        var totalDragY by remember { mutableFloatStateOf(0f) }

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0x0F000000))
                                .border(1.dp, if (showRecentSlider) Color(0xFF0F172A) else Color(0x1F000000), CircleShape)
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { totalDragY = 0f },
                                        onDragEnd = {
                                            if (totalDragY < -20f) showRecentSlider = true
                                            totalDragY = 0f
                                        },
                                        onDragCancel = { totalDragY = 0f }
                                    ) { change, dragAmount ->
                                        change.consume()
                                        totalDragY += dragAmount.y
                                        if (totalDragY < -25f) showRecentSlider = true
                                    }
                                }
                                .combinedClickable(
                                    onClick = {
                                        if (showRecentSlider) {
                                            showRecentSlider = false
                                        } else {
                                            onShareImageDirect(lastShareTarget)
                                        }
                                    },
                                    onLongClick = {
                                        showRecentSlider = !showRecentSlider
                                    }
                                )
                                .testTag("direct_share_crop_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            if (lastShareTarget.iconBitmap != null) {
                                Image(
                                    bitmap = lastShareTarget.iconBitmap,
                                    contentDescription = "${lastShareTarget.appName} に画像を送信（上にスライドで履歴表示）",
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "${lastShareTarget.appName} に画像を送信（上にスライドで履歴表示）",
                                    tint = Color(0xFF0F172A),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
