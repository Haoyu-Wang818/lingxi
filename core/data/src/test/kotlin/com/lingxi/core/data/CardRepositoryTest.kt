package com.lingxi.core.data

import com.lingxi.core.data.db.LingxiDatabase
import com.lingxi.core.model.CardType
import com.lingxi.core.model.Rating
import com.lingxi.core.model.ReviewCard
import com.lingxi.core.model.repository.CardMemory
import com.lingxi.core.model.repository.MemoryPhase
import com.lingxi.core.model.repository.ReviewDevice
import com.lingxi.core.model.repository.ReviewLogEntry
import com.lingxi.core.model.repository.ReviewMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** 卡片仓库：出卡队列、作答事务、撤销、去重、连续天数 [FR-REV-03][FR-REV-04][FR-REV-05]。 */
class CardRepositoryTest : DbTestBase() {
    private lateinit var repo: RoomCardRepository
    private val day = 86_400_000L
    private val zone = ZoneId.of("Asia/Shanghai")

    @Before
    fun setUp() {
        seedGraph()
        repo = RoomCardRepository(db)
    }

    private fun reviewed(
        rating: Rating,
        at: Long,
        dueAt: Long,
    ) = CardMemory(MemoryPhase.REVIEW, 3.0, 5.0, dueAt, at, reps = 1)

    /** [FR-REV-10] 写入卡片后建立 NEW 记忆状态，字段（选项、手表版、来源）往返一致，并带上课程、知识点与图谱邻居。 */
    @Test
    fun upsertCreatesNewMemoryAndRoundTripsFields() =
        runBlocking {
            repo.upsertCards(listOf(card("c1")), t0)
            assertEquals(1, repo.cardCount())
            assertEquals(CardMemory.new(t0), repo.memory("c1"))
            val study = repo.dueQueue(t0, 10).single()
            assertEquals(card("c1"), study.card)
            assertEquals("高等数学", study.course.name)
            assertEquals("泰勒公式", study.point.title)
            assertEquals(listOf("导数"), study.prerequisites)
            assertEquals(listOf("麦克劳林公式"), study.related)
        }

    /** 再次写入同一张卡只更新内容，保留记忆状态与创建时间。 */
    @Test
    fun upsertKeepsExistingMemoryAndCreatedAt() =
        runBlocking {
            repo.upsertCards(listOf(card("c1")), t0)
            repo.saveReview("c1", reviewed(Rating.GOOD, t0, t0 + 4 * day), ReviewLogEntry(t0, Rating.GOOD))
            repo.upsertCards(listOf(card("c1").copy(answer = "新答案")), t0 + day)
            assertEquals(t0 + 4 * day, repo.memory("c1")?.dueAtMillis)
            assertEquals(t0, db.cardDao().byId("c1")?.createdAt)
            assertEquals("新答案", db.cardDao().byId("c1")?.answer)
        }

    /** [FR-REV-03] 今日队列按到期时间升序，遵守上限与截止时间。 */
    @Test
    fun dueQueueOrdersByDueAndRespectsCapAndUntil() =
        runBlocking {
            repo.upsertCards(listOf(card("a"), card("b"), card("c")), t0)
            repo.saveReview("a", reviewed(Rating.GOOD, t0, t0 + 3 * day), ReviewLogEntry(t0, Rating.GOOD))
            repo.saveReview("b", reviewed(Rating.AGAIN, t0, t0 + 600_000), ReviewLogEntry(t0, Rating.AGAIN))
            val today = repo.dueQueue(t0 + day, 10).map { it.card.id }
            assertEquals(listOf("c", "b"), today)
            assertEquals(listOf("c"), repo.dueQueue(t0 + day, 1).map { it.card.id })
            assertEquals(listOf("c", "b", "a"), repo.dueQueue(t0 + 4 * day, 10).map { it.card.id })
        }

    /** [FR-REV-11] 问答题没有选项字段。 */
    @Test
    fun plainQuestionCardHasNoOptions() =
        runBlocking {
            val qa = ReviewCard("qa", "taylor", "问", "答", CardType.QUESTION_ANSWER)
            repo.upsertCards(listOf(qa), t0)
            assertEquals(qa, repo.dueQueue(t0, 10).single().card)
        }

    /** [FR-REV-05] 一次作答同时写入记忆状态与日志（评分、反应时长、设备、模式）。 */
    @Test
    fun saveReviewWritesMemoryAndLogInOneTransaction() =
        runBlocking {
            repo.upsertCards(listOf(card("c1")), t0)
            val next = reviewed(Rating.GOOD, t0, t0 + 4 * day)
            val id = repo.saveReview("c1", next, ReviewLogEntry(t0, Rating.GOOD, elapsedMs = 3200))
            assertEquals(next, repo.memory("c1"))
            val log = db.reviewLogDao().byId(id)!!
            assertEquals(3, log.rating)
            assertEquals(3200L, log.elapsedMs)
            assertEquals("PHONE", log.device)
            assertEquals("DAILY", log.mode)
            assertTrue(log.counted)
            assertEquals(1, repo.countedReviewsSince(t0))
        }

    /** [FR-REV-14] 组内回炉只写日志（counted=0），不更新记忆状态。 */
    @Test
    fun relearnLogDoesNotTouchMemoryOrCount() =
        runBlocking {
            repo.upsertCards(listOf(card("c1")), t0)
            repo.saveReview("c1", null, ReviewLogEntry(t0, Rating.GOOD, mode = ReviewMode.RELEARN_IN_GROUP, counted = false))
            assertEquals(CardMemory.new(t0), repo.memory("c1"))
            assertEquals(0, repo.countedReviewsSince(t0))
        }

    /** [FR-REV-04] 撤销用日志快照恢复记忆状态并删除日志（TC-REV-07），重复撤销无副作用。 */
    @Test
    fun undoRestoresPreviousMemoryAndDeletesLog() =
        runBlocking {
            repo.upsertCards(listOf(card("c1")), t0)
            val first = reviewed(Rating.GOOD, t0, t0 + 4 * day)
            repo.saveReview("c1", first, ReviewLogEntry(t0, Rating.GOOD))
            val second = reviewed(Rating.EASY, t0 + day, t0 + 20 * day)
            val id = repo.saveReview("c1", second, ReviewLogEntry(t0 + day, Rating.EASY))
            repo.undo(id)
            assertEquals(first, repo.memory("c1"))
            assertNull(db.reviewLogDao().byId(id))
            repo.undo(id) // 重复撤销无副作用
            assertEquals(first, repo.memory("c1"))
        }

    /** [FR-REV-04] 撤销首次作答恢复为新卡状态。 */
    @Test
    fun undoFirstReviewRestoresNewState() =
        runBlocking {
            repo.upsertCards(listOf(card("c1")), t0)
            val id = repo.saveReview("c1", reviewed(Rating.GOOD, t0, t0 + day), ReviewLogEntry(t0, Rating.GOOD))
            repo.undo(id)
            assertEquals(CardMemory.new(t0), repo.memory("c1"))
        }

    /** [FR-WEAR-04] 同一手表消息 id 重复回传只写一次（TC-REV-09）。 */
    @Test
    fun duplicateWatchMessageIsWrittenOnce() =
        runBlocking {
            repo.upsertCards(listOf(card("c1")), t0)
            val log = ReviewLogEntry(t0, Rating.GOOD, device = ReviewDevice.WATCH, messageId = "m-1")
            val first = repo.saveReview("c1", reviewed(Rating.GOOD, t0, t0 + day), log)
            val second = repo.saveReview("c1", reviewed(Rating.GOOD, t0, t0 + 9 * day), log)
            assertEquals(first, second)
            assertEquals(1, repo.countedReviewsSince(0))
            assertEquals(t0 + day, repo.memory("c1")?.dueAtMillis)
        }

    /** [FR-REV-09] 连续天数按本地日期聚合复习日志。 */
    @Test
    fun streakAggregatesByLocalDate() =
        runBlocking {
            repo.upsertCards(listOf(card("c1")), t0)
            val today = Instant.ofEpochMilli(t0).atZone(zone).toLocalDate()
            listOf(0L, 1L, 2L, 4L).forEach { d ->
                val at = today.minusDays(d).atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
                repo.saveReview("c1", null, ReviewLogEntry(at, Rating.GOOD))
            }
            assertEquals(3, repo.streakDays(today, zone))
            assertEquals(0, repo.streakDays(today.plusDays(2), zone))
        }

    /** [FR-COURSE-01] 删除课程级联删除卡片、记忆状态与日志。 */
    @Test
    fun deletingCourseCascadesToCardsMemoryAndLogs() =
        runBlocking {
            repo.upsertCards(listOf(card("c1")), t0)
            repo.saveReview("c1", reviewed(Rating.GOOD, t0, t0 + day), ReviewLogEntry(t0, Rating.GOOD))
            RoomCourseRepository(db, clock).deleteCourse("math")
            assertEquals(0, repo.cardCount())
            assertNull(repo.memory("c1"))
            assertEquals(0, repo.countedReviewsSince(0))
        }

    /** 数据库名与版本与 03-database.md 一致。 */
    @Test
    fun databaseNameAndVersionMatchDesign() {
        assertEquals("lingxi.db", LingxiDatabase.NAME)
        assertEquals(1, LingxiDatabase.VERSION)
    }
}
