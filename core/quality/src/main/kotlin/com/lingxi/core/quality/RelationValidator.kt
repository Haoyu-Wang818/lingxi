package com.lingxi.core.quality

import com.lingxi.core.model.KnowledgeEdge
import com.lingxi.core.model.ScoredEdge

/**
 * 关系校验的结果 [FR-KG-03]。
 *
 * @property accepted 可以写入图谱的边。
 * @property rejected 被丢弃的边及原因，原因写入冲突日志，便于调参与演示。
 */
data class RelationCheck(
    val accepted: List<KnowledgeEdge>,
    val rejected: List<Pair<ScoredEdge, Violation>>,
)

/**
 * 知识关系校验 [FR-KG-03]：丢弃自环、端点不存在、重复的边，并保证先修关系无环。
 *
 * 先修环检测按置信度从高到低逐条加入，冲突时丢弃置信度低的边（见详细设计 2.3 节）。
 */
interface RelationValidator {
    /**
     * @param candidates 本次抽取得到的候选边（端点已映射为图谱 id）。
     * @param knownPointIds 合并后图谱中存在的全部知识点 id。
     * @param existing 图谱中已有的边，参与先修环检测但不会被丢弃。
     */
    fun validate(
        candidates: List<ScoredEdge>,
        knownPointIds: Set<String>,
        existing: List<KnowledgeEdge>,
    ): RelationCheck
}
