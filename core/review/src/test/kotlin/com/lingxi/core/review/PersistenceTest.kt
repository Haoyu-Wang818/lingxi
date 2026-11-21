package com.lingxi.core.review

import com.lingxi.core.model.Course
import com.lingxi.core.model.KnowledgePoint
import com.lingxi.core.model.Rating
import com.lingxi.core.model.ReviewCard
import com.lingxi.core.model.repository.CardMemory
import com.lingxi.core.model.repository.MemoryPhase
import com.lingxi.core.model.repository.ReviewMode
import com.lingxi.core.model.repository.StudyCard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PersistenceTest {
    private val fsrs = FsrsScheduler()
    private val t0 = 1_795_000_000_000L
    private val study =
        StudyCard(
            card = ReviewCard("c1", "taylor", "问", "答"),
            course = Course("math", "高等数学"),
            point = KnowledgePoint("taylor", "泰勒公式", "多项式逼近", "math"),
            memory = CardMemory.new(t0),
            prerequisites = listOf("导数"),
            related = listOf("麦克劳林公式"),
        )

    @Test
    @DisplayName("[FR-REV-05] 新卡映射为空 FSRS 状态，复习过的卡往返一致")
    fun memoryRoundTrip() {
        assertNull(CardMemory.new(t0).toFsrs())
        val state = fsrs.review(null, Rating.GOOD, t0).state
        val db = state.toCardMemory(Rating.GOOD, t0 + 1000)
        assertEquals(MemoryPhase.REVIEW, db.phase)
        assertEquals(t0 + 1000, db.dueAtMillis)
        assertEquals(state, db.toFsrs())
    }

    @Test
    @DisplayName("[FR-REV-02] 记忆阶段：首次就忘为 LEARNING，复习中忘为 RELEARNING")
    fun phaseFromRating() {
        val first = fsrs.review(null, Rating.AGAIN, t0).state
        assertEquals(MemoryPhase.LEARNING, first.toCardMemory(Rating.AGAIN, t0).phase)
        val later = fsrs.review(fsrs.review(null, Rating.GOOD, t0).state, Rating.AGAIN, t0 + 86_400_000).state
        assertEquals(MemoryPhase.RELEARNING, later.toCardMemory(Rating.AGAIN, t0).phase)
    }

    @Test
    @DisplayName("[FR-REV-07] 仓库卡片映射为复习页卡片，保留课程、知识点、邻居与来源")
    fun studyCardToDeckCard() {
        val deck = study.toDeckCard("11月18日 板书 第1页")
        assertEquals("高等数学", deck.courseName)
        assertEquals("泰勒公式", deck.pointTitle)
        assertEquals("多项式逼近", deck.pointSummary)
        assertEquals(listOf("导数"), deck.prerequisites)
        assertEquals(listOf("麦克劳林公式"), deck.related)
        assertEquals("11月18日 板书 第1页", deck.sourceLabel)
        assertNull(deck.memory)
    }

    @Test
    @DisplayName("[FR-REV-04] 会话评分写入：新状态的到期时间 = 作答时间 + 调度间隔，日志计入 FSRS")
    fun sessionRatingProducesMemoryAndLog() {
        val session = ReviewSession.start(listOf(study.toDeckCard(null)), fsrs, t0).flip().rate(Rating.GOOD, t0 + 5000)
        val record = session.records.single()
        val memory = record.toCardMemory(session.memory["c1"])!!
        assertEquals(t0 + 5000 + record.interval!!.toMillis(), memory.dueAtMillis)
        val log = record.toLogEntry(elapsedMs = 4200)
        assertEquals(Rating.GOOD, log.rating)
        assertEquals(ReviewMode.DAILY, log.mode)
        assertEquals(4200L, log.elapsedMs)
    }

    @Test
    @DisplayName("[FR-REV-14] 组内回炉作答不更新记忆状态，日志 counted = false")
    fun relearnDoesNotUpdateMemory() {
        var s = ReviewSession.start(listOf(study.toDeckCard(null)), fsrs, t0).flip().rate(Rating.AGAIN, t0)
        s = s.flip().rate(Rating.GOOD, t0 + 60_000)
        val relearn = s.records.last()
        assertNull(relearn.toCardMemory(s.memory["c1"]))
        assertFalse(relearn.toLogEntry().counted)
        assertEquals(ReviewMode.RELEARN_IN_GROUP, relearn.toLogEntry().mode)
    }
}
