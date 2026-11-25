package com.example.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleRecPostProcessTest {

    @Test
    fun `parses character_dict block list with quoted scalars`() {
        val yaml = """
            Global:
              model_name: PP-OCRv5_server_rec
            PostProcess:
              name: CTCLabelDecode
              character_dict:
              - あ
              - '''
              - '#'
              - "　"
              - ': '
              - 漢
            PreProcess:
              transform_ops:
              - DecodeImage: null
        """.trimIndent()

        assertEquals(listOf("あ", "'", "#", "　", ": ", "漢"), PaddleRecPostProcess.parseCharacterDictFromYaml(yaml))
    }

    @Test
    fun `parses an unindented list under the key`() {
        val yaml = "character_dict:\n- a\n- b\nnext: 1\n"
        assertEquals(listOf("a", "b"), PaddleRecPostProcess.parseCharacterDictFromYaml(yaml))
    }

    @Test
    fun `space label is added only when the model has room for it`() {
        val dict = listOf("a", "b")
        assertEquals(listOf("", "a", "b", " "), PaddleRecPostProcess.buildLabels(dict, 4))
        assertEquals(listOf("", "a", "b"), PaddleRecPostProcess.buildLabels(dict, 3))
    }

    /** Builds a T x C one-hot-ish probability matrix from a label index per timestep. */
    private fun probs(path: List<Int>, numClasses: Int, p: Float = 0.9f): FloatArray {
        val out = FloatArray(path.size * numClasses) { (1f - p) / (numClasses - 1) }
        path.forEachIndexed { t, c -> out[t * numClasses + c] = p }
        return out
    }

    @Test
    fun `ctc collapses repeats and drops blanks but keeps separated repeats`() {
        val labels = listOf("", "a", "b", " ")
        // a a _ a b b _ _  -> "aab"
        val path = listOf(1, 1, 0, 1, 2, 2, 0, 0)
        val line = PaddleRecPostProcess.ctcDecode(probs(path, 4), path.size, 4, labels, 80f)

        assertEquals("aab", line.text)
        assertEquals(0.9f, line.meanConfidence, 1e-4f)
    }

    @Test
    fun `character boxes are ordered, non-overlapping and inside the image`() {
        val labels = listOf("", "x", "y", "z")
        val path = listOf(0, 1, 0, 0, 2, 0, 0, 3, 0, 0)
        val line = PaddleRecPostProcess.ctcDecode(probs(path, 4), path.size, 4, labels, 100f)

        assertEquals("xyz", line.text)
        line.chars.zipWithNext().forEach { (a, b) ->
            assertTrue("ordered", a.left < b.left)
            assertEquals("adjacent", a.right, b.left, 1e-3f)
        }
        assertTrue(line.chars.first().left >= 0f)
        assertTrue(line.chars.last().right <= 100f)
    }

    @Test
    fun `empty when everything is blank`() {
        val line = PaddleRecPostProcess.ctcDecode(probs(listOf(0, 0, 0), 3), 3, 3, listOf("", "a", "b"), 30f)
        assertEquals("", line.text)
        assertEquals(0f, line.meanConfidence, 0f)
    }

    @Test
    fun `groups cjk per character and latin per word split by spaces`() {
        val chars = listOf("恐", "い", "A", "B", " ", "C", "1", "！").mapIndexed { i, t ->
            PaddleRecPostProcess.RecChar(t, i * 10f, i * 10f + 10f, 0.9f)
        }
        val tokens = PaddleRecPostProcess.groupIntoTokens(PaddleRecPostProcess.RecLine(chars))

        assertEquals(listOf("恐", "い", "AB", "C1", "！"), tokens.map { it.text })
        assertEquals(listOf(true, true, false, false, true), tokens.map { it.isCjk })
        // The Latin word spans its first to last character; the space leaves a gap.
        assertEquals(20f, tokens[2].left, 0f)
        assertEquals(40f, tokens[2].right, 0f)
        assertEquals(50f, tokens[3].left, 0f)
    }
}
