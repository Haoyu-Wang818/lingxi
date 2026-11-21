package com.lingxi.core.model

/**
 * 网关 `/v1/extract` 返回的一次知识抽取结果，尚未与已有图谱合并 [FR-KG-01]。
 *
 * 放在 `core:model` 中，是因为网关客户端（产生它）、质量校验（检查它）与仓库层（合并它）
 * 都要用到，而 core 模块之间只允许依赖 `core:model`。
 */
data class Extraction(
    val points: List<ExtractedPoint>,
    val edges: List<ScoredEdge>,
    val promptVersion: String = "",
)

/**
 * 抽取出的知识点候选。[id] 只在本次抽取内唯一，合并时会映射为图谱中的稳定 id [FR-KG-02]。
 *
 * @property sourceIds 出现该知识点的捕获 id，用于溯源。
 */
data class ExtractedPoint(
    val id: String,
    val title: String,
    val summary: String,
    val sourceIds: List<String> = emptyList(),
)

/**
 * 带置信度的候选关系边。与 [KnowledgeEdge] 不同，它允许暂时不合法（如自环），
 * 由 `core:quality` 的关系校验决定去留 [FR-KG-03]。
 *
 * @property confidence 模型给出的置信度，取值 0–1，先修环冲突时保留置信度高的边。
 */
data class ScoredEdge(
    val fromId: String,
    val toId: String,
    val relation: RelationType,
    val confidence: Double,
)
