package com.lingxi.core.review

import com.lingxi.core.model.Rating
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FsrsSchedulerTest {
    private val fsrs = FsrsScheduler()
    private val day = FsrsScheduler.DAY_MILLIS.toLong()
    private val t0 = 1_700_000_000_000L

    @Test
    @DisplayName("经过一个稳定性周期后可回忆概率为 90%")
    fun retrievabilityIsNinetyPercentAfterOneStability() {
        assertEquals(0.9, fsrs.retrievability(elapsedDays = 10.0, stability = 10.0), 1e-9)
        assertEquals(1.0, fsrs.retrievability(elapsedDays = 0.0, stability = 10.0), 1e-9)
    }

    @Test
    @DisplayName("目标保持率 90% 时下次间隔约等于稳定性")
    fun intervalEqualsStabilityAtNinetyPercentRetention() {
        assertEquals(10.0, fsrs.nextIntervalDays(10.0), 1e-6)
    }

    @Test
    @DisplayName("新卡评分越高，初始稳定性越大、难度越低")
    fun higherFirstRatingGivesHigherStabilityAndLowerDifficulty() {
        val states = Rating.entries.map { fsrs.initialState(it, t0) }
        assertTrue(states.zipWithNext().all { (a, b) -> a.stability < b.stability })
        assertTrue(states.zipWithNext().all { (a, b) -> a.difficulty > b.difficulty })
    }

    @Test
    @DisplayName("按时复习并答对，稳定性增长")
    fun timelyCorrectReviewGrowsStability() {
        val first = fsrs.review(null, Rating.GOOD, t0)
        val second = fsrs.review(first.state, Rating.GOOD, first.dueAtMillis)
        assertTrue(second.state.stability > first.state.stability)
        assertTrue(second.intervalDays > first.intervalDays)
    }

    @Test
    @DisplayName("忘记后稳定性下降，10 分钟后重学并记一次遗忘")
    fun forgettingShrinksStabilityAndSchedulesRelearn() {
        val first = fsrs.review(null, Rating.GOOD, t0)
        val good = fsrs.review(first.state, Rating.GOOD, t0 + 3 * day)
        val again = fsrs.review(good.state, Rating.AGAIN, good.dueAtMillis)
        assertTrue(again.state.stability < good.state.stability)
        assertEquals(good.dueAtMillis + 10 * 60 * 1000, again.dueAtMillis)
        assertEquals(1, again.state.lapses)
    }

    @Test
    @DisplayName("同一时刻复习，评分越高下次间隔越长")
    fun higherRatingGivesLongerInterval() {
        val base = fsrs.review(null, Rating.GOOD, t0).state
        val now = t0 + 5 * day
        val hard = fsrs.review(base, Rating.HARD, now).intervalDays
        val good = fsrs.review(base, Rating.GOOD, now).intervalDays
        val easy = fsrs.review(base, Rating.EASY, now).intervalDays
        assertTrue(hard < good && good < easy, "hard=$hard good=$good easy=$easy")
    }

    @Test
    @DisplayName("难度始终在 1 到 10 之间")
    fun difficultyStaysWithinBounds() {
        var state = fsrs.review(null, Rating.AGAIN, t0).state
        var now = t0
        repeat(20) {
            now += day
            state = fsrs.review(state, Rating.AGAIN, now).state
            assertTrue(state.difficulty in 1.0..10.0)
        }
    }
}
