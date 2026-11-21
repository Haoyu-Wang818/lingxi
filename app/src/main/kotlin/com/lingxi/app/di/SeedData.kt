package com.lingxi.app.di

import com.lingxi.app.ui.SampleData
import com.lingxi.core.model.Course
import com.lingxi.core.model.ScheduleSlot
import com.lingxi.core.model.repository.CaptureKind
import com.lingxi.core.model.repository.CaptureRecord
import com.lingxi.core.model.repository.CaptureStatus
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 首次启动写入的种子数据：高等数学一门课、上课时段、第 5 讲「泰勒公式」的 7 个知识点与 8 张卡片。
 * 写入后复习页、课程页都从数据库读取，复习进度在进程重启后保留。
 */
object SeedData {
    const val COURSE_ID = "math"

    /** 种子卡片引用的板书捕获：写入 capture 表（拍摄于 11 月 18 日上午），示例数据没有原图文件。 */
    private const val SEED_CAPTURE = "cap-1118-board"

    suspend fun seedIfNeeded(
        container: AppContainer,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (container.settings.isSeeded()) return
        container.courses.saveCourse(Course(COURSE_ID, "高等数学", "示例教师", createdAtMillis = nowMillis))
        container.courses.saveSlot(ScheduleSlot("math-wed", COURSE_ID, 3, 8 * 60, 9 * 60 + 40, 1, 16, "教一 101"), force = true)
        container.courses.saveSlot(ScheduleSlot("math-fri", COURSE_ID, 5, 10 * 60, 11 * 60 + 40, 1, 16, "教一 101"), force = true)
        container.knowledge.upsert(SampleData.points, SampleData.edges)
        val shotAt = LocalDateTime.of(2026, 11, 18, 8, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val lesson = container.captures.lessonFor(COURSE_ID, shotAt)
        container.captures.addCapture(
            CaptureRecord(SEED_CAPTURE, lesson, CaptureKind.PHOTO, "seed/board-1118.jpg", CaptureStatus.ORGANIZED, shotAt),
        )
        container.cards.upsertCards(SampleData.cards, nowMillis)
        container.settings.markSeeded()
    }
}
