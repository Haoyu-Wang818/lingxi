package com.lingxi.core.quality

/** 违规的处理方式 [FR-REV-10]、[FR-WEAR-08]。 */
enum class Severity {
    /** 丢弃整张卡片。 */
    DROP,

    /** 只清空手表版本（watchPrompt / watchAnswer），卡片在手机端保留。 */
    STRIP_WATCH,
}

/**
 * 一条规则校验不通过的结果。
 *
 * @property code 机器可读的违规代码，与网关 `/v1/cards` 的 `rejected.reason` 取值一致，
 *   例如 `WATCH_PROMPT_TOO_LONG`，便于两端对账。
 * @property message 给开发者看的中文说明。
 */
data class Violation(
    val code: String,
    val severity: Severity,
    val message: String = "",
)
