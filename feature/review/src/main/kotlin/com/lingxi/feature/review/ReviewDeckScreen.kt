package com.lingxi.feature.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 卡片复习页：一屏一卡、翻面、三键与手势、撤销、组末小结；将取代原型中的 ReviewScreen [FR-REV-04]、[FR-REV-07]、[FR-REV-13]、[FR-REV-14]。
 *
 * 骨架阶段只是占位页面，尚未接入导航；对应迭代中实现后由 `app` 的导航图引用。
 */
@Composable
fun ReviewDeckScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("ReviewDeckScreen（建设中）")
    }
}
