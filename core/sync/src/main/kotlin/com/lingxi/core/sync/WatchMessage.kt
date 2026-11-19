package com.lingxi.core.sync

import com.lingxi.core.model.CardType
import com.lingxi.core.model.ReviewCard
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 手机与手表之间传递的消息。
 *
 * 消息与传输方式无关：同一条消息既可以走 Wear Data Layer，也可以走本地 WebSocket 中继，
 * 这样没有 Google Play 服务的手机（例如国行手机）也能和手表协同。
 * 每条消息带唯一 [id]，接收端据此去重，因此主备两条链路同时送达也只处理一次。
 */
@Serializable
sealed interface WatchMessage {
    val id: String

    /** 手机推给手表的一张微复习卡片，[reason] 用于在手表上解释「为什么现在推送」。 */
    @Serializable
    @SerialName("push_card")
    data class PushCard(
        override val id: String,
        val cardId: String,
        val question: String,
        val options: List<String>,
        val reason: String,
    ) : WatchMessage {
        companion object {
            /** 把一张复习卡片转成手表消息；不适合在手表上作答的题型会被拒绝。 */
            fun from(
                id: String,
                card: ReviewCard,
                options: List<String>,
                reason: String,
            ): PushCard {
                require(card.type.fitsWatch) { "题型 ${card.type} 不适合在手表上作答" }
                require(card.type != CardType.SINGLE_CHOICE || options.size >= 2) { "单选题至少需要两个选项" }
                return PushCard(id, card.id, card.question, options, reason)
            }
        }
    }

    /** 手表回传的作答结果，[grade] 与 FSRS 的 1 到 4 分一致。 */
    @Serializable
    @SerialName("review_result")
    data class ReviewResult(
        override val id: String,
        val cardId: String,
        val grade: Int,
        val answeredAtEpochMs: Long,
        val responseMs: Long,
    ) : WatchMessage {
        init {
            require(grade in 1..4) { "评分必须在 1 到 4 之间" }
        }
    }

    /** 手表采集的情境信号，供手机端情境引擎判断可学习时刻。字段缺失表示该传感器不可用。 */
    @Serializable
    @SerialName("context_signal")
    data class ContextSignal(
        override val id: String,
        val atEpochMs: Long,
        val heartRateBpm: Int? = null,
        val stepsLastMinute: Int? = null,
        val onWrist: Boolean = true,
    ) : WatchMessage
}
