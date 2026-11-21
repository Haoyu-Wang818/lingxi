package com.lingxi.core.network

import com.lingxi.core.model.CardType
import com.lingxi.core.model.ExtractedPoint
import com.lingxi.core.model.Extraction
import com.lingxi.core.model.RelationType
import com.lingxi.core.model.ReviewCard
import com.lingxi.core.model.ScoredEdge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 基于 OkHttp 的网关客户端 [FR-GW-01]、[FR-GW-05]。
 *
 * - 超时按 04-api.md 1.1 节：连接 5 秒、读取 30 秒。
 * - 每个请求带设备令牌、设备 id（网关按设备限流）与请求 id（端到端排查）。
 * - 非 2xx 响应按错误体中的 `code` 映射为 [GatewayError]；读不到错误体时按状态码映射。
 *
 * @param baseUrl 网关地址，模拟器访问 Mac 为 `http://10.0.2.2:8000`。
 * @param token 设备令牌（不是大模型 API Key，手机端从不持有 Key）。
 */
class OkHttpGatewayApi(
    baseUrl: String,
    private val token: String,
    private val deviceId: String,
    private val client: OkHttpClient = defaultClient(),
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) : GatewayApi {
    private val base = baseUrl.trimEnd('/')

    init {
        base.toHttpUrl() // 地址非法时尽早失败
    }

    override suspend fun health(): Result<GatewayHealth> =
        call("GET", "/health", null) { o ->
            GatewayHealth(
                version = o.optString("version", ""),
                llmConfigured = o.optBoolean("llm_configured", false),
                model = o.str("model"),
            )
        }

    override suspend fun chat(
        system: String,
        user: String,
        maxTokens: Int,
    ): Result<ChatReply> {
        val body = JSONObject().put("system", system).put("user", user).put("max_tokens", maxTokens)
        return call("POST", "/v1/chat", body) { o -> ChatReply(o.getString("text"), o.getString("model"), o.optLong("latency_ms")) }
    }

    override suspend fun extract(req: ExtractRequest): Result<Extraction> {
        val segments =
            JSONArray(
                req.segments.map { s ->
                    JSONObject()
                        .put("source_id", s.sourceId)
                        .put("kind", s.kind)
                        .put("text", s.text)
                        .apply {
                            s.page?.let { put("page", it) }
                            s.startMs?.let { put("start_ms", it) }
                            s.endMs?.let { put("end_ms", it) }
                        }
                },
            )
        val body =
            JSONObject()
                .put("course_name", req.courseName)
                .put("lesson_id", req.lessonId)
                .put("segments", segments)
                .put("existing_titles", JSONArray(req.existingTitles))
        return call("POST", "/v1/extract", body) { o -> parseExtraction(o) }
    }

    override suspend fun generateCards(req: CardsRequest): Result<CardsResponse> {
        val points =
            JSONArray(
                req.points.map {
                    JSONObject().put("id", it.id).put("title", it.title).put("summary", it.summary).put("context", it.context)
                },
            )
        val body =
            JSONObject()
                .put("course_name", req.courseName)
                .put("points", points)
                .put("per_point", req.perPoint)
                .put("types", JSONArray(req.types.map { it.name }))
                .put("require_watch_variant", req.requireWatchVariant)
        return call("POST", "/v1/cards", body) { o -> parseCards(o) }
    }

    private suspend fun <T> call(
        method: String,
        path: String,
        body: JSONObject?,
        parse: (JSONObject) -> T,
    ): Result<T> =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(base + path)
                    .header("Authorization", "Bearer $token")
                    .header("X-Device-Id", deviceId)
                    .header("X-Request-Id", newRequestId())
                    .method(method, body?.toString()?.toRequestBody(JSON))
                    .build()
            try {
                client.newCall(request).execute().use { resp ->
                    val text = resp.body.string()
                    if (!resp.isSuccessful) return@withContext Result.failure(mapError(resp.code, text, resp.header("Retry-After")))
                    Result.success(parse(JSONObject(text)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Result.failure(GatewayError.Network(e))
            } catch (e: JSONException) {
                Result.failure(GatewayError.Upstream("网关响应格式无法解析"))
            }
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** 04-api.md 1.1 节约定的超时。 */
        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

        /** 按错误体 `{"error": {"code", "message"}}` 或状态码映射为 [GatewayError]（04-api.md 1.2 节）。 */
        fun mapError(
            status: Int,
            body: String,
            retryAfter: String?,
        ): GatewayError {
            val error = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
            val message = error?.str("message") ?: "网关返回 $status"
            val code = error?.str("code")
            return when (code ?: codeForStatus(status)) {
                "BAD_REQUEST" -> GatewayError.BadRequest(message)
                "UNAUTHORIZED" -> GatewayError.Unauthorized(message)
                "TEXT_TOO_LONG" -> GatewayError.TextTooLong(message)
                "LLM_INVALID_OUTPUT" -> GatewayError.InvalidOutput(message)
                "RATE_LIMITED" -> GatewayError.RateLimited(retryAfter?.toLongOrNull(), message)
                "LLM_NOT_CONFIGURED" -> GatewayError.NotConfigured(message)
                else -> if (status in 400..499) GatewayError.BadRequest(message) else GatewayError.Upstream(message)
            }
        }

        private fun codeForStatus(status: Int) =
            when (status) {
                400 -> "BAD_REQUEST"
                401 -> "UNAUTHORIZED"
                413 -> "TEXT_TOO_LONG"
                422 -> "LLM_INVALID_OUTPUT"
                429 -> "RATE_LIMITED"
                503 -> "LLM_NOT_CONFIGURED"
                else -> "LLM_UPSTREAM"
            }

        internal fun parseExtraction(o: JSONObject): Extraction {
            val points = o.getJSONArray("points").objects().map { p ->
                ExtractedPoint(
                    id = p.getString("id"),
                    title = p.getString("title"),
                    summary = p.optString("summary", ""),
                    sourceIds = p.optJSONArray("source_ids")?.strings() ?: emptyList(),
                )
            }
            val edges =
                o.optJSONArray("edges")?.objects()?.mapNotNull { e ->
                    val relation = runCatching { RelationType.valueOf(e.getString("relation")) }.getOrNull() ?: return@mapNotNull null
                    ScoredEdge(e.getString("from"), e.getString("to"), relation, e.optDouble("confidence", DEFAULT_CONFIDENCE))
                } ?: emptyList()
            return Extraction(points, edges, o.optString("prompt_version", ""))
        }

        internal fun parseCards(o: JSONObject): CardsResponse {
            val cards =
                o.getJSONArray("cards").objects().mapIndexedNotNull { i, c ->
                    val type = runCatching { CardType.valueOf(c.getString("type")) }.getOrNull() ?: return@mapIndexedNotNull null
                    val options = c.optJSONArray("options")?.strings() ?: emptyList()
                    ReviewCard(
                        id = c.str("id") ?: "gen-$i-${UUID.randomUUID()}",
                        knowledgePointId = c.getString("point_id"),
                        question = c.getString("question"),
                        answer = c.getString("answer"),
                        type = type,
                        options = options,
                        correctIndex = if (c.has("correct_index")) c.getInt("correct_index") else null,
                        watchPrompt = c.str("watch_prompt"),
                        watchAnswer = c.str("watch_answer"),
                    )
                }
            val rejected = o.optJSONArray("rejected")?.objects()?.map { it.getString("point_id") to it.getString("reason") } ?: emptyList()
            return CardsResponse(cards, rejected, o.optString("prompt_version", ""))
        }

        private const val DEFAULT_CONFIDENCE = 0.8

        /** JSON 中缺失、为 null 或空串的字段都视为 null（org.json 的 optString 会把 null 读成 "null"）。 */
        private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

        private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    }
}
