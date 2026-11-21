package com.lingxi.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lingxi.app.di.AppContainer
import com.lingxi.app.ui.screens.CaptureScreen
import com.lingxi.app.ui.screens.ContextLabScreen
import com.lingxi.app.ui.screens.GraphScreen
import com.lingxi.app.ui.screens.WatchScreen
import com.lingxi.core.ai.AiTask
import com.lingxi.core.ai.AiTaskType
import com.lingxi.core.ai.CompletionRequest
import com.lingxi.core.ai.PrivacySettings
import com.lingxi.feature.course.CourseScreen
import com.lingxi.feature.settings.SettingsScreen
import kotlinx.coroutines.launch

/** 底部导航的五个页面。 */
private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    CAPTURE("capture", "捕获", Icons.Outlined.CameraAlt),
    GRAPH("graph", "图谱", Icons.Outlined.AccountTree),
    REVIEW("review", "复习", Icons.Outlined.Style),
    CONTEXT("context", "情境", Icons.Outlined.Sensors),
    WATCH("watch", "手表", Icons.Outlined.Watch),
}

/** 不在底部导航中的二级页面。 */
private object Routes {
    const val COURSES = "courses"
    const val SETTINGS = "settings"
}

/** 应用根组件：底部导航 + 页面切换。界面所需的仓库与服务都来自 [container]。 */
@Composable
fun LingxiApp(container: AppContainer) {
    MaterialTheme {
        val nav = rememberNavController()
        val backStack by nav.currentBackStackEntryAsState()
        val current = backStack?.destination?.route

        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = Tab.CAPTURE.route, modifier = Modifier.padding(padding)) {
                composable(Tab.CAPTURE.route) {
                    CaptureScreen(
                        onOpenCourses = { nav.navigate(Routes.COURSES) },
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.COURSES) { CoursesRoute(container) }
                composable(Routes.SETTINGS) { SettingsRoute(container) }
                composable(Tab.GRAPH.route) { GraphScreen() }
                composable(Tab.REVIEW.route) { ReviewTab(container) }
                composable(Tab.CONTEXT.route) { ContextLabScreen() }
                composable(Tab.WATCH.route) { WatchScreen() }
            }
        }
    }
}

@Composable
private fun CoursesRoute(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val semesterStart by container.settings.semesterStart.collectAsState(initial = null)
    semesterStart?.let { start ->
        CourseScreen(
            repository = container.courses,
            semesterStart = start,
            onSemesterStartChange = { scope.launch { container.settings.setSemesterStart(it) } },
        )
    }
}

@Composable
private fun SettingsRoute(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val privacy by container.settings.privacy.collectAsState(initial = PrivacySettings())
    SettingsScreen(
        privacy = privacy,
        onPrivacyChange = { scope.launch { container.settings.setPrivacy(it) } },
        checkGateway = {
            container.gateway().health().fold(
                onSuccess = { "网关 ${it.version} 在线 · " + if (it.llmConfigured) "大模型 ${it.model} 已配置" else "未配置大模型 API Key" },
                onFailure = { "无法连接网关：${it.message}" },
            )
        },
        askCloud = { question ->
            val task = AiTask(AiTaskType.CHAT, question.length, containsPersonalData = true)
            val result = container.router.run(task, CompletionRequest("你是大学课程助教，回答简洁准确。", question, 300))
            result.result.fold(
                onSuccess = { "【${it.modelName}】${it.text}" },
                onFailure = { "未完成：${it.message}" },
            )
        },
        recentExecutions = { container.aiExecutions.recent(30) },
    )
}
