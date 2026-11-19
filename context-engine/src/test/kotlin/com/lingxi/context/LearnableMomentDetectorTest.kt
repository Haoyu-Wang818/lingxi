package com.lingxi.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class LearnableMomentDetectorTest {
    private val detector = LearnableMomentDetector()

    /** 演示场景：下午排队，刚停下脚步，在刷短视频，心率平稳。 */
    private val waitingInLine =
        ContextSignals(
            localHour = 15,
            activity = Activity.STILL,
            previousActivity = Activity.WALKING,
            heartRateBpm = 70,
            restingHeartRateBpm = 66,
            screenOn = true,
            foregroundApp = AppCategory.SHORT_VIDEO,
        )

    @Test
    @DisplayName("排队刷短视频时判定为可学习时刻，并推送三张卡")
    fun waitingInLineIsLearnable() {
        val a = detector.evaluate(waitingInLine)
        assertTrue(a.learnable)
        assertEquals(CognitiveLoad.LOW, a.load)
        assertEquals(3, a.suggestedCards)
        assertTrue(a.evidence.any { it.description.contains("排队") })
    }

    @Test
    @DisplayName("上课时一律不推送")
    fun neverPromptsInClass() {
        val a = detector.evaluate(waitingInLine.copy(inClass = true))
        assertFalse(a.learnable)
        assertEquals("正在上课", a.blockedBy)
    }

    @Test
    @DisplayName("乘车、免打扰、深夜都会被硬性阻止")
    fun hardBlocksVehicleDndAndNight() {
        listOf(
            waitingInLine.copy(activity = Activity.IN_VEHICLE),
            waitingInLine.copy(doNotDisturb = true),
            waitingInLine.copy(localHour = 2),
        ).forEach { assertNotNull(detector.evaluate(it).blockedBy, "$it 应被阻止") }
    }

    @Test
    @DisplayName("冷却时间内不重复推送")
    fun respectsCooldown() {
        val a = detector.evaluate(waitingInLine.copy(minutesSinceLastPrompt = 10))
        assertFalse(a.learnable)
        assertTrue(a.blockedBy!!.contains("10"))
    }

    @Test
    @DisplayName("正在使用学习应用时不打断")
    fun doesNotInterruptStudyApps() {
        val a = detector.evaluate(waitingInLine.copy(foregroundApp = AppCategory.STUDY))
        assertFalse(a.learnable)
    }

    @Test
    @DisplayName("心率明显升高时视为高负荷并不推送")
    fun highHeartRateMeansHighLoad() {
        val a = detector.evaluate(waitingInLine.copy(heartRateBpm = 90))
        assertEquals(CognitiveLoad.HIGH, a.load)
        assertFalse(a.learnable)
        assertEquals(0, a.suggestedCards)
    }

    @Test
    @DisplayName("没有心率时按活动状态估计负荷")
    fun estimatesLoadFromActivityWithoutHeartRate() {
        val noHr = waitingInLine.copy(heartRateBpm = null, restingHeartRateBpm = null)
        assertEquals(CognitiveLoad.LOW, detector.estimateLoad(noHr))
        assertEquals(CognitiveLoad.MEDIUM, detector.estimateLoad(noHr.copy(activity = Activity.WALKING)))
    }

    @Test
    @DisplayName("分数始终在 0 到 1 之间")
    fun scoreStaysWithinUnitRange() {
        for (app in AppCategory.entries) {
            for (activity in listOf(Activity.STILL, Activity.WALKING, Activity.UNKNOWN)) {
                val s = detector.evaluate(waitingInLine.copy(foregroundApp = app, activity = activity)).score
                assertTrue(s in 0.0..1.0)
            }
        }
    }
}
