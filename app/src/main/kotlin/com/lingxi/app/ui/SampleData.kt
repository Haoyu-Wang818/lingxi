package com.lingxi.app.ui

import com.lingxi.core.model.CardType
import com.lingxi.core.model.KnowledgeEdge
import com.lingxi.core.model.KnowledgePoint
import com.lingxi.core.model.Rating
import com.lingxi.core.model.RelationType
import com.lingxi.core.model.ReviewCard
import com.lingxi.core.model.SourceRef
import com.lingxi.core.review.DeckCard
import com.lingxi.core.review.FsrsScheduler

/**
 * 原型阶段的演示数据：高等数学第 5 讲「泰勒公式」一节课（11 月 18 日板书 4 页）。
 * 8 张卡片覆盖问答、挖空、判断、单选四种题型 [FR-REV-11]，每张都带板书来源与手表压缩版 [FR-REV-10]、[FR-WEAR-08]。
 * 接入数据层（Room）和大模型抽取后，这些数据会被真实笔记替换。
 */
object SampleData {
    private const val COURSE = "高等数学"
    private const val LECTURE_DATE = "11月18日"
    private const val BOARD = "cap-1118-board"

    val points =
        listOf(
            KnowledgePoint("limit", "极限", "函数在某点附近的趋近行为，是连续与导数的基础。", "math"),
            KnowledgePoint("continuity", "连续与可导", "可导必连续，连续不一定可导（如 |x| 在 x=0 处）。", "math"),
            KnowledgePoint("derivative", "导数", "函数在某点的瞬时变化率：f′(x₀) = lim Δy/Δx。", "math"),
            KnowledgePoint("taylor", "泰勒公式", "在 x₀ 附近用多项式逼近函数，k 次项系数为 f⁽ᵏ⁾(x₀)/k!。", "math"),
            KnowledgePoint("maclaurin", "麦克劳林公式", "x₀ = 0 时的泰勒公式，常用于求极限和近似计算。", "math"),
            KnowledgePoint("remainder", "泰勒余项", "多项式逼近的误差：佩亚诺型刻画局部阶数，拉格朗日型可定量估计误差。", "math"),
            KnowledgePoint("lhopital", "洛必达法则", "0/0 或 ∞/∞ 型未定式，可改求导数之比的极限。", "math"),
        )

    val edges =
        listOf(
            KnowledgeEdge("limit", "continuity", RelationType.PREREQUISITE),
            KnowledgeEdge("limit", "derivative", RelationType.PREREQUISITE),
            KnowledgeEdge("continuity", "derivative", RelationType.RELATED),
            KnowledgeEdge("derivative", "taylor", RelationType.PREREQUISITE),
            KnowledgeEdge("maclaurin", "taylor", RelationType.EXAMPLE_OF),
            KnowledgeEdge("remainder", "taylor", RelationType.PART_OF),
            KnowledgeEdge("derivative", "lhopital", RelationType.PREREQUISITE),
            KnowledgeEdge("lhopital", "limit", RelationType.RELATED),
            KnowledgeEdge("taylor", "lhopital", RelationType.RELATED),
        )

    private fun board(page: Int) = SourceRef(captureId = BOARD, page = page)

    val cards =
        listOf(
            ReviewCard(
                id = "c1",
                knowledgePointId = "taylor",
                question = "f(x) 在 x₀ 处的 n 阶泰勒多项式中，(x − x₀)ⁿ 项的系数是什么？",
                answer = "f⁽ⁿ⁾(x₀) / n!",
                watchPrompt = "泰勒展开第 n 项系数？",
                watchAnswer = "f⁽ⁿ⁾(x₀)/n!",
                sourceRef = board(1),
            ),
            ReviewCard(
                id = "c2",
                knowledgePointId = "maclaurin",
                question = "eˣ 的麦克劳林展开：eˣ = {{c1::1 + x + x²/2! + x³/3! + …}}",
                answer = "1 + x + x²/2! + x³/3! + …",
                type = CardType.CLOZE,
                watchPrompt = "eˣ 麦克劳林前三项？",
                watchAnswer = "1+x+x²/2",
                sourceRef = board(2),
            ),
            ReviewCard(
                id = "c3",
                knowledgePointId = "maclaurin",
                question = "sin x 在 x = 0 处带佩亚诺余项的三阶泰勒展开是？",
                answer = "x − x³/6 + o(x³)",
                type = CardType.SINGLE_CHOICE,
                options = listOf("x − x³/6 + o(x³)", "x + x³/6 + o(x³)", "1 − x²/2 + o(x³)", "x − x³/3 + o(x³)"),
                correctIndex = 0,
                watchPrompt = "sin x 三阶麦克劳林展开？",
                watchAnswer = "x−x³/6",
                sourceRef = board(2),
            ),
            ReviewCard(
                id = "c4",
                knowledgePointId = "lhopital",
                question = "0/0 型极限中，若 f、g 在该点去心邻域可导、g′ ≠ 0，且 lim f′/g′ 存在，则 lim f/g 等于它。",
                answer = "正确",
                type = CardType.TRUE_FALSE,
                options = listOf("正确", "错误"),
                correctIndex = 0,
                watchPrompt = "0/0 型可用洛必达？",
                watchAnswer = "可以，需导数比极限存在",
                sourceRef = board(4),
            ),
            ReviewCard(
                id = "c5",
                knowledgePointId = "continuity",
                question = "函数在某点连续，则在该点一定可导。",
                answer = "错误。反例：|x| 在 x = 0 处连续但不可导",
                type = CardType.TRUE_FALSE,
                options = listOf("正确", "错误"),
                correctIndex = 1,
                watchPrompt = "连续一定可导？",
                watchAnswer = "否，反例 |x|",
                sourceRef = board(1),
            ),
            ReviewCard(
                id = "c6",
                knowledgePointId = "remainder",
                question = "泰勒公式的佩亚诺余项和拉格朗日余项分别是什么形式？",
                answer = "佩亚诺：o((x − x₀)ⁿ)；拉格朗日：f⁽ⁿ⁺¹⁾(ξ)(x − x₀)ⁿ⁺¹ / (n+1)!，ξ 在 x₀ 与 x 之间",
                watchPrompt = "佩亚诺余项写作？",
                watchAnswer = "o((x−x₀)ⁿ)",
                sourceRef = board(3),
            ),
            ReviewCard(
                id = "c7",
                knowledgePointId = "taylor",
                question = "用泰勒展开求极限：lim(x→0) (x − sin x) / x³ = {{c1::1/6}}",
                answer = "1/6",
                type = CardType.CLOZE,
                watchPrompt = "x→0，(x−sinx)/x³ 极限？",
                watchAnswer = "1/6",
                sourceRef = board(4),
            ),
            ReviewCard(
                id = "c8",
                knowledgePointId = "maclaurin",
                question = "ln(1 + x) 的麦克劳林展开中，x³ 项的系数是？",
                answer = "1/3（ln(1+x) = x − x²/2 + x³/3 − …）",
                type = CardType.SINGLE_CHOICE,
                options = listOf("1/3", "−1/3", "1/6", "1/2"),
                correctIndex = 0,
                watchPrompt = "ln(1+x) 展开中 x³ 系数？",
                watchAnswer = "1/3",
                sourceRef = board(3),
            ),
        )

    /** 连续学习天数（演示值）。 */
    const val STREAK_DAYS = 6

    /** 按每张约 15 秒估算今日用时，首页与复习页顶部显示「今日 N 张，约 M 分钟」[FR-REV-08]。 */
    fun todayLine(count: Int = cards.size): String = "今日 $count 张，约 ${(count * 15 + 59) / 60} 分钟"

    /**
     * 把卡片与知识点、图谱邻居、板书来源打包成复习页需要的 [DeckCard]。
     * 后 4 张模拟 3 天前已学过一次，让按钮上的间隔与新卡不同，体现 FSRS 按记忆状态调度。
     */
    fun deck(nowMillis: Long = System.currentTimeMillis()): List<DeckCard> {
        val byId = points.associateBy { it.id }
        val fsrs = FsrsScheduler()
        val reviewedAt = nowMillis - 3 * FsrsScheduler.DAY_MILLIS.toLong()
        return cards.mapIndexed { index, card ->
            val point = byId.getValue(card.knowledgePointId)
            DeckCard(
                card = card,
                courseName = COURSE,
                pointTitle = point.title,
                pointSummary = point.summary,
                sourceLabel = card.sourceRef?.page?.let { "$LECTURE_DATE 板书 第${it}页" },
                prerequisites = neighbors(point.id, prerequisite = true).map { byId.getValue(it).title },
                related = neighbors(point.id, prerequisite = false).map { byId.getValue(it).title },
                memory = if (index >= 4) fsrs.review(null, Rating.GOOD, reviewedAt).state else null,
            )
        }
    }

    /** 前置：指向该点的先修边；相关：其余任意方向的边（含后续知识点）。 */
    private fun neighbors(
        id: String,
        prerequisite: Boolean,
    ): List<String> =
        if (prerequisite) {
            edges.filter { it.toId == id && it.relation == RelationType.PREREQUISITE }.map { it.fromId }
        } else {
            edges
                .filter { (it.fromId == id || it.toId == id) && !(it.toId == id && it.relation == RelationType.PREREQUISITE) }
                .map { if (it.fromId == id) it.toId else it.fromId }
                .distinct()
        }

    val notes =
        listOf(
            "高等数学 · 第 5 讲 泰勒公式（板书 4 张，录音 12 分钟）",
            "高等数学 · 第 4 讲 洛必达法则（板书 3 张）",
            "线性代数 · 第 3 讲 矩阵的秩（课件 PDF 18 页）",
        )
}
