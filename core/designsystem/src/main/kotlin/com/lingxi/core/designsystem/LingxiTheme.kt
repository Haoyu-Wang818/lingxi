package com.lingxi.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 灵犀学伴的统一主题（NFR-UX）。
 *
 * 骨架阶段只区分明暗两套 Material 3 配色；05-ui.md 的视觉规范（配色、字号、卡片组件、翻面动画）
 * 在 I3 迭代落地，届时各 feature 模块改为依赖本模块。
 */
@Composable
fun LingxiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
