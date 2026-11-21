package com.lingxi.app.pipeline

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lingxi.core.ai.AiTask
import com.lingxi.core.ai.AiTaskType
import com.lingxi.core.ai.DeviceStateProvider
import com.lingxi.core.ai.RouteDecision
import com.lingxi.core.ai.RoutingPolicy
import com.lingxi.core.model.ErrorCoded
import com.lingxi.core.model.ReviewCard
import com.lingxi.core.model.SourceRef
import com.lingxi.core.model.repository.AiExecution
import com.lingxi.core.model.repository.AiExecutionRepository
import com.lingxi.core.model.repository.CaptureKind
import com.lingxi.core.model.repository.CaptureRecord
import com.lingxi.core.model.repository.CaptureRepository
import com.lingxi.core.model.repository.CaptureStatus
import com.lingxi.core.model.repository.CardRepository
import com.lingxi.core.model.repository.CourseRepository
import com.lingxi.core.model.repository.KnowledgeRepository
import com.lingxi.core.model.repository.LessonNote
import com.lingxi.core.model.repository.NoteRepository
import com.lingxi.core.model.repository.NoteStatus
import com.lingxi.core.model.repository.PendingTaskRepository
import com.lingxi.core.model.repository.PendingTaskType
import com.lingxi.core.model.repository.RecognizedText
import com.lingxi.core.model.repository.TextLine
import com.lingxi.core.network.ExtractRequest
import com.lingxi.core.network.GatewayApi
import com.lingxi.core.network.GatewayError
import com.lingxi.core.network.TextSegment
import com.lingxi.core.ondevice.OcrEngine
import kotlinx.coroutines.CancellationException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 一次整理的结果，界面据此显示进度与用时。 */
sealed interface OrganizeOutcome {
    /** 成功：生成了笔记、知识点与卡片。 */
    data class Done(
        val noteTitle: String,
        val points: Int,
        val cards: Int,
        val cloudMs: Long,
    ) : OrganizeOutcome

    /** 暂时无法整理（断网、隐私设置不允许、云端暂时失败），已进入待办队列 [FR-AI-05]。 */
    data class Pending(
        val reason: String,
    ) : OrganizeOutcome

    /** 无法自动恢复的失败（例如网关未配置 API Key），笔记标记为失败，可手动重试 [FR-NOTE-06]。 */
    data class Failed(
        val reason: String,
    ) : OrganizeOutcome
}

/** 一张板书从拍照到卡片的结果。 */
data class CaptureOutcome(
    val captureId: String,
    val lessonId: String,
    val ocrLines: Int,
    val ocrMs: Long,
    val organize: OrganizeOutcome,
    val totalMs: Long,
)

/**
 * 课堂内容到卡片的流水线（06-detailed-design.md 2.1 节）[FR-CAP-06]、[FR-NOTE-01]、[FR-NOTE-03]、[FR-KG-01]、[FR-REV-01]、[FR-AI-05]：
 *
 * 照片存入私有目录并写 capture → 端侧 OCR，写 recognized_text（含文字框）→ 路由判断能否上云 →
 * 网关一次生成笔记、知识点、关系与卡片 → 合并知识点（事务）→ 写卡片（带来源与文字框）。
 * 每一步都写入 AI 执行记录 [FR-AI-04]；不能上云或云端失败时进入待办队列，由 [OrganizeWorker] 联网后重试。
 */
class CapturePipeline(
    private val context: Context,
    private val captures: CaptureRepository,
    private val courses: CourseRepository,
    private val knowledge: KnowledgeRepository,
    private val cards: CardRepository,
    private val notes: NoteRepository,
    private val pending: PendingTaskRepository,
    private val executions: AiExecutionRepository,
    private val ocr: OcrEngine,
    private val gateway: suspend () -> GatewayApi,
    private val policy: RoutingPolicy,
    private val deviceState: DeviceStateProvider,
    private val scheduleRetry: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val filesDir: File get() = context.filesDir

    /** 新照片的保存目录（按日期分子目录）。 */
    fun captureDir(nowMillis: Long = clock()): File = File(filesDir, "captures/${DAY.format(Date(nowMillis))}")

    fun absolute(relativePath: String) = File(filesDir, relativePath)

    /** 把已在私有目录中的照片登记为捕获，归档到课程的当前课次 [FR-CAP-06]。 */
    suspend fun register(
        file: File,
        courseId: String,
    ): CaptureRecord {
        val now = clock()
        val lesson = captures.lessonFor(courseId, now)
        val record =
            CaptureRecord(UUID.randomUUID().toString(), lesson, CaptureKind.PHOTO, file.relativeTo(filesDir).path, CaptureStatus.NEW, now)
        captures.addCapture(record)
        return record
    }

    /** 从相册导入：复制到私有目录后登记 [FR-CAP-03]。 */
    suspend fun import(
        uri: Uri,
        courseId: String,
    ): CaptureRecord {
        val dir = captureDir().apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取图片" }.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return register(file, courseId)
    }

    /** 处理一张已登记的照片：识别后整理所在课次。 */
    suspend fun process(record: CaptureRecord): CaptureOutcome {
        val start = clock()
        val page =
            try {
                ocr.recognize(absolute(record.filePath).path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                captures.setStatus(record.id, CaptureStatus.FAILED)
                record(AiExecution("OCR", "ON_DEVICE", "端侧识别板书，图片不出手机", start, clock() - start, false, e::class.simpleName, record.id))
                return CaptureOutcome(record.id, record.lessonId, 0, clock() - start, OrganizeOutcome.Failed("识别失败：${e.message}"), clock() - start)
            }
        val decision = policy.decide(AiTask(AiTaskType.OCR, 0, containsPersonalData = true), deviceState.current())
        record(AiExecution("OCR", "ON_DEVICE", decision.reasons.joinToString("；"), start, page.elapsedMs, true, null, record.id))
        captures.saveRecognized(
            RecognizedText(
                id = UUID.randomUUID().toString(),
                captureId = record.id,
                engine = page.engine,
                location = "ON_DEVICE",
                text = page.text,
                confidence = page.confidence,
                lines = page.lines.map { TextLine(it.text, it.confidence, it.box) },
            ),
        )
        captures.setStatus(record.id, CaptureStatus.RECOGNIZED)
        val organize = organizeLesson(record.lessonId)
        val total = clock() - start
        // 端到端耗时（拍照完成 → 卡片写入），供 NFR-PERF-01 评测脚本从 logcat 读取。
        Log.i(TAG, "e2e capture=${record.id} ocr_ms=${page.elapsedMs} lines=${page.lines.size} total_ms=$total outcome=${organize::class.simpleName}")
        return CaptureOutcome(record.id, record.lessonId, page.lines.size, page.elapsedMs, organize, total)
    }

    /**
     * 整理一个课次：合并该课次所有已识别的照片，经网关生成笔记、知识点与卡片并写库。
     * 待办队列的重试也调用这里。
     */
    suspend fun organizeLesson(lessonId: String): OrganizeOutcome {
        val courseId = captures.courseOfLesson(lessonId) ?: return OrganizeOutcome.Failed("课次不存在")
        val course = courses.course(courseId)
        val lessonCaptures = captures.captures(lessonId)
        val linesByCapture = lessonCaptures.associate { c -> c.id to captures.recognized(c.id).flatMap { it.lines } }
        // 每行一个文本段，id 为「捕获id#L行号」，模型返回的来源 id 可以精确定位到原图文字框。
        val segments =
            linesByCapture.flatMap { (captureId, lines) ->
                lines.mapIndexed { i, line -> TextSegment("$captureId#L${i + 1}", "OCR", line.text, page = 1) }
            }
        if (segments.isEmpty()) return OrganizeOutcome.Failed("没有识别出文字")
        val chars = segments.sumOf { it.text.length }
        val start = clock()
        val decision = policy.decide(AiTask(AiTaskType.EXTRACT, chars, containsPersonalData = true), deviceState.current())
        if (decision is RouteDecision.Unavailable) {
            val reason = decision.reasons.joinToString("；")
            record(AiExecution("EXTRACT", "UNAVAILABLE", reason, start, null, false, "DEFERRED", lessonId))
            return defer(lessonId, reason)
        }
        val existing = knowledge.graph(courseId).points.map { it.title }
        val request = ExtractRequest(course?.name.orEmpty(), lessonId, segments, existing)
        val result = gateway().organize(request)
        val reason = decision.reasons.joinToString("；")
        val error = result.exceptionOrNull()
        record(AiExecution("EXTRACT", "CLOUD", reason, start, clock() - start, result.isSuccess, (error as? ErrorCoded)?.code ?: error?.let { it::class.simpleName }, lessonId))
        if (error != null) {
            val retryable = (error as? GatewayError)?.retryable ?: true
            return if (retryable) defer(lessonId, "云端暂时不可用：${error.message}") else fail(lessonId, error.message ?: "整理失败")
        }
        val organized = result.getOrThrow()
        val now = clock()
        val merge = knowledge.mergeExtraction(lessonId, organized.extraction)
        val newCards =
            organized.cards.mapNotNull { generated ->
                val kp = merge.idMap[generated.card.knowledgePointId] ?: return@mapNotNull null
                generated.card.copy(id = UUID.randomUUID().toString(), knowledgePointId = kp, sourceRef = sourceRef(generated.sourceIds, linesByCapture))
            }
        cards.upsertCards(newCards, now)
        val note = organized.note
        notes.save(
            LessonNote(
                id = UUID.randomUUID().toString(),
                lessonId = lessonId,
                title = note?.title ?: organized.extraction.points.firstOrNull()?.title ?: "课堂笔记",
                markdown = note?.markdown ?: organized.extraction.points.joinToString("\n") { "- **${it.title}**：${it.summary}" },
                status = NoteStatus.READY,
                promptVersion = organized.promptVersion,
                updatedAtMillis = now,
            ),
        )
        lessonCaptures.forEach { captures.setStatus(it.id, CaptureStatus.ORGANIZED) }
        return OrganizeOutcome.Done(note?.title ?: "课堂笔记", organized.extraction.points.size, newCards.size, organized.latencyMs)
    }

    /** 来源：第一个来源段所在的照片，文字框取该照片上全部来源行的外接矩形。 */
    private fun sourceRef(
        sourceIds: List<String>,
        linesByCapture: Map<String, List<TextLine>>,
    ): SourceRef? {
        val parsed = sourceIds.mapNotNull { id -> id.split("#L").takeIf { it.size == 2 }?.let { it[0] to it[1].toIntOrNull() } }
        val captureId = parsed.firstOrNull()?.first ?: return null
        val boxes =
            parsed
                .filter { it.first == captureId }
                .mapNotNull { (_, line) -> line?.let { linesByCapture[captureId]?.getOrNull(it - 1)?.box } }
                .filter { it.size == 4 }
        val box =
            if (boxes.isEmpty()) emptyList() else listOf(boxes.minOf { it[0] }, boxes.minOf { it[1] }, boxes.maxOf { it[2] }, boxes.maxOf { it[3] })
        return SourceRef(captureId, page = 1, box = box)
    }

    private suspend fun defer(
        lessonId: String,
        reason: String,
    ): OrganizeOutcome {
        pending.enqueue(PendingTaskType.EXTRACT, lessonId, "{\"lessonId\":\"$lessonId\"}", clock())
        markNote(lessonId, NoteStatus.PENDING, "待整理")
        scheduleRetry()
        return OrganizeOutcome.Pending(reason)
    }

    private suspend fun fail(
        lessonId: String,
        reason: String,
    ): OrganizeOutcome {
        markNote(lessonId, NoteStatus.FAILED, "整理失败")
        return OrganizeOutcome.Failed(reason)
    }

    /** 还没有笔记时写一条占位笔记，笔记列表里能看到「待整理 / 整理失败」并手动重试 [FR-NOTE-06]。 */
    private suspend fun markNote(
        lessonId: String,
        status: NoteStatus,
        title: String,
    ) {
        val current = notes.byLesson(lessonId)
        if (current != null && current.status == NoteStatus.READY) return
        val text = captures.captures(lessonId).flatMap { captures.recognized(it.id) }.joinToString("\n\n") { it.text }
        notes.save(LessonNote(UUID.randomUUID().toString(), lessonId, title, "识别原文：\n\n$text", status, null, clock()))
    }

    private suspend fun record(execution: AiExecution) {
        runCatching { executions.record(execution) }
    }

    companion object {
        const val TAG = "LingxiPipeline"
        private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
