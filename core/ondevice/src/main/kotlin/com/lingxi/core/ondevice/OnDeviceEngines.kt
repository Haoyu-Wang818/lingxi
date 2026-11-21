package com.lingxi.core.ondevice

/**
 * 一行 OCR 结果。
 *
 * @property confidence 识别置信度 0–1，页面平均值低于 0.6 时可回退云端 [FR-NOTE-01]。
 * @property box 文字框 [左, 上, 右, 下]（原图像素），用于溯源时在原图上高亮 [FR-NOTE-05]。
 */
data class OcrLine(
    val text: String,
    val confidence: Double,
    val box: List<Int> = emptyList(),
)

/**
 * 一页图片的识别结果。
 *
 * @property width 原图宽度（像素），文字框以此为坐标系。
 * @property elapsedMs 端侧识别耗时，写入 AI 执行记录 [FR-AI-04]。
 */
data class OcrPage(
    val lines: List<OcrLine>,
    val width: Int,
    val height: Int,
    val elapsedMs: Long,
    val engine: String,
) {
    /** 按阅读顺序合并的全文。 */
    val text: String get() = lines.joinToString("\n") { it.text }

    /** 平均置信度，没有文字时为 0。 */
    val confidence: Double get() = if (lines.isEmpty()) 0.0 else lines.sumOf { it.confidence } / lines.size
}

/**
 * 端侧文字识别引擎 [FR-NOTE-01]。图片不出手机（ADR 0003）。
 */
interface OcrEngine {
    suspend fun recognize(imagePath: String): OcrPage
}

/** 一段转写结果，时间用于溯源到录音片段 [FR-NOTE-05]。 */
data class AsrSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * 端侧离线语音识别引擎（计划使用 sherpa-onnx）[FR-NOTE-02]。音频不出手机（NFR-PRIV-01）。
 */
interface AsrEngine {
    suspend fun transcribe(audioPath: String): List<AsrSegment>
}
