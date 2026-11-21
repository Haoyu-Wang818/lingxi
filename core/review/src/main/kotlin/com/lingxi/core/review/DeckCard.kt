package com.lingxi.core.review

import com.lingxi.core.model.CardType
import com.lingxi.core.model.ReviewCard
import java.time.Duration

/**
 * 复习页一张卡片所需的全部信息：卡片本身 + 背面要展示的课堂溯源与图谱上下文 [FR-REV-07]、[FR-REV-10]。
 *
 * 背面展示「这张卡来自哪堂课、和哪些知识点相连」是与背单词 App 的主要差异，
 * 所以把这些上下文和卡片打包在一起，复习会话和界面都不必再去查仓库。
 *
 * @property courseName 课程名，例如「高等数学」。
 * @property pointTitle 知识点名称，显示在顶栏与小结中。
 * @property pointSummary 知识点一句话解释（`KnowledgePoint.summary`）。
 * @property sourceLabel 课堂溯源的可读文本，例如「11月18日 板书 第3页」；为空表示没有来源。
 * @property prerequisites 图谱中的前置知识点名称。
 * @property related 图谱中的相关知识点名称。
 * @property memory 卡片当前的记忆状态，新卡为 null。
 */
data class DeckCard(
    val card: ReviewCard,
    val courseName: String,
    val pointTitle: String,
    val pointSummary: String,
    val sourceLabel: String? = null,
    val prerequisites: List<String> = emptyList(),
    val related: List<String> = emptyList(),
    val memory: MemoryState? = null,
)

/** 客观题（判断、单选）的选项与正确答案，用于自动判分 [FR-REV-13]。 */
data class Choices(
    val options: List<String>,
    val correctIndex: Int,
)

/** 卡片文本的展示规则：挖空、选项、间隔格式化。全部为纯函数，便于单测。 */
object CardText {
    private val CLOZE = Regex("""\{\{c1::(.+?)}}""")

    /** 挖空题正面：把 `{{c1::…}}` 换成下划线；其他题型原样返回。 */
    fun front(card: ReviewCard): String =
        if (card.type == CardType.CLOZE) card.question.replace(CLOZE, "____") else card.question

    /** 挖空题背面：把挖空处填回答案并用【】标出；其他题型原样返回问题。 */
    fun back(card: ReviewCard): String =
        if (card.type == CardType.CLOZE) card.question.replace(CLOZE) { "【${it.groupValues[1]}】" } else card.question

    /**
     * 卡片的客观题选项；不是客观题时返回 null。
     *
     * 有 `options` 的卡直接使用；判断题没给选项时默认「正确 / 错误」，
     * 正确项取 `correctIndex`，缺省时按答案文字推断。
     */
    fun choices(card: ReviewCard): Choices? {
        if (card.options.isNotEmpty()) {
            val index = card.correctIndex ?: return null
            if (index !in card.options.indices) return null
            return Choices(card.options, index)
        }
        if (card.type != CardType.TRUE_FALSE) return null
        val index = card.correctIndex ?: if (TRUE_WORDS.any { card.answer.startsWith(it) }) 0 else 1
        return Choices(listOf("正确", "错误"), index)
    }

    /** 把间隔格式化成按钮上的短文本：10分、3小时、4天、2个月、1年。 */
    fun interval(duration: Duration): String {
        val minutes = duration.toMinutes()
        val days = duration.toMillis() / DAY_MILLIS
        return when {
            minutes < 60 -> "${minutes.coerceAtLeast(1)}分"
            days < 1.0 -> "${duration.toHours()}小时"
            days < 30.0 -> "${Math.round(days).coerceAtLeast(1)}天"
            days < 365.0 -> "${Math.round(days / 30).coerceAtLeast(1)}个月"
            else -> "${Math.round(days / 365)}年"
        }
    }

    private const val DAY_MILLIS = 86_400_000.0
    private val TRUE_WORDS = listOf("正确", "对", "是", "可以", "√", "✓")
}
