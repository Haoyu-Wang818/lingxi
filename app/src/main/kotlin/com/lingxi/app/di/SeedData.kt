package com.lingxi.app.di

import com.lingxi.app.ui.SampleData
import com.lingxi.core.model.Course
import com.lingxi.core.model.ScheduleSlot

/**
 * 首次启动写入的种子数据：高等数学一门课、上课时段、第 5 讲「泰勒公式」的 7 个知识点与 8 张卡片。
 * 写入后复习页、课程页都从数据库读取，复习进度在进程重启后保留。
 */
object SeedData {
    const val COURSE_ID = "math"

    /** 种子卡片引用的板书捕获及其拍摄日期，用于卡片背面「11月18日 板书 第3页」。I2 起真实捕获从 capture 表读取。 */
    val captureDates = mapOf("cap-1118-board" to "11月18日")

    suspend fun seedIfNeeded(
        container: AppContainer,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (container.settings.isSeeded()) return
        container.courses.saveCourse(Course(COURSE_ID, "高等数学", "示例教师", createdAtMillis = nowMillis))
        container.courses.saveSlot(ScheduleSlot("math-wed", COURSE_ID, 3, 8 * 60, 9 * 60 + 40, 1, 16, "教一 101"), force = true)
        container.courses.saveSlot(ScheduleSlot("math-fri", COURSE_ID, 5, 10 * 60, 11 * 60 + 40, 1, 16, "教一 101"), force = true)
        container.knowledge.upsert(SampleData.points, SampleData.edges)
        container.cards.upsertCards(SampleData.cards, nowMillis)
        container.settings.markSeeded()
    }
}
