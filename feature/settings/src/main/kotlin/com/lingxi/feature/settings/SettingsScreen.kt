package com.lingxi.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 设置：隐私、推送、数据导出、首次引导与手表配对 [FR-SET-01]、[FR-SYNC-01]。
 *
 * 骨架阶段只是占位页面，尚未接入导航；对应迭代中实现后由 `app` 的导航图引用。
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("SettingsScreen（建设中）")
    }
}
