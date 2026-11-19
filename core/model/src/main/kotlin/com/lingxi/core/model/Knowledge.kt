package com.lingxi.core.model

/**
 * 个人知识图谱中的一个知识点。
 *
 * 知识点由大模型从课堂笔记中抽取，同一知识点在多次课堂中出现时会被合并（按 [id] 去重）。
 *
 * @property id 稳定的唯一标识，合并时保持不变。
 * @property title 知识点名称，例如「泰勒展开」。
 * @property summary 一到两句话的解释，用于卡片背面与图谱节点提示。
 * @property courseId 所属课程。
 * @property sourceNoteIds 出现过该知识点的笔记，用于溯源到原始板书或录音。
 */
data class KnowledgePoint(
    val id: String,
    val title: String,
    val summary: String,
    val courseId: String,
    val sourceNoteIds: Set<String> = emptySet(),
) {
    init {
        require(id.isNotBlank()) { "知识点 id 不能为空" }
        require(title.isNotBlank()) { "知识点名称不能为空" }
    }
}

/** 知识点之间的关系类型。 */
enum class RelationType {
    /** 学习 to 之前需要先掌握 from。 */
    PREREQUISITE,

    /** from 是 to 的组成部分。 */
    PART_OF,

    /** from 是 to 的一个例子或应用。 */
    EXAMPLE_OF,

    /** 一般相关，没有明确方向。 */
    RELATED,
}

/** 知识图谱中的一条有向边。 */
data class KnowledgeEdge(
    val fromId: String,
    val toId: String,
    val relation: RelationType,
) {
    init {
        require(fromId != toId) { "知识点不能与自身建立关系" }
    }
}
