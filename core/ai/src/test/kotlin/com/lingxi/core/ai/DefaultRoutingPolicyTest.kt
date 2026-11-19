package com.lingxi.core.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class DefaultRoutingPolicyTest {
    private val policy = DefaultRoutingPolicy(onDeviceMaxInputChars = 1_500, lowBatteryPercent = 20)
    private val wifiFull = DeviceState(NetworkType.UNMETERED, batteryPercent = 80, isCharging = false)
    private val offline = wifiFull.copy(network = NetworkType.NONE)

    private fun run(decision: RouteDecision) = decision as RouteDecision.Run

    @Test
    @DisplayName("嵌入向量永远在端侧计算")
    fun embeddingAlwaysOnDevice() {
        val d = run(policy.decide(AiTask(AiTaskType.EMBEDDING, 100), wifiFull))
        assertEquals(Route.ON_DEVICE, d.primary)
        assertEquals(null, d.fallback)
    }

    @Test
    @DisplayName("断网时只能上云的任务不可用")
    fun cloudOnlyTaskUnavailableOffline() {
        val d = policy.decide(AiTask(AiTaskType.KNOWLEDGE_EXTRACTION, 3_000), offline)
        assertTrue(d is RouteDecision.Unavailable)
    }

    @Test
    @DisplayName("断网时两侧都能做的任务走端侧")
    fun dualCapableTaskRunsOnDeviceOffline() {
        val d = run(policy.decide(AiTask(AiTaskType.QUESTION_GENERATION, 300), offline))
        assertEquals(Route.ON_DEVICE, d.primary)
        assertEquals(null, d.fallback)
    }

    @Test
    @DisplayName("含隐私内容优先端侧，并以云端兜底")
    fun personalDataPrefersOnDeviceWithCloudFallback() {
        val d = run(policy.decide(AiTask(AiTaskType.QUESTION_GENERATION, 300, containsPersonalData = true), wifiFull))
        assertEquals(Route.ON_DEVICE, d.primary)
        assertEquals(Route.CLOUD, d.fallback)
    }

    @Test
    @DisplayName("输入超过端侧上限时上云")
    fun oversizedInputGoesToCloud() {
        val d = run(policy.decide(AiTask(AiTaskType.SHORT_SUMMARY, 1_501), wifiFull))
        assertEquals(Route.CLOUD, d.primary)
    }

    @Test
    @DisplayName("设备过热时上云")
    fun thermalThrottlingGoesToCloud() {
        val hot = wifiFull.copy(thermalThrottled = true)
        val d = run(policy.decide(AiTask(AiTaskType.SHORT_SUMMARY, 100), hot))
        assertEquals(Route.CLOUD, d.primary)
        assertEquals(Route.ON_DEVICE, d.fallback)
    }

    @Test
    @DisplayName("低电量且未充电时重任务上云，轻任务仍在端侧")
    fun lowBatterySendsHeavyTasksToCloud() {
        val low = wifiFull.copy(batteryPercent = 10)
        assertEquals(Route.CLOUD, run(policy.decide(AiTask(AiTaskType.QUESTION_GENERATION, 100), low)).primary)
        assertEquals(Route.ON_DEVICE, run(policy.decide(AiTask(AiTaskType.CLASSIFY, 100), low)).primary)
    }

    @Test
    @DisplayName("正常条件下轻任务端侧、重任务云端")
    fun defaultSplitsLightAndHeavyTasks() {
        assertEquals(Route.ON_DEVICE, run(policy.decide(AiTask(AiTaskType.ANSWER_GRADING, 50), wifiFull)).primary)
        assertEquals(Route.CLOUD, run(policy.decide(AiTask(AiTaskType.QUESTION_GENERATION, 50), wifiFull)).primary)
    }

    @Test
    @DisplayName("每个决策都带有可读理由")
    fun everyDecisionHasReasons() {
        val devices =
            listOf(wifiFull, offline, wifiFull.copy(batteryPercent = 5), wifiFull.copy(thermalThrottled = true))
        for (type in AiTaskType.entries) {
            for (device in devices) {
                val d = policy.decide(AiTask(type, 200), device)
                assertTrue(d.reasons.isNotEmpty(), "$type 在 $device 下缺少理由")
            }
        }
    }
}
