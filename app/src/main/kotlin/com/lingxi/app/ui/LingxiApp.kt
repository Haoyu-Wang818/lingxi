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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lingxi.app.ui.screens.CaptureScreen
import com.lingxi.app.ui.screens.ContextLabScreen
import com.lingxi.app.ui.screens.GraphScreen
import com.lingxi.app.ui.screens.ReviewScreen
import com.lingxi.app.ui.screens.WatchScreen

/** 底部导航的五个页面。 */
private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    CAPTURE("capture", "捕获", Icons.Outlined.CameraAlt),
    GRAPH("graph", "图谱", Icons.Outlined.AccountTree),
    REVIEW("review", "复习", Icons.Outlined.Style),
    CONTEXT("context", "情境", Icons.Outlined.Sensors),
    WATCH("watch", "手表", Icons.Outlined.Watch),
}

/** 应用根组件：底部导航 + 页面切换。 */
@Composable
fun LingxiApp() {
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
                composable(Tab.CAPTURE.route) { CaptureScreen() }
                composable(Tab.GRAPH.route) { GraphScreen() }
                composable(Tab.REVIEW.route) { ReviewScreen() }
                composable(Tab.CONTEXT.route) { ContextLabScreen() }
                composable(Tab.WATCH.route) { WatchScreen() }
            }
        }
    }
}
