package com.lingxi.core.ondevice

/**
 * 一行 OCR 结果。
 *
 * @property confidence 识别置信度 0–1，页面平均值低于 0.6 时可回退云端 [FR-NOTE-01]。
 */
data class OcrLine(
    val text: String,
    val confidence: Double,
    val box: List<Int> = emptyList(),
)

/**
 * 端侧文字识别引擎（计划使用 PaddleOCR + ONNX Runtime）[FR-NOTE-01]。
 *
 * 后续由 `OcrBackend` 包装为 `core:ai` 的 `ModelBackend`，业务代码只通过 `InferenceRouter` 调用。
 */
interface OcrEngine {
    suspend fun recognize(imagePath: String): List<OcrLine>
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
