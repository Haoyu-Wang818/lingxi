package com.lingxi.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.lingxi.core.data.db.CaptureEntity
import com.lingxi.core.data.db.CardFeedbackEntity
import com.lingxi.core.data.db.ContextEvaluationEntity
import com.lingxi.core.data.db.LingxiDatabase
import com.lingxi.core.data.db.PairedDeviceEntity
import com.lingxi.core.data.db.PendingTaskEntity
import com.lingxi.core.data.db.RecognizedTextEntity
import com.lingxi.core.model.repository.AiExecution
import com.lingxi.core.model.repository.CardMemory
import com.lingxi.core.model.repository.MemoryPhase
import com.lingxi.core.model.repository.PushChannel
import com.lingxi.core.model.repository.PushOutcome
import com.lingxi.core.model.repository.PushRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** AI 执行记录、推送记录、待办队列、其余表的读写与 schema 校验 [FR-AI-04][FR-CTX-08][FR-AI-05]。 */
class LogRepositoriesTest : DbTestBase() {
    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LingxiDatabase::class.java)

    /** [FR-AI-04] 执行记录按时间倒序返回，字段完整。 */
    @Test
    fun aiExecutionsRecordedNewestFirst() =
        runBlocking {
            val repo = RoomAiExecutionRepository(db)
            repo.record(AiExecution("CHAT", "CLOUD", "生成类任务，云端质量更好", t0, 820, true))
            val id = repo.record(AiExecution("EXTRACT", "UNAVAILABLE", "无网络", t0 + 1, null, false, "NETWORK", "lesson-1"))
            val recent = repo.recent(10)
            assertEquals(listOf("EXTRACT", "CHAT"), recent.map { it.taskType })
            assertEquals(AiExecution("EXTRACT", "UNAVAILABLE", "无网络", t0 + 1, null, false, "NETWORK", "lesson-1", id), recent[0])
            assertEquals(1, repo.recent(1).size)
        }

    /** [FR-CTX-08] 推送计数、最近一次时间与结果回写。 */
    @Test
    fun pushRecordsCountAndOutcome() =
        runBlocking {
            seedGraph()
            RoomCardRepository(db).upsertCards(listOf(card("c1")), t0)
            val repo = RoomPushRepository(db)
            assertNull(repo.lastSentAt())
            val evalId =
                db.contextEvaluationDao().insert(
                    ContextEvaluationEntity(0, t0, "PERIODIC", "{}", true, 0.8, 2, "[]", null),
                )
            repo.record(PushRecord("p1", "c1", PushChannel.RELAY, t0, evaluationId = evalId))
            repo.record(PushRecord("p2", "c1", PushChannel.NOTIFICATION, t0 + 5000))
            assertEquals(2, repo.countSince(t0))
            assertEquals(1, repo.countSince(t0 + 1))
            assertEquals(t0 + 5000, repo.lastSentAt())
            repo.setOutcome("p1", PushOutcome.ANSWERED, t0 + 9000)
            assertEquals(1, db.contextEvaluationDao().recent(5).size)
        }

    /** [FR-AI-05] 待办任务按 (类型, 关联 id) 去重，按下次尝试时间取出。 */
    @Test
    fun pendingTasksDeduplicated() =
        runBlocking {
            val dao = db.pendingTaskDao()
            dao.enqueue(PendingTaskEntity("t1", "EXTRACT", "{}", "lesson-1", 0, t0, null))
            dao.enqueue(PendingTaskEntity("t2", "EXTRACT", "{}", "lesson-1", 0, t0, null))
            dao.enqueue(PendingTaskEntity("t3", "CARDS", "{}", "lesson-1", 0, t0 + 1000, null))
            assertEquals(listOf("t1"), dao.ready(t0).map { it.id })
            assertEquals(2, dao.ready(t0 + 1000).size)
            dao.delete("t1")
            assertEquals(listOf("t3"), dao.ready(t0 + 1000).map { it.id })
        }

    /** 其余表（捕获、识别文本、纠错、配对设备）可写可读，外键生效。 */
    @Test
    fun remainingTablesReadWrite() =
        runBlocking {
            seedGraph()
            val lesson = RoomKnowledgeRepository(db, clock).createLesson("math", t0)
            val captures = db.captureDao()
            captures.insert(CaptureEntity("cap-1", lesson, "PHOTO", "captures/1.jpg", null, null, null, "NEW", t0))
            captures.insertText(RecognizedTextEntity("rt-1", "cap-1", "PP-OCRv4", "ON_DEVICE", "泰勒公式", 0.92, null))
            assertEquals(1, captures.byLesson(lesson).size)
            assertEquals("泰勒公式", captures.texts("cap-1").single().text)
            RoomCardRepository(db).upsertCards(listOf(card("c1")), t0)
            db.cardDao().insertFeedback(CardFeedbackEntity("f1", "c1", "UNCLEAR", null, t0))
            val device = PairedDeviceEntity("w1", "手表", "WEAR_OS", null, "RELAY", "demo", byteArrayOf(1, 2), t0, t0)
            db.pairedDeviceDao().upsert(device)
            assertEquals(device, db.pairedDeviceDao().all().single())
            assertEquals(lesson, db.lessonDao().byId(lesson)?.id)
        }

    /** 记忆状态快照 JSON 往返一致；无状态为 `{}`。 */
    @Test
    fun memorySnapshotRoundTrip() {
        val m = CardMemory(MemoryPhase.RELEARNING, 1.5, 7.2, t0, t0 - 1, reps = 4, lapses = 2)
        assertEquals(m, memoryFromJson(memoryToJson(m)))
        assertEquals(CardMemory.new(t0), memoryFromJson(memoryToJson(CardMemory.new(t0))))
        assertNull(memoryFromJson(memoryToJson(null)))
    }

    /** 导出的 v1 schema 与实体定义一致（迁移测试基线，03-database.md 第 6 节）。 */
    @Test
    fun exportedSchemaMatchesEntities() {
        migrationHelper.createDatabase("migration-test", 1).close()
        migrationHelper.runMigrationsAndValidate("migration-test", 1, true, *LingxiDatabase.MIGRATIONS).close()
    }
}
