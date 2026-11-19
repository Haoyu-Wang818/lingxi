package com.lingxi.context

/** 认知负荷等级，决定推送多少、推送什么难度的复习内容。 */
enum class CognitiveLoad {
    LOW,
    MEDIUM,
    HIGH,
}

/**
 * 一条判断依据。[weight] 为正表示支持「现在适合学」，为负表示反对。
 * 依据会原样展示在应用的「情境面板」上，让判断过程可解释。
 */
data class Evidence(
    val description: String,
    val weight: Double,
)

/**
 * 情境引擎的输出。
 *
 * @property learnable 是否判定为「可学习时刻」。
 * @property score 0–1 的适合程度。
 * @property blockedBy 若被硬性条件阻止（上课、驾驶、免打扰、夜间、冷却中），说明原因。
 * @property suggestedCards 建议推送的卡片数量，不可学习时为 0。
 */
data class ContextAssessment(
    val learnable: Boolean,
    val score: Double,
    val load: CognitiveLoad,
    val evidence: List<Evidence>,
    val blockedBy: String? = null,
    val suggestedCards: Int = 0,
)
