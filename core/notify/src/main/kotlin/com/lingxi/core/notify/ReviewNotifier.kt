package com.lingxi.core.notify

/**
 * 复习通知 [FR-WEAR-05]、[FR-REV-12]。
 *
 * 通知带两键操作（作答 / 稍后），点击进入碎片复习模式；有手表时由系统桥接到手表。
 * 实现（NotificationCompat 与通知渠道）在 I5 迭代加入。
 */
interface ReviewNotifier {
    /**
     * 发出一次微复习通知。
     *
     * @param pushId 推送 id，作答与划掉时回传。
     * @param reason 推送依据短句（≤ 10 字），例如「排队中·无课」[FR-CTX-03]。
     */
    fun notifyMicroReview(
        pushId: String,
        cardIds: List<String>,
        reason: String,
    )

    /** 撤下某次推送的通知（已在手表作答或已过期）。 */
    fun cancel(pushId: String)
}
