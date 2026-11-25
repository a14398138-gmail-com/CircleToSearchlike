package com.example.ocr

/**
 * Pure post-processing for PaddleOCR (PP-OCRv5) text-line recognition models:
 * dictionary parsing and CTC greedy decoding with per-character x positions.
 * Kept free of Android / ONNX types so it can be unit tested directly.
 */
object PaddleRecPostProcess {

    /** One decoded character and its horizontal extent in the *input line image* (pixels). */
    data class RecChar(
        val text: String,
        val left: Float,
        val right: Float,
        val confidence: Float
    )

    data class RecLine(val chars: List<RecChar>) {
        val text: String get() = chars.joinToString("") { it.text }
        val meanConfidence: Float
            get() = chars.filter { it.text.isNotBlank() }.map { it.confidence }
                .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
    }

    /**
     * Extracts `PostProcess.character_dict` from a PaddleX `inference.yml`.
     * Only the subset of YAML that file uses is handled: a block list of scalars,
     * each plain, 'single-quoted' or "double-quoted".
     */
    fun parseCharacterDictFromYaml(yaml: String): List<String> {
        val lines = yaml.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("character_dict:") }
        if (start < 0) return emptyList()
        val keyIndent = lines[start].indexOfFirst { !it.isWhitespace() }

        val result = mutableListOf<String>()
        for (i in start + 1 until lines.size) {
            val raw = lines[i]
            if (raw.isBlank()) continue
            val indent = raw.indexOfFirst { !it.isWhitespace() }
            val body = raw.substring(indent)
            if (!body.startsWith("-")) {
                if (indent <= keyIndent) break else continue
            }
            if (indent < keyIndent) break
            // "- value" ; a bare "-" would be an empty item (not expected)
            val value = if (body.length > 1) body.substring(1).removePrefix(" ") else ""
            result.add(unquoteYamlScalar(value))
        }
        return result
    }

    internal fun unquoteYamlScalar(value: String): String {
        if (value.length >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length - 1).replace("''", "'")
        }
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            val inner = value.substring(1, value.length - 1)
            val sb = StringBuilder()
            var i = 0
            while (i < inner.length) {
                val c = inner[i]
                if (c == '\\' && i + 1 < inner.length) {
                    when (val n = inner[i + 1]) {
                        'n' -> { sb.append('\n'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        '"' -> { sb.append('"'); i += 2 }
                        '/' -> { sb.append('/'); i += 2 }
                        ' ' -> { sb.append(' '); i += 2 }
                        'u' -> if (i + 6 <= inner.length) {
                            sb.append(inner.substring(i + 2, i + 6).toInt(16).toChar()); i += 6
                        } else { sb.append(n); i += 2 }
                        'U' -> if (i + 10 <= inner.length) {
                            sb.appendCodePoint(inner.substring(i + 2, i + 10).toInt(16)); i += 10
                        } else { sb.append(n); i += 2 }
                        'x' -> if (i + 4 <= inner.length) {
                            sb.append(inner.substring(i + 2, i + 4).toInt(16).toChar()); i += 4
                        } else { sb.append(n); i += 2 }
                        else -> { sb.append(n); i += 2 }
                    }
                } else {
                    sb.append(c); i++
                }
            }
            return sb.toString()
        }
        // Plain scalar; strip a trailing comment only when preceded by a space.
        val hash = value.indexOf(" #")
        return (if (hash >= 0) value.substring(0, hash) else value).trimEnd()
    }

    /**
     * Builds the label table used by CTC decoding: index 0 is the CTC blank,
     * then the dictionary, then (PaddleX default `use_space_char=True`) a space.
     * The space is only appended if the model's class count says it exists.
     */
    fun buildLabels(dict: List<String>, numClasses: Int): List<String> {
        val labels = ArrayList<String>(numClasses)
        labels.add("") // blank
        labels.addAll(dict)
        if (numClasses == dict.size + 2) labels.add(" ")
        return labels
    }

    /**
     * Greedy CTC decode of a [timesteps] x [numClasses] probability matrix (row-major).
     * Character positions: each emitted character's centre is the mean of its timesteps,
     * mapped to [imageWidth]; boundaries are placed half-way between neighbouring centres.
     */
    fun ctcDecode(
        probs: FloatArray,
        timesteps: Int,
        numClasses: Int,
        labels: List<String>,
        imageWidth: Float
    ): RecLine {
        require(probs.size >= timesteps * numClasses) { "probs too small" }
        if (timesteps == 0) return RecLine(emptyList())

        data class Span(val label: Int, var firstT: Int, var lastT: Int, var probSum: Float, var count: Int)

        val spans = mutableListOf<Span>()
        var prevIdx = -1
        for (t in 0 until timesteps) {
            val base = t * numClasses
            var best = 0
            var bestP = probs[base]
            for (c in 1 until numClasses) {
                val p = probs[base + c]
                if (p > bestP) { bestP = p; best = c }
            }
            if (best != 0) {
                if (best == prevIdx && spans.isNotEmpty()) {
                    val s = spans.last()
                    s.lastT = t; s.probSum += bestP; s.count++
                } else {
                    spans.add(Span(best, t, t, bestP, 1))
                }
            }
            prevIdx = best
        }
        if (spans.isEmpty()) return RecLine(emptyList())

        val stepW = imageWidth / timesteps
        val centres = spans.map { (it.firstT + it.lastT + 1) / 2f * stepW }
        val avgGap = if (centres.size > 1) (centres.last() - centres.first()) / (centres.size - 1) else stepW * 4

        val chars = spans.mapIndexed { i, s ->
            val left = if (i == 0) (centres[0] - avgGap / 2f).coerceAtLeast(0f) else (centres[i - 1] + centres[i]) / 2f
            val right = if (i == spans.lastIndex) (centres[i] + avgGap / 2f).coerceAtMost(imageWidth) else (centres[i] + centres[i + 1]) / 2f
            RecChar(
                text = labels.getOrElse(s.label) { "" },
                left = left,
                right = right.coerceAtLeast(left + 1f),
                confidence = s.probSum / s.count
            )
        }.filter { it.text.isNotEmpty() }
        return RecLine(chars)
    }

    /** A token to show in the UI: one CJK char, or one Latin/number word. Spaces only separate. */
    data class TokenSpan(val text: String, val left: Float, val right: Float, val isCjk: Boolean)

    fun groupIntoTokens(line: RecLine): List<TokenSpan> {
        val out = mutableListOf<TokenSpan>()
        var word = StringBuilder()
        var wordLeft = 0f
        var wordRight = 0f
        fun flush() {
            if (word.isNotEmpty()) out.add(TokenSpan(word.toString(), wordLeft, wordRight, false))
            word = StringBuilder()
        }
        for (c in line.chars) {
            when {
                c.text.isBlank() -> flush()
                isCjk(c.text) -> { flush(); out.add(TokenSpan(c.text, c.left, c.right, true)) }
                else -> {
                    if (word.isEmpty()) wordLeft = c.left
                    word.append(c.text)
                    wordRight = c.right
                }
            }
        }
        flush()
        return out
    }

    fun isCjk(text: String): Boolean = text.any { ch ->
        val code = ch.code
        code in 0x3040..0x309F || code in 0x30A0..0x30FF || code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF || code in 0xFF00..0xFFEF || code in 0x3000..0x303F
    }
}
