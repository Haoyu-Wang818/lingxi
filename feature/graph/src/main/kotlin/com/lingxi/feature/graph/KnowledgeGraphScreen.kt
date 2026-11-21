package com.lingxi.feature.graph

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 知识图谱：可视化、节点详情与记忆着色；将取代原型中的 GraphScreen [FR-KG-04]、[FR-KG-05]。
 *
 * 骨架阶段只是占位页面，尚未接入导航；对应迭代中实现后由 `app` 的导航图引用。
 */
@Composable
fun KnowledgeGraphScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("KnowledgeGraphScreen（建设中）")
    }
}
