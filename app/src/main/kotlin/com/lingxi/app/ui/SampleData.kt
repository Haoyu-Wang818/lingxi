package com.lingxi.app.ui

import com.lingxi.core.model.CardType
import com.lingxi.core.model.KnowledgeEdge
import com.lingxi.core.model.KnowledgePoint
import com.lingxi.core.model.RelationType
import com.lingxi.core.model.ReviewCard

/**
 * 原型阶段的演示数据（高等数学一节课）。
 * 接入数据层（Room）和大模型抽取后，这些数据会被真实笔记替换。
 */
object SampleData {
    val points =
        listOf(
            KnowledgePoint("limit", "极限", "函数在某点附近的趋近行为。", "math"),
            KnowledgePoint("derivative", "导数", "函数在某点的瞬时变化率。", "math"),
            KnowledgePoint("taylor", "泰勒展开", "用多项式在一点附近逼近函数。", "math"),
            KnowledgePoint("maclaurin", "麦克劳林公式", "在 0 点处的泰勒展开。", "math"),
            KnowledgePoint("lhopital", "洛必达法则", "用导数之比求未定式极限。", "math"),
        )

    val edges =
        listOf(
            KnowledgeEdge("limit", "derivative", RelationType.PREREQUISITE),
            KnowledgeEdge("derivative", "taylor", RelationType.PREREQUISITE),
            KnowledgeEdge("maclaurin", "taylor", RelationType.EXAMPLE_OF),
            KnowledgeEdge("derivative", "lhopital", RelationType.PREREQUISITE),
            KnowledgeEdge("lhopital", "limit", RelationType.RELATED),
        )

    val cards =
        listOf(
            ReviewCard("c1", "taylor", "泰勒展开中第 n 项的系数是什么？", "f⁽ⁿ⁾(a) / n!"),
            ReviewCard("c2", "maclaurin", "eˣ 的麦克劳林展开前三项？", "1 + x + x²/2"),
            ReviewCard("c3", "lhopital", "0/0 型未定式可以用洛必达法则吗？", "可以", CardType.TRUE_FALSE),
        )

    val notes =
        listOf(
            "高等数学 · 第 5 讲 泰勒公式（板书 2 张，录音 12 分钟）",
            "高等数学 · 第 4 讲 洛必达法则（板书 3 张）",
            "线性代数 · 第 3 讲 矩阵的秩（课件 PDF 18 页）",
        )
}
