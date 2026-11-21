package com.lingxi.core.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PP-OCR 前后处理（纯 Kotlin）[FR-NOTE-01]。 */
class OcrPostProcessTest {
    /** [FR-NOTE-01] 检测输入：长边不超过 960，宽高取 32 的倍数，小图不放大。 */
    @Test
    fun detInputSizeIsMultipleOf32() {
        assertEquals(960 to 544, OcrPostProcess.detInputSize(1920, 1080))
        assertEquals(544 to 960, OcrPostProcess.detInputSize(1080, 1920))
        assertEquals(320 to 224, OcrPostProcess.detInputSize(320, 230))
        assertEquals(32 to 32, OcrPostProcess.detInputSize(5, 5))
    }

    private fun map(
        w: Int,
        h: Int,
        vararg rects: IntArray,
        value: Float = 0.9f,
    ): FloatArray {
        val m = FloatArray(w * h)
        for (r in rects) {
            for (y in r[1] until r[3]) for (x in r[0] until r[2]) m[y * w + x] = value
        }
        return m
    }

    /** [FR-NOTE-01] 两行文字得到两个框，按 unclip 外扩并换算回原图坐标，按阅读顺序排列。 */
    @Test
    fun detectBoxesFindsLinesInReadingOrder() {
        val prob = map(100, 40, intArrayOf(10, 25, 90, 31), intArrayOf(10, 5, 60, 11))
        val boxes = OcrPostProcess.detectBoxes(prob, 100, 40, imageW = 200, imageH = 80)
        assertEquals(2, boxes.size)
        // 第一个是上面那行；宽 50、高 6 → 外扩 d = 50*6*1.6/(2*56) ≈ 4.3 像素（概率图坐标），原图放大 2 倍。
        val top = boxes[0]
        assertEquals(1, top.top) // (5 − 4.3) × 2
        assertTrue(top.left < 20 && top.right > 120)
        assertTrue(boxes[1].top > top.bottom - 20)
        assertEquals(listOf(top.left, top.top, top.right, top.bottom), top.toList())
    }

    /** 平均概率不足或太小的区域被丢弃；框不会超出图片边界。 */
    @Test
    fun weakTinyAndEdgeRegionsHandled() {
        val weak = map(50, 20, intArrayOf(5, 5, 40, 12), value = 0.4f)
        assertTrue(OcrPostProcess.detectBoxes(weak, 50, 20, 50, 20).isEmpty())
        val tiny = map(50, 20, intArrayOf(5, 5, 7, 7))
        assertTrue(OcrPostProcess.detectBoxes(tiny, 50, 20, 50, 20).isEmpty())
        val edge = map(50, 20, intArrayOf(0, 0, 50, 8))
        val b = OcrPostProcess.detectBoxes(edge, 50, 20, 50, 20).single()
        assertEquals(0, b.left)
        assertEquals(0, b.top)
        assertEquals(50, b.right)
    }

    /** 同一行的多个框从左到右排列，不同行从上到下。 */
    @Test
    fun readingOrderGroupsRows() {
        val a = Box(200, 10, 300, 40)
        val b = Box(10, 14, 150, 42)
        val c = Box(10, 60, 100, 90)
        assertEquals(listOf(b, a, c), OcrPostProcess.sortReadingOrder(listOf(c, a, b)))
    }

    /** [FR-NOTE-01] 识别输入宽度按比例缩放并限制范围。 */
    @Test
    fun recWidthKeepsAspectRatio() {
        assertEquals(480, OcrPostProcess.recInputWidth(400, 40))
        assertEquals(16, OcrPostProcess.recInputWidth(2, 40))
        assertEquals(1600, OcrPostProcess.recInputWidth(5000, 20))
    }

    /** [FR-NOTE-01] CTC 贪心解码：去空白、去连续重复，置信度为保留字符概率均值。 */
    @Test
    fun ctcDecodeRemovesBlanksAndRepeats() {
        val dict = listOf("泰", "勒", " ")
        // 类别：0 空白，1 泰，2 勒，3 空格。时间步：泰 泰 空白 勒 勒 空白 勒。
        val steps = listOf(1 to 0.9f, 1 to 0.8f, 0 to 0.99f, 2 to 0.7f, 2 to 0.6f, 0 to 0.9f, 2 to 0.5f)
        val probs = FloatArray(steps.size * 4)
        steps.forEachIndexed { t, (c, p) ->
            for (k in 0 until 4) probs[t * 4 + k] = (1 - p) / 3
            probs[t * 4 + c] = p
        }
        val (text, conf) = OcrPostProcess.ctcDecode(probs, steps.size, 4, dict)
        assertEquals("泰勒勒", text)
        assertEquals((0.9f + 0.7f + 0.5f) / 3, conf, 1e-5f)
        assertEquals("" to 0f, OcrPostProcess.ctcDecode(FloatArray(4) { if (it == 0) 1f else 0f }, 1, 4, dict))
    }

    /** 深色板书（黑板、绿板）判为深色背景，白纸不是。 */
    @Test
    fun darkBackgroundDetection() {
        val chalkboard = IntArray(100) { if (it % 10 == 0) 0xFFFFFFFF.toInt() else 0xFF1F3A2E.toInt() }
        val paper = IntArray(100) { if (it % 10 == 0) 0xFF000000.toInt() else 0xFFF5F5F5.toInt() }
        assertTrue(OcrPostProcess.isDarkBackground(chalkboard))
        assertFalse(OcrPostProcess.isDarkBackground(paper))
        assertEquals(255.0, OcrPostProcess.meanLuminance(IntArray(0)), 1e-9)
        assertEquals(OcrPage(emptyList(), 1, 1, 0, "x").confidence, 0.0, 1e-9)
    }
}
