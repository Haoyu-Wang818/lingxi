package com.lingxi.core.review

import com.lingxi.core.model.CardType
import com.lingxi.core.model.Rating
import com.lingxi.core.model.ReviewCard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

class ReviewSessionTest {
    private val fsrs = FsrsScheduler()
    private val t0 = 1_700_000_000_000L

    private fun qa(
        id: String,
        point: String = "知识点$id",
    ) = DeckCard(ReviewCard(id, "kp$id", "问题$id", "答案$id"), "高等数学", point, "解释$id")

    private val trueFalse =
        DeckCard(
            ReviewCard("tf", "kpTf", "可导一定连续？", "正确", CardType.TRUE_FALSE),
            "高等数学",
            "可导与连续",
            "可导必连续，反之不然。",
        )

    private val single =
        DeckCard(
            ReviewCard(
                "sc",
                "kpSc",
                "sin x 的导数？",
                "cos x",
                CardType.SINGLE_CHOICE,
                options = listOf("-cos x", "cos x", "sin x"),
                correctIndex = 1,
            ),
            "高等数学",
            "导数公式",
            "基本初等函数求导。",
        )

    private fun start(vararg cards: DeckCard) = ReviewSession.start(cards.toList(), fsrs, t0, streakDays = 6)

    @Test
    @DisplayName("[FR-REV-07] 轻点翻面：正面 → 背面 → 正面，未翻面时不能评分")
    fun flipTogglesFaceAndRatingNeedsBack() {
        val s = start(qa("1"), qa("2"))
        assertEquals(CardFace.FRONT, s.face)
        assertSame(s, s.rate(Rating.GOOD, t0), "正面不能评分")
        val back = s.flip()
        assertEquals(CardFace.BACK, back.face)
        assertEquals(CardFace.FRONT, back.flip().face)
    }

    @Test
    @DisplayName("[FR-REV-04] 评分后进入下一张，FSRS 状态与下次间隔立即更新")
    fun ratingAdvancesAndUpdatesMemory() {
        val s = start(qa("1"), qa("2")).flip().rate(Rating.GOOD, t0 + 1000)
        assertEquals(1, s.position)
        assertEquals("2", s.current?.card?.id)
        assertEquals(CardFace.FRONT, s.face)
        val expected = fsrs.schedule(null, Rating.GOOD, t0 + 1000)
        assertEquals(expected.state, s.memory["1"])
        assertEquals(Duration.ofMillis(expected.dueAtMillis - t0 - 1000), s.records.single().interval)
    }

    @Test
    @DisplayName("[FR-REV-07] 三键与上滑分别记录 AGAIN / HARD / GOOD / EASY，按钮间隔与调度一致")
    fun eachRatingIsRecordedAndMatchesPrediction() {
        Rating.entries.forEach { rating ->
            val s = start(qa("1")).flip()
            val predicted = s.predictIntervals(t0).getValue(rating)
            val after = s.rate(rating, t0)
            assertEquals(rating, after.records.single().rating)
            assertEquals(predicted, after.records.single().interval, "rating=$rating")
        }
    }

    @Test
    @DisplayName("[FR-REV-13] 判断题点选即判分：答对自动 GOOD，答错自动 AGAIN")
    fun trueFalseAutoGrades() {
        val s = start(trueFalse)
        assertSame(s, s.flip(), "客观题不能直接翻面")
        val right = s.choose(0)
        assertEquals(CardFace.BACK, right.face)
        assertTrue(right.choiceAnswer!!.correct)
        assertSame(right, right.rate(Rating.EASY, t0), "客观题不走手动自评")
        assertEquals(
            Rating.GOOD,
            right
                .confirmChoice(t0)
                .records
                .single()
                .rating,
        )

        val wrong = s.choose(1)
        assertFalse(wrong.choiceAnswer!!.correct)
        assertEquals(
            Rating.AGAIN,
            wrong
                .confirmChoice(t0)
                .records
                .single()
                .rating,
        )
    }

    @Test
    @DisplayName("[FR-REV-13] 单选题答对后「其实是蒙的」改为 HARD，答错时不可改判")
    fun guessedDowngradesToHard() {
        val s = start(single)
        assertEquals(listOf("-cos x", "cos x", "sin x"), s.choices?.options)
        val right = s.choose(1)
        assertSame(right, right.choose(0), "重复点选无效")
        assertEquals(
            Rating.HARD,
            right
                .markGuessed(t0)
                .records
                .single()
                .rating,
        )

        val wrong = s.choose(2)
        assertSame(wrong, wrong.markGuessed(t0))
    }

    @Test
    @DisplayName("[FR-REV-04] 撤销上一张：回到评分前的背面，FSRS 状态与回炉队列一并恢复")
    fun undoRestoresPreviousState() {
        val s = start(qa("1"), qa("2"))
        assertFalse(s.canUndo)
        val back = s.flip()
        val rated = back.rate(Rating.AGAIN, t0)
        assertEquals(3, rated.queue.size)
        val undone = rated.undo()
        assertSame(back, undone)
        assertEquals(CardFace.BACK, undone.face)
        assertEquals(2, undone.queue.size)
        assertTrue(undone.memory.isEmpty())
        assertTrue(undone.records.isEmpty())
    }

    @Test
    @DisplayName("[FR-REV-04] 撤销客观题：回到已作答的背面，可改为「其实是蒙的」")
    fun undoObjectiveCardKeepsAnswer() {
        val answered = start(single, qa("2")).choose(1)
        val undone = answered.confirmChoice(t0).undo()
        assertEquals(1, undone.choiceAnswer?.selectedIndex)
        assertEquals(
            Rating.HARD,
            undone
                .markGuessed(t0)
                .records
                .single()
                .rating,
        )
    }

    @Test
    @DisplayName("[FR-REV-14] 「忘了」的卡在组末再出一次，回炉作答不重复写入 FSRS")
    fun forgottenCardReturnsOnceAtEnd() {
        var s = start(qa("1"), qa("2"), qa("3"))
        s = s.flip().rate(Rating.AGAIN, t0)
        s = s.flip().rate(Rating.GOOD, t0)
        s = s.flip().rate(Rating.GOOD, t0)
        assertFalse(s.isFinished)
        assertTrue(s.isRelearn)
        assertEquals("1", s.current?.card?.id)
        assertEquals(4 to 4, s.progress)
        assertTrue(s.predictIntervals(t0).isEmpty(), "回炉卡不显示间隔")
        val memoryBefore = s.memory
        s = s.flip().rate(Rating.AGAIN, t0 + 60_000)
        assertTrue(s.isFinished, "回炉再忘也不会无限追加")
        assertEquals(memoryBefore, s.memory)
        assertTrue(s.records.last().relearn)
        assertNull(s.records.last().interval)
    }

    @Test
    @DisplayName("[FR-REV-09] 组末小结：张数、记得率、巩固的知识点、用时与连续天数")
    fun summaryAggregatesFirstPass() {
        var s = start(qa("1", "泰勒展开"), qa("2", "麦克劳林公式"), trueFalse, qa("4", "泰勒展开"))
        assertNull(s.summary())
        s = s.flip().rate(Rating.GOOD, t0 + 10_000)
        s = s.flip().rate(Rating.AGAIN, t0 + 20_000)
        s = s.choose(0).confirmChoice(t0 + 30_000)
        s = s.flip().rate(Rating.EASY, t0 + 40_000)
        s = s.flip().rate(Rating.GOOD, t0 + 50_000) // 回炉
        val summary = requireNotNull(s.summary())
        assertEquals(4, summary.cardCount)
        assertEquals(3, summary.rememberedCount)
        assertEquals(0.75, summary.rememberRate, 1e-9)
        assertEquals(listOf("泰勒展开", "可导与连续"), summary.consolidated)
        assertEquals(50_000, summary.durationMillis)
        assertEquals(6, summary.streakDays)
    }

    @Test
    @DisplayName("[FR-REV-04] 进度显示组内第几张，结束后不可再操作")
    fun progressAndFinishedState() {
        var s = start(qa("1"), qa("2"))
        assertEquals(1 to 2, s.progress)
        s =
            s
                .flip()
                .rate(Rating.GOOD, t0)
                .flip()
                .rate(Rating.GOOD, t0)
        assertTrue(s.isFinished)
        assertNull(s.current)
        assertSame(s, s.flip())
        assertSame(s, s.rate(Rating.GOOD, t0))
        assertTrue(s.canUndo)
    }

    @Test
    @DisplayName("[FR-REV-11] 挖空题正面隐藏、背面填回；判断题默认选项按答案推断")
    fun clozeAndDefaultChoices() {
        val cloze = ReviewCard("cz", "kp", "eˣ 的麦克劳林展开为 {{c1::1 + x + x²/2! + …}}", "1 + x + x²/2! + …", CardType.CLOZE)
        assertEquals("eˣ 的麦克劳林展开为 ____", CardText.front(cloze))
        assertEquals("eˣ 的麦克劳林展开为 【1 + x + x²/2! + …】", CardText.back(cloze))
        assertNull(CardText.choices(cloze))
        assertEquals(0, CardText.choices(trueFalse.card)?.correctIndex)
        val falseCard = trueFalse.card.copy(answer = "错误。连续不一定可导")
        assertEquals(1, CardText.choices(falseCard)?.correctIndex)
    }

    @Test
    @DisplayName("[FR-REV-07] 按钮间隔格式：10分、3小时、4天、2个月、1年")
    fun intervalFormatting() {
        assertEquals("10分", CardText.interval(Duration.ofMinutes(10)))
        assertEquals("3小时", CardText.interval(Duration.ofHours(3)))
        assertEquals("1天", CardText.interval(Duration.ofHours(34)))
        assertEquals("4天", CardText.interval(Duration.ofHours(89)))
        assertEquals("2个月", CardText.interval(Duration.ofDays(62)))
        assertEquals("1年", CardText.interval(Duration.ofDays(365)))
    }
}
