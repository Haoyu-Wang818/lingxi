package com.lingxi.core.model.repository

/** 捕获素材类型，对应 `capture.kind`。 */
enum class CaptureKind { PHOTO, AUDIO, PDF }

/** 捕获处理状态：NEW → RECOGNIZED → ORGANIZED，任一步失败为 FAILED。 */
enum class CaptureStatus { NEW, RECOGNIZED, ORGANIZED, FAILED }

/**
 * 一条捕获记录（`capture` 表）[FR-CAP-06]。
 *
 * @property filePath 应用私有目录下的相对路径，例如 `captures/2026-11-18/xxx.jpg`。
 */
data class CaptureRecord(
    val id: String,
    val lessonId: String,
    val kind: CaptureKind,
    val filePath: String,
    val status: CaptureStatus,
    val createdAtMillis: Long,
)

/** 识别出的一行文字及其在原图中的位置 [左, 上, 右, 下]。 */
data class TextLine(
    val text: String,
    val confidence: Double,
    val box: List<Int>,
)

/**
 * 一次识别结果（`recognized_text` 表）[FR-NOTE-01]、[FR-NOTE-05]。
 *
 * @property location 执行位置 ON_DEVICE / CLOUD [FR-AI-04]。
 */
data class RecognizedText(
    val id: String,
    val captureId: String,
    val engine: String,
    val location: String,
    val text: String,
    val confidence: Double?,
    val lines: List<TextLine>,
)

/** 捕获与识别结果仓库 [FR-CAP-01]、[FR-CAP-03]、[FR-CAP-06]、[FR-NOTE-01]。 */
interface CaptureRepository {
    /**
     * 返回某课程当前的课次：2 小时内已有课次则复用，否则新建（03-database.md 3.3 节）[FR-CAP-06]。
     */
    suspend fun lessonFor(
        courseId: String,
        nowMillis: Long,
    ): String

    suspend fun addCapture(record: CaptureRecord)

    suspend fun capture(id: String): CaptureRecord?

    suspend fun captures(lessonId: String): List<CaptureRecord>

    suspend fun setStatus(
        captureId: String,
        status: CaptureStatus,
    )

    suspend fun saveRecognized(text: RecognizedText)

    suspend fun recognized(captureId: String): List<RecognizedText>

    /** 课次所属课程，课次不存在时为 null。 */
    suspend fun courseOfLesson(lessonId: String): String?
}

/** 笔记状态：PENDING 待整理（断网或失败，可重试）、READY、FAILED [FR-NOTE-06]。 */
enum class NoteStatus { PENDING, READY, FAILED }

/**
 * 一个课次的笔记（`note` 表）[FR-NOTE-03]、[FR-NOTE-04]。
 *
 * @property originalMarkdown 用户第一次编辑前的版本，保留供对照；未编辑过为 null。
 */
data class LessonNote(
    val id: String,
    val lessonId: String,
    val title: String,
    val markdown: String,
    val status: NoteStatus,
    val promptVersion: String?,
    val updatedAtMillis: Long,
    val originalMarkdown: String? = null,
)

/** 笔记列表中的一行。 */
data class NoteSummary(
    val lessonId: String,
    val courseName: String,
    val title: String,
    val status: NoteStatus,
    val updatedAtMillis: Long,
    val captureCount: Int,
)

/** 笔记仓库 [FR-NOTE-03]、[FR-NOTE-04]、[FR-NOTE-06]。 */
interface NoteRepository {
    /** 写入或替换某课次的笔记（一个课次一份）。 */
    suspend fun save(note: LessonNote)

    suspend fun byLesson(lessonId: String): LessonNote?

    /** 用户编辑笔记：首次编辑时把生成的原文存入 originalMarkdown，之后不再覆盖 [FR-NOTE-04]。 */
    suspend fun edit(
        lessonId: String,
        markdown: String,
        nowMillis: Long,
    )

    /** 全部笔记，新的在前。 */
    suspend fun notes(): List<NoteSummary>
}

/** 待办任务类型（`pending_task.task_type`）。 */
enum class PendingTaskType { EXTRACT, CARDS }

/** 一条待办任务 [FR-AI-05]。 */
data class PendingTask(
    val id: String,
    val type: PendingTaskType,
    val refId: String,
    val payloadJson: String,
    val attempts: Int,
    val nextAttemptAtMillis: Long,
    val lastError: String?,
)

/**
 * 只能上云的任务在断网或失败时进入持久化队列，联网后补做 [FR-AI-05]。
 * 退避 1、2、4、8、16 分钟，最多 [MAX_ATTEMPTS] 次（06-detailed-design.md 3.1 节）。
 */
interface PendingTaskRepository {
    /** 入队；同类型同关联 id 已在队列中时不重复入队，返回 false。 */
    suspend fun enqueue(
        type: PendingTaskType,
        refId: String,
        payloadJson: String,
        nowMillis: Long,
    ): Boolean

    /** 到期可执行的任务。 */
    suspend fun ready(nowMillis: Long): List<PendingTask>

    suspend fun all(): List<PendingTask>

    /** 记一次失败并按退避安排下次；超过上限返回 false（任务移出队列，由调用方标记失败）。 */
    suspend fun recordFailure(
        id: String,
        error: String,
        nowMillis: Long,
    ): Boolean

    suspend fun remove(id: String)

    companion object {
        const val MAX_ATTEMPTS = 5

        /** 第 [attempt] 次失败后的等待时间：1、2、4、8、16 分钟。 */
        fun backoffMillis(attempt: Int): Long = 60_000L shl (attempt - 1).coerceIn(0, 4)
    }
}
