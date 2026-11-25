package com.example.model

import kotlin.math.min

/**
 * Concatenates selected tokens into text: newline between lines, no space
 * around Japanese/CJK tokens, and for Latin/digit tokens a space only when
 * the physical gap between the token boxes is wide enough to be a real word
 * space.
 *
 * The previous rule inserted a space between *every* pair of non-CJK
 * tokens, which shredded symbol-split sequences into things like
 * "2 0 2 5 / 0 4 / 2 1" and "m y way" whenever ML Kit returned a Latin word
 * or a number as individual character symbols.
 *
 * Extracted from CircleLensViewModel so the rule is unit-testable.
 */
object TextJoiner {
    fun join(selected: List<OcrToken>): String {
        val sb = StringBuilder()
        var lastLineIdx = -1
        for (i in selected.indices) {
            val token = selected[i]
            if (i > 0) {
                if (token.lineIndex != lastLineIdx) {
                    sb.append("\n")
                } else {
                    val prev = selected[i - 1]
                    if (!prev.isJapaneseOrCjk && !token.isJapaneseOrCjk && shouldInsertSpace(prev, token)) {
                        sb.append(" ")
                    }
                }
            }
            sb.append(token.text)
            lastLineIdx = token.lineIndex
        }
        return sb.toString()
    }

    fun shouldInsertSpace(prev: OcrToken, cur: OcrToken): Boolean {
        // Closing punctuation binds to the previous token: "410 ," -> "410,"
        if (cur.text.isNotEmpty() && cur.text.all { it in ",.!?;:%/)]}\u3001\u3002" }) return false
        // Nothing separates a token from a preceding opening bracket
        if (prev.text.isNotEmpty() && prev.text.all { it in "([{" }) return false

        val gap = cur.boundingBox.left - prev.boundingBox.right
        if (gap <= 0) return false
        val refHeight = min(prev.boundingBox.height(), cur.boundingBox.height()).coerceAtLeast(1)
        // Intra-word symbol gaps are a small fraction of the glyph height,
        // while a real word space is roughly a quarter of it or more.
        return gap > refHeight * 0.25f
    }
}
