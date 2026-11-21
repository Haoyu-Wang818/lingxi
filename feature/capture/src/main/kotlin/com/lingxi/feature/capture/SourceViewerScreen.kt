package com.lingxi.feature.capture

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.lingxi.core.model.repository.TextLine
import java.io.File

/**
 * 课堂溯源：显示卡片来源的原始板书，并在原图上框出对应的文字 [FR-REV-10]、[FR-NOTE-05]。
 *
 * @param imageFile 原图；示例数据没有原图时为 null。
 * @param lines 这张图上识别出的全部文字行（淡色框）。
 * @param highlight 卡片来源的区域 [左, 上, 右, 下]（醒目框），为空则只显示原图。
 */
@Composable
fun SourceViewerScreen(
    title: String,
    imageFile: File?,
    lines: List<TextLine>,
    highlight: List<Int>,
    modifier: Modifier = Modifier,
) {
    val decoded =
        remember(imageFile) {
            imageFile?.takeIf { it.exists() }?.let { f ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.let { it to sample }
            }
        }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (decoded == null) {
            Text("示例卡片没有原图；拍照生成的卡片可以在这里查看原始板书。", color = MaterialTheme.colorScheme.outline)
            return@Column
        }
        val (bitmap, sample) = decoded
        val accent = MaterialTheme.colorScheme.tertiary
        Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height), contentAlignment = Alignment.Center) {
            Image(bitmap.asImageBitmap(), contentDescription = "原始板书", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                // 文字框以原图像素为坐标，解码时降采样了 sample 倍，再按显示尺寸缩放。
                val sx = size.width / (bitmap.width * sample)
                val sy = size.height / (bitmap.height * sample)
                fun rect(b: List<Int>) = Offset(b[0] * sx, b[1] * sy) to Size((b[2] - b[0]) * sx, (b[3] - b[1]) * sy)
                lines.filter { it.box.size == 4 }.forEach { line ->
                    val (o, s) = rect(line.box)
                    drawRect(Color.White.copy(alpha = 0.35f), o, s, style = Stroke(width = 2f))
                }
                if (highlight.size == 4) {
                    val (o, s) = rect(highlight)
                    drawRect(accent.copy(alpha = 0.25f), o, s)
                    drawRect(accent, o, s, style = Stroke(width = 5f))
                }
            }
        }
        if (highlight.size == 4) {
            Text("彩色框为这张卡片对应的板书位置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
