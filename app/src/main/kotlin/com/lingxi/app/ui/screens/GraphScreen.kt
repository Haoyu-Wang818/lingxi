package com.lingxi.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.lingxi.app.ui.SampleData
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 知识图谱页。原型阶段将节点均匀排在圆周上；P2 阶段改为力导向布局并支持点击、缩放。
 */
@Composable
fun GraphScreen() {
    val nodeColor = MaterialTheme.colorScheme.primary
    val edgeColor = MaterialTheme.colorScheme.outline
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("知识图谱 · 高等数学", style = MaterialTheme.typography.headlineSmall)
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = min(size.width, size.height) * 0.35f
                val points = SampleData.points
                val positions =
                    points.mapIndexed { i, p ->
                        val angle = 2 * Math.PI * i / points.size - Math.PI / 2
                        p.id to Offset(center.x + radius * cos(angle).toFloat(), center.y + radius * sin(angle).toFloat())
                    }.toMap()

                SampleData.edges.forEach { e ->
                    val from = positions.getValue(e.fromId)
                    val to = positions.getValue(e.toId)
                    drawLine(edgeColor, from, to, strokeWidth = 3f)
                }
                val paint =
                    android.graphics.Paint().apply {
                        color = textColor
                        textSize = 36f
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                    }
                points.forEach { p ->
                    val pos = positions.getValue(p.id)
                    drawCircle(nodeColor, radius = 18f, center = pos)
                    drawIntoCanvas { it.nativeCanvas.drawText(p.title, pos.x, pos.y + 60f, paint) }
                }
            }
        }
    }
}
