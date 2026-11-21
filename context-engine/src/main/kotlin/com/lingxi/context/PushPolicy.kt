package com.lingxi.context

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * 推送策略：回答「现在能不能打扰用户」，管打扰预算与静音时段 [FR-CTX-08]、[FR-CTX-09]。
 *
 * 与 [LearnableMomentDetector]（回答「现在适不适合学」）职责分离，两者分别测试；
 * 推送前两者都放行才会真正推送（见详细设计 1.1 节）。
 */
interface PushPolicy {
    /**
     * @param now 当前时刻。
     * @param history 今日推送记录，按 [PushRecord.sentAt] 升序。
     * @param settings 用户的推送设置。
     */
    fun allow(
        now: Instant,
        history: List<PushRecord>,
        settings: PushSettings,
    ): PushDecision
}

/** 推送策略的判断结果，阻止时带可展示给用户的原因 [FR-CTX-03]。 */
sealed interface PushDecision {
    /** 允许推送。 */
    data object Allow : PushDecision

    /**
     * 阻止推送。
     *
     * @property code 阻止原因代码，用于统计与测试断言。
     * @property reason 中文原因，例如「静音时段 22:30–07:30」，在情境实验室中展示。
     */
    data class Block(
        val code: BlockCode,
        val reason: String,
    ) : PushDecision
}

/** 推送被阻止的原因类别 [FR-CTX-08]、[FR-CTX-09]。 */
enum class BlockCode {
    /** 用户关闭了推送。 */
    DISABLED,

    /** 处于静音时段。 */
    QUIET_HOURS,

    /** 今日推送次数已达上限。 */
    BUDGET_DAILY,

    /** 距上次推送不足最短间隔。 */
    BUDGET_INTERVAL,

    /** 最近两次推送都被忽略或划掉，当天不再打扰。 */
    IGNORED_TWICE,
}

/**
 * 一段静音时段，左闭右开，支持跨零点（如 22:30–07:30）[FR-CTX-09]。
 */
data class QuietWindow(
    val start: LocalTime,
    val end: LocalTime,
)

/**
 * 用户的推送设置，默认值来自问卷结论 [FR-CTX-08]、[FR-CTX-09]。
 *
 * @property dailyLimit 每日推送上限。
 * @property minInterval 两次推送之间的最短间隔。
 * @property quietHours 静音时段列表，为空表示不因时段阻止。
 * @property zone 判断静音时段所用的时区。
 */
data class PushSettings(
    val pushEnabled: Boolean = true,
    val dailyLimit: Int = 3,
    val minInterval: Duration = Duration.ofMinutes(120),
    val quietHours: List<QuietWindow> =
        listOf(
            QuietWindow(LocalTime.of(22, 30), LocalTime.of(7, 30)),
            QuietWindow(LocalTime.of(12, 0), LocalTime.of(13, 30)),
        ),
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** 一次推送的用户反馈，用于「连续忽略两次」判断 [FR-CTX-05]。 */
enum class PushOutcome {
    /** 尚无反馈。 */
    PENDING,

    /** 用户作答了。 */
    ANSWERED,

    /** 超时未处理。 */
    IGNORED,

    /** 用户主动划掉。 */
    DISMISSED,

    /** 用户选择稍后提醒。 */
    SNOOZED,
}

/**
 * 一条推送记录，对应数据库 `push_log` 表 [FR-CTX-08]。
 *
 * @property pushId 推送 id，手表回传的作答与划掉消息会带上它。
 * @property cardIds 本次推送的卡片。
 */
data class PushRecord(
    val pushId: String,
    val sentAt: Instant,
    val cardIds: List<String> = emptyList(),
    val outcome: PushOutcome = PushOutcome.PENDING,
)
