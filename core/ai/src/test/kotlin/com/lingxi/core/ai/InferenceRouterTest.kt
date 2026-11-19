package com.lingxi.core.ai

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class InferenceRouterTest {
    private class FakeBackend(
        override val route: Route,
        private val fail: Boolean = false,
    ) : ModelBackend {
        var calls = 0

        override suspend fun complete(request: CompletionRequest): CompletionResult {
            calls++
            if (fail) error("$route 后端故障")
            return CompletionResult(text = "ok from $route", modelName = route.name)
        }
    }

    private val request = CompletionRequest(systemPrompt = "", userPrompt = "什么是导数？")
    private val wifi = DeviceState(NetworkType.UNMETERED, batteryPercent = 80, isCharging = true)

    private fun router(
        onDevice: FakeBackend,
        cloud: FakeBackend,
        device: DeviceState = wifi,
    ) = InferenceRouter(DefaultRoutingPolicy(), listOf(onDevice, cloud), { device }, clock = { 0L })

    @Test
    @DisplayName("主路线成功时不触发备用")
    fun primarySuccessSkipsFallback() =
        runTest {
            val onDevice = FakeBackend(Route.ON_DEVICE)
            val cloud = FakeBackend(Route.CLOUD)
            val r = router(onDevice, cloud).run(AiTask(AiTaskType.SHORT_SUMMARY, 50), request)
            assertEquals(Route.ON_DEVICE, r.finalRoute)
            assertFalse(r.usedFallback)
            assertEquals(0, cloud.calls)
        }

    @Test
    @DisplayName("端侧失败时自动切到云端")
    fun onDeviceFailureFallsBackToCloud() =
        runTest {
            val onDevice = FakeBackend(Route.ON_DEVICE, fail = true)
            val cloud = FakeBackend(Route.CLOUD)
            val r = router(onDevice, cloud).run(AiTask(AiTaskType.SHORT_SUMMARY, 50), request)
            assertEquals(Route.CLOUD, r.finalRoute)
            assertTrue(r.usedFallback)
            assertEquals("ok from CLOUD", r.result.getOrThrow().text)
        }

    @Test
    @DisplayName("两条路线都失败时返回失败结果")
    fun bothRoutesFailingReturnsFailure() =
        runTest {
            val r =
                router(FakeBackend(Route.ON_DEVICE, fail = true), FakeBackend(Route.CLOUD, fail = true))
                    .run(AiTask(AiTaskType.SHORT_SUMMARY, 50), request)
            assertTrue(r.result.isFailure)
            assertEquals(null, r.finalRoute)
        }

    @Test
    @DisplayName("不可用时不调用任何后端")
    fun unavailableCallsNoBackend() =
        runTest {
            val onDevice = FakeBackend(Route.ON_DEVICE)
            val cloud = FakeBackend(Route.CLOUD)
            val offline = wifi.copy(network = NetworkType.NONE)
            val r = router(onDevice, cloud, offline).run(AiTask(AiTaskType.AGENT_PLANNING, 50), request)
            assertTrue(r.result.exceptionOrNull() is RouteUnavailableException)
            assertEquals(0, onDevice.calls + cloud.calls)
        }
}
