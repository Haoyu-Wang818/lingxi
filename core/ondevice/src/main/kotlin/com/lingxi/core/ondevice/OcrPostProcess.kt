package com.lingxi.core.ondevice

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 图片中的一个矩形区域（像素，左闭右开）。 */
data class Box(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width get() = right - left
    val height get() = bottom - top

    /** 与 `SourceRef.box`、`recognized_text.segments_json` 一致的 [左, 上, 右, 下]。 */
    fun toList() = listOf(left, top, right, bottom)
}

/**
 * PP-OCR 的前后处理，全部是纯 Kotlin 计算，不依赖 Android，可在 JVM 上单测 [FR-NOTE-01]。
 *
 * 检测模型（DB）输出每个像素是文字的概率；这里把概率图二值化、找连通区域、按 DB 论文的 unclip 系数外扩，
 * 得到文字框。识别模型（CRNN + CTC）输出每个时间步的字符分布，用贪心 CTC 解码成文字。
 * 板书基本是横排，所以用轴对齐矩形，没有实现旋转框。
 */
object OcrPostProcess {
    /** 检测输入尺寸：长边不超过 [maxSide]，宽高都取 32 的倍数（DB 网络的下采样倍数）。 */
    fun detInputSize(
        width: Int,
        height: Int,
        maxSide: Int = 960,
    ): Pair<Int, Int> {
        val scale = min(1.0, maxSide.toDouble() / max(width, height))
        fun round32(v: Double) = max(32, (v / 32).roundToInt() * 32)
        return round32(width * scale) to round32(height * scale)
    }

    /**
     * 从概率图中提取文字框，并换算回原图坐标。
     *
     * @param prob 检测输出，长度 mapW × mapH，行优先。
     * @param threshold 像素二值化阈值（PaddleOCR 默认 0.3）。
     * @param boxThreshold 区域平均概率低于此值的框丢弃（默认 0.5）。
     * @param unclipRatio 外扩系数：DB 训练时把文字区域向内收缩，推理时按「面积 × 系数 / 周长」外扩回来。
     */
    fun detectBoxes(
        prob: FloatArray,
        mapW: Int,
        mapH: Int,
        imageW: Int,
        imageH: Int,
        threshold: Float = 0.3f,
        boxThreshold: Float = 0.5f,
        unclipRatio: Double = 1.6,
        minSide: Int = 3,
    ): List<Box> {
        require(prob.size == mapW * mapH) { "概率图尺寸不符" }
        val visited = BooleanArray(prob.size)
        val queue = IntArray(prob.size)
        val boxes = mutableListOf<Box>()
        val sx = imageW.toDouble() / mapW
        val sy = imageH.toDouble() / mapH
        for (start in prob.indices) {
            if (visited[start] || prob[start] <= threshold) continue
            // 8 邻域广度优先找连通区域，同时统计外接矩形与平均概率。
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = mapW
            var minY = mapH
            var maxX = -1
            var maxY = -1
            var sum = 0.0
            while (head < tail) {
                val p = queue[head++]
                val x = p % mapW
                val y = p / mapW
                sum += prob[p]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= mapH) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= mapW) continue
                        val q = ny * mapW + nx
                        if (!visited[q] && prob[q] > threshold) {
                            visited[q] = true
                            queue[tail++] = q
                        }
                    }
                }
            }
            val w = maxX - minX + 1
            val h = maxY - minY + 1
            if (min(w, h) < minSide || sum / tail < boxThreshold) continue
            val d = w.toDouble() * h * unclipRatio / (2.0 * (w + h))
            val left = ((minX - d) * sx).roundToInt().coerceIn(0, imageW - 1)
            val top = ((minY - d) * sy).roundToInt().coerceIn(0, imageH - 1)
            val right = ((maxX + 1 + d) * sx).roundToInt().coerceIn(left + 1, imageW)
            val bottom = ((maxY + 1 + d) * sy).roundToInt().coerceIn(top + 1, imageH)
            boxes += Box(left, top, right, bottom)
        }
        return sortReadingOrder(boxes)
    }

    /**
     * 阅读顺序：先按行（纵向重叠超过较矮框一半视为同一行），行内从左到右。
     */
    fun sortReadingOrder(boxes: List<Box>): List<Box> {
        val rows = mutableListOf<MutableList<Box>>()
        for (b in boxes.sortedBy { it.top }) {
            val row =
                rows.lastOrNull { r ->
                    val ref = r.first()
                    val overlap = min(ref.bottom, b.bottom) - max(ref.top, b.top)
                    overlap > min(ref.height, b.height) / 2
                }
            if (row != null) row += b else rows += mutableListOf(b)
        }
        return rows.flatMap { r -> r.sortedBy { it.left } }
    }

    /** 识别输入宽度：高度固定 [height]，宽度按比例缩放，限制在 [minW, maxW]。 */
    fun recInputWidth(
        boxW: Int,
        boxH: Int,
        height: Int = 48,
        minW: Int = 16,
        maxW: Int = 1600,
    ): Int = ceil(boxW.toDouble() * height / boxH).toInt().coerceIn(minW, maxW)

    /**
     * 贪心 CTC 解码：每个时间步取概率最大的类，去掉空白（下标 0）与连续重复。
     *
     * @param probs 识别输出，长度 steps × classes。
     * @param dict 字符表，类别 i 对应 dict[i - 1]。
     * @return 文字与平均置信度（保留字符的最大概率均值，没有字符时为 0）。
     */
    fun ctcDecode(
        probs: FloatArray,
        steps: Int,
        classes: Int,
        dict: List<String>,
    ): Pair<String, Float> {
        require(probs.size == steps * classes) { "识别输出尺寸不符" }
        val sb = StringBuilder()
        var last = -1
        var confSum = 0f
        var count = 0
        for (t in 0 until steps) {
            var best = 0
            var bestP = probs[t * classes]
            for (c in 1 until classes) {
                val p = probs[t * classes + c]
                if (p > bestP) {
                    bestP = p
                    best = c
                }
            }
            if (best != 0 && best != last && best - 1 < dict.size) {
                sb.append(dict[best - 1])
                confSum += bestP
                count++
            }
            last = best
        }
        return sb.toString() to if (count == 0) 0f else confSum / count
    }

    /** 平均亮度（0–255）。板书多为深色底浅色字，低于阈值时先反色，再交给在浅底深字上训练的模型。 */
    fun meanLuminance(argb: IntArray): Double {
        if (argb.isEmpty()) return 255.0
        var sum = 0.0
        for (c in argb) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            sum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        return sum / argb.size
    }

    /** 判断是否深色背景（黑板、绿板）。 */
    fun isDarkBackground(argb: IntArray): Boolean = meanLuminance(argb) < DARK_THRESHOLD

    private const val DARK_THRESHOLD = 110.0
}
