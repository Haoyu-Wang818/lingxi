package com.lingxi.app.ui.screens

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
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lingxi.app.ui.SampleData

/**
 * 课堂捕获页：拍板书、录音、导入课件，并列出已生成的笔记。
 * 原型阶段按钮尚未接入 CameraX 与录音，P1 阶段实现。
 */
@Composable
fun CaptureScreen(
    onOpenCourses: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("课堂捕获", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenCourses) { Text("课程与课表") }
            TextButton(onClick = onOpenSettings) { Text("设置") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptureButton("拍板书", Icons.Outlined.CameraAlt)
            CaptureButton("录音", Icons.Outlined.Mic)
            CaptureButton("导入课件", Icons.Outlined.PictureAsPdf)
        }
        Text("最近笔记", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SampleData.notes) { note ->
                Card(Modifier.fillMaxWidth()) {
                    Text(note, Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun CaptureButton(
    label: String,
    icon: ImageVector,
) {
    FilledTonalButton(onClick = { /* P1：接入 CameraX / 录音 / 文件选择 */ }) {
        Icon(icon, contentDescription = null)
        Text(label, Modifier.padding(start = 4.dp))
    }
}
