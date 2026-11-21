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

/**
 * 网关生成的一张卡片。[card] 的 id 由网关临时生成，知识点 id 是抽取结果中的临时 id，写库前需按合并结果映射；
 * [sourceIds] 是卡片来源的识别文本段 id，用于溯源到原图文字框 [FR-REV-10]。
 */
data class GeneratedCard(
    val card: ReviewCard,
    val sourceIds: List<String> = emptyList(),
)

/** `/v1/cards` 响应；[rejected] 为知识点 id 到拒绝原因代码的列表 [FR-REV-10]。 */
data class CardsResponse(
    val cards: List<ReviewCard>,
    val rejected: List<Pair<String, String>> = emptyList(),
    val promptVersion: String = "",
    val sourceIds: Map<String, List<String>> = emptyMap(),
)

/** 网关整理出的结构化笔记 [FR-NOTE-03]。 */
data class GeneratedNote(
    val title: String,
    val markdown: String,
)

/**
 * `/v1/extract`（with_cards）响应：一次请求得到笔记、知识点、关系与卡片 [FR-NOTE-03]、[FR-KG-01]、[FR-REV-01]。
 *
 * @property note 笔记生成失败时为 null，不影响知识点与卡片。
 * @property latencyMs 网关端整理耗时。
 */
data class OrganizeResult(
    val note: GeneratedNote?,
    val extraction: Extraction,
    val cards: List<GeneratedCard>,
    val rejected: List<Pair<String, String>>,
    val promptVersion: String,
    val latencyMs: Long,
)

/** `/health` 响应。 */
data class GatewayHealth(
    val version: String,
    val llmConfigured: Boolean,
    val model: String? = null,
)

/** `/v1/chat` 响应。 */
data class ChatReply(
    val text: String,
    val model: String,
    val latencyMs: Long,
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

    /** 识别文本 → 笔记 + 知识点 + 关系 + 卡片，一次请求完成（手机端整理流水线使用）。 */
    suspend fun organize(req: ExtractRequest): Result<OrganizeResult>

    /** 知识点 → 卡片（含手表压缩版）。 */
    suspend fun generateCards(req: CardsRequest): Result<CardsResponse>

    /** 健康检查，返回网关版本与大模型是否已配置。 */
    suspend fun health(): Result<GatewayHealth>

    /** 通用对话（答疑与 `InferenceRouter` 的云端路线）[FR-GW-03]。 */
    suspend fun chat(
        system: String,
        user: String,
        maxTokens: Int = 512,
    ): Result<ChatReply>
}
