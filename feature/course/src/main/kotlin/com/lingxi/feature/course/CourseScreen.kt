package com.lingxi.feature.course

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lingxi.core.model.Course
import com.lingxi.core.model.ScheduleSlot
import com.lingxi.core.model.Timetable
import com.lingxi.core.model.repository.CourseRepository
import com.lingxi.core.model.repository.CourseUsage
import com.lingxi.core.model.repository.SlotSaveResult
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** 可选的课程颜色。 */
private val COURSE_COLORS =
    listOf(0xFF3F51B5, 0xFF009688, 0xFFE91E63, 0xFFFF9800, 0xFF795548, 0xFF607D8B).map { it.toInt() }

/**
 * 课程与课表管理页 [FR-COURSE-01]、[FR-COURSE-02]、[FR-COURSE-03]。
 *
 * 只做界面：数据读写都通过 [CourseRepository] 接口，冲突检测与「正在上的课」规则在 `core:model` 的 [Timetable]。
 *
 * @param semesterStart 学期第一天，用于换算教学周。
 * @param now 当前时间（可注入，便于演示与测试）。
 */
@Composable
fun CourseScreen(
    repository: CourseRepository,
    semesterStart: LocalDate,
    onSemesterStartChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    now: () -> LocalDateTime = LocalDateTime::now,
) {
    val scope = rememberCoroutineScope()
    var courses by remember { mutableStateOf<List<Course>>(emptyList()) }
    var slots by remember { mutableStateOf<List<ScheduleSlot>>(emptyList()) }
    var current by remember { mutableStateOf<Course?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Course?>(null) }
    var addingSlotFor by remember { mutableStateOf<Course?>(null) }
    var deleting by remember { mutableStateOf<Pair<Course, CourseUsage>?>(null) }
    var editingSemester by remember { mutableStateOf(false) }

    LaunchedEffect(version, semesterStart) {
        courses = repository.courses()
        slots = repository.slots()
        current = repository.currentCourse(now(), semesterStart)
    }
    val reload = { version++ }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("课程与课表", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = { editing = Course(UUID.randomUUID().toString(), "新课程") }) { Text("＋ 课程") }
        }
        val week = Timetable.teachingWeek(semesterStart, now().toLocalDate())
        Text(
            "学期从 $semesterStart 开始 · 今天第 $week 周  ✎",
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.clickable { editingSemester = true },
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Text(
                text = current?.let { "正在上：${it.name}（捕获与推送会据此判断「上课中」）" } ?: "现在没有课",
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
            )
        }
        if (courses.isEmpty()) {
            Text("还没有课程，点右上角添加。", color = MaterialTheme.colorScheme.outline)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(courses, key = { it.id }) { course ->
                CourseCard(
                    course = course,
                    slots = slots.filter { it.courseId == course.id },
                    onEdit = { editing = course },
                    onAddSlot = { addingSlotFor = course },
                    onDeleteSlot = { id ->
                        scope.launch {
                            repository.deleteSlot(id)
                            reload()
                        }
                    },
                    onDelete = { scope.launch { deleting = course to repository.usage(course.id) } },
                )
            }
        }
    }

    editing?.let { course ->
        CourseDialog(
            initial = course,
            onDismiss = { editing = null },
            onSave = { saved ->
                scope.launch {
                    repository.saveCourse(saved)
                    editing = null
                    reload()
                }
            },
        )
    }
    addingSlotFor?.let { course ->
        SlotDialog(
            course = course,
            save = { slot, force -> repository.saveSlot(slot, force) },
            onDone = {
                addingSlotFor = null
                reload()
            },
            onDismiss = { addingSlotFor = null },
        )
    }
    deleting?.let { (course, usage) ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${course.name}」？") },
            text = { Text("该课程下的 ${usage.notes} 份笔记、${usage.cards} 张卡片及复习记录会一并删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.deleteCourse(course.id)
                        deleting = null
                        reload()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    if (editingSemester) {
        var text by remember { mutableStateOf(semesterStart.toString()) }
        val parsed = runCatching { LocalDate.parse(text.trim()) }.getOrNull()
        AlertDialog(
            onDismissRequest = { editingSemester = false },
            title = { Text("学期第一天") },
            text = {
                OutlinedTextField(text, { text = it }, label = { Text("格式 2026-09-07") }, isError = parsed == null, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = parsed != null, onClick = {
                    parsed?.let(onSemesterStartChange)
                    editingSemester = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingSemester = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun CourseCard(
    course: Course,
    slots: List<ScheduleSlot>,
    onEdit: () -> Unit,
    onAddSlot: () -> Unit,
    onDeleteSlot: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(Color(course.color), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(course.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                course.teacher?.let { Text("  ·  $it", color = MaterialTheme.colorScheme.outline) }
            }
            if (slots.isEmpty()) Text("尚未录入上课时间", color = MaterialTheme.colorScheme.outline)
            slots.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(slotLabel(s), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onDeleteSlot(s.id) }) { Text("移除") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAddSlot) { Text("＋ 上课时间") }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** 「周三 08:00–09:40 · 第1–16周 · 教一101」。 */
fun slotLabel(s: ScheduleSlot): String =
    buildString {
        append(Timetable.weekdayName(s.weekday)).append(' ')
        append(Timetable.formatMinute(s.startMin)).append('–').append(Timetable.formatMinute(s.endMin))
        append(" · 第").append(s.weekFrom).append('–').append(s.weekTo).append("周")
        s.location?.let { append(" · ").append(it) }
    }

@Composable
private fun CourseDialog(
    initial: Course,
    onDismiss: () -> Unit,
    onSave: (Course) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var teacher by remember { mutableStateOf(initial.teacher.orEmpty()) }
    var color by remember { mutableIntStateOf(initial.color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("课程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, isError = name.isBlank())
                OutlinedTextField(teacher, { teacher = it }, label = { Text("教师（可选）") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    COURSE_COLORS.forEach { c ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(Color(c), CircleShape)
                                .then(if (c == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = c },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(initial.copy(name = name.trim(), teacher = teacher.trim().ifEmpty { null }, color = color))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 解析「8:00」「08:05」为当日分钟数，非法返回 null。 */
fun parseMinute(text: String): Int? {
    val m = Regex("""^\s*(\d{1,2})[:：](\d{2})\s*$""").find(text) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toInt()
    return if (h in 0..23 && min in 0..59) h * 60 + min else null
}

@Composable
private fun SlotDialog(
    course: Course,
    save: suspend (ScheduleSlot, Boolean) -> SlotSaveResult,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var weekday by remember { mutableIntStateOf(1) }
    var start by remember { mutableStateOf("08:00") }
    var end by remember { mutableStateOf("09:40") }
    var weekFrom by remember { mutableStateOf("1") }
    var weekTo by remember { mutableStateOf("16") }
    var location by remember { mutableStateOf("") }
    var conflict by remember { mutableStateOf<List<ScheduleSlot>?>(null) }

    val slot =
        runCatching {
            ScheduleSlot(
                id = UUID.randomUUID().toString(),
                courseId = course.id,
                weekday = weekday,
                startMin = parseMinute(start)!!,
                endMin = parseMinute(end)!!,
                weekFrom = weekFrom.toInt(),
                weekTo = weekTo.toInt(),
                location = location.trim().ifEmpty { null },
            )
        }.getOrNull()
    val id = remember { UUID.randomUUID().toString() }

    fun submit(force: Boolean) {
        val s = slot?.copy(id = id) ?: return
        scope.launch {
            when (val r = save(s, force)) {
                SlotSaveResult.Saved -> onDone()
                is SlotSaveResult.Conflict -> conflict = r.with
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${course.name} · 上课时间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    (1..7).forEach { d ->
                        FilterChip(
                            selected = weekday == d,
                            onClick = {
                                weekday = d
                                conflict = null
                            },
                            label = { Text("一二三四五六日"[d - 1].toString()) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it }, Modifier.weight(1f), label = { Text("开始") }, singleLine = true)
                    OutlinedTextField(end, { end = it }, Modifier.weight(1f), label = { Text("结束") }, singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(weekFrom, { weekFrom = it }, Modifier.weight(1f), label = { Text("起始周") }, singleLine = true)
                    OutlinedTextField(weekTo, { weekTo = it }, Modifier.weight(1f), label = { Text("结束周") }, singleLine = true)
                }
                OutlinedTextField(location, { location = it }, label = { Text("教室（可选）") }, singleLine = true)
                if (slot == null) Text("时间或周次不合法", color = MaterialTheme.colorScheme.error)
                conflict?.let { list ->
                    Text(
                        "与已有时段冲突：" + list.joinToString("；") { slotLabel(it) },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (conflict == null) {
                TextButton(enabled = slot != null, onClick = { submit(force = false) }) { Text("保存") }
            } else {
                TextButton(onClick = { submit(force = true) }) { Text("仍然保存") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
