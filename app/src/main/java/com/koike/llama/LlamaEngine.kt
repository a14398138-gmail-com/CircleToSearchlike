package com.koike.llama

/** Minimal llama.cpp JNI wrapper shared by the PLaMo translate prototypes. */
class LlamaEngine {
    external fun nativeLoad(path: String, threads: Int, ctxLen: Int): Long
    external fun nativeTranslateStream(handle: Long, prompt: String, maxTokens: Int, callback: TokenCallback): String?

    fun interface TokenCallback {
        fun onToken(piece: String)
    }
    external fun nativeStats(handle: Long): String
    external fun nativeLastError(): String
    external fun nativeFree(handle: Long)

    data class Stats(
        val promptTokens: Int,
        val genTokens: Int,
        val prefillMs: Double,
        val decodeMs: Double,
    ) {
        val prefillTokPerSec: Double
            get() = if (prefillMs > 0) promptTokens * 1000.0 / prefillMs else 0.0
        val decodeTokPerSec: Double
            get() = if (decodeMs > 0) genTokens * 1000.0 / decodeMs else 0.0
    }

    fun parseStats(raw: String): Stats {
        val p = raw.split(",")
        return Stats(
            promptTokens = p.getOrNull(0)?.toIntOrNull() ?: 0,
            genTokens = p.getOrNull(1)?.toIntOrNull() ?: 0,
            prefillMs = p.getOrNull(2)?.toDoubleOrNull() ?: 0.0,
            decodeMs = p.getOrNull(3)?.toDoubleOrNull() ?: 0.0,
        )
    }

    companion object {
        @Volatile
        var libraryLoaded: Boolean = false
            private set

        @Volatile
        var libraryError: String? = null
            private set

        /** Explicit, catchable library load (diagnostics: never call from class init). */
        fun loadLibrary(): Boolean {
            if (libraryLoaded) return true
            return try {
                System.loadLibrary("plamo_llama")
                libraryLoaded = true
                true
            } catch (t: Throwable) {
                libraryError = "${t.javaClass.simpleName}: ${t.message}"
                false
            }
        }
    }
}
