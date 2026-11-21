package com.lingxi.core.network

import com.lingxi.core.model.CardType
import com.lingxi.core.model.RelationType
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** 网关客户端：请求格式、响应解析、超时与错误映射 [FR-GW-01][FR-GW-05][FR-AI-03]。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OkHttpGatewayApiTest {
    private val server = MockWebServer()
    private lateinit var api: OkHttpGatewayApi

    @Before
    fun setUp() {
        server.start()
        api = OkHttpGatewayApi(server.url("/").toString(), "secret", "phone-1", newRequestId = { "req-1" })
    }

    @After
    fun tearDown() = server.close()

    private fun respond(
        code: Int,
        body: String,
        vararg headers: Pair<String, String>,
    ) {
        val builder = MockResponse.Builder().code(code).body(body)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        server.enqueue(builder.build())
    }

    /** /health：解析版本与是否已配置大模型。 */
    @Test
    fun healthParsesConfiguration() =
        runBlocking {
            respond(200, """{"status":"ok","version":"0.2.0","llm_configured":false,"model":null}""")
            val health = api.health().getOrThrow()
            assertEquals(GatewayHealth("0.2.0", false, null), health)
            assertEquals("GET", server.takeRequest().method)
        }

    /** [FR-GW-05] 请求带设备令牌、设备 id 与请求 id；/v1/chat 正文与响应解析。 */
    @Test
    fun chatSendsAuthHeadersAndParsesReply() =
        runBlocking {
            respond(200, """{"text":"导数是瞬时变化率","model":"qwen-plus","latency_ms":812}""")
            val reply = api.chat("你是助教", "什么是导数", 256).getOrThrow()
            assertEquals(ChatReply("导数是瞬时变化率", "qwen-plus", 812), reply)
            val req = server.takeRequest()
            assertEquals("/v1/chat", req.url.encodedPath)
            assertEquals("Bearer secret", req.headers["Authorization"])
            assertEquals("phone-1", req.headers["X-Device-Id"])
            assertEquals("req-1", req.headers["X-Request-Id"])
            val body = JSONObject(req.body!!.utf8())
            assertEquals("你是助教", body.getString("system"))
            assertEquals(256, body.getInt("max_tokens"))
        }

    /** [FR-GW-01] /v1/extract：segments 请求与带置信度的边。 */
    @Test
    fun extractSendsSegmentsAndParsesEdges() =
        runBlocking {
            respond(
                200,
                """{"points":[{"id":"d","title":"导数","summary":"变化率"},{"id":"t","title":"泰勒公式"}],
                   "edges":[{"from":"d","to":"t","relation":"PREREQUISITE","confidence":0.9},
                            {"from":"d","to":"t","relation":"UNKNOWN"},{"from":"t","to":"d","relation":"RELATED"}],
                   "prompt_version":"extract-v1"}""",
            )
            val req =
                ExtractRequest(
                    "高等数学",
                    "lesson-1",
                    listOf(TextSegment("cap-1", "OCR", "泰勒公式", page = 1), TextSegment("cap-2", "ASR", "今天讲", startMs = 0, endMs = 9)),
                    listOf("导数"),
                )
            val result = api.extract(req).getOrThrow()
            assertEquals(listOf("导数", "泰勒公式"), result.points.map { it.title })
            assertEquals(2, result.edges.size)
            assertEquals(RelationType.PREREQUISITE, result.edges[0].relation)
            assertEquals(0.9, result.edges[0].confidence, 1e-9)
            assertEquals(0.8, result.edges[1].confidence, 1e-9)
            assertEquals("extract-v1", result.promptVersion)
            val body = JSONObject(server.takeRequest().body!!.utf8())
            assertEquals("lesson-1", body.getString("lesson_id"))
            val seg = body.getJSONArray("segments")
            assertEquals(1, seg.getJSONObject(0).getInt("page"))
            assertEquals(9, seg.getJSONObject(1).getInt("end_ms"))
            assertEquals("导数", body.getJSONArray("existing_titles").getString(0))
        }

    /** [FR-GW-02] /v1/cards：解析卡片与拒绝原因，空手表版本为 null。 */
    @Test
    fun cardsParsesCardsAndRejections() =
        runBlocking {
            respond(
                200,
                """{"cards":[{"point_id":"t","type":"TRUE_FALSE","question":"可导必连续？","answer":"正确",
                     "options":null,"watch_prompt":"可导必连续？","watch_answer":null},
                    {"point_id":"t","type":"SINGLE_CHOICE","question":"q","answer":"a","options":["a","b"],"correct_index":0},
                    {"point_id":"t","type":"BOGUS","question":"q","answer":"a"}],
                   "rejected":[{"point_id":"x","reason":"WATCH_PROMPT_TOO_LONG"}],"prompt_version":"cards-v1"}""",
            )
            val resp = api.generateCards(CardsRequest("高等数学", listOf(CardPointInput("t", "泰勒公式", "…")))).getOrThrow()
            assertEquals(2, resp.cards.size)
            assertEquals(CardType.TRUE_FALSE, resp.cards[0].type)
            assertEquals("可导必连续？", resp.cards[0].watchPrompt)
            assertNull(resp.cards[0].watchAnswer)
            assertEquals(listOf("a", "b"), resp.cards[1].options)
            assertEquals(0, resp.cards[1].correctIndex)
            assertEquals(listOf("x" to "WATCH_PROMPT_TOO_LONG"), resp.rejected)
            val body = JSONObject(server.takeRequest().body!!.utf8())
            assertEquals(4, body.getJSONArray("types").length())
        }

    /** 错误体中的错误码映射为对应的 GatewayError，并标注是否可重试。 */
    @Test
    fun errorCodesMapToGatewayErrors() =
        runBlocking {
            fun err(code: String) = """{"error":{"code":"$code","message":"说明","request_id":"r"}}"""
            respond(503, err("LLM_NOT_CONFIGURED"))
            val notConfigured = api.chat("", "x").exceptionOrNull()
            assertTrue(notConfigured is GatewayError.NotConfigured)
            assertEquals("说明", notConfigured!!.message)
            assertTrue(!(notConfigured as GatewayError).retryable)

            respond(429, err("RATE_LIMITED"), "Retry-After" to "17")
            val limited = api.chat("", "x").exceptionOrNull() as GatewayError.RateLimited
            assertEquals(17L, limited.retryAfterSeconds)
            assertTrue(limited.retryable)

            respond(422, err("LLM_INVALID_OUTPUT"))
            assertTrue(api.chat("", "x").exceptionOrNull() is GatewayError.InvalidOutput)
            respond(401, err("UNAUTHORIZED"))
            assertTrue(api.chat("", "x").exceptionOrNull() is GatewayError.Unauthorized)
            respond(413, err("TEXT_TOO_LONG"))
            assertTrue(api.chat("", "x").exceptionOrNull() is GatewayError.TextTooLong)
            respond(400, err("BAD_REQUEST"))
            assertTrue(api.chat("", "x").exceptionOrNull() is GatewayError.BadRequest)
            respond(502, err("LLM_UPSTREAM"))
            assertEquals("LLM_UPSTREAM", (api.chat("", "x").exceptionOrNull() as GatewayError).code)
        }

    /** 没有错误体时按状态码映射（例如旧版网关或反向代理返回的 404 / 500 / 503）。 */
    @Test
    fun statusFallbackWithoutErrorBody() {
        assertTrue(OkHttpGatewayApi.mapError(404, "Not Found", null) is GatewayError.BadRequest)
        assertTrue(OkHttpGatewayApi.mapError(500, "", null) is GatewayError.Upstream)
        assertTrue(OkHttpGatewayApi.mapError(503, "<html>", null) is GatewayError.NotConfigured)
        assertTrue(OkHttpGatewayApi.mapError(429, "", "x") is GatewayError.RateLimited)
        assertEquals("网关返回 500", OkHttpGatewayApi.mapError(500, "", null).message)
    }

    /** [FR-AI-03] 读取超时与无法连接映射为可重试的网络错误，由路由器切换备用路线。 */
    @Test
    fun timeoutAndConnectionFailureMapToNetwork() =
        runBlocking {
            val fast = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()
            val slowApi = OkHttpGatewayApi(server.url("/").toString(), "t", "d", fast)
            server.enqueue(MockResponse.Builder().body("{}").headersDelay(2, TimeUnit.SECONDS).build())
            val timeout = slowApi.health().exceptionOrNull()
            assertTrue(timeout is GatewayError.Network)
            assertTrue((timeout as GatewayError).retryable)

            val dead = OkHttpGatewayApi("http://127.0.0.1:1", "t", "d")
            assertTrue(dead.health().exceptionOrNull() is GatewayError.Network)
        }

    /** 2xx 但正文不是 JSON 时视为上游错误。 */
    @Test
    fun malformedSuccessBodyIsUpstreamError() =
        runBlocking {
            respond(200, "<html>proxy</html>")
            assertTrue(api.health().exceptionOrNull() is GatewayError.Upstream)
        }
}
