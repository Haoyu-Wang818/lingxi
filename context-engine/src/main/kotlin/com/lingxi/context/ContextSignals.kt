package com.lingxi.context

/** 由手机或手表活动识别得到的身体状态。 */
enum class Activity {
    STILL,
    WALKING,
    RUNNING,
    IN_VEHICLE,
    UNKNOWN,
}

/** 前台应用的粗粒度类别。只记录类别，不记录具体应用名，保护隐私。 */
enum class AppCategory {
    /** 屏幕关闭或在桌面。 */
    NONE,
    STUDY,
    SOCIAL,
    SHORT_VIDEO,
    GAME,
    NAVIGATION,
    OTHER,
}

/**
 * 某一时刻的情境信号快照，由手机与手表的传感器、系统状态、课表融合而来。
 *
 * 所有信号都可以缺失（null），引擎会在缺失时退化为只看已有信号。
 *
 * @property heartRateBpm 手表测得的当前心率。
 * @property restingHeartRateBpm 用户的静息心率基线。
 * @property activity 当前活动状态。
 * @property previousActivity 约 3 分钟前的活动状态，用于识别「刚停下来」这类等待场景。
 * @property inClass 课表显示当前正在上课。
 * @property minutesSinceLastPrompt 距离上次推送微复习的分钟数，从未推送则为 null。
 * @property localHour 本地时间的小时（0–23）。
 */
data class ContextSignals(
    val localHour: Int,
    val activity: Activity = Activity.UNKNOWN,
    val previousActivity: Activity = Activity.UNKNOWN,
    val heartRateBpm: Int? = null,
    val restingHeartRateBpm: Int? = null,
    val screenOn: Boolean = false,
    val foregroundApp: AppCategory = AppCategory.NONE,
    val inClass: Boolean = false,
    val doNotDisturb: Boolean = false,
    val minutesSinceLastPrompt: Int? = null,
) {
    init {
        require(localHour in 0..23) { "小时必须在 0 到 23 之间" }
    }
}
