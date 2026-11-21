package com.lingxi.core.quality

import com.lingxi.core.model.ReviewCard

/**
 * 一条卡片规则，例如「单选题恰好一个正确项」「手表题面 ≤ 20 字」[FR-REV-10]。
 *
 * 每条规则独立、无状态，便于逐条单测并与网关的规则表一一对应。
 */
fun interface CardRule {
    /** 检查一张卡片，通过时返回 null。字数一律按 Unicode 码点计数。 */
    fun check(card: ReviewCard): Violation?
}

/**
 * 卡片质量校验入口 [FR-REV-10]、[FR-WEAR-08]。
 *
 * 手机端在写入卡片前再校验一次，规则与网关 `/v1/cards` 相同，防止网关版本不一致或
 * 本地编辑产生不合规卡片。
 */
interface CardValidator {
    /** 返回全部违规；空列表表示卡片合格。 */
    fun validate(card: ReviewCard): List<Violation>

    /** 手表版本是否可用：手表字段齐全且没有 [Severity.STRIP_WATCH] 与 [Severity.DROP] 违规。 */
    fun watchVariantOk(card: ReviewCard): Boolean
}

/**
 * 由一组 [CardRule] 组成的校验器。
 *
 * 骨架阶段默认不带任何规则，因而所有卡片都合格；具体规则在 I2 迭代中补齐。
 */
class RuleBasedCardValidator(
    private val rules: List<CardRule> = emptyList(),
) : CardValidator {
    override fun validate(card: ReviewCard): List<Violation> = rules.mapNotNull { it.check(card) }

    override fun watchVariantOk(card: ReviewCard): Boolean =
        card.watchPrompt != null &&
            card.watchAnswer != null &&
            validate(card).isEmpty()
}
