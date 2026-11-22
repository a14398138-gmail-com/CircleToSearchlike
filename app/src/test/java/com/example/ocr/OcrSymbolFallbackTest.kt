package com.example.ocr

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ML Kit's Text objects are mocked so we can reproduce the case seen on outlined TV captions:
 * the recognizer's line/element text is complete but the per-character symbols miss some characters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OcrSymbolFallbackTest {

    private fun symbol(text: String, left: Int): Text.Symbol = mockk {
        every { this@mockk.text } returns text
        every { boundingBox } returns Rect(left, 0, left + 40, 40)
    }

    private fun element(text: String, left: Int, symbols: List<Text.Symbol>): Text.Element = mockk {
        every { this@mockk.text } returns text
        every { boundingBox } returns Rect(left, 0, left + 40 * text.length, 40)
        every { this@mockk.symbols } returns symbols
    }

    private fun visionText(lineText: String, elements: List<Text.Element>): Text {
        val line = mockk<Text.Line> {
            every { text } returns lineText
            every { boundingBox } returns Rect(0, 0, 40 * lineText.length, 40)
            every { this@mockk.elements } returns elements
        }
        val block = mockk<Text.TextBlock> { every { lines } returns listOf(line) }
        return mockk { every { textBlocks } returns listOf(block) }
    }

    private fun tokensText(items: List<com.example.model.OcrTextItem>) =
        items.flatMap { it.tokens }.joinToString("") { it.text }

    @Test
    fun `complete symbols are used as-is with their own boxes`() {
        val syms = "あいうえお".mapIndexed { i, c -> symbol(c.toString(), i * 40) }
        val items = OcrManager().processVisionText(visionText("あいうえお", listOf(element("あいうえお", 0, syms))))

        assertEquals("あいうえお", tokensText(items))
        assertEquals(syms.map { it.boundingBox }, items.single().tokens.map { it.boundingBox })
    }

    @Test
    fun `symbols missing characters fall back to element text`() {
        // Symbols drop the kanji and one kana that element.text contains.
        val syms = listOf(symbol("あ", 0), symbol("い", 40), symbol("え", 120))
        val items = OcrManager().processVisionText(visionText("あ漢いうえ", listOf(element("あ漢いうえ", 0, syms))))

        assertEquals("あ漢いうえ", tokensText(items))
        assertEquals(5, items.single().tokens.size)
    }

    @Test
    fun `elements missing characters fall back to line text`() {
        val e1 = element("あい", 0, listOf(symbol("あ", 0), symbol("い", 40)))
        val e2 = element("えお", 160, listOf(symbol("え", 160), symbol("お", 200)))
        val items = OcrManager().processVisionText(visionText("あい漢えお", listOf(e1, e2)))

        assertEquals("あい漢えお", tokensText(items))
        // Indices stay contiguous after the rebuild.
        assertEquals((0 until 5).toList(), items.single().tokens.map { it.globalIndex })
    }

    @Test
    fun `latin words with spaces are not treated as a mismatch`() {
        val e1 = element("Hello", 0, emptyList())
        val e2 = element("World", 240, emptyList())
        val items = OcrManager().processVisionText(visionText("Hello World", listOf(e1, e2)))

        assertEquals(listOf("Hello", "World"), items.single().tokens.map { it.text })
        assertEquals(Rect(0, 0, 200, 40), items.single().tokens.first().boundingBox)
    }
}
