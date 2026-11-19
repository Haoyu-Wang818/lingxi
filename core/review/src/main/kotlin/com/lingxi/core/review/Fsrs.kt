package com.lingxi.core.review

import com.lingxi.core.model.Rating
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 一张卡片的记忆状态。
 *
 * @property stability 记忆稳定性 S：可回忆概率降到 90% 所需的天数。
 * @property difficulty 难度 D，范围 1–10。
 * @property lastReviewMillis 上次复习时间，新卡为 null。
 */
data class MemoryState(
    val stability: Double,
    val difficulty: Double,
    val lastReviewMillis: Long?,
    val reps: Int = 0,
    val lapses: Int = 0,
)

/** 一次复习后的调度结果。 */
data class ScheduleResult(
    val state: MemoryState,
    val dueAtMillis: Long,
    val intervalDays: Double,
)

/**
 * FSRS（Free Spaced Repetition Scheduler）间隔重复算法，实现 FSRS-4.5 版本公式。
 *
 * 开源说明：算法与默认参数来自开源项目 open-spaced-repetition/fsrs4anki（MIT 许可），
 * 本文件为按其公开公式独立编写的 Kotlin 实现。
 *
 * @property weights 17 个模型参数，默认值为官方在大规模数据上拟合的结果，后续可按用户数据个性化。
 * @property desiredRetention 目标记忆保持率，越高复习越频繁。
 * @property maxIntervalDays 最长间隔。
 */
class FsrsScheduler(
    private val weights: DoubleArray = DEFAULT_WEIGHTS,
    private val desiredRetention: Double = 0.9,
    private val maxIntervalDays: Double = 365.0,
) {
    init {
        require(weights.size == 17) { "FSRS-4.5 需要 17 个参数" }
        require(desiredRetention in 0.7..0.99) { "目标保持率应在 0.7 到 0.99 之间" }
    }

    private fun w(i: Int) = weights[i]

    /** 距上次复习 [elapsedDays] 天后，稳定性为 [stability] 的记忆仍能回忆起的概率。 */
    fun retrievability(
        elapsedDays: Double,
        stability: Double,
    ): Double = (1 + FACTOR * elapsedDays / stability).pow(DECAY)

    /** 为达到目标保持率，下一次复习应间隔的天数。 */
    fun nextIntervalDays(stability: Double): Double {
        val raw = stability / FACTOR * (desiredRetention.pow(1 / DECAY) - 1)
        return raw.coerceIn(1.0, maxIntervalDays)
    }

    /** 新卡片首次复习后的初始状态。 */
    fun initialState(
        rating: Rating,
        nowMillis: Long,
    ): MemoryState =
        MemoryState(
            stability = w(rating.grade - 1).coerceAtLeast(MIN_STABILITY),
            difficulty = initialDifficulty(rating),
            lastReviewMillis = nowMillis,
            reps = 1,
            lapses = if (rating == Rating.AGAIN) 1 else 0,
        )

    /**
     * 根据本次评分更新记忆状态并计算下次到期时间。
     * 新卡（[MemoryState.lastReviewMillis] 为 null）走初始化逻辑。
     * 选择「忘记」时安排 10 分钟后重学。
     */
    fun review(
        state: MemoryState?,
        rating: Rating,
        nowMillis: Long,
    ): ScheduleResult {
        val next =
            if (state?.lastReviewMillis == null) {
                initialState(rating, nowMillis)
            } else {
                val elapsedDays = ((nowMillis - state.lastReviewMillis).coerceAtLeast(0)) / DAY_MILLIS
                val r = retrievability(elapsedDays, state.stability)
                val stability =
                    if (rating == Rating.AGAIN) {
                        forgetStability(state.difficulty, state.stability, r)
                    } else {
                        recallStability(state.difficulty, state.stability, r, rating)
                    }
                MemoryState(
                    stability = stability.coerceAtLeast(MIN_STABILITY),
                    difficulty = nextDifficulty(state.difficulty, rating),
                    lastReviewMillis = nowMillis,
                    reps = state.reps + 1,
                    lapses = state.lapses + if (rating == Rating.AGAIN) 1 else 0,
                )
            }

        return if (rating == Rating.AGAIN) {
            ScheduleResult(next, nowMillis + RELEARN_MILLIS, RELEARN_MILLIS / DAY_MILLIS)
        } else {
            val days = nextIntervalDays(next.stability)
            ScheduleResult(next, nowMillis + (days * DAY_MILLIS).roundToLong(), days)
        }
    }

    private fun initialDifficulty(rating: Rating): Double = (w(4) - (rating.grade - 3) * w(5)).coerceIn(1.0, 10.0)

    private fun nextDifficulty(
        d: Double,
        rating: Rating,
    ): Double {
        val updated = d - w(6) * (rating.grade - 3)
        // 均值回归：防止难度长期漂移到极端值。
        val reverted = w(7) * initialDifficulty(Rating.EASY) + (1 - w(7)) * updated
        return reverted.coerceIn(1.0, 10.0)
    }

    private fun recallStability(
        d: Double,
        s: Double,
        r: Double,
        rating: Rating,
    ): Double {
        val hardPenalty = if (rating == Rating.HARD) w(15) else 1.0
        val easyBonus = if (rating == Rating.EASY) w(16) else 1.0
        val growth = exp(w(8)) * (11 - d) * s.pow(-w(9)) * (exp(w(10) * (1 - r)) - 1) * hardPenalty * easyBonus
        return s * (growth + 1)
    }

    private fun forgetStability(
        d: Double,
        s: Double,
        r: Double,
    ): Double = w(11) * d.pow(-w(12)) * ((s + 1).pow(w(13)) - 1) * exp(w(14) * (1 - r))

    companion object {
        const val DECAY = -0.5
        const val FACTOR = 19.0 / 81.0
        const val DAY_MILLIS = 86_400_000.0
        const val RELEARN_MILLIS = 10 * 60 * 1000L
        const val MIN_STABILITY = 0.1

        /** FSRS-4.5 官方默认参数。 */
        val DEFAULT_WEIGHTS =
            doubleArrayOf(
                0.4872,
                1.4003,
                3.7145,
                13.8206,
                5.1618,
                1.2298,
                0.8975,
                0.031,
                1.6474,
                0.1367,
                1.0461,
                2.1072,
                0.0793,
                0.3246,
                1.587,
                0.2272,
                2.8755,
            )
    }
}
