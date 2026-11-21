package com.lingxi.core.review

import com.lingxi.core.model.Rating
import java.time.Duration

/**
 * 卡片调度的纯函数接口，供 `feature:review` 与仓库层使用，便于替换算法或在测试中注入假实现。
 *
 * 现有 [FsrsScheduler] 尚未实现本接口；I3 迭代补齐 [predictIntervals] 与出卡队列后再接入，
 * 以免本阶段改动已验证的 FSRS 计算。
 */
interface Scheduler {
    /** 根据本次评分更新记忆状态并计算下次到期时间 [FR-REV-02]。新卡传 null。 */
    fun schedule(
        state: MemoryState?,
        rating: Rating,
        nowMillis: Long,
    ): ScheduleResult

    /**
     * 预测四档评分各自的下次间隔，显示在三键上（如「10分 / 1天 / 4天」）[FR-REV-07]。
     * 返回值应随评分单调递增（TC-REV-01）。
     */
    fun predictIntervals(
        state: MemoryState?,
        nowMillis: Long,
    ): Map<Rating, Duration>
}
