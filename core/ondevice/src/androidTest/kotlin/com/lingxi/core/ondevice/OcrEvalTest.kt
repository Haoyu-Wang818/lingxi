package com.lingxi.core.ondevice

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 在模拟器 / 真机上用真实模型识别测试板书，结果以 JSON 逐行写入 logcat（标签 OcrEval），
 * 由 scripts/ocr_eval.py 读取并计算字准确率 [FR-NOTE-01]。测试结束后测试包会被卸载，所以不写文件。
 *
 * 每张图识别 3 次：第 1 次含模型加载（冷启动），后两次为常规耗时。
 */
@RunWith(AndroidJUnit4::class)
class OcrEvalTest {
    private companion object {
        const val TAG = "OcrEval"
    }

    @Test
    fun recognizeFixtures(): Unit =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val testAssets = instrumentation.context.assets
            val engine = PaddleOcrEngine(instrumentation.targetContext)
            assertTrue("缺少模型文件，请先运行 scripts/fetch_models.sh", engine.modelsAvailable())
            val results = JSONArray()
            val names = testAssets.list("")!!.filter { it.startsWith("board-") && it.endsWith(".png") }.sorted()
            assertTrue("没有找到测试板书", names.isNotEmpty())
            for (name in names) {
                val file = File(instrumentation.targetContext.cacheDir, name)
                testAssets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
                val runs = (1..3).map { engine.recognize(file.path) }
                val page = runs.last()
                results.put(
                    JSONObject()
                        .put("name", name.removeSuffix(".png"))
                        .put("text", page.text)
                        .put("confidence", page.confidence)
                        .put("elapsed_ms", JSONArray(runs.map { it.elapsedMs }))
                        .put("lines", JSONArray(page.lines.map { JSONObject().put("text", it.text).put("box", JSONArray(it.box)) })),
                )
            }
            Log.i(TAG, "BEGIN")
            results.toString(2).lines().forEach { Log.i(TAG, "|$it") }
            Log.i(TAG, "END")
        }
}
