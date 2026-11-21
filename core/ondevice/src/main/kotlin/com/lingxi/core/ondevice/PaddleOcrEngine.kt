package com.lingxi.core.ondevice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * PaddleOCR PP-OCRv4 mobile（检测 + 识别）的端侧实现，通过 ONNX Runtime 推理（ADR 0003）[FR-NOTE-01]。
 *
 * 模型文件放在 assets/ocr（由 `scripts/fetch_models.sh` 下载，不进版本库）；首次识别时才加载，冷启动不受影响。
 * 预处理与 PaddleOCR 一致：检测输入按 ImageNet 均值方差归一化，识别输入高 48、归一化到 [-1, 1]，通道顺序 BGR。
 */
class PaddleOcrEngine(
    context: Context,
    private val assetDir: String = "ocr",
) : OcrEngine {
    private val appContext = context.applicationContext
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val lock = Mutex()
    private var det: OrtSession? = null
    private var rec: OrtSession? = null
    private var dict: List<String> = emptyList()

    /** 模型文件是否已就位（未运行下载脚本时为 false，调用方可提示用户）。 */
    fun modelsAvailable(): Boolean =
        runCatching { appContext.assets.list(assetDir)?.toSet()?.containsAll(MODEL_FILES) == true }.getOrDefault(false)

    /** 预先加载模型（约 1 秒），在用户打开拍照页时调用，拍完后识别不再等待加载。 */
    suspend fun warmUp() =
        withContext(Dispatchers.Default) {
            lock.withLock { if (modelsAvailable()) load() }
        }

    private fun load() {
        if (det != null) return
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        fun bytes(name: String) = appContext.assets.open("$assetDir/$name").use { it.readBytes() }
        det = env.createSession(bytes("det.onnx"), options)
        rec = env.createSession(bytes("rec.onnx"), options)
        // 字典每行一个字符；PaddleOCR 训练时在末尾追加了空格。
        dict = appContext.assets.open("$assetDir/keys.txt").bufferedReader().readLines() + " "
    }

    override suspend fun recognize(imagePath: String): OcrPage =
        withContext(Dispatchers.Default) {
            lock.withLock {
                val start = System.currentTimeMillis()
                load()
                val original = decode(imagePath)
                val pixels = IntArray(original.width * original.height).also {
                    original.getPixels(it, 0, original.width, 0, 0, original.width, original.height)
                }
                // 深色板书先反色，模型在浅底深字上训练。
                val image = if (OcrPostProcess.isDarkBackground(pixels)) invert(original) else original
                val boxes = detect(image)
                val lines =
                    boxes.mapNotNull { box ->
                        val (text, conf) = recognizeLine(image, box)
                        text
                            .trim()
                            .takeIf { it.isNotEmpty() && conf >= MIN_LINE_CONFIDENCE }
                            ?.let { OcrLine(it, conf.toDouble(), box.toList()) }
                    }
                OcrPage(lines, original.width, original.height, System.currentTimeMillis() - start, ENGINE)
            }
        }

    /** 解码图片；超过 [MAX_DECODE_SIDE] 时按 2 的幂降采样，避免大照片占满内存。 */
    private fun decode(path: String): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        require(bounds.outWidth > 0) { "无法读取图片：$path" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return requireNotNull(BitmapFactory.decodeFile(path, opts)) { "无法读取图片：$path" }
    }

    private fun invert(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val matrix = ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        return out
    }

    private fun detect(image: Bitmap): List<Box> {
        val (w, h) = OcrPostProcess.detInputSize(image.width, image.height)
        val scaled = Bitmap.createScaledBitmap(image, w, h, true)
        val input = toTensor(scaled, DET_MEAN, DET_STD)
        val session = requireNotNull(det)
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val out = result[0] as OnnxTensor
                val shape = out.info.shape // [1, 1, H, W]
                val prob = FloatArray(out.floatBuffer.remaining()).also { out.floatBuffer.get(it) }
                return OcrPostProcess.detectBoxes(prob, shape[3].toInt(), shape[2].toInt(), image.width, image.height)
            }
        }
    }

    private fun recognizeLine(
        image: Bitmap,
        box: Box,
    ): Pair<String, Float> {
        val crop = Bitmap.createBitmap(image, box.left, box.top, box.width, box.height)
        val w = OcrPostProcess.recInputWidth(box.width, box.height, REC_HEIGHT)
        val scaled = Bitmap.createScaledBitmap(crop, w, REC_HEIGHT, true)
        val input = toTensor(scaled, REC_MEAN, REC_STD)
        val session = requireNotNull(rec)
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, REC_HEIGHT.toLong(), w.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val out = result[0] as OnnxTensor
                val shape = out.info.shape // [1, T, C]
                val probs = FloatArray(out.floatBuffer.remaining()).also { out.floatBuffer.get(it) }
                return OcrPostProcess.ctcDecode(probs, shape[1].toInt(), shape[2].toInt(), dict)
            }
        }
    }

    /** 位图 → NCHW 浮点张量，通道顺序 BGR，按 (x/255 − mean) / std 归一化。 */
    private fun toTensor(
        bitmap: Bitmap,
        mean: FloatArray,
        std: FloatArray,
    ): FloatBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        val buf = FloatBuffer.allocate(3 * w * h)
        val plane = w * h
        for (i in pixels.indices) {
            val c = pixels[i]
            val b = (c and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val r = ((c shr 16) and 0xFF) / 255f
            buf.put(i, (b - mean[0]) / std[0])
            buf.put(plane + i, (g - mean[1]) / std[1])
            buf.put(2 * plane + i, (r - mean[2]) / std[2])
        }
        return buf
    }

    companion object {
        const val ENGINE = "PP-OCRv4"
        private val MODEL_FILES = setOf("det.onnx", "rec.onnx", "keys.txt")
        private const val MAX_DECODE_SIDE = 2400
        private const val REC_HEIGHT = 48
        private const val MIN_LINE_CONFIDENCE = 0.5f

        // 与 PaddleOCR 一致：OpenCV 读入的 BGR 图像直接按 [0.485, 0.456, 0.406] / [0.229, 0.224, 0.225] 归一化。
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
    }
}
