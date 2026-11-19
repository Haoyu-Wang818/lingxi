package com.lingxi.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lingxi.context.Activity
import com.lingxi.context.AppCategory
import com.lingxi.context.ContextSignals
import com.lingxi.context.LearnableMomentDetector
import com.lingxi.core.ai.AiTask
import com.lingxi.core.ai.AiTaskType
import com.lingxi.core.ai.DefaultRoutingPolicy
import com.lingxi.core.ai.DeviceState
import com.lingxi.core.ai.NetworkType
import com.lingxi.core.ai.RouteDecision

/**
 * 情境实验室：手动调节信号，实时查看情境引擎与端云路由的判断及依据。
 * 既是开发调试工具，也是现场演示时的「手动触发入口」。接入真实传感器后，信号改为自动采集。
 */
@Composable
fun ContextLabScreen() {
    val detector = remember { LearnableMomentDetector() }
    val policy = remember { DefaultRoutingPolicy() }

    var heartRate by remember { mutableFloatStateOf(70f) }
    var justStopped by remember { mutableStateOf(true) }
    var app by remember { mutableStateOf(AppCategory.SHORT_VIDEO) }
    var inClass by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(true) }
    var battery by remember { mutableFloatStateOf(80f) }

    val signals =
        ContextSignals(
            localHour = 15,
            activity = Activity.STILL,
            previousActivity = if (justStopped) Activity.WALKING else Activity.STILL,
            heartRateBpm = heartRate.toInt(),
            restingHeartRateBpm = 66,
            screenOn = app != AppCategory.NONE,
            foregroundApp = app,
            inClass = inClass,
        )
    val assessment = detector.evaluate(signals)
    val device =
        DeviceState(
            network = if (online) NetworkType.UNMETERED else NetworkType.NONE,
            batteryPercent = battery.toInt(),
            isCharging = false,
        )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("情境实验室", style = MaterialTheme.typography.headlineSmall)

        Text("心率 ${heartRate.toInt()} 次/分（静息 66）")
        Slider(value = heartRate, onValueChange = { heartRate = it }, valueRange = 55f..120f)
        LabSwitch("刚从走动转为静止", justStopped) { justStopped = it }
        LabSwitch("正在上课", inClass) { inClass = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(AppCategory.SHORT_VIDEO, AppCategory.STUDY, AppCategory.NONE).forEach { category ->
                FilterChip(selected = app == category, onClick = { app = category }, label = { Text(category.label()) })
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val verdict = if (assessment.learnable) "可学习时刻：推送 ${assessment.suggestedCards} 张卡" else "现在不打扰"
                Text(verdict, style = MaterialTheme.typography.titleMedium)
                Text("得分 ${"%.2f".format(assessment.score)} · 认知负荷 ${assessment.load}")
                assessment.blockedBy?.let { Text("阻止原因：$it", color = MaterialTheme.colorScheme.error) }
                assessment.evidence.forEach { e ->
                    val sign = if (e.weight >= 0) "+" else "−"
                    Text("$sign ${e.description}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        HorizontalDivider()
        Text("端云路由", style = MaterialTheme.typography.titleMedium)
        LabSwitch("有网络", online) { online = it }
        Text("电量 ${battery.toInt()}%")
        Slider(value = battery, onValueChange = { battery = it }, valueRange = 0f..100f)
        AiTaskType.entries.forEach { type ->
            val decision = policy.decide(AiTask(type, inputChars = 400), device)
            val where =
                when (decision) {
                    is RouteDecision.Run -> decision.primary.name
                    is RouteDecision.Unavailable -> "不可用"
                }
            Text("${type.name} → $where：${decision.reasons.first()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LabSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun AppCategory.label() =
    when (this) {
        AppCategory.SHORT_VIDEO -> "刷短视频"
        AppCategory.STUDY -> "学习应用"
        AppCategory.NONE -> "息屏"
        else -> name
    }
