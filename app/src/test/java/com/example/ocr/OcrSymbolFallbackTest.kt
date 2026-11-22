package com.example.ocr

import android.graphics.Rect
import com.example.model.OcrTextItem
import com.example.ocr.OcrManager.RawElement
import com.example.ocr.OcrManager.RawLine
import com.example.ocr.OcrManager.RawSymbol
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reproduces the case seen on outlined TV captions: ML Kit's line/element text is complete
 * but the per-character symbols (or elements) miss some characters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OcrSymbolFallbackTest {

    private fun box(left: Int, chars: Int = 1) = Rect(left, 0, left + 40 * chars, 40)
    private fun sym(text: String, left: Int) = RawSymbol(text, box(left))
    private fun elem(text: String, left: Int, symbols: List<RawSymbol>) = RawElement(text, box(left, text.length), symbols)
    private fun line(text: String, vararg elements: RawElement) = RawLine(text, box(0, text.length), elements.toList())

    private fun build(vararg lines: RawLine) = OcrManager().buildItemsFromLines(lines.toList())
    private fun tokensText(items: List<OcrTextItem>) = items.flatMap { it.tokens }.joinToString("") { it.text }

    @Test
    fun `complete symbols are used as-is with their own boxes`() {
        val syms = "あいうえお".mapIndexed { i, c -> sym(c.toString(), i * 40) }
        val items = build(line("あいうえお", elem("あいうえお", 0, syms)))

        assertEquals("あいうえお", tokensText(items))
        assertEquals(syms.map { it.box }, items.single().tokens.map { it.boundingBox })
    }

    @Test
    fun `symbols missing characters fall back to element text`() {
        // Symbols drop the kanji and one kana that element.text contains.
        val syms = listOf(sym("あ", 0), sym("い", 80), sym("え", 160))
        val items = build(line("あ漢いうえ", elem("あ漢いうえ", 0, syms)))

        assertEquals("あ漢いうえ", tokensText(items))
        assertEquals(5, items.single().tokens.size)
    }

    @Test
    fun `elements missing characters fall back to line text`() {
        val e1 = elem("あい", 0, listOf(sym("あ", 0), sym("い", 40)))
        val e2 = elem("えお", 120, listOf(sym("え", 120), sym("お", 160)))
        val items = build(line("あい漢えお", e1, e2))

        assertEquals("あい漢えお", tokensText(items))
        // Indices stay contiguous after the rebuild.
        assertEquals((0 until 5).toList(), items.single().tokens.map { it.globalIndex })
    }

    @Test
    fun `global indices stay contiguous across a rebuilt line`() {
        val ok = line("かき", elem("かき", 0, listOf(sym("か", 0), sym("き", 40))))
        val broken = line("あ漢い", elem("あ漢い", 0, listOf(sym("あ", 0), sym("い", 80))))
        val after = line("さし", elem("さし", 0, listOf(sym("さ", 0), sym("し", 40))))
        val items = build(ok, broken, after)

        assertEquals("かきあ漢いさし", tokensText(items))
        assertEquals((0 until 7).toList(), items.flatMap { it.tokens }.map { it.globalIndex })
    }

    @Test
    fun `latin words with spaces are not treated as a mismatch`() {
        val items = build(line("Hello World", elem("Hello", 0, emptyList()), elem("World", 240, emptyList())))

        assertEquals(listOf("Hello", "World"), items.single().tokens.map { it.text })
        assertEquals(box(0, 5), items.single().tokens.first().boundingBox)
    }
}
