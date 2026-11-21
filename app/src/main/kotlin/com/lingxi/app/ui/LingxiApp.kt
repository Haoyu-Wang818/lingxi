package com.lingxi.app.ui

import android.net.Uri
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lingxi.app.di.AppContainer
import com.lingxi.app.di.SeedData
import com.lingxi.app.ui.screens.CaptureScreen
import com.lingxi.app.ui.screens.ContextLabScreen
import com.lingxi.app.ui.screens.GraphScreen
import com.lingxi.app.ui.screens.NoteScreen
import com.lingxi.app.ui.screens.WatchScreen
import com.lingxi.core.ai.AiTask
import com.lingxi.core.ai.AiTaskType
import com.lingxi.core.ai.CompletionRequest
import com.lingxi.core.ai.PrivacySettings
import com.lingxi.core.model.Course
import com.lingxi.core.model.repository.AiExecution
import com.lingxi.core.model.repository.LessonNote
import com.lingxi.core.model.repository.NoteSummary
import com.lingxi.core.model.repository.TextLine
import com.lingxi.feature.capture.CaptureFlowScreen
import com.lingxi.feature.capture.SourceViewerScreen
import com.lingxi.feature.course.CourseScreen
import com.lingxi.feature.settings.SettingsScreen
import kotlinx.coroutines.launch
import java.time.LocalDateTime

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
    const val CAMERA = "camera"
    const val NOTE = "note/{lessonId}"
    const val SOURCE = "source/{captureId}?box={box}&title={title}"

    fun note(lessonId: String) = "note/$lessonId"

    fun source(
        captureId: String,
        box: List<Int>,
        title: String,
    ) = "source/$captureId?box=${box.joinToString(",")}&title=${Uri.encode(title)}"
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
                    CaptureRoute(
                        container,
                        onCamera = { nav.navigate(Routes.CAMERA) },
                        onOpenNote = { nav.navigate(Routes.note(it)) },
                        onOpenCourses = { nav.navigate(Routes.COURSES) },
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.CAMERA) { CameraRoute(container, onDone = { nav.popBackStack() }) }
                composable(Routes.NOTE) { entry -> NoteRoute(container, entry.arguments?.getString("lessonId").orEmpty()) }
                composable(Routes.SOURCE) { entry ->
                    SourceRoute(
                        container,
                        captureId = entry.arguments?.getString("captureId").orEmpty(),
                        box = entry.arguments?.getString("box").orEmpty().split(",").mapNotNull { it.toIntOrNull() },
                        title = entry.arguments?.getString("title").orEmpty(),
                    )
                }
                composable(Routes.COURSES) { CoursesRoute(container) }
                composable(Routes.SETTINGS) { SettingsRoute(container) }
                composable(Tab.GRAPH.route) { GraphScreen() }
                composable(Tab.REVIEW.route) {
                    ReviewTab(container, onOpenSource = { id, box, title -> nav.navigate(Routes.source(id, box, title)) })
                }
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

@Composable
private fun CaptureRoute(
    container: AppContainer,
    onCamera: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenCourses: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val progress by container.captureRunner.progress.collectAsState()
    val semesterStart by container.settings.semesterStart.collectAsState(initial = null)
    var courses by remember { mutableStateOf<List<Course>>(emptyList()) }
    var notes by remember { mutableStateOf<List<NoteSummary>>(emptyList()) }
    val selected by container.selectedCourseId.collectAsState()
    LaunchedEffect(semesterStart, progress) {
        SeedData.seedIfNeeded(container)
        courses = container.courses.courses()
        notes = container.notes.notes()
        // 上课时间内自动预选正在上的课 [FR-COURSE-03]；否则沿用上次选择或第一门课。
        val current = semesterStart?.let { container.courses.currentCourse(LocalDateTime.now(), it) }
        if (current != null) {
            container.selectedCourseId.value = current.id
        } else if (selected == null || courses.none { it.id == selected }) {
            container.selectedCourseId.value = courses.firstOrNull()?.id
        }
    }
    CaptureScreen(
        courses = courses,
        selectedCourseId = selected,
        onSelectCourse = { container.selectedCourseId.value = it },
        progress = progress,
        notes = notes,
        onCamera = onCamera,
        onOpenNote = onOpenNote,
        onRetry = { container.captureRunner.retry(it) },
        onOpenCourses = onOpenCourses,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
private fun CameraRoute(
    container: AppContainer,
    onDone: () -> Unit,
) {
    val courseId by container.selectedCourseId.collectAsState()
    var courseName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(courseId) { courseName = courseId?.let { container.courses.course(it)?.name } }
    val id = courseId ?: return
    CaptureFlowScreen(
        outputDir = container.pipeline.captureDir(),
        onCaptured = { container.captureRunner.submitFile(it, id) },
        onImported = { uris ->
            uris.forEach { container.captureRunner.submitUri(it, id) }
            onDone()
        },
        onDone = onDone,
        courseLabel = courseName,
    )
}

@Composable
private fun NoteRoute(
    container: AppContainer,
    lessonId: String,
) {
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf<LessonNote?>(null) }
    var text by remember { mutableStateOf("") }
    var executions by remember { mutableStateOf<List<AiExecution>>(emptyList()) }
    var cardCount by remember { mutableStateOf(0) }
    val progress by container.captureRunner.progress.collectAsState()
    LaunchedEffect(lessonId, version, progress) {
        note = container.notes.byLesson(lessonId)
        val captures = container.captures.captures(lessonId)
        text = captures.flatMap { container.captures.recognized(it.id) }.joinToString("\n\n") { it.text }
        val ids = captures.map { it.id }.toSet() + lessonId
        executions = container.aiExecutions.recent(200).filter { it.refId in ids }.take(4)
        cardCount = container.cards.countFromCaptures(captures.map { it.id }.toSet())
    }
    NoteScreen(
        note = note,
        recognizedText = text,
        executions = executions,
        cardCount = cardCount,
        onSave = { md ->
            scope.launch {
                container.notes.edit(lessonId, md, System.currentTimeMillis())
                version++
            }
        },
        onRetry = { container.captureRunner.retry(lessonId) },
    )
}

@Composable
private fun SourceRoute(
    container: AppContainer,
    captureId: String,
    box: List<Int>,
    title: String,
) {
    var file by remember { mutableStateOf<java.io.File?>(null) }
    var lines by remember { mutableStateOf<List<TextLine>>(emptyList()) }
    LaunchedEffect(captureId) {
        file = container.captures.capture(captureId)?.let { container.pipeline.absolute(it.filePath) }
        lines = container.captures.recognized(captureId).flatMap { it.lines }
    }
    SourceViewerScreen(title = title, imageFile = file, lines = lines, highlight = box)
}
