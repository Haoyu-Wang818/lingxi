package com.lingxi.core.review

import com.lingxi.core.model.Rating
import java.time.Duration

/** 卡片当前朝上的一面。 */
enum class CardFace { FRONT, BACK }

/**
 * 客观题的作答结果 [FR-REV-13]。
 *
 * @property selectedIndex 用户点选的选项下标。
 * @property correct 是否答对；答对自动评 GOOD，答错自动评 AGAIN。
 */
data class ChoiceAnswer(
    val selectedIndex: Int,
    val correct: Boolean,
) {
    /** 自动判分得到的评分。 */
    val autoRating: Rating get() = if (correct) Rating.GOOD else Rating.AGAIN
}

/**
 * 一次已提交的自评。
 *
 * @property relearn 是否为组末回炉作答；回炉不写入 FSRS（同日重复不重复计分）[FR-REV-14]。
 * @property interval 本次调度得到的下次间隔，回炉作答为 null。
 */
data class RatingRecord(
    val cardId: String,
    val rating: Rating,
    val relearn: Boolean,
    val interval: Duration?,
    val atMillis: Long,
)

/**
 * 组末小结 [FR-REV-09]。
 *
 * @property cardCount 本组卡片张数（不含回炉重复）。
 * @property rememberedCount 首次作答没有选「忘了」的张数。
 * @property consolidated 首次作答记住的知识点名称，按出卡顺序去重。
 * @property durationMillis 从开始到最后一次评分的用时。
 */
data class SessionSummary(
    val cardCount: Int,
    val rememberedCount: Int,
    val consolidated: List<String>,
    val durationMillis: Long,
    val streakDays: Int,
) {
    /** 记得率，0–1；空组为 0。 */
    val rememberRate: Double get() = if (cardCount == 0) 0.0 else rememberedCount.toDouble() / cardCount
}

/**
 * 一组卡片的复习会话状态机 [FR-REV-04]、[FR-REV-07]、[FR-REV-13]、[FR-REV-14]。
 *
 * 不可变对象：每个操作返回新会话，界面只需持有当前值并重绘，撤销就是回到保存的上一个快照，
 * 不必逆向计算 FSRS。所有出卡、翻面、判分、回炉、小结逻辑都在这里，界面层只负责展示与手势。
 *
 * 流程：正面 →（轻点）背面 → 自评 → 下一张；客观题在正面点选即判分并翻到背面，
 * 再「继续」提交自动评分或「其实是蒙的」改为 HARD。首轮答「忘了」的卡在组末再出现一次。
 */
class ReviewSession private constructor(
    private val scheduler: Scheduler,
    /** 出卡队列：本组卡片，后面追加需要回炉的卡。 */
    val queue: List<DeckCard>,
    /** 当前卡片在 [queue] 中的位置；等于队列长度表示本组结束。 */
    val position: Int,
    val face: CardFace,
    /** 当前客观题的作答结果，未作答或非客观题为 null。 */
    val choiceAnswer: ChoiceAnswer?,
    /** 本组内更新过的记忆状态，按卡片 id。 */
    val memory: Map<String, MemoryState>,
    val records: List<RatingRecord>,
    private val initialCount: Int,
    private val startedAtMillis: Long,
    private val streakDays: Int,
    private val previous: ReviewSession?,
) {
    /** 本组是否已全部完成。 */
    val isFinished: Boolean get() = position >= queue.size

    /** 当前卡片；本组完成后为 null。 */
    val current: DeckCard? get() = queue.getOrNull(position)

    /** 当前卡片是否为组末回炉。 */
    val isRelearn: Boolean get() = position >= initialCount

    /** 当前卡片的客观题选项，非客观题为 null。 */
    val choices: Choices? get() = current?.let { CardText.choices(it.card) }

    /** 是否可以撤销上一次评分。 */
    val canUndo: Boolean get() = previous != null

    /** 顶栏进度：第几张 / 共几张（含已追加的回炉卡）。 */
    val progress: Pair<Int, Int> get() = (position + 1).coerceAtMost(queue.size) to queue.size

    /** 当前卡片的记忆状态：本组更新过的优先，否则用卡片自带状态。 */
    private fun memoryOf(card: DeckCard): MemoryState? = memory[card.card.id] ?: card.memory

    /** 轻点翻面；客观题必须点选作答，不能直接翻面。 */
    fun flip(): ReviewSession {
        if (isFinished || choices != null) return this
        return copy(face = if (face == CardFace.FRONT) CardFace.BACK else CardFace.FRONT)
    }

    /** 客观题点选 [index]：自动判对错并翻到背面 [FR-REV-13]。重复点选无效。 */
    fun choose(index: Int): ReviewSession {
        val c = choices ?: return this
        if (choiceAnswer != null || index !in c.options.indices) return this
        return copy(face = CardFace.BACK, choiceAnswer = ChoiceAnswer(index, index == c.correctIndex))
    }

    /** 提交客观题的自动判分。 */
    fun confirmChoice(nowMillis: Long): ReviewSession {
        val answer = choiceAnswer ?: return this
        return commit(answer.autoRating, nowMillis)
    }

    /** 答对后「其实是蒙的」：改判为 HARD [FR-REV-13]。答错时无效。 */
    fun markGuessed(nowMillis: Long): ReviewSession {
        val answer = choiceAnswer ?: return this
        if (!answer.correct) return this
        return commit(Rating.HARD, nowMillis)
    }

    /**
     * 自评当前卡片（按钮或滑动手势）[FR-REV-07]。只能在背面评分；客观题请走 [confirmChoice] / [markGuessed]。
     */
    fun rate(
        rating: Rating,
        nowMillis: Long,
    ): ReviewSession {
        if (isFinished || face != CardFace.BACK || choices != null) return this
        return commit(rating, nowMillis)
    }

    private fun commit(
        rating: Rating,
        nowMillis: Long,
    ): ReviewSession {
        val card = current ?: return this
        val id = card.card.id
        val relearn = isRelearn
        var newMemory = memory
        var interval: Duration? = null
        if (!relearn) {
            val result = scheduler.schedule(memoryOf(card), rating, nowMillis)
            newMemory = memory + (id to result.state)
            interval = Duration.ofMillis(result.dueAtMillis - nowMillis)
        }
        val newQueue = if (!relearn && rating == Rating.AGAIN) queue + card else queue
        return ReviewSession(
            scheduler = scheduler,
            queue = newQueue,
            position = position + 1,
            face = CardFace.FRONT,
            choiceAnswer = null,
            memory = newMemory,
            records = records + RatingRecord(id, rating, relearn, interval, nowMillis),
            initialCount = initialCount,
            startedAtMillis = startedAtMillis,
            streakDays = streakDays,
            previous = this,
        )
    }

    /** 撤销上一次评分：回到评分前的那一面，FSRS 状态与回炉队列一并恢复。 */
    fun undo(): ReviewSession = previous ?: this

    /** 当前卡片四档评分各自的下次间隔，显示在按钮上；回炉卡与已结束时为空。 */
    fun predictIntervals(nowMillis: Long): Map<Rating, Duration> {
        val card = current ?: return emptyMap()
        if (isRelearn) return emptyMap()
        return scheduler.predictIntervals(memoryOf(card), nowMillis)
    }

    /** 组末小结；本组未完成时返回 null。 */
    fun summary(): SessionSummary? {
        if (!isFinished) return null
        val firstPass = records.filterNot { it.relearn }
        val remembered = firstPass.filter { it.rating != Rating.AGAIN }.map { it.cardId }.toSet()
        val titles =
            queue
                .take(initialCount)
                .filter { it.card.id in remembered }
                .map { it.pointTitle }
                .distinct()
        return SessionSummary(
            cardCount = initialCount,
            rememberedCount = remembered.size,
            consolidated = titles,
            durationMillis = ((records.lastOrNull()?.atMillis ?: startedAtMillis) - startedAtMillis).coerceAtLeast(0),
            streakDays = streakDays,
        )
    }

    private fun copy(
        face: CardFace = this.face,
        choiceAnswer: ChoiceAnswer? = this.choiceAnswer,
    ) = ReviewSession(
        scheduler,
        queue,
        position,
        face,
        choiceAnswer,
        memory,
        records,
        initialCount,
        startedAtMillis,
        streakDays,
        previous,
    )

    companion object {
        /**
         * 开始一组复习。
         *
         * @param cards 本组卡片，按出卡顺序；设计上每组 5–8 张 [FR-REV-14]。
         * @param streakDays 含今天在内的连续学习天数，用于小结展示。
         */
        fun start(
            cards: List<DeckCard>,
            scheduler: Scheduler,
            nowMillis: Long,
            streakDays: Int = 0,
        ): ReviewSession =
            ReviewSession(
                scheduler = scheduler,
                queue = cards,
                position = 0,
                face = CardFace.FRONT,
                choiceAnswer = null,
                memory = emptyMap(),
                records = emptyList(),
                initialCount = cards.size,
                startedAtMillis = nowMillis,
                streakDays = streakDays,
                previous = null,
            )
    }
}
