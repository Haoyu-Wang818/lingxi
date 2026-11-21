package com.lingxi.core.model.repository

import com.lingxi.core.model.Course
import com.lingxi.core.model.KnowledgePoint
import com.lingxi.core.model.Rating
import com.lingxi.core.model.ReviewCard
import java.time.LocalDate
import java.time.ZoneId

/** 卡片记忆阶段，对应 `memory_state.state`。 */
enum class MemoryPhase { NEW, LEARNING, REVIEW, RELEARNING }

/**
 * 一张卡片持久化的记忆状态（`memory_state` 表）[FR-REV-02]。
 *
 * 与 `core:review` 的 FSRS 内存状态分开定义：core 模块之间只能依赖 `core:model`，
 * 由界面层在两者之间转换。新卡的 [stability] / [difficulty] 为 null。
 */
data class CardMemory(
    val phase: MemoryPhase,
    val stability: Double?,
    val difficulty: Double?,
    val dueAtMillis: Long,
    val lastReviewMillis: Long?,
    val reps: Int = 0,
    val lapses: Int = 0,
) {
    companion object {
        /** 新卡：立即到期。 */
        fun new(nowMillis: Long) = CardMemory(MemoryPhase.NEW, null, null, nowMillis, null)
    }
}

/** 作答来自哪一端 [FR-WEAR-03]。 */
enum class ReviewDevice { PHONE, WATCH, NOTIFICATION }

/** 复习模式 [FR-REV-12]、[FR-REV-14]、[FR-REV-16]。 */
enum class ReviewMode { DAILY, FRAGMENT, RELEARN_IN_GROUP, EXAM }

/**
 * 写入 `review_log` 的一次作答 [FR-REV-05]。
 *
 * @property counted 是否计入 FSRS；组内回炉为 false，不更新记忆状态 [FR-REV-14]。
 * @property messageId 手表回传消息 id，唯一约束去重（TC-REV-09）；手机端作答为 null。
 */
data class ReviewLogEntry(
    val reviewedAtMillis: Long,
    val rating: Rating,
    val elapsedMs: Long? = null,
    val device: ReviewDevice = ReviewDevice.PHONE,
    val mode: ReviewMode = ReviewMode.DAILY,
    val counted: Boolean = true,
    val messageId: String? = null,
)

/**
 * 复习页需要的一张卡：卡片 + 所属课程与知识点 + 记忆状态 + 图谱邻居名称。
 * 一次查询拼好，界面不必再逐张查库。
 *
 * @property capturedAtMillis 卡片来源素材的拍摄时间（`capture.created_at`），用于背面「11月18日 板书 第3页」。
 */
data class StudyCard(
    val card: ReviewCard,
    val course: Course,
    val point: KnowledgePoint,
    val memory: CardMemory?,
    val prerequisites: List<String> = emptyList(),
    val related: List<String> = emptyList(),
    val capturedAtMillis: Long? = null,
)

/**
 * 卡片与复习记录的仓库 [FR-REV-03]、[FR-REV-04]、[FR-REV-05]。
 *
 * 新记忆状态由调用方用 FSRS 算好再传进来：仓库在 `core:data`，按分层规则不能依赖 `core:review`。
 * 写记忆状态与写日志在同一事务内完成，日志里保存作答前的状态快照，撤销时据此精确恢复。
 */
interface CardRepository {
    /** 截至 [untilMillis] 到期的卡（含新卡），按到期时间升序，最多 [cap] 张 [FR-REV-03]。 */
    suspend fun dueQueue(
        untilMillis: Long,
        cap: Int,
    ): List<StudyCard>

    /**
     * 记录一次作答，返回日志 id，供撤销使用 [FR-REV-04]。
     * [newMemory] 为 null 表示不更新记忆状态（组内回炉）。
     * 带 [ReviewLogEntry.messageId] 的重复回传不再写入，返回已有日志 id。
     */
    suspend fun saveReview(
        cardId: String,
        newMemory: CardMemory?,
        log: ReviewLogEntry,
    ): Long

    /** 撤销一次作答：用日志里的快照恢复记忆状态并删除该日志，同一事务 [FR-REV-04]。 */
    suspend fun undo(logId: Long)

    /** 写入或更新卡片；新卡同时建立 NEW 记忆状态（已有状态保留）。 */
    suspend fun upsertCards(
        cards: List<ReviewCard>,
        nowMillis: Long,
    )

    /** 来源属于这些捕获的卡片数，笔记详情显示「本节生成 N 张卡片」。 */
    suspend fun countFromCaptures(captureIds: Set<String>): Int

    /** 卡片总数（不含已删除），用于判断是否需要写入种子数据。 */
    suspend fun cardCount(): Int

    /** 某张卡的记忆状态。 */
    suspend fun memory(cardId: String): CardMemory?

    /** 自 [sinceMillis] 起计入 FSRS 的作答次数，用于「今日已复习」。 */
    suspend fun countedReviewsSince(sinceMillis: Long): Int

    /** 截至 [today]（含）的连续学习天数，按 [zone] 的本地日期聚合 `review_log` [FR-REV-09]。 */
    suspend fun streakDays(
        today: LocalDate,
        zone: ZoneId,
    ): Int
}

/** 连续天数计算规则：今天还没学时，从昨天往前数，不让「今天还没开始」打断连续记录。 */
object Streak {
    fun count(
        days: Set<LocalDate>,
        today: LocalDate,
    ): Int {
        var day = if (today in days) today else today.minusDays(1)
        var n = 0
        while (day in days) {
            n++
            day = day.minusDays(1)
        }
        return n
    }
}
