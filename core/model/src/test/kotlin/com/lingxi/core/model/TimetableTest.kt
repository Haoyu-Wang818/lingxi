package com.lingxi.core.model

import com.lingxi.core.model.repository.Streak
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalDateTime

class TimetableTest {
    // 2026-09-07 是周一。
    private val semesterStart = LocalDate.of(2026, 9, 7)

    private fun slot(
        id: String,
        weekday: Int,
        start: Int,
        end: Int,
        weeks: IntRange = 1..16,
    ) = ScheduleSlot(id, "math", weekday, start, end, weeks.first, weeks.last)

    @Test
    @DisplayName("[FR-COURSE-02] 同一天时间重叠且教学周重叠的时段判为冲突")
    fun overlappingSlotsConflict() {
        val existing = listOf(slot("a", 3, 8 * 60, 9 * 60 + 40))
        assertEquals(1, Timetable.conflicts(existing, slot("b", 3, 9 * 60, 10 * 60)).size)
    }

    @Test
    @DisplayName("[FR-COURSE-02] 首尾相接、不同星期、教学周不重叠、同一时段自身都不算冲突")
    fun nonOverlappingSlotsDoNotConflict() {
        val a = slot("a", 3, 8 * 60, 9 * 60 + 40, 1..8)
        val existing = listOf(a)
        assertTrue(Timetable.conflicts(existing, slot("b", 3, 9 * 60 + 40, 11 * 60)).isEmpty())
        assertTrue(Timetable.conflicts(existing, slot("c", 4, 8 * 60, 9 * 60)).isEmpty())
        assertTrue(Timetable.conflicts(existing, slot("d", 3, 8 * 60, 9 * 60, 9..16)).isEmpty())
        assertTrue(Timetable.conflicts(existing, a.copy(endMin = 10 * 60)).isEmpty())
    }

    @Test
    @DisplayName("[FR-COURSE-02] 非法时段被拒绝：结束早于开始、星期越界、周次颠倒")
    fun invalidSlotsRejected() {
        assertThrows<IllegalArgumentException> { slot("x", 3, 600, 600) }
        assertThrows<IllegalArgumentException> { slot("x", 8, 600, 660) }
        assertThrows<IllegalArgumentException> { slot("x", 1, 600, 660, 10..10).copy(weekFrom = 11) }
    }

    @Test
    @DisplayName("[FR-COURSE-03] 教学周：学期首周为 1，学期前为 0，按周一切换")
    fun teachingWeekCountsFromSemesterStart() {
        assertEquals(0, Timetable.teachingWeek(semesterStart, LocalDate.of(2026, 9, 6)))
        assertEquals(1, Timetable.teachingWeek(semesterStart, semesterStart))
        assertEquals(1, Timetable.teachingWeek(semesterStart, LocalDate.of(2026, 9, 13)))
        assertEquals(2, Timetable.teachingWeek(semesterStart, LocalDate.of(2026, 9, 14)))
        assertEquals(11, Timetable.teachingWeek(semesterStart, LocalDate.of(2026, 11, 18)))
        // 学期从周三开始，下周一就是第 2 周。
        assertEquals(2, Timetable.teachingWeek(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 14)))
    }

    @Test
    @DisplayName("[FR-COURSE-03] 上课时间内找到正在上的课，课前、下课时刻和周次外找不到")
    fun currentSlotFindsActiveClass() {
        val wed = slot("a", 3, 8 * 60, 9 * 60 + 40, 1..16)
        val slots = listOf(wed)
        // 2026-11-18 周三，第 11 周。
        assertEquals(wed, Timetable.current(slots, LocalDateTime.of(2026, 11, 18, 8, 0), semesterStart))
        assertEquals(wed, Timetable.current(slots, LocalDateTime.of(2026, 11, 18, 9, 39), semesterStart))
        assertNull(Timetable.current(slots, LocalDateTime.of(2026, 11, 18, 9, 40), semesterStart))
        assertNull(Timetable.current(slots, LocalDateTime.of(2026, 11, 18, 7, 59), semesterStart))
        assertNull(Timetable.current(slots, LocalDateTime.of(2026, 11, 19, 8, 30), semesterStart))
        // 第 17 周已结课。
        assertNull(Timetable.current(slots, LocalDateTime.of(2027, 1, 6, 8, 30), semesterStart))
    }

    @Test
    @DisplayName("[FR-COURSE-01] 课程名不能为空，保持率需在合理范围")
    fun courseValidation() {
        assertThrows<IllegalArgumentException> { Course("c", " ") }
        assertThrows<IllegalArgumentException> { Course("c", "高数", targetRetention = 0.5) }
        assertEquals("08:05", Timetable.formatMinute(485))
        assertEquals("周日", Timetable.weekdayName(7))
    }

    @Test
    @DisplayName("[FR-REV-09] 连续天数：今天未学从昨天数起，中断则重新计数")
    fun streakCountsConsecutiveDays() {
        val today = LocalDate.of(2026, 11, 21)
        val days = (1L..6L).map { today.minusDays(it) }.toSet()
        assertEquals(6, Streak.count(days, today))
        assertEquals(7, Streak.count(days + today, today))
        assertEquals(0, Streak.count(setOf(today.minusDays(3)), today))
        assertEquals(0, Streak.count(emptySet(), today))
    }
}
