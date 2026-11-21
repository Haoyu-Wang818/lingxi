package com.lingxi.core.network

import com.lingxi.core.model.CardType
import com.lingxi.core.model.Extraction
import com.lingxi.core.model.ReviewCard

/** `/v1/extract` 请求中的一段识别文本，[sourceId] 用于溯源 [FR-GW-01]。 */
data class TextSegment(
    val sourceId: String,
    val kind: String,
    val text: String,
    val page: Int? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
)

/**
 * `/v1/extract` 请求 [FR-GW-01]、[FR-KG-01]。
 *
 * @property existingTitles 已有知识点名称，让模型复用名称以减少重复节点 [FR-KG-02]。
 */
data class ExtractRequest(
    val courseName: String,
    val lessonId: String,
    val segments: List<TextSegment>,
    val existingTitles: List<String> = emptyList(),
)

/** `/v1/cards` 请求中的一个知识点及其上下文。 */
data class CardPointInput(
    val id: String,
    val title: String,
    val summary: String,
    val context: String = "",
)

/** `/v1/cards` 请求 [FR-GW-02]、[FR-REV-01]。 */
data class CardsRequest(
    val courseName: String,
    val points: List<CardPointInput>,
    val perPoint: Int = 3,
    val types: List<CardType> = listOf(CardType.CLOZE, CardType.TRUE_FALSE, CardType.SINGLE_CHOICE, CardType.QUESTION_ANSWER),
    val requireWatchVariant: Boolean = true,
)

/** `/v1/cards` 响应；[rejected] 为知识点 id 到拒绝原因代码的列表 [FR-REV-10]。 */
data class CardsResponse(
    val cards: List<ReviewCard>,
    val rejected: List<Pair<String, String>> = emptyList(),
    val promptVersion: String = "",
)

/** `/health` 响应。 */
data class GatewayHealth(
    val version: String,
    val llmConfigured: Boolean,
    val model: String? = null,
)

/**
 * 云端网关客户端 [FR-GW-01]、[FR-GW-02]。
 *
 * 手机端不持有大模型 API Key，所有云端 AI 调用都经过网关。失败统一映射为 [GatewayError]，
 * 由 `InferenceRouter` 与待办队列决定是否重试或切换 [FR-AI-03]、[FR-AI-05]。
 */
interface GatewayApi {
    /** 识别文本 → 知识点与关系。 */
    suspend fun extract(req: ExtractRequest): Result<Extraction>

    /** 知识点 → 卡片（含手表压缩版）。 */
    suspend fun generateCards(req: CardsRequest): Result<CardsResponse>

    /** 健康检查，返回网关版本与大模型是否已配置。 */
    suspend fun health(): Result<GatewayHealth>
}
