package com.lingxi.core.ai

import com.lingxi.core.model.ErrorCoded
import com.lingxi.core.model.repository.AiExecution
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 06-detailed-design.md 3.2 节决策表，每格至少一个用例（TC-AI-01 至 TC-AI-12）。 */
class DecisionTableTest {
    private val policy = DefaultRoutingPolicy()
    private val wifi = DeviceState(NetworkType.UNMETERED, batteryPercent = 80, isCharging = false)
    private val offline = wifi.copy(network = NetworkType.NONE)
    private val lowBattery = wifi.copy(batteryPercent = 10)
    private val hot = wifi.copy(thermalThrottled = true)
    private val noUpload =
        wifi.copy(
            allowCloudFor = PrivacySettings(uploadOcrText = false, uploadTranscript = false).allowCloudFor(),
        )

    private fun decide(
        type: AiTaskType,
        device: DeviceState,
        personal: Boolean = false,
    ) = policy.decide(AiTask(type, 500, personal), device)

    private fun RouteDecision.route() = (this as RouteDecision.Run).primary to fallback

    private val RouteDecision.fallback get() = (this as RouteDecision.Run).fallback

    @Test
    @DisplayName("[FR-AI-01] 任务类型补全：OCR、ASR、EXTRACT、CARD_GEN、CHAT 的能力标注")
    fun taskTypesDeclareCapabilities() {
        assertTrue(AiTaskType.OCR.onDeviceCapable && AiTaskType.OCR.cloudCapable)
        assertTrue(AiTaskType.ASR.onDeviceCapable && !AiTaskType.ASR.cloudCapable)
        listOf(AiTaskType.EXTRACT, AiTaskType.CARD_GEN, AiTaskType.CHAT).forEach {
            assertTrue(!it.onDeviceCapable && it.cloudCapable && it.heavy, it.name)
        }
    }

    @Test
    @DisplayName("[FR-AI-02] OCR：有网端侧并可回退云端，无网端侧，低电量 / 发热仍端侧优先或转云")
    fun ocrRow() {
        assertEquals(Route.ON_DEVICE to Route.CLOUD, decide(AiTaskType.OCR, wifi).route())
        assertEquals(Route.ON_DEVICE to null, decide(AiTaskType.OCR, offline).route())
        assertEquals(Route.ON_DEVICE to Route.CLOUD, decide(AiTaskType.OCR, lowBattery).route())
        assertEquals(Route.CLOUD to Route.ON_DEVICE, decide(AiTaskType.OCR, hot).route())
    }

    @Test
    @DisplayName("[FR-SET-01] OCR 含隐私且未授权上传：只在端侧，不回退云端")
    fun ocrPrivacyNoFallback() {
        assertEquals(Route.ON_DEVICE to null, decide(AiTaskType.OCR, noUpload, personal = true).route())
    }

    @Test
    @DisplayName("[FR-AI-02] ASR：任何条件下都只在端侧（音频不出手机）")
    fun asrRow() {
        listOf(wifi, offline, lowBattery, hot, noUpload).forEach {
            assertEquals(Route.ON_DEVICE to null, decide(AiTaskType.ASR, it, personal = true).route())
        }
    }

    @Test
    @DisplayName("[FR-AI-02] EMBEDDING：只在端侧")
    fun embeddingRow() {
        listOf(
            wifi,
            offline,
            hot,
        ).forEach { assertEquals(Route.ON_DEVICE to null, decide(AiTaskType.EMBEDDING, it).route()) }
    }

    @Test
    @DisplayName("[FR-AI-02] EXTRACT / CARD_GEN：有网云端，无网不可用（延迟入队），低电量仍云端")
    fun extractAndCardsRows() {
        listOf(AiTaskType.EXTRACT, AiTaskType.CARD_GEN).forEach { type ->
            assertEquals(Route.CLOUD to null, decide(type, wifi, personal = true).route())
            assertTrue(decide(type, offline) is RouteDecision.Unavailable)
            assertEquals(Route.CLOUD to null, decide(type, lowBattery).route())
        }
    }

    @Test
    @DisplayName("[FR-SET-01] EXTRACT 含隐私且两个上传开关都关：不执行，提示开启上传")
    fun extractBlockedByPrivacy() {
        val d = decide(AiTaskType.EXTRACT, noUpload, personal = true)
        assertTrue(d is RouteDecision.Unavailable)
        assertTrue(d.reasons.single().contains("隐私设置"))
        // 不含隐私内容的调用不受影响。
        assertEquals(Route.CLOUD to null, decide(AiTaskType.EXTRACT, noUpload).route())
    }

    @Test
    @DisplayName("[FR-AI-02] CHAT：有网云端，无网不可用")
    fun chatRow() {
        assertEquals(Route.CLOUD to null, decide(AiTaskType.CHAT, wifi).route())
        assertTrue(decide(AiTaskType.CHAT, offline) is RouteDecision.Unavailable)
        assertTrue(decide(AiTaskType.CHAT, noUpload, personal = true) is RouteDecision.Unavailable)
    }

    @Test
    @DisplayName("[FR-SET-01] 隐私设置换算：只开识别文本时 OCR 与抽取可上云")
    fun privacySettingsMapping() {
        val ocrOnly = PrivacySettings(uploadOcrText = true, uploadTranscript = false).allowCloudFor()
        assertTrue(AiTaskType.OCR in ocrOnly && AiTaskType.EXTRACT in ocrOnly)
        val transcriptOnly = PrivacySettings(uploadOcrText = false, uploadTranscript = true).allowCloudFor()
        assertFalse(AiTaskType.OCR in transcriptOnly)
        assertTrue(AiTaskType.EXTRACT in transcriptOnly)
        assertEquals(AiTaskType.entries.toSet(), PrivacySettings().allowCloudFor())
    }

    private class Coded :
        Exception("网关超时"),
        ErrorCoded {
        override val code = "LLM_UPSTREAM"
    }

    private class Backend(
        override val route: Route,
        private val error: Exception? = null,
    ) : ModelBackend {
        override suspend fun complete(request: CompletionRequest): CompletionResult {
            error?.let { throw it }
            return CompletionResult("ok", route.name)
        }
    }

    private val request = CompletionRequest("", "问题")

    @Test
    @DisplayName("[FR-AI-04] 每次调用通知观察者，执行记录字段完整（成功）")
    fun observerReceivesSuccessRecord() =
        runTest {
            val records = mutableListOf<AiExecution>()
            var t = 1_000L
            val router =
                InferenceRouter(
                    policy,
                    listOf(Backend(Route.CLOUD)),
                    { wifi },
                    clock = { t.also { t += 250 } },
                ) { r, start ->
                    records += r.toExecution(start, refId = "note-1")
                }
            router.run(AiTask(AiTaskType.CHAT, 10), request)
            val e = records.single()
            assertEquals(AiExecution("CHAT", "CLOUD", "CHAT 需要云端大模型", 1_000, 250, true, null, "note-1"), e)
        }

    @Test
    @DisplayName("[FR-AI-03] 主路线失败切换备用，记录标注回退；两侧都失败时记录错误码")
    fun observerRecordsFallbackAndErrors() =
        runTest {
            val records = mutableListOf<AiExecution>()
            val observer = RouteObserver { r, start -> records += r.toExecution(start) }
            val ok =
                InferenceRouter(
                    policy,
                    listOf(Backend(Route.ON_DEVICE, Coded()), Backend(Route.CLOUD)),
                    { wifi },
                    { 0L },
                    observer,
                )
            ok.run(AiTask(AiTaskType.SHORT_SUMMARY, 10), request)
            assertEquals("CLOUD", records[0].location)
            assertTrue(records[0].reason.endsWith("主路线失败，已切换备用路线"))
            assertTrue(records[0].success)

            val bad =
                InferenceRouter(
                    policy,
                    listOf(Backend(Route.ON_DEVICE, IllegalStateException()), Backend(Route.CLOUD, Coded())),
                    { wifi },
                    { 0L },
                    observer,
                )
            bad.run(AiTask(AiTaskType.SHORT_SUMMARY, 10), request)
            assertEquals("ON_DEVICE", records[1].location)
            assertFalse(records[1].success)
            assertEquals("LLM_UPSTREAM", records[1].errorCode)
        }

    @Test
    @DisplayName("[FR-AI-04] 不可用也会记录；观察者自身出错不影响调用结果")
    fun unavailableRecordedAndObserverErrorsIgnored() =
        runTest {
            val records = mutableListOf<AiExecution>()
            val router =
                InferenceRouter(policy, listOf(Backend(Route.CLOUD)), { offline }, { 0L }) { r, s ->
                    records +=
                        r.toExecution(s)
                }
            val r = router.run(AiTask(AiTaskType.EXTRACT, 10), request)
            assertNull(r.finalRoute)
            assertEquals("UNAVAILABLE", records.single().location)
            assertEquals("RouteUnavailableException", records.single().errorCode)

            val throwing =
                InferenceRouter(policy, listOf(Backend(Route.CLOUD)), { wifi }, { 0L }) { _, _ -> error("数据库已关闭") }
            assertTrue(throwing.run(AiTask(AiTaskType.CHAT, 10), request).result.isSuccess)
        }
}
