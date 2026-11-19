package com.lingxi.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lingxi.app.sync.WatchSync
import com.lingxi.app.ui.SampleData
import com.lingxi.core.model.Rating
import com.lingxi.core.sync.LinkState
import com.lingxi.core.sync.WatchMessage
import kotlinx.coroutines.launch
import java.util.UUID

/** 手表联调页：查看链路状态，把适合手表的卡片推过去，并显示手表回传的作答结果。 */
@Composable
fun WatchScreen() {
    val link = WatchSync.link
    val state by link.state.collectAsState()
    val log = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val watchCards = remember { SampleData.cards.filter { it.type.fitsWatch } }

    LaunchedEffect(link) {
        link.incoming.collect { msg ->
            if (msg is WatchMessage.ReviewResult) {
                val rating = Rating.entries.first { it.grade == msg.grade }
                log.add(0, "手表作答 ${msg.cardId}：$rating，用时 ${msg.responseMs / 1000.0} 秒")
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("手表联调", style = MaterialTheme.typography.headlineSmall)
        Text("链路：${link.transport} · ${state.label()}")
        WatchSync.relay.lastRelayError?.let { Text("中继提示：$it", color = MaterialTheme.colorScheme.outline) }
        watchCards.forEach { card ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(card.question)
                    Button(
                        enabled = state == LinkState.CONNECTED,
                        onClick = {
                            scope.launch {
                                val msg =
                                    WatchMessage.PushCard.from(
                                        id = UUID.randomUUID().toString(),
                                        card = card,
                                        options = listOf("对", "错"),
                                        reason = "联调：手动推送",
                                    )
                                log.add(0, if (link.send(msg)) "已推送 ${card.id}" else "推送失败 ${card.id}")
                            }
                        },
                    ) { Text("推送到手表") }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(log) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun LinkState.label(): String =
    when (this) {
        LinkState.CONNECTED -> "已连接"
        LinkState.CONNECTING -> "连接中"
        LinkState.DISCONNECTED -> "未连接"
    }
