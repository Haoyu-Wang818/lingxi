package com.lingxi.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lingxi.app.di.AppContainer
import com.lingxi.app.di.SeedData
import com.lingxi.core.model.repository.StudyCard
import com.lingxi.core.review.DeckCard
import com.lingxi.core.review.toCardMemory
import com.lingxi.core.review.toDeckCard
import com.lingxi.core.review.toLogEntry
import com.lingxi.feature.review.ReviewDeckScreen
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** 一组最多出几张卡（设计为每组 5–8 张，FR-REV-14）。 */
private const val GROUP_SIZE = 8

/** 今日剩余卡片统计上限，避免积压很多时一次查太多。 */
private const val TODAY_CAP = 500

/** 每张卡预计用时（秒），用于「约 N 分钟」。 */
private const val SECONDS_PER_CARD = 15

/**
 * 复习页：从数据库读取今日到期的卡片，评分写入 `memory_state` 与 `review_log`，
 * 杀掉进程重启后进度仍在 [FR-REV-04]、[FR-REV-05]、[FR-REV-08]。
 */
@Composable
fun ReviewTab(
    container: AppContainer,
    onOpenSource: (captureId: String, box: List<Int>, title: String) -> Unit = { _, _, _ -> },
) {
    val scope = rememberCoroutineScope()
    var deck by remember { mutableStateOf<List<DeckCard>?>(null) }
    var dueToday by remember { mutableIntStateOf(0) }
    var reviewedToday by remember { mutableIntStateOf(0) }
    var streak by remember { mutableIntStateOf(0) }
    var loadKey by remember { mutableIntStateOf(0) }
    // 评分与撤销按发生顺序串行写库；栈里是本次会话写入的日志 id，撤销时逐条回退。
    val writes = remember { Mutex() }
    val logIds = remember { ArrayDeque<Long>() }

    suspend fun refreshCounts() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val endOfDay = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        dueToday = container.cards.dueQueue(endOfDay, TODAY_CAP).size
        reviewedToday = container.cards.countedReviewsSince(today.atStartOfDay(zone).toInstant().toEpochMilli())
        streak = container.cards.streakDays(today, zone)
    }

    LaunchedEffect(loadKey) {
        SeedData.seedIfNeeded(container)
        logIds.clear()
        val endOfDay = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        deck = container.cards.dueQueue(endOfDay, GROUP_SIZE).map { it.toDeckCard(sourceLabel(it)) }
        refreshCounts()
    }

    Column {
        val minutes = (dueToday * SECONDS_PER_CARD + 59) / 60
        Text(
            text = "今日 $dueToday 张，约 $minutes 分钟" + if (reviewedToday > 0) " · 已复习 $reviewedToday 张" else "",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        deck?.let { cards ->
            ReviewDeckScreen(
                cards = cards,
                // 小结里的连续天数含今天；今天还没复习过时，完成本组后会 +1。
                streakDays = if (cards.isEmpty() || reviewedToday > 0) streak else streak + 1,
                onRated = { record, state ->
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        writes.withLock {
                            logIds.addLast(container.cards.saveReview(record.cardId, record.toCardMemory(state), record.toLogEntry()))
                            refreshCounts()
                        }
                    }
                },
                onUndo = {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        writes.withLock {
                            logIds.removeLastOrNull()?.let { container.cards.undo(it) }
                            refreshCounts()
                        }
                    }
                },
                onNextGroup = { loadKey++ },
                onSourceClick = { deck ->
                    deck.card.sourceRef?.let { ref -> onOpenSource(ref.captureId, ref.box, "${deck.courseName} · ${deck.pointTitle}") }
                },
            )
        }
    }
}

/** 卡片背面的课堂溯源文字，如「11月18日 板书 第3页」；日期来自 capture 表的拍摄时间。 */
private fun sourceLabel(card: StudyCard): String? {
    val ref = card.card.sourceRef ?: return null
    val date = card.capturedAtMillis?.let { SimpleDateFormat("M月d日", Locale.CHINA).format(Date(it)) }
    val page = ref.page?.let { "板书 第${it}页" } ?: "课堂素材"
    return listOfNotNull(date, page).joinToString(" ")
}
