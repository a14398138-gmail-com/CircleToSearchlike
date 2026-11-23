package com.example.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * High-accuracy text-line recognizer (PaddleOCR PP-OCRv5_server_rec, ONNX Runtime).
 *
 * Recognition only: callers pass an image of ONE text line (e.g. cut out using ML Kit's
 * line box). On decorated TV captions this reads characters that ML Kit and the mobile
 * PaddleOCR model confuse.
 *
 * The model (~85 MB) is not bundled in the APK; [ensureModel] downloads it on first use.
 */
class ServerTextRecognizer(context: Context) {

    companion object {
        private const val TAG = "ServerTextRecognizer"

        private const val BASE_URL = "https://huggingface.co/PaddlePaddle/PP-OCRv5_server_rec_onnx/resolve/main/"
        const val MODEL_URL = BASE_URL + "inference.onnx"
        const val CONFIG_URL = BASE_URL + "inference.yml"

        private const val INPUT_HEIGHT = 48
        private const val MIN_INPUT_WIDTH = 48
        private const val MAX_INPUT_WIDTH = 3200
    }

    enum class State { NOT_DOWNLOADED, DOWNLOADING, READY, FAILED }

    private val dir = File(context.filesDir, "ocr/ppocrv5_server_rec")
    private val modelFile = File(dir, "inference.onnx")
    private val configFile = File(dir, "inference.yml")

    @Volatile var state: State = if (modelFile.isFile && configFile.isFile) State.READY else State.NOT_DOWNLOADED
        private set

    @Volatile var downloadProgress: Float = 0f
        private set

    private val downloadMutex = Mutex()
    private val sessionMutex = Mutex()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var dict: List<String> = emptyList()

    /** Downloads model + config if missing. Returns true when the model is usable. */
    suspend fun ensureModel(onProgress: (Float) -> Unit = {}): Boolean = downloadMutex.withLock {
        if (modelFile.isFile && configFile.isFile) {
            state = State.READY
            return@withLock true
        }
        state = State.DOWNLOADING
        withContext(Dispatchers.IO) {
            try {
                dir.mkdirs()
                download(CONFIG_URL, configFile) { }
                download(MODEL_URL, modelFile) { p ->
                    downloadProgress = p
                    onProgress(p)
                }
                state = State.READY
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Model download failed", e)
                configFile.delete()
                modelFile.delete()
                state = State.FAILED
                false
            }
        }
    }

    private fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        var conn = URL(url).openConnection() as HttpURLConnection
        // Follow redirects manually (Hugging Face redirects to a CDN host).
        var redirects = 0
        while (true) {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                val loc = conn.getHeaderField("Location") ?: error("Redirect without Location")
                conn.disconnect()
                conn = URL(URL(url), loc).openConnection() as HttpURLConnection
                redirects++
                continue
            }
            if (code != 200) error("HTTP $code for $url")
            break
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastReported = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastReported) { lastReported = pct; onProgress(pct / 100f) }
                    }
                }
            }
        }
        conn.disconnect()
        if (!tmp.renameTo(dest)) error("Failed to move ${tmp.name}")
    }

    private fun openSessionIfNeeded(): OrtSession {
        session?.let { return it }
        val yaml = configFile.readText()
        dict = PaddleRecPostProcess.parseCharacterDictFromYaml(yaml)
        check(dict.isNotEmpty()) { "character_dict not found in ${configFile.name}" }
        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val s = e.createSession(modelFile.absolutePath, opts)
        env = e
        session = s
        Log.d(TAG, "Session ready: dict=${dict.size} inputs=${s.inputNames} outputs=${s.outputNames}")
        return s
    }

    /**
     * Recognizes a single text line. Returned character x positions are in [line]'s pixels.
     * Returns null if the model isn't downloaded or inference fails.
     */
    suspend fun recognizeLine(line: Bitmap): PaddleRecPostProcess.RecLine? {
        if (state != State.READY || line.width < 2 || line.height < 2) return null
        return sessionMutex.withLock {
            withContext(Dispatchers.Default) {
                try {
                    val s = openSessionIfNeeded()
                    val e = env ?: return@withContext null
                    runInference(e, s, line)
                } catch (t: Throwable) {
                    Log.e(TAG, "Recognition failed", t)
                    null
                }
            }
        }
    }

    private fun runInference(env: OrtEnvironment, session: OrtSession, line: Bitmap): PaddleRecPostProcess.RecLine {
        val ratio = line.width.toFloat() / line.height
        val inputW = ceil(INPUT_HEIGHT * ratio).toInt().coerceIn(MIN_INPUT_WIDTH, MAX_INPUT_WIDTH)
        // Keep aspect: if clamped to MAX width, the text is squeezed; fine for a rare very long line.
        val contentW = (INPUT_HEIGHT * ratio).roundToInt().coerceIn(1, inputW)

        val scaled = Bitmap.createScaledBitmap(line, contentW, INPUT_HEIGHT, true)
        val pixels = IntArray(contentW * INPUT_HEIGHT)
        scaled.getPixels(pixels, 0, contentW, 0, 0, contentW, INPUT_HEIGHT)
        if (scaled != line) scaled.recycle()

        // NCHW, BGR channel order (PaddleOCR reads images with OpenCV), normalized to [-1, 1].
        // Columns beyond contentW stay 0 (= padding, same as PaddleOCR's zero pad after normalize).
        val plane = INPUT_HEIGHT * inputW
        val data = FloatArray(3 * plane)
        for (y in 0 until INPUT_HEIGHT) {
            for (x in 0 until contentW) {
                val p = pixels[y * contentW + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val idx = y * inputW + x
                data[idx] = (b / 255f - 0.5f) / 0.5f
                data[plane + idx] = (g / 255f - 0.5f) / 0.5f
                data[2 * plane + idx] = (r / 255f - 0.5f) / 0.5f
            }
        }

        val inputName = session.inputNames.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, INPUT_HEIGHT.toLong(), inputW.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val shape = out.info.shape // [1, T, C]
                val timesteps = shape[1].toInt()
                val numClasses = shape[2].toInt()
                val buf = out.floatBuffer
                val probs = FloatArray(buf.remaining())
                buf.get(probs)
                val labels = PaddleRecPostProcess.buildLabels(dict, numClasses)
                // The model sees inputW columns; only contentW of them are the line image.
                val decoded = PaddleRecPostProcess.ctcDecode(probs, timesteps, numClasses, labels, inputW.toFloat())
                val toLine = line.width.toFloat() / contentW
                return PaddleRecPostProcess.RecLine(
                    decoded.chars.map { c ->
                        c.copy(
                            left = (c.left * toLine).coerceIn(0f, line.width.toFloat()),
                            right = (c.right * toLine).coerceIn(0f, line.width.toFloat())
                        )
                    }
                )
            }
        }
    }

    fun close() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
    }
}
