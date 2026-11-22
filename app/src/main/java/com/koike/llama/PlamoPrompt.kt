package com.koike.llama

/**
 * Builds the PFML3 prompt for PLaMo 3 Translate directly, matching
 * pfnet/plamo-3-translate chat_template.jinja output for a single
 * translation request without few-shot pairs:
 *
 * <|plamo:bos|><|plamo:tag|>dataset<|plamo:msg|>translation
 * <|plamo:tag|>input[<|plamo:key|>lang<|plamo:val|>{source}]
 * <|plamo:msg|>{source text}
 * <|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>{target}<|plamo:msg|>
 */
object PlamoPrompt {
    fun build(sourceText: String, targetLang: String, sourceLang: String? = null): String {
        val sb = StringBuilder()
        sb.append("<|plamo:bos|>")
        sb.append("<|plamo:tag|>dataset<|plamo:msg|>translation")
        sb.append("<|plamo:tag|>input")
        if (sourceLang != null) {
            sb.append("<|plamo:key|>lang<|plamo:val|>").append(sourceLang)
        }
        sb.append("<|plamo:msg|>").append(sourceText)
        sb.append("<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>").append(targetLang)
        sb.append("<|plamo:msg|>")
        return sb.toString()
    }
}
