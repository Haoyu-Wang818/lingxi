package com.lingxi.core.quality

import com.lingxi.core.model.CardType
import com.lingxi.core.model.ReviewCard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class RuleBasedCardValidatorTest {
    private val card = ReviewCard("c1", "k1", "可导就能任意阶泰勒展开吗？", "错误", CardType.TRUE_FALSE)

    @Test
    @DisplayName("骨架校验器没有规则时，所有卡片都合格")
    fun emptyRulesAcceptAll() {
        assertEquals(emptyList<Violation>(), RuleBasedCardValidator().validate(card))
    }

    @Test
    @DisplayName("规则返回的违规会被汇总，且手表版本随之不可用")
    fun collectsViolations() {
        val strip = CardRule { Violation("WATCH_PROMPT_TOO_LONG", Severity.STRIP_WATCH) }
        val validator = RuleBasedCardValidator(listOf(strip, CardRule { null }))
        val withWatch = card.copy(watchPrompt = "可导就能展开？", watchAnswer = "错")

        assertEquals(listOf("WATCH_PROMPT_TOO_LONG"), validator.validate(withWatch).map { it.code })
        assertFalse(validator.watchVariantOk(withWatch))
    }

    @Test
    @DisplayName("没有手表字段的卡片不可推送到手表")
    fun watchVariantRequiresFields() {
        val validator = RuleBasedCardValidator()
        assertFalse(validator.watchVariantOk(card))
        assertTrue(validator.watchVariantOk(card.copy(watchPrompt = "可导就能展开？", watchAnswer = "错")))
    }
}
