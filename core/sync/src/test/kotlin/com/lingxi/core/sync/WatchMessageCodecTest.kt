package com.lingxi.core.sync

import com.lingxi.core.model.CardType
import com.lingxi.core.model.ReviewCard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WatchMessageCodecTest {
    @Test
    @DisplayName("三种消息编码后都能原样解码")
    fun allMessageTypesRoundTrip() {
        val messages =
            listOf(
                WatchMessage.PushCard("m1", "c1", "0/0 型可以用洛必达吗？", listOf("对", "错"), "你刚结束步行，心率平稳"),
                WatchMessage.ReviewResult(
                    "m2",
                    "c1",
                    grade = 3,
                    answeredAtEpochMs = 1_700_000_000_000,
                    responseMs = 4200,
                ),
                WatchMessage.ContextSignal(
                    "m3",
                    atEpochMs = 1_700_000_000_000,
                    heartRateBpm = 72,
                    stepsLastMinute = null,
                ),
            )
        messages.forEach { assertEquals(it, WatchMessageCodec.decode(WatchMessageCodec.encode(it))) }
    }

    @Test
    @DisplayName("JSON 中用 type 字段区分消息类型，便于 Python 网关和调试工具读取")
    fun encodesTypeDiscriminator() {
        val text = WatchMessageCodec.encode(WatchMessage.ReviewResult("m2", "c1", 4, 0, 0))
        assertTrue(text.contains("\"type\":\"review_result\""), text)
    }

    @Test
    @DisplayName("未知类型、损坏文本和非法评分都被丢弃而不是抛异常")
    fun invalidInputDecodesToNull() {
        assertNull(WatchMessageCodec.decode("""{"type":"future_type","id":"x"}"""))
        assertNull(WatchMessageCodec.decode("not json"))
        assertNull(
            WatchMessageCodec.decode(
                """{"type":"review_result","id":"x","cardId":"c","grade":9,"answeredAtEpochMs":0,"responseMs":0}""",
            ),
        )
    }

    @Test
    @DisplayName("新版本多出的字段会被忽略，保证手机和手表版本不一致时仍能通信")
    fun ignoresUnknownFields() {
        val decoded = WatchMessageCodec.decode("""{"type":"context_signal","id":"s","atEpochMs":1,"newField":true}""")
        assertEquals(WatchMessage.ContextSignal("s", atEpochMs = 1), decoded)
    }

    @Test
    @DisplayName("问答题不适合在手表上作答，转换时被拒绝")
    fun rejectsCardsThatDoNotFitWatch() {
        val card = ReviewCard("c9", "k", "请推导泰勒公式", "略", CardType.QUESTION_ANSWER)
        assertThrows<IllegalArgumentException> { WatchMessage.PushCard.from("m", card, emptyList(), "测试") }
    }
}
