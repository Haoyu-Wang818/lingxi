package com.lingxi.core.data

import com.lingxi.core.model.Rating
import com.lingxi.core.model.ReviewCard
import java.time.LocalDate

/** 一次作答来自哪一端，手表端只会产生 AGAIN / GOOD [FR-WEAR-03]。 */
enum class ReviewSource { PHONE, WATCH }

/**
 * 写入 `review_log` 表的一条复习日志。
 *
 * @property messageId 手表消息 id，手机端据此唯一约束去重（TC-REV-09）；手机端作答为 null。
 * @property counted 组内回炉的第二次作答为 false，不参与 FSRS 调度 [FR-REV-14]。
 * @property pushId 由情境推送触发时的推送 id，用于统计推送的作答率 [FR-CTX-05]。
 */
data class ReviewLogEntry(
    val answeredAtMillis: Long,
    val responseMs: Long,
    val source: ReviewSource = ReviewSource.PHONE,
    val messageId: String? = null,
    val counted: Boolean = true,
    val pushId: String? = null,
)

/**
 * 卡片与复习记录的仓库 [FR-REV-03]、[FR-REV-04]。
 *
 * 自评写入记忆状态与写日志在同一事务内完成，保证撤销时能精确恢复。
 * 实现（Room）在 I1 迭代加入，本阶段只定义接口。
 */
interface CardRepository {
    /** 某一天的出卡队列：到期复习卡与新卡交错，复习卡不超过 [cap] 张 [FR-REV-03]。 */
    suspend fun dueQueue(
        day: LocalDate,
        cap: Int,
    ): List<ReviewCard>

    /** 记录一次自评，返回日志 id，供撤销使用 [FR-REV-07]。 */
    suspend fun saveReview(
        cardId: String,
        rating: Rating,
        log: ReviewLogEntry,
    ): Long

    /** 撤销一次自评：恢复记忆状态并删除该日志 [FR-REV-04]。 */
    suspend fun undo(logId: Long)
}
