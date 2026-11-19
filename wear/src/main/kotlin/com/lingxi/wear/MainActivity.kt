package com.lingxi.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.lingxi.core.model.Rating
import com.lingxi.core.sync.LinkState
import com.lingxi.core.sync.RelayRole
import com.lingxi.core.sync.RelayWatchLink
import com.lingxi.core.sync.WatchLink
import com.lingxi.core.sync.WatchMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/** 手表与手机的链路，进程内唯一。原型阶段走网关中继，接入 Data Layer 后换成主备双链路。 */
private object WatchSide {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val link: WatchLink by lazy {
        RelayWatchLink(BuildConfig.GATEWAY_URL, BuildConfig.PAIR_CODE, RelayRole.WATCH).also { it.start(scope) }
    }
}

/**
 * 手表端：等待手机推送的微复习卡片，作答后把结果回传给手机，由手机端的 FSRS 更新记忆状态。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MicroReview(WatchSide.link) }
    }
}

@Composable
private fun MicroReview(link: WatchLink) {
    val state by link.state.collectAsState()
    var card by remember { mutableStateOf<WatchMessage.PushCard?>(null) }
    var shownAt by remember { mutableLongStateOf(0L) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(link) {
        link.incoming.collect { msg ->
            if (msg is WatchMessage.PushCard) {
                card = msg
                shownAt = System.currentTimeMillis()
                feedback = null
            }
        }
    }

    fun answer(
        current: WatchMessage.PushCard,
        option: String,
    ) {
        // 原型约定第一个选项为正确答案；接入真实题库后由卡片携带答案或由手机判分。
        val correct = option == current.options.first()
        feedback = if (correct) "答对了！" else "再想想：${current.options.first()}"
        val now = System.currentTimeMillis()
        val rating = if (correct) Rating.GOOD else Rating.AGAIN
        scope.launch {
            link.send(WatchMessage.ReviewResult(UUID.randomUUID().toString(), current.cardId, rating.grade, now, now - shownAt))
        }
    }

    MaterialTheme {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val current = card
            when {
                current == null -> {
                    Text(if (state == LinkState.CONNECTED) "等待手机推送…" else "正在连接手机…", textAlign = TextAlign.Center)
                }

                feedback != null -> {
                    Text(feedback!!, textAlign = TextAlign.Center)
                }

                else -> {
                    Text(current.question, textAlign = TextAlign.Center)
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        current.options.take(2).forEach { option ->
                            Button(onClick = { answer(current, option) }) { Text(option) }
                        }
                    }
                }
            }
        }
    }
}
