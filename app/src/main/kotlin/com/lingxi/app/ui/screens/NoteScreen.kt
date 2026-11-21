package com.lingxi.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lingxi.core.model.repository.AiExecution
import com.lingxi.core.model.repository.LessonNote
import com.lingxi.core.model.repository.NoteStatus

/**
 * 笔记详情：结构化笔记、识别原文对照、编辑（保留生成原文）、执行位置与原因 [FR-NOTE-03]、[FR-NOTE-04]、[FR-AI-04]。
 */
@Composable
fun NoteScreen(
    note: LessonNote?,
    recognizedText: String,
    executions: List<AiExecution>,
    cardCount: Int,
    onSave: (String) -> Unit,
    onRetry: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (note == null) {
            Text("这个课次还没有笔记。", color = MaterialTheme.colorScheme.outline)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(note.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (note.status == NoteStatus.READY) TextButton(onClick = { editing = true }) { Text("编辑") } else TextButton(onClick = onRetry) { Text("重试") }
        }
        executions.forEach { e ->
            Text(
                "${taskLabel(e.taskType)}：${locationLabel(e.location)}${e.durationMs?.let { "，${it} ms" } ?: ""}，原因：${e.reason}" +
                    if (e.success) "" else "（未完成 ${e.errorCode ?: ""}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (note.status == NoteStatus.READY) Text("本节生成 $cardCount 张卡片，已加入今日复习。", color = MaterialTheme.colorScheme.primary)
        Card(Modifier.fillMaxWidth()) {
            Text(note.markdown, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        }
        TextButton(onClick = { showOriginal = !showOriginal }) { Text(if (showOriginal) "收起识别原文" else "对照识别原文") }
        if (showOriginal) {
            HorizontalDivider()
            Text(recognizedText.ifBlank { "（无）" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            note.originalMarkdown?.let {
                Text("编辑前的生成版本：", style = MaterialTheme.typography.titleSmall)
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
    if (editing && note != null) {
        var text by remember { mutableStateOf(note.markdown) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("编辑笔记") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 240.dp)) },
            confirmButton = {
                TextButton(onClick = {
                    onSave(text)
                    editing = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } },
        )
    }
}

private fun taskLabel(type: String) =
    when (type) {
        "OCR" -> "文字识别"
        "EXTRACT" -> "整理笔记与出卡"
        else -> type
    }

private fun locationLabel(location: String) =
    when (location) {
        "CLOUD" -> "云端"
        "ON_DEVICE" -> "手机本地"
        else -> "未执行"
    }
