package com.lingxi.core.model

/** 一次课堂捕获的原始素材。 */
sealed interface CaptureSource {
    /** 素材在设备上的位置（content:// 或文件路径）。 */
    val uri: String

    /** 板书、PPT 或教材照片。 */
    data class Photo(
        override val uri: String,
    ) : CaptureSource

    /** 课堂录音片段。 */
    data class Audio(
        override val uri: String,
        val durationMs: Long,
    ) : CaptureSource {
        init {
            require(durationMs > 0) { "录音时长必须大于 0" }
        }
    }

    /** 导入的课件 PDF。 */
    data class Pdf(
        override val uri: String,
        val pageCount: Int,
    ) : CaptureSource
}

/**
 * 一条课堂笔记：由若干素材经 OCR / 语音识别 / 大模型整理后得到。
 *
 * @property recognizedText OCR 与语音识别合并后的原文，保留用于溯源。
 * @property structuredMarkdown 大模型整理后的结构化笔记。
 */
data class Note(
    val id: String,
    val courseId: String,
    val createdAtMillis: Long,
    val sources: List<CaptureSource>,
    val recognizedText: String,
    val structuredMarkdown: String,
)
