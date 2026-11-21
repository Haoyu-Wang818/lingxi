package com.lingxi.feature.note

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 笔记列表与详情：编辑与溯源到原始板书或录音 [FR-NOTE-03]、[FR-NOTE-05]。
 *
 * 骨架阶段只是占位页面，尚未接入导航；对应迭代中实现后由 `app` 的导航图引用。
 */
@Composable
fun NoteListScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("NoteListScreen（建设中）")
    }
}
