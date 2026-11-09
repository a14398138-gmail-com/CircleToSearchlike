package com.example.translate

import android.content.Context
import android.net.Uri
import com.koike.llama.LlamaEngine
import com.koike.llama.PlamoPrompt
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device translation with PLaMo 3 Translate 0.8B via llama.cpp.
 * Used by the in-app translate bar (selected OCR text -> translation).
 *
 * The native engine is a process-wide singleton: the screen (and this
 * controller) can be recreated while the process lives, and loading a
 * second 550MB model + context in the same process risks an OOM abort.
 * All native calls are serialized through one mutex.
 */
class TranslateController(context: Context) {
    private val appContext = context.applicationContext
    val modelFile: File = File(appContext.filesDir, MODEL_FILE_NAME)

    val handle: Long
        get() = sharedHandle

    fun modelExists(): Boolean =
        modelFile.exists() && modelFile.length() == EXPECTED_MODEL_BYTES

    fun modelFilePresent(): Boolean = modelFile.exists() && modelFile.length() > 100_000_000

    suspend fun loadModel(): LoadResult = engineMutex.withLock {
        withContext(Dispatchers.Default) {
            if (sharedHandle != 0L) return@withContext LoadResult.Ok
            if (!LlamaEngine.loadLibrary()) {
                return@withContext LoadResult.Error("ライブラリ読込失敗: ${LlamaEngine.libraryError}")
            }
            val h = try {
                sharedEngine.nativeLoad(modelFile.absolutePath, 6, 4096)
            } catch (t: Throwable) {
                -1L
            }
            // Android heap pointers carry a 0xB4 tag byte, so a valid handle is
            // negative as a signed Long. Only 0 and the reserved codes are errors.
            when (h) {
                0L -> LoadResult.Error("モデル読み込み失敗" + errorDetailSuffix())
                -2L -> LoadResult.Error("モデルファイルを読めませんでした" + errorDetailSuffix())
                -3L -> LoadResult.Error("コンテキスト作成に失敗しました" + errorDetailSuffix())
                -1L -> LoadResult.Error("モデル読み込みで例外が発生しました" + errorDetailSuffix())
                else -> {
                    sharedHandle = h
                    LoadResult.Ok
                }
            }
        }
    }

    /** Streaming variant: [onPartial] receives the accumulated translation as it grows. */
    suspend fun translateStream(
        text: String,
        srcLang: String,
        tgtLang: String,
        onPartial: (String) -> Unit,
    ): TranslateResult = engineMutex.withLock {
        withContext(Dispatchers.Default) {
            val h = sharedHandle
            if (h == 0L) return@withContext TranslateResult.Error("モデル未読み込み")
            val prompt = PlamoPrompt.build(text, tgtLang, srcLang)
            val acc = StringBuilder()
            val result = try {
                sharedEngine.nativeTranslateStream(h, prompt, 1024) { piece ->
                    acc.append(piece)
                    onPartial(acc.toString())
                }
            } catch (t: Throwable) {
                null
            }
            if (result == null) {
                TranslateResult.Error("翻訳に失敗しました（テキストが長すぎる可能性があります）" + errorDetailSuffix())
            } else {
                TranslateResult.Ok(result.trim(), null)
            }
        }
    }

    /** Tail of the llama.cpp log captured natively; empty when unavailable. */
    fun lastErrorDetail(): String = try {
        sharedEngine.nativeLastError().trim().takeLast(400)
    } catch (t: Throwable) {
        ""
    }

    private fun errorDetailSuffix(): String {
        val d = lastErrorDetail()
        return if (d.isBlank()) "" else "\n詳細: $d"
    }

    /**
     * Frees the native model/context (about 550MB) so low-memory pressure can
     * evict it. The next [loadModel] call transparently reloads from disk.
     * Note on cancellation: a *queued* call behind [engineMutex] is
     * cancellable, but a native generation already running cannot be
     * interrupted — it holds the mutex until it finishes (max 1024 tokens).
     */
    suspend fun releaseModel() = engineMutex.withLock {
        val h = sharedHandle
        if (h != 0L) {
            sharedHandle = 0L
            withContext(Dispatchers.Default) {
                try { sharedEngine.nativeFree(h) } catch (_: Throwable) {}
            }
        }
    }

    suspend fun downloadModel(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.connect()
            if (conn.responseCode !in 200..299) return@withContext false
            val total = conn.contentLengthLong
            val tmp = File(modelFile.parentFile, modelFile.name + ".part")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var readTotal = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        readTotal += n
                        if (total > 0) onProgress(readTotal.toFloat() / total)
                    }
                }
            }
            if (tmp.length() != EXPECTED_MODEL_BYTES) {
                tmp.delete()
                return@withContext false
            }
            if (modelFile.exists()) modelFile.delete()
            tmp.renameTo(modelFile)
            modelExists()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun importModel(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                modelFile.outputStream().use { out -> input.copyTo(out) }
            }
            modelExists()
        } catch (e: Exception) {
            false
        }
    }

    sealed interface LoadResult {
        object Ok : LoadResult
        data class Error(val message: String) : LoadResult
    }

    sealed interface TranslateResult {
        data class Ok(val text: String, val stats: LlamaEngine.Stats?) : TranslateResult
        data class Error(val message: String) : TranslateResult
    }

    companion object {
        // Process-wide native engine state (see class doc).
        private val engineMutex = Mutex()
        private val sharedEngine = LlamaEngine()
        @Volatile
        private var sharedHandle: Long = 0L

        const val MODEL_FILE_NAME = "plamo-3-translate-0.8b-Q4_0.gguf"
        const val MODEL_URL =
            "https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF/resolve/main/plamo-3-translate-0.8b-Q4_0.gguf"
        const val EXPECTED_MODEL_BYTES = 547_532_672L

        /**
         * Language guess for OCR text by script majority: Japanese only when
         * kana+kanji outnumber Latin letters, so a stray kana inside an
         * English passage no longer flips the direction.
         */
        fun detectLang(text: String): String {
            var kana = 0
            var cjk = 0
            var latin = 0
            for (ch in text) {
                when {
                    ch in 'ぁ'..'ゖ' || ch in 'ァ'..'ヶ' || ch == 'ー' -> kana++
                    ch in '一'..'龯' -> cjk++
                    ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
                }
            }
            return if (kana + cjk > latin) "ja" else "en"
        }

        fun langLabel(code: String): String = if (code == "ja") "日本語" else "English"
    }
}
