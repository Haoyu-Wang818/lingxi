package com.lingxi.feature.review

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingxi.core.model.Rating
import com.lingxi.core.review.CardFace
import com.lingxi.core.review.CardText
import com.lingxi.core.review.DeckCard
import com.lingxi.core.review.FsrsScheduler
import com.lingxi.core.review.MemoryState
import com.lingxi.core.review.RatingRecord
import com.lingxi.core.review.ReviewSession
import com.lingxi.core.review.Scheduler
import com.lingxi.core.review.SessionSummary
import java.time.Duration
import kotlin.math.abs

private const val SWIPE_THRESHOLD_DP = 96f

/**
 * 卡片复习页：一屏一卡、轻点 3D 翻面、三键与滑动自评、客观题自动判分、撤销、组末小结
 * [FR-REV-04]、[FR-REV-07]、[FR-REV-13]、[FR-REV-14]。
 *
 * 只负责展示与手势，全部状态转换交给 [ReviewSession]，因此界面逻辑无需单测即可保证评分正确。
 *
 * @param cards 本组卡片，按出卡顺序；为空时显示「今天复习完了」。
 * @param streakDays 含今天的连续学习天数，用于组末小结。
 * @param onRated 每次提交评分后回调：评分记录与该卡更新后的 FSRS 状态（回炉作答为 null），由 app 写入数据库。
 * @param onUndo 撤销上一次评分后回调，由 app 撤销对应的数据库写入。
 * @param onNextGroup 小结页「再来一组」；为空时用同一批卡片再来一组（原型行为）。
 * @param onSourceClick 点卡片背面的课堂溯源，查看原始板书 [FR-REV-10]。
 * @param onDone 小结页点「今天到这」时回调，为空则不显示该按钮。
 */
@Composable
fun ReviewDeckScreen(
    cards: List<DeckCard>,
    modifier: Modifier = Modifier,
    streakDays: Int = 0,
    scheduler: Scheduler = remember { FsrsScheduler() },
    onRated: (RatingRecord, MemoryState?) -> Unit = { _, _ -> },
    onUndo: () -> Unit = {},
    onNextGroup: (() -> Unit)? = null,
    onSourceClick: (DeckCard) -> Unit = {},
    onDone: (() -> Unit)? = null,
) {
    var session by remember(cards) {
        mutableStateOf(ReviewSession.start(cards, scheduler, System.currentTimeMillis(), streakDays))
    }

    // 所有状态转换都经过这里：评分记录增加 → 持久化；减少 → 撤销。
    val update: (ReviewSession) -> Unit = { next ->
        val before = session.records.size
        session = next
        when {
            next.records.size > before -> next.records.last().let { onRated(it, if (it.relearn) null else next.memory[it.cardId]) }
            next.records.size < before -> onUndo()
        }
    }

    if (cards.isEmpty()) {
        AllDone(streakDays, modifier)
        return
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TopBar(session, onUndo = { update(session.undo()) })

        val summary = session.summary()
        if (summary != null) {
            SummaryPage(
                summary = summary,
                onAgain = {
                    if (onNextGroup != null) {
                        onNextGroup()
                    } else {
                        val next = cards.map { it.copy(memory = session.memory[it.card.id] ?: it.memory) }
                        session = ReviewSession.start(next, scheduler, System.currentTimeMillis(), streakDays)
                    }
                },
                onDone = onDone,
            )
            return@Column
        }

        key(session.position) {
            FlipCard(
                session = session,
                onFlip = { session = session.flip() },
                onChoose = { session = session.choose(it) },
                onSwipe = { update(session.rate(it, System.currentTimeMillis())) },
                onSourceClick = onSourceClick,
                modifier = Modifier.weight(1f),
            )
        }
        BottomActions(session, update)
    }
}

@Composable
private fun AllDone(
    streakDays: Int,
    modifier: Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("今天的卡片都复习完了 🎉", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("到期的卡片会在该复习的时候再出现。", color = MaterialTheme.colorScheme.outline)
        if (streakDays > 0) Text("🔥 连续学习 $streakDays 天", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TopBar(
    session: ReviewSession,
    onUndo: () -> Unit,
) {
    val card = session.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = card?.let { "${it.courseName} · ${it.pointTitle}" } ?: "本组完成",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (card != null) {
            val (index, total) = session.progress
            Text(
                text = if (session.isRelearn) "回炉 $index/$total" else "$index/$total",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        TextButton(onClick = onUndo, enabled = session.canUndo) { Text("↶ 撤销") }
    }
}

/** 一张可翻面、可滑动的卡片。滑动只在背面且非客观题时生效，正面滑动会回弹，保证「先回忆后评分」。 */
@Composable
private fun FlipCard(
    session: ReviewSession,
    onFlip: () -> Unit,
    onChoose: (Int) -> Unit,
    onSwipe: (Rating) -> Unit,
    onSourceClick: (DeckCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (session.current == null) return
    val density = LocalDensity.current
    val threshold = with(density) { SWIPE_THRESHOLD_DP.dp.toPx() }
    val rotation by animateFloatAsState(
        targetValue = if (session.face == CardFace.BACK) 180f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "flip",
    )
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val swipeEnabled = session.face == CardFace.BACK && session.choices == null
    val pending = swipeRating(dragX, dragY, threshold)

    Box(modifier.fillMaxWidth()) {
        Card(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationY = rotation
                        cameraDistance = 14f * density.density
                        translationX = dragX
                        translationY = dragY.coerceAtMost(0f)
                        rotationZ = dragX / 40f
                    }.pointerInput(swipeEnabled) {
                        detectDragGestures(
                            onDragEnd = {
                                val rating = swipeRating(dragX, dragY, threshold)
                                dragX = 0f
                                dragY = 0f
                                if (swipeEnabled && rating != null) onSwipe(rating)
                            },
                            onDragCancel = {
                                dragX = 0f
                                dragY = 0f
                            },
                        ) { change, amount ->
                            change.consume()
                            if (swipeEnabled) {
                                dragX += amount.x
                                dragY += amount.y
                            }
                        }
                    }.clickable(enabled = session.choices == null) { onFlip() },
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            if (rotation <= 90f) {
                FrontFace(session, onChoose)
            } else {
                // 背面内容再翻转 180°，避免文字镜像。
                Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { BackFace(session, onSourceClick) }
            }
        }
        if (pending != null) {
            Text(
                text = ratingLabel(pending),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
                style = MaterialTheme.typography.titleLarge.copy(background = ratingColor(pending)),
            )
        }
    }
}

@Composable
private fun FrontFace(
    session: ReviewSession,
    onChoose: (Int) -> Unit,
) {
    val deck = session.current ?: return
    val choices = session.choices
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = CardText.front(deck.card),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        if (choices == null) {
            Text("轻点翻面 · 先想一想", color = MaterialTheme.colorScheme.outline)
        } else {
            choices.options.forEachIndexed { index, option ->
                OutlinedButton(
                    onClick = { onChoose(index) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(min = 48.dp),
                ) { Text(option, fontSize = 17.sp) }
            }
        }
    }
}

@Composable
private fun BackFace(
    session: ReviewSession,
    onSourceClick: (DeckCard) -> Unit,
) {
    val deck = session.current ?: return
    val answer = session.choiceAnswer
    val choices = session.choices
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(CardText.back(deck.card), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        if (answer != null && choices != null) {
            Text(
                text = if (answer.correct) "✓ 答对了" else "✗ 你选了「${choices.options[answer.selectedIndex]}」",
                color = if (answer.correct) GOOD_COLOR else AGAIN_COLOR,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = deck.card.answer,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(deck.pointSummary, style = MaterialTheme.typography.bodyLarge)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        deck.sourceLabel?.let {
            Text(
                "📷 $it  ▸",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.clickable { onSourceClick(deck) }.padding(vertical = 4.dp),
            )
        }
        val neighbors =
            listOfNotNull(
                deck.prerequisites.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "前置："),
                deck.related.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "相关："),
            )
        if (neighbors.isNotEmpty()) {
            Text(neighbors.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

/** 底部操作区：正面留白；背面为三键（客观题为继续 / 其实是蒙的）。 */
@Composable
private fun BottomActions(
    session: ReviewSession,
    update: (ReviewSession) -> Unit,
) {
    val now = System.currentTimeMillis()
    Box(Modifier.fillMaxWidth().heightIn(min = 96.dp), contentAlignment = Alignment.Center) {
        when {
            session.face == CardFace.FRONT -> {
                if (session.choices != null) {
                    Text("点选答案，自动判分", color = MaterialTheme.colorScheme.outline)
                }
            }

            session.choiceAnswer != null -> {
                val answer = session.choiceAnswer!!
                val intervals = session.predictIntervals(now)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (answer.correct) {
                        RatingButton("其实是蒙的", intervals[Rating.HARD], HARD_COLOR, Modifier.weight(1f)) {
                            update(session.markGuessed(System.currentTimeMillis()))
                        }
                    }
                    RatingButton("继续", intervals[answer.autoRating], ratingColor(answer.autoRating), Modifier.weight(1f)) {
                        update(session.confirmChoice(System.currentTimeMillis()))
                    }
                }
            }

            else -> {
                val intervals = session.predictIntervals(now)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD).forEach { rating ->
                            RatingButton(ratingLabel(rating), intervals[rating], ratingColor(rating), Modifier.weight(1f)) {
                                update(session.rate(rating, System.currentTimeMillis()))
                            }
                        }
                    }
                    val easy = intervals[Rating.EASY]?.let { " ${CardText.interval(it)}" } ?: ""
                    Text(
                        text = "← 左滑忘了 · 右滑记得 → · ↑ 上滑太简单$easy",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RatingButton(
    label: String,
    interval: Duration?,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontWeight = FontWeight.SemiBold)
            if (interval != null) Text(CardText.interval(interval), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SummaryPage(
    summary: SessionSummary,
    onAgain: () -> Unit,
    onDone: (() -> Unit)?,
) {
    val seconds = summary.durationMillis / 1000
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("本组完成 ✓", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "${summary.cardCount} 张 · ${seconds / 60} 分 ${seconds % 60} 秒 · 记得 ${summary.rememberedCount}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "记得率 ${Math.round(summary.rememberRate * 100)}%",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (summary.consolidated.isNotEmpty()) {
            Text(
                "巩固了：${summary.consolidated.joinToString("、")}",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
        if (summary.streakDays > 0) {
            Text("🔥 连续学习 ${summary.streakDays} 天", style = MaterialTheme.typography.titleMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onAgain) { Text("再来一组") }
            if (onDone != null) OutlinedButton(onClick = onDone) { Text("今天到这") }
        }
    }
}

/** 根据拖动距离判断滑动意图：上滑优先于左右。 */
private fun swipeRating(
    dx: Float,
    dy: Float,
    threshold: Float,
): Rating? =
    when {
        -dy > threshold && -dy > abs(dx) -> Rating.EASY
        dx > threshold -> Rating.GOOD
        dx < -threshold -> Rating.AGAIN
        else -> null
    }

private fun ratingLabel(rating: Rating) =
    when (rating) {
        Rating.AGAIN -> "忘了"
        Rating.HARD -> "模糊"
        Rating.GOOD -> "记得"
        Rating.EASY -> "太简单"
    }

private val AGAIN_COLOR = Color(0xFFD9534F)
private val HARD_COLOR = Color(0xFFE6A23C)
private val GOOD_COLOR = Color(0xFF3C9D5D)
private val EASY_COLOR = Color(0xFF3A7BD5)

private fun ratingColor(rating: Rating) =
    when (rating) {
        Rating.AGAIN -> AGAIN_COLOR
        Rating.HARD -> HARD_COLOR
        Rating.GOOD -> GOOD_COLOR
        Rating.EASY -> EASY_COLOR
    }
