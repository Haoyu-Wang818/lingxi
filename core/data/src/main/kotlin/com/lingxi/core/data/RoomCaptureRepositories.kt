package com.lingxi.core.data

import androidx.room.withTransaction
import com.lingxi.core.data.db.CaptureEntity
import com.lingxi.core.data.db.LessonEntity
import com.lingxi.core.data.db.LingxiDatabase
import com.lingxi.core.data.db.NoteEntity
import com.lingxi.core.data.db.PendingTaskEntity
import com.lingxi.core.data.db.RecognizedTextEntity
import com.lingxi.core.model.repository.CaptureKind
import com.lingxi.core.model.repository.CaptureRecord
import com.lingxi.core.model.repository.CaptureRepository
import com.lingxi.core.model.repository.CaptureStatus
import com.lingxi.core.model.repository.LessonNote
import com.lingxi.core.model.repository.NoteRepository
import com.lingxi.core.model.repository.NoteStatus
import com.lingxi.core.model.repository.NoteSummary
import com.lingxi.core.model.repository.PendingTask
import com.lingxi.core.model.repository.PendingTaskRepository
import com.lingxi.core.model.repository.PendingTaskType
import com.lingxi.core.model.repository.RecognizedText
import com.lingxi.core.model.repository.TextLine
import org.json.JSONArray
import org.json.JSONObject

/** 同一课程 2 小时内的捕获归入同一课次（03-database.md 3.3 节）。 */
private const val LESSON_WINDOW_MILLIS = 2 * 60 * 60 * 1000L

/** [CaptureRepository] 的 Room 实现 [FR-CAP-06]、[FR-NOTE-01]。 */
class RoomCaptureRepository(
    private val db: LingxiDatabase,
    private val newId: () -> String = uuidGenerator,
) : CaptureRepository {
    private val captures = db.captureDao()
    private val lessons = db.lessonDao()

    override suspend fun lessonFor(
        courseId: String,
        nowMillis: Long,
    ): String =
        db.withTransaction {
            val latest = lessons.latestForCourse(courseId)
            if (latest != null && nowMillis - latest.startedAt in 0 until LESSON_WINDOW_MILLIS) {
                latest.id
            } else {
                newId().also { lessons.insert(LessonEntity(it, courseId, nowMillis)) }
            }
        }

    override suspend fun addCapture(record: CaptureRecord) =
        captures.insert(
            CaptureEntity(
                id = record.id,
                lessonId = record.lessonId,
                kind = record.kind.name,
                filePath = record.filePath,
                correctedPath = null,
                durationMs = null,
                pageCount = null,
                status = record.status.name,
                createdAt = record.createdAtMillis,
            ),
        )

    override suspend fun capture(id: String): CaptureRecord? = captures.byId(id)?.toModel()

    override suspend fun captures(lessonId: String): List<CaptureRecord> = captures.byLesson(lessonId).map { it.toModel() }

    override suspend fun setStatus(
        captureId: String,
        status: CaptureStatus,
    ) = captures.setStatus(captureId, status.name)

    override suspend fun saveRecognized(text: RecognizedText) =
        captures.insertText(
            RecognizedTextEntity(
                id = text.id,
                captureId = text.captureId,
                engine = text.engine,
                location = text.location,
                text = text.text,
                confidence = text.confidence,
                segmentsJson = linesToJson(text.lines),
            ),
        )

    override suspend fun recognized(captureId: String): List<RecognizedText> =
        captures.texts(captureId).map {
            RecognizedText(it.id, it.captureId, it.engine, it.location, it.text, it.confidence, linesFromJson(it.segmentsJson))
        }

    override suspend fun courseOfLesson(lessonId: String): String? = lessons.byId(lessonId)?.courseId

    private fun CaptureEntity.toModel() =
        CaptureRecord(id, lessonId, CaptureKind.valueOf(kind), filePath, CaptureStatus.valueOf(status), createdAt)
}

/** 识别文本的分段 JSON：`[{"text", "confidence", "box": [l, t, r, b]}]`，用于溯源时在原图上高亮。 */
internal fun linesToJson(lines: List<TextLine>): String =
    JSONArray(
        lines.map { JSONObject().put("text", it.text).put("confidence", it.confidence).put("box", JSONArray(it.box)) },
    ).toString()

internal fun linesFromJson(json: String?): List<TextLine> {
    if (json.isNullOrBlank()) return emptyList()
    val arr = JSONArray(json)
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val box = o.optJSONArray("box")?.let { b -> (0 until b.length()).map { b.getInt(it) } } ?: emptyList()
        TextLine(o.getString("text"), o.optDouble("confidence", 0.0), box)
    }
}

/** [NoteRepository] 的 Room 实现 [FR-NOTE-03]、[FR-NOTE-04]、[FR-NOTE-06]。 */
class RoomNoteRepository(
    private val db: LingxiDatabase,
) : NoteRepository {
    private val notes = db.noteDao()

    override suspend fun save(note: LessonNote) {
        db.withTransaction {
            // 一个课次一份笔记：沿用已有记录的 id，避免唯一约束冲突。
            val id = notes.byLesson(note.lessonId)?.id ?: note.id
            notes.upsert(
                NoteEntity(id, note.lessonId, note.title, note.markdown, note.originalMarkdown, note.status.name, note.promptVersion, note.updatedAtMillis),
            )
        }
    }

    override suspend fun byLesson(lessonId: String): LessonNote? =
        notes.byLesson(lessonId)?.let {
            LessonNote(it.id, it.lessonId, it.title, it.markdown, NoteStatus.valueOf(it.status), it.promptVersion, it.updatedAt, it.originalMarkdown)
        }

    override suspend fun edit(
        lessonId: String,
        markdown: String,
        nowMillis: Long,
    ) {
        db.withTransaction {
            val current = notes.byLesson(lessonId) ?: throw IllegalArgumentException("课次没有笔记：$lessonId")
            notes.upsert(current.copy(markdown = markdown, originalMarkdown = current.originalMarkdown ?: current.markdown, updatedAt = nowMillis))
        }
    }

    override suspend fun notes(): List<NoteSummary> =
        notes.summaries().map { NoteSummary(it.lessonId, it.courseName, it.title, NoteStatus.valueOf(it.status), it.updatedAt, it.captureCount) }
}

/** [PendingTaskRepository] 的 Room 实现 [FR-AI-05]。 */
class RoomPendingTaskRepository(
    private val db: LingxiDatabase,
    private val newId: () -> String = uuidGenerator,
) : PendingTaskRepository {
    private val dao = db.pendingTaskDao()

    override suspend fun enqueue(
        type: PendingTaskType,
        refId: String,
        payloadJson: String,
        nowMillis: Long,
    ): Boolean = dao.enqueue(PendingTaskEntity(newId(), type.name, payloadJson, refId, 0, nowMillis, null)) != -1L

    override suspend fun ready(nowMillis: Long): List<PendingTask> = dao.ready(nowMillis).map { it.toModel() }

    override suspend fun all(): List<PendingTask> = dao.all().map { it.toModel() }

    override suspend fun recordFailure(
        id: String,
        error: String,
        nowMillis: Long,
    ): Boolean =
        db.withTransaction {
            val task = dao.byId(id) ?: return@withTransaction false
            val attempts = task.attempts + 1
            if (attempts >= PendingTaskRepository.MAX_ATTEMPTS) {
                dao.delete(id)
                false
            } else {
                val next = nowMillis + PendingTaskRepository.backoffMillis(attempts)
                dao.update(task.copy(attempts = attempts, nextAttemptAt = next, lastError = error.take(MAX_ERROR_CHARS)))
                true
            }
        }

    override suspend fun remove(id: String) = dao.delete(id)

    private fun PendingTaskEntity.toModel() =
        PendingTask(id, PendingTaskType.valueOf(taskType), refId, payloadJson, attempts, nextAttemptAt, lastError)

    private companion object {
        const val MAX_ERROR_CHARS = 300
    }
}
