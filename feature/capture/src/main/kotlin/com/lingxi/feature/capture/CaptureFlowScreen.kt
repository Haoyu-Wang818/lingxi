package com.lingxi.feature.capture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 捕获页：拍照、相册导入、透视矫正与录音；将取代原型中的 CaptureScreen [FR-CAP-01]、[FR-CAP-04]。
 *
 * 骨架阶段只是占位页面，尚未接入导航；对应迭代中实现后由 `app` 的导航图引用。
 */
@Composable
fun CaptureFlowScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("CaptureFlowScreen（建设中）")
    }
}
