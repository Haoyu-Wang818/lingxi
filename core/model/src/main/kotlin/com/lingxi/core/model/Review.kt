package com.lingxi.core.model

/**
 * 一张复习卡片，绑定到一个知识点。
 *
 * 手机端展示完整的 [question] / [answer]；手表端只展示压缩后的 [watchPrompt] / [watchAnswer]，
 * 两者为空表示这张卡不推送到手表 [FR-WEAR-08]。每张卡通过 [sourceRef] 溯源到原始板书或录音 [FR-REV-10]。
 *
 * @property options 单选题的选项，其他题型为空。
 * @property correctIndex 单选题正确项在 [options] 中的下标，其他题型为 null。
 * @property watchPrompt 手表版题面，不超过 20 个字（按 Unicode 码点计数）。
 * @property watchAnswer 手表版答案，不超过 12 个字。
 * @property sourceRef 卡片来源，用于卡片背面「来自哪页板书」。
 */
data class ReviewCard(
    val id: String,
    val knowledgePointId: String,
    val question: String,
    val answer: String,
    val type: CardType = CardType.QUESTION_ANSWER,
    val options: List<String> = emptyList(),
    val correctIndex: Int? = null,
    val watchPrompt: String? = null,
    val watchAnswer: String? = null,
    val sourceRef: SourceRef? = null,
)

/**
 * 卡片或知识点的来源定位 [FR-REV-10]、[FR-NOTE-05]。
 *
 * 照片来源用 [page] 与 [box] 定位到板书区域；录音来源用 [startMs] / [endMs] 定位到片段。
 * 对应数据库中的 `source_ref_json` 字段。
 *
 * @property captureId 原始捕获（照片或录音）的 id。
 * @property box 图片中的区域，依次为左、上、右、下（像素），未知时为空。
 */
data class SourceRef(
    val captureId: String,
    val page: Int? = null,
    val box: List<Int> = emptyList(),
    val startMs: Long? = null,
    val endMs: Long? = null,
)

/** 卡片形式。手表上只适合展示 [TRUE_FALSE] 与 [SINGLE_CHOICE] 这类能快速作答的题型。 */
enum class CardType(
    val fitsWatch: Boolean,
) {
    QUESTION_ANSWER(fitsWatch = false),
    TRUE_FALSE(fitsWatch = true),
    SINGLE_CHOICE(fitsWatch = true),
    VOICE_EXPLAIN(fitsWatch = true),

    /** 挖空题，题面含且仅含一个 `{{c1::…}}`；手表屏幕太小，不推送 [FR-REV-10]。 */
    CLOZE(fitsWatch = false),
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
