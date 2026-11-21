package com.lingxi.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lingxi.core.model.Course
import com.lingxi.core.model.repository.NoteStatus
import com.lingxi.core.model.repository.NoteSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一张照片在流水线中的进度。 */
data class CaptureProgress(
    val id: String,
    val label: String,
    val stage: String,
    val done: Boolean = false,
    val failed: Boolean = false,
)

/**
 * 课堂捕获页：选择课程（上课时间内自动预选正在上的课）、拍板书或相册导入，查看整理进度与笔记列表
 * [FR-CAP-01]、[FR-CAP-03]、[FR-COURSE-03]、[FR-NOTE-06]。
 */
@Composable
fun CaptureScreen(
    courses: List<Course>,
    selectedCourseId: String?,
    onSelectCourse: (String) -> Unit,
    progress: List<CaptureProgress>,
    notes: List<NoteSummary>,
    onCamera: () -> Unit,
    onOpenNote: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpenCourses: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("课堂捕获", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenCourses) { Text("课程与课表") }
            TextButton(onClick = onOpenSettings) { Text("设置") }
        }
        if (courses.isEmpty()) {
            Text("先在「课程与课表」里添加课程。", color = MaterialTheme.colorScheme.outline)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                courses.take(4).forEach { c ->
                    FilterChip(selected = c.id == selectedCourseId, onClick = { onSelectCourse(c.id) }, label = { Text(c.name) })
                }
            }
        }
        FilledTonalButton(onClick = onCamera, enabled = selectedCourseId != null, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.CameraAlt, contentDescription = null)
            Text("拍板书 / 相册导入", Modifier.padding(start = 8.dp))
        }
        progress.forEach { p ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(p.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        p.stage,
                        color = if (p.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        Text("最近笔记", style = MaterialTheme.typography.titleMedium)
        if (notes.isEmpty()) Text("还没有笔记，拍一张板书试试。", color = MaterialTheme.colorScheme.outline)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(notes, key = { it.lessonId }) { note ->
                Card(Modifier.fillMaxWidth().clickable { onOpenNote(note.lessonId) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${note.courseName} · ${note.title}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${DATE.format(Date(note.updatedAtMillis))} · 板书 ${note.captureCount} 张 · ${statusLabel(note.status)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        if (note.status != NoteStatus.READY) TextButton(onClick = { onRetry(note.lessonId) }) { Text("重试") }
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: NoteStatus) =
    when (status) {
        NoteStatus.READY -> "已整理"
        NoteStatus.PENDING -> "待整理（联网后自动补做）"
        NoteStatus.FAILED -> "整理失败"
    }

private val DATE = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
