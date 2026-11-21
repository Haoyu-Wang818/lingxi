package com.lingxi.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lingxi.core.ai.PrivacySettings
import com.lingxi.core.model.repository.AiExecution
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页（I1 部分）：隐私开关、网关状态、云端问答试运行与 AI 执行记录 [FR-SET-01]、[FR-AI-04]。
 *
 * 只做界面，读写全部通过参数传入的函数完成，由 app 的 AppContainer 提供实现。
 *
 * @param checkGateway 调用网关 /health，返回给用户看的一行状态。
 * @param askCloud 通过 InferenceRouter 发起一次答疑（CHAT），返回回答或失败原因。
 * @param recentExecutions 最近的 AI 执行记录（新的在前）。
 */
@Composable
fun SettingsScreen(
    privacy: PrivacySettings,
    onPrivacyChange: (PrivacySettings) -> Unit,
    checkGateway: suspend () -> String,
    askCloud: suspend (String) -> String,
    recentExecutions: suspend () -> List<AiExecution>,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var gatewayStatus by remember { mutableStateOf("尚未检查") }
    var question by remember { mutableStateOf("用一句话解释泰勒公式") }
    var answer by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var executions by remember { mutableStateOf<List<AiExecution>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(version) { executions = recentExecutions() }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("设置", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("隐私", style = MaterialTheme.typography.titleMedium)
                    ToggleRow("允许上传板书识别文本", privacy.uploadOcrText) { onPrivacyChange(privacy.copy(uploadOcrText = it)) }
                    ToggleRow("允许上传录音转写文本", privacy.uploadTranscript) { onPrivacyChange(privacy.copy(uploadTranscript = it)) }
                    Text(
                        "录音音频始终只在手机上处理。两项都关闭后，含个人内容的整理、出题与答疑不会再发往云端。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("云端网关", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(gatewayStatus, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { scope.launch { gatewayStatus = checkGateway() } }) { Text("检查") }
                    }
                    OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), label = { Text("试问一个问题（含个人内容）") })
                    Button(
                        enabled = !busy && question.isNotBlank(),
                        onClick = {
                            busy = true
                            scope.launch {
                                answer = askCloud(question)
                                busy = false
                                version++
                            }
                        },
                    ) { Text(if (busy) "请求中…" else "发送") }
                    answer?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        item { Text("AI 执行记录（在哪里执行、为什么）", style = MaterialTheme.typography.titleMedium) }
        if (executions.isEmpty()) {
            item { Text("暂无记录", color = MaterialTheme.colorScheme.outline) }
        }
        items(executions, key = { it.id }) { ExecutionRow(it) }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ExecutionRow(e: AiExecution) {
    val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(e.startedAtMillis))
    Column(Modifier.fillMaxWidth()) {
        Text(
            "$time  ${e.taskType} → ${locationLabel(e.location)}  ${if (e.success) "✓" else "✗ ${e.errorCode ?: ""}"}" +
                (e.durationMs?.let { "  $it ms" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(e.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

private fun locationLabel(location: String) =
    when (location) {
        "CLOUD" -> "云端"
        "ON_DEVICE" -> "端侧"
        else -> "未执行"
    }
