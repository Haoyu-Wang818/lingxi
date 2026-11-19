package com.lingxi.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lingxi.app.ui.SampleData
import com.lingxi.core.model.Rating
import com.lingxi.core.review.FsrsScheduler
import com.lingxi.core.review.MemoryState

/** 复习页：翻卡 → 自评 → 由 FSRS 算出下次复习时间。 */
@Composable
fun ReviewScreen() {
    val scheduler = remember { FsrsScheduler() }
    val states = remember { mutableStateMapOf<String, MemoryState>() }
    var index by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<String?>(null) }
    val card = SampleData.cards[index % SampleData.cards.size]

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("今日复习", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth().height(220.dp).clickable { revealed = !revealed }) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(card.question, style = MaterialTheme.typography.titleLarge)
                if (revealed) {
                    Text(card.answer, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("点击卡片查看答案", color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        if (revealed) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Rating.entries.forEach { rating ->
                    OutlinedButton(onClick = {
                        val result = scheduler.review(states[card.id], rating, System.currentTimeMillis())
                        states[card.id] = result.state
                        lastResult = "下次复习：${formatInterval(result.intervalDays)}后"
                        revealed = false
                        index++
                    }) { Text(rating.label()) }
                }
            }
        }
        lastResult?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
    }
}

private fun Rating.label() =
    when (this) {
        Rating.AGAIN -> "忘了"
        Rating.HARD -> "困难"
        Rating.GOOD -> "记得"
        Rating.EASY -> "简单"
    }

private fun formatInterval(days: Double): String =
    if (days < 1) "${(days * 24 * 60).toInt()} 分钟" else "${"%.1f".format(days)} 天"
