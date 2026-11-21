package com.lingxi.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 一门课程 [FR-COURSE-01]。
 *
 * @property color ARGB 颜色，用于卡片与图谱着色。
 * @property examDateMillis 考试日期（UTC 毫秒），考前模式使用 [FR-REV-16]。
 * @property targetRetention FSRS 目标保持率，默认 0.90，考前模式提到 0.95。
 */
data class Course(
    val id: String,
    val name: String,
    val teacher: String? = null,
    val color: Int = DEFAULT_COLOR,
    val examDateMillis: Long? = null,
    val targetRetention: Double = 0.9,
    val createdAtMillis: Long = 0,
) {
    init {
        require(id.isNotBlank()) { "课程 id 不能为空" }
        require(name.isNotBlank()) { "课程名不能为空" }
        require(targetRetention in 0.7..0.99) { "目标保持率应在 0.7 到 0.99 之间" }
    }

    companion object {
        /** 默认课程色（靛蓝）。 */
        const val DEFAULT_COLOR: Int = 0xFF3F51B5.toInt()
    }
}

/**
 * 课程的一个上课时段 [FR-COURSE-02]。
 *
 * 用「星期 + 当日分钟数 + 教学周范围」描述，既能表达「第 1–16 周每周三 8:00–9:40」，
 * 也便于情境引擎和捕获页判断「正在上的课」[FR-COURSE-03]。
 *
 * @property weekday 星期，1 = 周一 … 7 = 周日（与 [DayOfWeek.getValue] 一致）。
 * @property startMin 开始时间，当日第几分钟。
 * @property endMin 结束时间，必须晚于开始时间。
 */
data class ScheduleSlot(
    val id: String,
    val courseId: String,
    val weekday: Int,
    val startMin: Int,
    val endMin: Int,
    val weekFrom: Int = 1,
    val weekTo: Int = 18,
    val location: String? = null,
) {
    init {
        require(weekday in 1..7) { "星期必须在 1 到 7 之间" }
        require(startMin in 0 until MINUTES_PER_DAY && endMin in 1..MINUTES_PER_DAY) { "时间必须在当天内" }
        require(startMin < endMin) { "结束时间必须晚于开始时间" }
        require(weekFrom in 1..weekTo) { "教学周范围无效" }
    }

    /** 两个时段是否在同一天、时间和教学周都有重叠。 */
    fun overlaps(other: ScheduleSlot): Boolean =
        weekday == other.weekday &&
            startMin < other.endMin &&
            other.startMin < endMin &&
            weekFrom <= other.weekTo &&
            other.weekFrom <= weekTo

    /** 是否在 [week] 周的 [weekday] [minute] 时刻正在上课（含开始、不含结束）。 */
    fun isActive(
        weekday: Int,
        minute: Int,
        week: Int,
    ): Boolean = this.weekday == weekday && minute in startMin until endMin && week in weekFrom..weekTo

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

/** 课表规则：时段冲突检测、当前教学周与「正在上的课」，纯函数，供课程页与情境引擎共用。 */
object Timetable {
    /** 与 [candidate] 冲突的已有时段（忽略同 id，便于编辑时自检）[FR-COURSE-02]。 */
    fun conflicts(
        existing: List<ScheduleSlot>,
        candidate: ScheduleSlot,
    ): List<ScheduleSlot> = existing.filter { it.id != candidate.id && it.overlaps(candidate) }

    /**
     * [date] 所在的教学周，学期第一天所在周为第 1 周；学期开始前返回 0。
     * 周按周一起算，所以学期从周三开始时，下一个周一就是第 2 周。
     */
    fun teachingWeek(
        semesterStart: LocalDate,
        date: LocalDate,
    ): Int {
        if (date.isBefore(semesterStart)) return 0
        val firstMonday = semesterStart.minusDays((semesterStart.dayOfWeek.value - 1).toLong())
        return (ChronoUnit.DAYS.between(firstMonday, date) / 7 + 1).toInt()
    }

    /** [now] 时刻正在进行的时段，没有则为 null [FR-COURSE-03]。 */
    fun current(
        slots: List<ScheduleSlot>,
        now: LocalDateTime,
        semesterStart: LocalDate,
    ): ScheduleSlot? {
        val week = teachingWeek(semesterStart, now.toLocalDate())
        val minute = now.hour * 60 + now.minute
        return slots.firstOrNull { it.isActive(now.dayOfWeek.value, minute, week) }
    }

    /** 把当日分钟数格式化为「08:05」。 */
    fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    /** 中文星期名，1 →「周一」。 */
    fun weekdayName(weekday: Int): String = "周" + "一二三四五六日"[weekday - 1]
}
