package com.lingxi.app.pipeline

import android.net.Uri
import com.lingxi.app.ui.screens.CaptureProgress
import com.lingxi.core.model.repository.CaptureRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 在应用级协程中依次处理新照片，离开捕获页也不会中断；进度通过 [progress] 显示在捕获页。
 * 同一时间只处理一张，避免同一课次被并发整理。
 */
class CaptureRunner(
    private val scope: CoroutineScope,
    private val pipeline: CapturePipeline,
) {
    private val _progress = MutableStateFlow<List<CaptureProgress>>(emptyList())
    val progress: StateFlow<List<CaptureProgress>> = _progress
    private val serial = Mutex()
    private var counter = 0

    fun submitFile(
        file: File,
        courseId: String,
    ) = submit { pipeline.register(file, courseId) }

    fun submitUri(
        uri: Uri,
        courseId: String,
    ) = submit { pipeline.import(uri, courseId) }

    fun retry(lessonId: String) =
        scope.launch {
            serial.withLock {
                val id = "retry-$lessonId"
                set(CaptureProgress(id, "重新整理", "整理中…"))
                val text =
                    when (val o = pipeline.organizeLesson(lessonId)) {
                        is OrganizeOutcome.Done -> "完成：${o.points} 个知识点，${o.cards} 张卡片"
                        is OrganizeOutcome.Pending -> "待整理：${o.reason}"
                        is OrganizeOutcome.Failed -> "失败：${o.reason}"
                    }
                set(CaptureProgress(id, "重新整理", text, done = true))
            }
        }

    private fun submit(register: suspend () -> CaptureRecord) {
        val label = "第 ${++counter} 张板书"
        val key = "pending-$counter"
        set(CaptureProgress(key, label, "保存中…"))
        scope.launch {
            serial.withLock {
                val record =
                    runCatching { register() }.getOrElse {
                        set(CaptureProgress(key, label, "保存失败：${it.message}", done = true, failed = true))
                        return@withLock
                    }
                set(CaptureProgress(key, label, "手机本地识别文字 → 云端整理笔记与卡片…"))
                val outcome = pipeline.process(record)
                val seconds = "%.1f".format(outcome.totalMs / 1000.0)
                val stage =
                    when (val o = outcome.organize) {
                        is OrganizeOutcome.Done ->
                            "完成：识别 ${outcome.ocrLines} 行（${outcome.ocrMs} ms）→ ${o.points} 个知识点、${o.cards} 张卡片，用时 $seconds 秒"
                        is OrganizeOutcome.Pending -> "已识别 ${outcome.ocrLines} 行，待整理：${o.reason}"
                        is OrganizeOutcome.Failed -> "失败：${o.reason}"
                    }
                set(CaptureProgress(key, label, stage, done = true, failed = outcome.organize is OrganizeOutcome.Failed))
            }
        }
    }

    private fun set(p: CaptureProgress) {
        _progress.update { list -> (list.filterNot { it.id == p.id } + p).takeLast(MAX_SHOWN) }
    }

    private companion object {
        const val MAX_SHOWN = 4
    }
}
