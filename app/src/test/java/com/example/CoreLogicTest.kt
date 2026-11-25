package com.example

import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import com.example.gesture.GestureProcessor
import com.example.model.OcrTextItem
import com.example.model.OcrToken
import com.example.model.TextJoiner
import com.example.ocr.OcrManager
import com.example.translate.TranslateController
import com.koike.llama.PlamoPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plain-JVM tests for the PFML3 prompt builder. */
class PlamoPromptTest {
    @Test
    fun build_withoutSourceLang_matchesOfficialTemplate() {
        val p = PlamoPrompt.build("Hello", "ja")
        assertEquals(
            "<|plamo:bos|><|plamo:tag|>dataset<|plamo:msg|>translation" +
                "<|plamo:tag|>input<|plamo:msg|>Hello" +
                "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>ja<|plamo:msg|>",
            p
        )
    }

    @Test
    fun build_withSourceLang_insertsKeyVal() {
        val p = PlamoPrompt.build("こんにちは", "en", "ja")
        assertTrue(p.contains("<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>ja<|plamo:msg|>こんにちは"))
        assertTrue(p.endsWith("<|plamo:key|>lang<|plamo:val|>en<|plamo:msg|>"))
    }
}

/** Plain-JVM tests for script-majority language detection. */
class DetectLangTest {
    @Test
    fun japaneseMajority_isJa() {
        assertEquals("ja", TranslateController.detectLang("これはテストです"))
    }

    @Test
    fun englishMajority_isEn() {
        assertEquals("en", TranslateController.detectLang("This is a test sentence"))
    }

    @Test
    fun strayKanaInEnglish_staysEn() {
        // One kana must not flip an otherwise English passage (the v6 fix).
        assertEquals("en", TranslateController.detectLang("Hello world, this is a pen あ"))
    }

    @Test
    fun kanjiHeavy_isJa() {
        assertEquals("ja", TranslateController.detectLang("東京大学の講義資料"))
    }

    @Test
    fun empty_isEn() {
        assertEquals("en", TranslateController.detectLang(""))
    }
}

private fun token(
    text: String,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    line: Int = 0,
    global: Int = 0,
    cjk: Boolean = false
) = OcrToken(
    id = "t_${line}_$global",
    text = text,
    boundingBox = Rect(left, top, right, bottom),
    lineIndex = line,
    tokenIndex = global,
    globalIndex = global,
    isJapaneseOrCjk = cjk
)

/** Token-joining rules (Robolectric: android.graphics.Rect needs a real impl). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextJoinerTest {
    @Test
    fun latinWords_wideGap_getSpace() {
        val a = token("Hello", 0, 0, 50, 20, global = 0)
        val b = token("world", 60, 0, 110, 20, global = 1)
        assertEquals("Hello world", TextJoiner.join(listOf(a, b)))
    }

    @Test
    fun latinSymbols_narrowGap_noSpace() {
        // "2025" split into per-character symbols must not become "2 0 2 5".
        val toks = listOf("2", "0", "2", "5").mapIndexed { i, ch ->
            token(ch, i * 11, 0, i * 11 + 10, 20, global = i)
        }
        assertEquals("2025", TextJoiner.join(toks))
    }

    @Test
    fun cjkTokens_neverGetSpaces() {
        val a = token("日", 0, 0, 20, 20, global = 0, cjk = true)
        val b = token("本", 30, 0, 50, 20, global = 1, cjk = true)
        assertEquals("日本", TextJoiner.join(listOf(a, b)))
    }

    @Test
    fun newLine_betweenLines() {
        val a = token("one", 0, 0, 30, 20, line = 0, global = 0)
        val b = token("two", 0, 30, 30, 50, line = 1, global = 1)
        assertEquals("one\ntwo", TextJoiner.join(listOf(a, b)))
    }

    @Test
    fun closingPunctuation_bindsWithoutSpace() {
        val a = token("410", 0, 0, 30, 20, global = 0)
        val b = token(",", 40, 0, 45, 20, global = 1)
        assertEquals("410,", TextJoiner.join(listOf(a, b)))
    }
}

/** Gesture detection / token lookup (Robolectric: RectF). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GestureProcessorTest {
    private val identity: (Rect) -> RectF = { r ->
        RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
    }

    @Test
    fun circleGesture_detectedForLoop() {
        val pts = (0..40).map { i ->
            val a = i * (2.0 * Math.PI / 40.0)
            Offset(200f + 100f * Math.cos(a).toFloat(), 200f + 100f * Math.sin(a).toFloat())
        }
        val rect = GestureProcessor.detectCircleGesture(pts)
        assertNotNull(rect)
        assertTrue(rect!!.width() > 150f)
    }

    @Test
    fun straightLine_isNotCircle() {
        val pts = (0..20).map { Offset(it * 20f, 100f) }
        assertNull(GestureProcessor.detectCircleGesture(pts))
    }

    @Test
    fun findTokensInStroke_returnsCoveredRange() {
        val toks = listOf(
            token("a", 0, 0, 40, 20, global = 0),
            token("b", 50, 0, 90, 20, global = 1),
            token("c", 300, 0, 340, 20, global = 2)
        )
        val range = GestureProcessor.findTokensInStroke(
            listOf(Offset(10f, 10f), Offset(60f, 10f)), toks, identity
        )
        assertEquals(0 to 1, range)
    }

    @Test
    fun findClosestToken_picksNearest() {
        val toks = listOf(
            token("a", 0, 0, 40, 20, global = 0),
            token("b", 200, 0, 240, 20, global = 1)
        )
        val hit = GestureProcessor.findClosestToken(Offset(215f, 10f), toks, identity)
        assertEquals("b", hit?.text)
    }
}

/** Tile-merge de-duplication and renumbering (Robolectric: Rect). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TileMergeTest {
    private fun item(text: String, top: Int, bottom: Int, lineIdx: Int): OcrTextItem {
        val toks = text.mapIndexed { i, ch ->
            token(ch.toString(), i * 10, top, i * 10 + 10, bottom, line = lineIdx, global = 0, cjk = false)
        }
        return OcrTextItem(
            id = "line_$lineIdx",
            text = text,
            boundingBox = Rect(0, top, text.length * 10, bottom),
            lineIndex = lineIdx,
            blockIndex = 0,
            tokens = toks
        )
    }

    @Test
    fun duplicateOverlapLine_keepsLongerText() {
        val mgr = OcrManager()
        val merged = mgr.mergeTileResults(
            listOf(
                item("Hello wor", 0, 20, 0),
                item("Hello world", 2, 22, 1), // same line seen by the next tile, whole
                item("Second", 40, 60, 2)
            )
        )
        assertEquals(2, merged.size)
        assertEquals("Hello world", merged[0].text)
        assertEquals("Second", merged[1].text)
    }

    @Test
    fun merge_renumbersLinesAndGlobalIndicesSequentially() {
        val mgr = OcrManager()
        val merged = mgr.mergeTileResults(
            listOf(item("ab", 40, 60, 5), item("cd", 0, 20, 9))
        )
        assertEquals(listOf(0, 1), merged.map { it.lineIndex })
        val globals = merged.flatMap { it.tokens }.map { it.globalIndex }
        assertEquals((0 until globals.size).toList(), globals)
    }

    @Test
    fun merge_emptyInput_emptyOutput() {
        assertTrue(OcrManager().mergeTileResults(emptyList()).isEmpty())
        assertFalse(OcrManager().mergeTileResults(listOf(item("x", 0, 10, 0))).isEmpty())
    }
}
