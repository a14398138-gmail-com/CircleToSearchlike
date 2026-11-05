package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.translate.TranslateController
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Translate bar modeled on the Circle-to-Search translate UI:
 * [source chip] -> [target chip] [blue translate button].
 * Opening the bar translates immediately (one tap from the selection popup).
 * The result panel is semi-transparent and can be dragged up/down by its handle.
 * No completion popups and no speed stats are shown.
 */
@Composable
fun TranslateBar(
    modifier: Modifier = Modifier,
    controller: TranslateController,
    sourceText: String,
    refreshKey: Int = 0,
    onPickModelFile: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var srcLang by remember(sourceText) { mutableStateOf(TranslateController.detectLang(sourceText)) }
    var tgtLang by remember(sourceText) { mutableStateOf(if (srcLang == "ja") "en" else "ja") }
    var modelPresent by remember { mutableStateOf(controller.modelExists()) }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var result by remember(sourceText) { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var panelOffsetY by remember(sourceText) { mutableFloatStateOf(0f) }

    fun startTranslate() {
        if (busy || sourceText.isBlank()) return
        busy = true
        errorText = null
        scope.launch {
            if (controller.handle == 0L) {
                val load = controller.loadModel()
                if (load is TranslateController.LoadResult.Error) {
                    errorText = load.message
                    busy = false
                    return@launch
                }
            }
            var gotPartial = false
            when (val r = controller.translateStream(sourceText, srcLang, tgtLang) { partial ->
                gotPartial = true
                result = partial
            }) {
                is TranslateController.TranslateResult.Ok -> {
                    result = r.text
                    panelOffsetY = 0f
                }
                is TranslateController.TranslateResult.Error -> {
                    if (!gotPartial) errorText = r.message
                }
            }
            busy = false
        }
    }

    // One tap: translate as soon as the bar opens.
    LaunchedEffect(sourceText) {
        if (modelPresent) startTranslate()
    }

    // Model imported via the file picker while the bar is open.
    LaunchedEffect(refreshKey) {
        if (refreshKey > 0) {
            modelPresent = controller.modelExists()
            if (modelPresent) startTranslate()
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Result / error panel: semi-transparent, draggable by the handle.
        if (result != null || errorText != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, panelOffsetY.roundToInt()) }
                    .shadow(elevation = 10.dp, shape = RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xCCFFFFFF),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    // Drag handle
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(18.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    panelOffsetY = (panelOffsetY + dragAmount.y)
                                        .coerceIn(-700f, 150f)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0x66000000))
                        )
                    }
                    errorText?.let {
                        Text(it, fontSize = 13.sp, color = Color(0xFFB3261E))
                    }
                    result?.let { res ->
                        Box(
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(res, fontSize = 15.sp, color = Color(0xFF1F1F1F))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(res)) },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "訳文をコピー",
                                    tint = Color(0xFF5F6368),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        downloadProgress?.let { p ->
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        }

        // Pill bar
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 14.dp, shape = RoundedCornerShape(32.dp)),
            shape = RoundedCornerShape(32.dp),
            color = Color(0xFFFFFFFF),
        ) {
            if (!modelPresent) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "翻訳モデルが未保存です",
                        fontSize = 13.sp,
                        color = Color(0xFF1F1F1F),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { onPickModelFile() },
                    ) { Text("ファイル選択", fontSize = 13.sp) }
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            errorText = null
                            scope.launch {
                                val ok = controller.downloadModel { frac -> downloadProgress = frac }
                                downloadProgress = null
                                modelPresent = controller.modelExists()
                                if (!ok) {
                                    errorText = "ダウンロードに失敗しました"
                                }
                                busy = false
                                if (ok) startTranslate()
                            }
                        },
                    ) { Text("DL", fontSize = 13.sp) }
                }
            } else {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LanguageChip(
                        label = TranslateController.langLabel(srcLang),
                        enabled = !busy,
                        onClick = {
                            val s = srcLang; srcLang = tgtLang; tgtLang = s
                        },
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = Color(0xFF5F6368),
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .size(18.dp),
                    )
                    LanguageChip(
                        label = TranslateController.langLabel(tgtLang),
                        enabled = !busy,
                        onClick = {
                            val s = srcLang; srcLang = tgtLang; tgtLang = s
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "翻訳を閉じる",
                            tint = Color(0xFF5F6368),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0B57D0))
                            .clickable(enabled = !busy) { startTranslate() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(22.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = "翻訳する",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFFF1F3F4),
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF1F1F1F),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
