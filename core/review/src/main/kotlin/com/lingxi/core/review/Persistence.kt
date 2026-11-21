package com.lingxi.core.review

import com.lingxi.core.model.Rating
import com.lingxi.core.model.repository.CardMemory
import com.lingxi.core.model.repository.MemoryPhase
import com.lingxi.core.model.repository.ReviewDevice
import com.lingxi.core.model.repository.ReviewLogEntry
import com.lingxi.core.model.repository.ReviewMode
import com.lingxi.core.model.repository.StudyCard

// 复习会话与持久化之间的转换 [FR-REV-04]、[FR-REV-05]、[FR-REV-14]。
// 放在 core:review 是因为它同时认识 FSRS 状态（本模块）与仓库模型（core:model），且可在 JVM 上单测。

/** 数据库中的记忆状态 → FSRS 状态；新卡（尚无稳定度）返回 null，FSRS 会走首次学习逻辑。 */
fun CardMemory.toFsrs(): MemoryState? {
    val s = stability
    val d = difficulty
    if (phase == MemoryPhase.NEW || s == null || d == null) return null
    return MemoryState(s, d, lastReviewMillis, reps, lapses)
}

/**
 * FSRS 状态 → 数据库中的记忆状态。
 * 阶段：首次学习就忘记为 LEARNING，复习中忘记为 RELEARNING，其余为 REVIEW。
 */
fun MemoryState.toCardMemory(
    rating: Rating,
    dueAtMillis: Long,
): CardMemory {
    val phase =
        when {
            rating != Rating.AGAIN -> MemoryPhase.REVIEW
            reps <= 1 -> MemoryPhase.LEARNING
            else -> MemoryPhase.RELEARNING
        }
    return CardMemory(phase, stability, difficulty, dueAtMillis, lastReviewMillis, reps, lapses)
}

/** 仓库查出的卡片 → 复习页卡片；[sourceLabel] 由调用方按捕获时间生成（如「11月18日 板书 第3页」）。 */
fun StudyCard.toDeckCard(sourceLabel: String?): DeckCard =
    DeckCard(
        card = card,
        courseName = course.name,
        pointTitle = point.title,
        pointSummary = point.summary,
        sourceLabel = sourceLabel,
        prerequisites = prerequisites,
        related = related,
        memory = memory?.toFsrs(),
    )

/** 会话中的一次评分 → 复习日志；回炉作答记为 RELEARN_IN_GROUP 且不计入 FSRS。 */
fun RatingRecord.toLogEntry(
    elapsedMs: Long? = null,
    device: ReviewDevice = ReviewDevice.PHONE,
): ReviewLogEntry =
    ReviewLogEntry(
        reviewedAtMillis = atMillis,
        rating = rating,
        elapsedMs = elapsedMs,
        device = device,
        mode = if (relearn) ReviewMode.RELEARN_IN_GROUP else ReviewMode.DAILY,
        counted = !relearn,
    )

/** 会话评分 → 要写入的新记忆状态；回炉作答（[state] 为 null 或 [RatingRecord.interval] 为空）不更新。 */
fun RatingRecord.toCardMemory(state: MemoryState?): CardMemory? {
    if (relearn || state == null || interval == null) return null
    return state.toCardMemory(rating, atMillis + interval.toMillis())
}
