package com.lingxi.core.model.repository

import com.lingxi.core.model.Course
import com.lingxi.core.model.Extraction
import com.lingxi.core.model.KnowledgeEdge
import com.lingxi.core.model.KnowledgePoint
import com.lingxi.core.model.ScheduleSlot
import java.time.LocalDate
import java.time.LocalDateTime

/** 删除课程前提示用户的关联数据量 [FR-COURSE-01]。 */
data class CourseUsage(
    val notes: Int,
    val cards: Int,
)

/**
 * 保存时段的结果 [FR-COURSE-02]：有冲突时不写入，把冲突的时段返回给界面提示。
 */
sealed interface SlotSaveResult {
    data object Saved : SlotSaveResult

    data class Conflict(
        val with: List<ScheduleSlot>,
    ) : SlotSaveResult
}

/** 课程与课表仓库 [FR-COURSE-01]、[FR-COURSE-02]、[FR-COURSE-03]。删除课程级联删除其下全部数据。 */
interface CourseRepository {
    suspend fun courses(): List<Course>

    suspend fun course(id: String): Course?

    /** 新建或更新课程。 */
    suspend fun saveCourse(course: Course)

    /** 删除课程，级联删除时段、课次、笔记、知识点、卡片与复习日志。 */
    suspend fun deleteCourse(id: String)

    /** 删除前提示用：该课程下的笔记与卡片数量。 */
    suspend fun usage(courseId: String): CourseUsage

    /** 某门课的时段；[courseId] 为 null 时返回全部时段。 */
    suspend fun slots(courseId: String? = null): List<ScheduleSlot>

    /** 保存时段；与任何已有时段冲突且 [force] 为 false 时不写入并返回冲突。 */
    suspend fun saveSlot(
        slot: ScheduleSlot,
        force: Boolean = false,
    ): SlotSaveResult

    suspend fun deleteSlot(id: String)

    /** [now] 正在上的课，供捕获页预选与情境引擎「上课中」判断 [FR-COURSE-03]。 */
    suspend fun currentCourse(
        now: LocalDateTime,
        semesterStart: LocalDate,
    ): Course?
}

/** 一门课程的知识图谱快照，供 `feature:graph` 渲染 [FR-KG-04]。 */
data class KnowledgeGraph(
    val points: List<KnowledgePoint>,
    val edges: List<KnowledgeEdge>,
)

/**
 * 一次合并的结果 [FR-KG-02]。
 *
 * @property idMap 抽取结果中的临时 id 到图谱稳定 id 的映射。
 * @property createdIds 本次新建的知识点，后续只为它们生成卡片。
 * @property rejectedEdges 被丢弃的边数量（自环、端点不存在）。
 */
data class MergeResult(
    val idMap: Map<String, String>,
    val createdIds: Set<String>,
    val rejectedEdges: Int = 0,
)

/**
 * 知识点与关系的仓库 [FR-KG-02]、[FR-KG-03]。合并与关系写入在同一事务内完成，中途异常整体回滚（TC-KG-06）。
 */
interface KnowledgeRepository {
    /**
     * 把一次课堂抽取结果合并进该课次所属课程的图谱 [FR-KG-02]。
     * 本迭代按归一化名称去重；向量去重在 I4 接入端侧向量模型后补充。
     */
    suspend fun mergeExtraction(
        lessonId: String,
        extraction: Extraction,
    ): MergeResult

    /** 直接写入知识点与关系（种子数据、导入）。 */
    suspend fun upsert(
        points: List<KnowledgePoint>,
        edges: List<KnowledgeEdge>,
    )

    /** 某门课程的完整图谱（不含软删除的知识点）[FR-KG-04]。 */
    suspend fun graph(courseId: String): KnowledgeGraph

    /** 某知识点的直接前置知识点 [FR-REV-15]。 */
    suspend fun prerequisites(kpId: String): List<KnowledgePoint>

    /** 为课程新建一个课次，返回课次 id；抽取结果按课次归档 [FR-CAP-06]。 */
    suspend fun createLesson(
        courseId: String,
        startedAtMillis: Long,
    ): String
}

/**
 * 一次 AI 调用的执行记录（`ai_execution` 表）[FR-AI-04]：在哪里执行、为什么、耗时与结果。
 * 只记元数据，不含用户内容。
 */
data class AiExecution(
    val taskType: String,
    val location: String,
    val reason: String,
    val startedAtMillis: Long,
    val durationMs: Long?,
    val success: Boolean,
    val errorCode: String? = null,
    val refId: String? = null,
    val id: Long = 0,
)

/** AI 执行记录仓库 [FR-AI-04]。 */
interface AiExecutionRepository {
    suspend fun record(execution: AiExecution): Long

    /** 最近的记录，新的在前，供「路由面板」展示。 */
    suspend fun recent(limit: Int = 50): List<AiExecution>
}

/** 推送渠道与结果 [FR-CTX-05]、[FR-WEAR-05]。 */
enum class PushChannel { NOTIFICATION, DATA_LAYER, RELAY, WEAR_ENGINE }

enum class PushOutcome { ANSWERED, OPENED, DISMISSED, IGNORED, SNOOZED }

/** 一条推送记录（`push_record` 表）。 */
data class PushRecord(
    val id: String,
    val cardId: String,
    val channel: PushChannel,
    val sentAtMillis: Long,
    val evaluationId: Long? = null,
    val outcome: PushOutcome? = null,
    val outcomeAtMillis: Long? = null,
)

/** 推送记录仓库：打扰预算按当日计数与最近一次时间判断 [FR-CTX-08]。 */
interface PushRepository {
    suspend fun record(push: PushRecord)

    suspend fun setOutcome(
        pushId: String,
        outcome: PushOutcome,
        atMillis: Long,
    )

    suspend fun countSince(sinceMillis: Long): Int

    suspend fun lastSentAt(): Long?
}
