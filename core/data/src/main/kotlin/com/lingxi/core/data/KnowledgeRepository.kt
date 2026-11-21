package com.lingxi.core.data

import com.lingxi.core.model.Extraction
import com.lingxi.core.model.KnowledgeEdge
import com.lingxi.core.model.KnowledgePoint

/** 一门课程的知识图谱快照，供 `feature:graph` 渲染 [FR-KG-04]。 */
data class KnowledgeGraph(
    val points: List<KnowledgePoint>,
    val edges: List<KnowledgeEdge>,
)

/**
 * 一次合并的结果 [FR-KG-02]。
 *
 * @property idMap 抽取结果中的临时 id 到图谱稳定 id 的映射。
 * @property createdIds 本次新建的知识点，后续只为它们生成卡片。
 * @property rejectedEdges 被关系校验丢弃的边数量。
 */
data class MergeResult(
    val idMap: Map<String, String>,
    val createdIds: Set<String>,
    val rejectedEdges: Int = 0,
)

/**
 * 知识点与关系的仓库 [FR-KG-02]、[FR-KG-03]。
 *
 * 合并去重与关系写入在同一事务内完成，中途异常整体回滚（TC-KG-06）。
 */
interface KnowledgeRepository {
    /** 把一次课堂抽取结果合并进该课次所属课程的图谱 [FR-KG-02]。 */
    suspend fun mergeExtraction(
        lessonId: String,
        extraction: Extraction,
    ): MergeResult

    /** 某门课程的完整图谱 [FR-KG-04]。 */
    suspend fun graph(courseId: String): KnowledgeGraph

    /** 某知识点的直接前置知识点，用于图谱感知排序与前置失败顺延 [FR-REV-15]。 */
    suspend fun prerequisites(kpId: String): List<KnowledgePoint>
}
