package com.lingxi.core.data

import com.lingxi.core.model.SourceRef
import com.lingxi.core.model.repository.CaptureKind
import com.lingxi.core.model.repository.CaptureRecord
import com.lingxi.core.model.repository.CaptureStatus
import com.lingxi.core.model.repository.LessonNote
import com.lingxi.core.model.repository.NoteStatus
import com.lingxi.core.model.repository.PendingTaskRepository
import com.lingxi.core.model.repository.PendingTaskType
import com.lingxi.core.model.repository.RecognizedText
import com.lingxi.core.model.repository.TextLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 捕获、识别文本、笔记与待办队列仓库 [FR-CAP-06][FR-NOTE-01][FR-NOTE-04][FR-NOTE-06][FR-AI-05]。 */
class CaptureRepositoryTest : DbTestBase() {
    private lateinit var captures: RoomCaptureRepository
    private lateinit var notes: RoomNoteRepository
    private lateinit var pending: RoomPendingTaskRepository
    private var seq = 0
    private val hour = 3_600_000L

    @Before
    fun setUp() {
        seedGraph()
        captures = RoomCaptureRepository(db) { "id-${++seq}" }
        notes = RoomNoteRepository(db)
        pending = RoomPendingTaskRepository(db) { "task-${++seq}" }
    }

    private fun photo(
        id: String,
        lesson: String,
        at: Long = t0,
    ) = CaptureRecord(id, lesson, CaptureKind.PHOTO, "captures/$id.jpg", CaptureStatus.NEW, at)

    /** [FR-CAP-06] 同一课程 2 小时内的捕获归入同一课次，超过则新建课次；课次可查到所属课程。 */
    @Test
    fun lessonReusedWithinTwoHours() =
        runBlocking {
            val first = captures.lessonFor("math", t0)
            assertEquals(first, captures.lessonFor("math", t0 + hour))
            val next = captures.lessonFor("math", t0 + 3 * hour)
            assertNotEquals(first, next)
            assertEquals("math", captures.courseOfLesson(next))
            assertNull(captures.courseOfLesson("missing"))
        }

    /** [FR-CAP-06] 捕获记录与文件路径一一对应，状态可推进。 */
    @Test
    fun captureRecordAndStatus() =
        runBlocking {
            val lesson = captures.lessonFor("math", t0)
            captures.addCapture(photo("c1", lesson))
            captures.addCapture(photo("c2", lesson, t0 + 1000))
            assertEquals(listOf("c1", "c2"), captures.captures(lesson).map { it.id })
            captures.setStatus("c1", CaptureStatus.RECOGNIZED)
            assertEquals(CaptureStatus.RECOGNIZED, captures.capture("c1")?.status)
            assertEquals("captures/c1.jpg", captures.capture("c1")?.filePath)
            assertNull(captures.capture("nope"))
        }

    /** [FR-NOTE-01] 识别文本连同每行文字框往返一致，用于溯源高亮。 */
    @Test
    fun recognizedTextRoundTripsLineBoxes() =
        runBlocking {
            val lesson = captures.lessonFor("math", t0)
            captures.addCapture(photo("c1", lesson))
            val lines = listOf(TextLine("泰勒公式", 0.98, listOf(10, 20, 300, 60)), TextLine("f(x)=…", 0.9, listOf(10, 80, 400, 120)))
            val text = RecognizedText("rt1", "c1", "PP-OCRv4", "ON_DEVICE", "泰勒公式\nf(x)=…", 0.94, lines)
            captures.saveRecognized(text)
            assertEquals(listOf(text), captures.recognized("c1"))
            assertTrue(linesFromJson(null).isEmpty())
        }

    /** [FR-NOTE-03][FR-NOTE-04] 笔记一课次一份；编辑保留生成的原文，再次编辑不覆盖原文；列表带课程名与素材数。 */
    @Test
    fun noteSaveEditAndSummaries() =
        runBlocking {
            val lesson = captures.lessonFor("math", t0)
            captures.addCapture(photo("c1", lesson))
            notes.save(LessonNote("n1", lesson, "泰勒公式", "## 要点\n- 生成的", NoteStatus.READY, "note-v1", t0))
            // 同一课次再次整理：替换内容，id 不变。
            notes.save(LessonNote("n2", lesson, "泰勒公式（续）", "## 要点\n- 第二版", NoteStatus.READY, "note-v1", t0 + 1))
            assertEquals("n1", notes.byLesson(lesson)?.id)
            notes.edit(lesson, "## 要点\n- 我改过", t0 + 2)
            notes.edit(lesson, "## 要点\n- 又改了", t0 + 3)
            val note = notes.byLesson(lesson)!!
            assertEquals("## 要点\n- 又改了", note.markdown)
            assertEquals("## 要点\n- 第二版", note.originalMarkdown)
            val summary = notes.notes().single()
            assertEquals("高等数学", summary.courseName)
            assertEquals(1, summary.captureCount)
            assertEquals(NoteStatus.READY, summary.status)
            assertNull(notes.byLesson("missing"))
        }

    /** [FR-NOTE-06] 编辑不存在的笔记报错。 */
    @Test(expected = IllegalArgumentException::class)
    fun editMissingNoteFails() =
        runBlocking {
            notes.edit("missing", "x", t0)
        }

    /** [FR-AI-05] 待办任务去重、退避 1/2/4/8 分钟、第 5 次失败后移出队列。 */
    @Test
    fun pendingTaskBackoffAndGiveUp() =
        runBlocking {
            assertTrue(pending.enqueue(PendingTaskType.EXTRACT, "lesson-1", "{}", t0))
            assertFalse(pending.enqueue(PendingTaskType.EXTRACT, "lesson-1", "{}", t0))
            val task = pending.ready(t0).single()
            var now = t0
            listOf(1L, 2L, 4L, 8L).forEachIndexed { i, minutes ->
                assertTrue(pending.recordFailure(task.id, "网络错误", now))
                val t = pending.all().single()
                assertEquals(i + 1, t.attempts)
                assertEquals(now + minutes * 60_000, t.nextAttemptAtMillis)
                assertTrue(pending.ready(now).isEmpty())
                now = t.nextAttemptAtMillis
            }
            assertFalse(pending.recordFailure(task.id, "网络错误", now))
            assertTrue(pending.all().isEmpty())
            assertFalse(pending.recordFailure("missing", "x", now))
            assertEquals(16 * 60_000L, PendingTaskRepository.backoffMillis(5))
        }

    /** [FR-AI-05] 成功后移出队列。 */
    @Test
    fun pendingTaskRemoved() =
        runBlocking {
            pending.enqueue(PendingTaskType.CARDS, "lesson-2", "{\"a\":1}", t0)
            val t = pending.all().single()
            assertEquals("{\"a\":1}", t.payloadJson)
            pending.remove(t.id)
            assertTrue(pending.all().isEmpty())
        }

    /** [FR-REV-10] 卡片背面的拍摄日期来自 capture 表。 */
    @Test
    fun studyCardCarriesCaptureDate() =
        runBlocking {
            val lesson = captures.lessonFor("math", t0)
            captures.addCapture(photo("cap-x", lesson, at = t0 - 5 * hour))
            val repo = RoomCardRepository(db)
            repo.upsertCards(listOf(card("c1").copy(sourceRef = SourceRef("cap-x", page = 1)), card("c2")), t0)
            val due = repo.dueQueue(t0, 10).associateBy { it.card.id }
            assertEquals(t0 - 5 * hour, due.getValue("c1").capturedAtMillis)
            assertNull(due.getValue("c2").capturedAtMillis) // c2 的来源 cap-1 不在 capture 表中
            assertEquals(1, repo.countFromCaptures(setOf("cap-x")))
            assertEquals(0, repo.countFromCaptures(emptySet()))
        }
}
