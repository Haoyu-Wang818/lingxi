package com.lingxi.core.model

/** 一张复习卡片，绑定到一个知识点。 */
data class ReviewCard(
    val id: String,
    val knowledgePointId: String,
    val question: String,
    val answer: String,
    val type: CardType = CardType.QUESTION_ANSWER,
)

/** 卡片形式。手表上只适合展示 [TRUE_FALSE] 与 [SINGLE_CHOICE] 这类能快速作答的题型。 */
enum class CardType(
    val fitsWatch: Boolean,
) {
    QUESTION_ANSWER(fitsWatch = false),
    TRUE_FALSE(fitsWatch = true),
    SINGLE_CHOICE(fitsWatch = true),
    VOICE_EXPLAIN(fitsWatch = true),
}

/** 用户对一次复习的自评，取值与 FSRS 算法的 1–4 分一致。 */
enum class Rating(
    val grade: Int,
) {
    AGAIN(1),
    HARD(2),
    GOOD(3),
    EASY(4),
}
