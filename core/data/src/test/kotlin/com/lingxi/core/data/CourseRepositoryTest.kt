package com.lingxi.core.data

import com.lingxi.core.data.db.LessonEntity
import com.lingxi.core.data.db.NoteEntity
import com.lingxi.core.model.ScheduleSlot
import com.lingxi.core.model.repository.CourseUsage
import com.lingxi.core.model.repository.SlotSaveResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** 课程与课表仓库 [FR-COURSE-01][FR-COURSE-02][FR-COURSE-03]。 */
class CourseRepositoryTest : DbTestBase() {
    private lateinit var repo: RoomCourseRepository
    private val semesterStart = LocalDate.of(2026, 9, 7)

    @Before
    fun setUp() {
        repo = RoomCourseRepository(db, clock)
    }

    private fun slot(
        id: String,
        weekday: Int = 3,
        start: Int = 480,
        end: Int = 580,
        course: String = "math",
    ) = ScheduleSlot(id, course, weekday, start, end, 1, 16, "教一 101")

    /** [FR-COURSE-01] 新建、编辑、删除课程后数据一致；创建时间首次写入后保持不变。 */
    @Test
    fun courseCrud() =
        runBlocking {
            repo.saveCourse(math.copy(createdAtMillis = 0))
            val created = repo.course("math")!!
            assertEquals(t0, created.createdAtMillis)
            repo.saveCourse(created.copy(name = "高等数学（上）", teacher = null))
            assertEquals("高等数学（上）", repo.course("math")?.name)
            assertNull(repo.course("math")?.teacher)
            assertEquals(t0, repo.course("math")?.createdAtMillis)
            repo.saveCourse(math.copy(id = "la", name = "线性代数", createdAtMillis = t0 + 1))
            assertEquals(listOf("math", "la"), repo.courses().map { it.id })
            repo.deleteCourse("la")
            assertEquals(listOf("math"), repo.courses().map { it.id })
        }

    /** [FR-COURSE-01] 删除前提示课程下的笔记与卡片数量。 */
    @Test
    fun usageCountsNotesAndCards() =
        runBlocking {
            seedGraph()
            assertEquals(CourseUsage(0, 0), repo.usage("math"))
            db.lessonDao().insert(LessonEntity("l1", "math", t0))
            db.noteDao().upsert(NoteEntity("n1", "l1", "第5讲", "## 要点", null, "READY", "extract-v2", t0))
            RoomCardRepository(db).upsertCards(listOf(card("c1"), card("c2")), t0)
            assertEquals(CourseUsage(1, 2), repo.usage("math"))
        }

    /** [FR-COURSE-02] 时段冲突时不写入并返回冲突；强制保存或调整后可写入。 */
    @Test
    fun slotConflictIsReported() =
        runBlocking {
            repo.saveCourse(math)
            assertEquals(SlotSaveResult.Saved, repo.saveSlot(slot("a")))
            val result = repo.saveSlot(slot("b", start = 540, end = 600))
            assertTrue(result is SlotSaveResult.Conflict)
            assertEquals(listOf("a"), (result as SlotSaveResult.Conflict).with.map { it.id })
            assertEquals(1, repo.slots().size)
            assertEquals(SlotSaveResult.Saved, repo.saveSlot(slot("b", start = 540, end = 600), force = true))
            assertEquals(2, repo.slots("math").size)
            // 编辑自身不算冲突。
            assertEquals(SlotSaveResult.Saved, repo.saveSlot(slot("a", end = 590).copy(weekday = 1)))
            repo.deleteSlot("b")
            assertEquals(listOf("a"), repo.slots().map { it.id })
        }

    /** [FR-COURSE-03] 上课时间内返回正在上的课，否则为空。 */
    @Test
    fun currentCourseDuringClass() =
        runBlocking {
            repo.saveCourse(math)
            repo.saveSlot(slot("a"))
            assertEquals("math", repo.currentCourse(LocalDateTime.of(2026, 11, 18, 8, 30), semesterStart)?.id)
            assertNull(repo.currentCourse(LocalDateTime.of(2026, 11, 18, 10, 0), semesterStart))
            assertNull(repo.currentCourse(LocalDateTime.of(2027, 1, 6, 8, 30), semesterStart))
        }

    /** [FR-COURSE-01] 删除课程级联删除其时段。 */
    @Test
    fun deleteCourseCascadesSlots() =
        runBlocking {
            repo.saveCourse(math)
            repo.saveSlot(slot("a"))
            repo.deleteCourse("math")
            assertTrue(repo.slots().isEmpty())
        }
}
