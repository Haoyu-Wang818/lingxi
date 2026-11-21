package com.lingxi.core.data

import androidx.room.withTransaction
import com.lingxi.core.data.db.AiExecutionEntity
import com.lingxi.core.data.db.KnowledgeEdgeEntity
import com.lingxi.core.data.db.KpSourceEntity
import com.lingxi.core.data.db.LessonEntity
import com.lingxi.core.data.db.LingxiDatabase
import com.lingxi.core.data.db.PushRecordEntity
import com.lingxi.core.data.db.ReviewLogEntity
import com.lingxi.core.model.Course
import com.lingxi.core.model.Extraction
import com.lingxi.core.model.KnowledgeEdge
import com.lingxi.core.model.KnowledgePoint
import com.lingxi.core.model.RelationType
import com.lingxi.core.model.ScheduleSlot
import com.lingxi.core.model.Timetable
import com.lingxi.core.model.repository.AiExecution
import com.lingxi.core.model.repository.AiExecutionRepository
import com.lingxi.core.model.repository.CardMemory
import com.lingxi.core.model.repository.CardRepository
import com.lingxi.core.model.repository.CourseRepository
import com.lingxi.core.model.repository.CourseUsage
import com.lingxi.core.model.repository.KnowledgeGraph
import com.lingxi.core.model.repository.KnowledgeRepository
import com.lingxi.core.model.repository.MergeResult
import com.lingxi.core.model.repository.PushOutcome
import com.lingxi.core.model.repository.PushRecord
import com.lingxi.core.model.repository.PushRepository
import com.lingxi.core.model.repository.ReviewLogEntry
import com.lingxi.core.model.repository.SlotSaveResult
import com.lingxi.core.model.repository.Streak
import com.lingxi.core.model.repository.StudyCard
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

private const val ACTIVE = "ACTIVE"

/** 默认 id 生成器：客户端 UUID，便于离线创建与导出合并（03-database.md 第 1 节）。 */
val uuidGenerator: () -> String = { UUID.randomUUID().toString() }

/** [CardRepository] 的 Room 实现 [FR-REV-03]、[FR-REV-04]、[FR-REV-05]。 */
class RoomCardRepository(
    private val db: LingxiDatabase,
) : CardRepository {
    private val cards = db.cardDao()
    private val logs = db.reviewLogDao()
    private val knowledge = db.knowledgeDao()

    override suspend fun dueQueue(
        untilMillis: Long,
        cap: Int,
    ): List<StudyCard> {
        val rows = cards.due(untilMillis, cap)
        if (rows.isEmpty()) return emptyList()
        val kpIds = rows.map { it.card.kpId }.toSet()
        val edges = knowledge.edgesTouching(kpIds)
        val allIds = kpIds + edges.flatMap { listOf(it.fromId, it.toId) }
        val points = knowledge.pointsByIds(allIds).associateBy { it.id }
        val courses = points.values.map { it.courseId }.toSet().associateWith { db.courseDao().byId(it)?.toModel() }
        return rows.mapNotNull { row ->
            val point = points[row.card.kpId] ?: return@mapNotNull null
            val course = courses[point.courseId] ?: return@mapNotNull null
            val title = { id: String -> points[id]?.takeIf { it.deletedAt == null }?.title }
            StudyCard(
                card = row.card.toModel(),
                course = course,
                point = point.toModel(),
                memory = row.memory?.toModel(),
                prerequisites =
                    edges
                        .filter { it.toId == point.id && it.relation == RelationType.PREREQUISITE.name }
                        .mapNotNull { title(it.fromId) },
                related =
                    edges
                        .filter { it.fromId == point.id || it.toId == point.id }
                        .filterNot { it.toId == point.id && it.relation == RelationType.PREREQUISITE.name }
                        .mapNotNull { title(if (it.fromId == point.id) it.toId else it.fromId) }
                        .distinct(),
            )
        }
    }

    override suspend fun saveReview(
        cardId: String,
        newMemory: CardMemory?,
        log: ReviewLogEntry,
    ): Long =
        db.withTransaction {
            log.messageId?.let { id -> logs.byMessageId(id)?.let { return@withTransaction it.id } }
            val prev = cards.memory(cardId)?.toModel()
            if (newMemory != null && log.counted) cards.upsertMemory(newMemory.toEntity(cardId))
            logs.insert(
                ReviewLogEntity(
                    cardId = cardId,
                    reviewedAt = log.reviewedAtMillis,
                    rating = log.rating.grade,
                    elapsedMs = log.elapsedMs,
                    device = log.device.name,
                    mode = log.mode.name,
                    counted = log.counted,
                    prevStateJson = memoryToJson(prev),
                    messageId = log.messageId,
                ),
            )
        }

    override suspend fun undo(logId: Long) {
        db.withTransaction {
            val log = logs.byId(logId) ?: return@withTransaction
            if (log.counted) memoryFromJson(log.prevStateJson)?.let { cards.upsertMemory(it.toEntity(log.cardId)) }
            logs.delete(logId)
        }
    }

    override suspend fun upsertCards(
        cards: List<com.lingxi.core.model.ReviewCard>,
        nowMillis: Long,
    ) {
        db.withTransaction {
            cards.forEach { card ->
                val createdAt = this.cards.byId(card.id)?.createdAt ?: nowMillis
                this.cards.upsert(card.toEntity(ACTIVE, createdAt, nowMillis))
                this.cards.insertMemoryIfAbsent(CardMemory.new(nowMillis).toEntity(card.id))
            }
        }
    }

    override suspend fun cardCount(): Int = cards.count()

    override suspend fun memory(cardId: String): CardMemory? = cards.memory(cardId)?.toModel()

    override suspend fun countedReviewsSince(sinceMillis: Long): Int = logs.countedSince(sinceMillis)

    override suspend fun streakDays(
        today: LocalDate,
        zone: ZoneId,
    ): Int {
        val since = today.minusDays(STREAK_LOOKBACK_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val days = logs.reviewTimesSince(since).map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.toSet()
        return Streak.count(days, today)
    }

    private companion object {
        const val STREAK_LOOKBACK_DAYS = 400L
    }
}

/** [CourseRepository] 的 Room 实现 [FR-COURSE-01]、[FR-COURSE-02]、[FR-COURSE-03]。 */
class RoomCourseRepository(
    private val db: LingxiDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : CourseRepository {
    private val courses = db.courseDao()
    private val slots = db.scheduleSlotDao()

    override suspend fun courses(): List<Course> = courses.all().map { it.toModel() }

    override suspend fun course(id: String): Course? = courses.byId(id)?.toModel()

    override suspend fun saveCourse(course: Course) = courses.upsert(course.toEntity(clock()))

    override suspend fun deleteCourse(id: String) = courses.delete(id)

    override suspend fun usage(courseId: String) = CourseUsage(courses.noteCount(courseId), courses.cardCount(courseId))

    override suspend fun slots(courseId: String?): List<ScheduleSlot> =
        (if (courseId == null) slots.all() else slots.byCourse(courseId)).map { it.toModel() }

    override suspend fun saveSlot(
        slot: ScheduleSlot,
        force: Boolean,
    ): SlotSaveResult =
        db.withTransaction {
            val conflicts = Timetable.conflicts(slots.all().map { it.toModel() }, slot)
            if (conflicts.isNotEmpty() && !force) return@withTransaction SlotSaveResult.Conflict(conflicts)
            slots.upsert(slot.toEntity())
            SlotSaveResult.Saved
        }

    override suspend fun deleteSlot(id: String) = slots.delete(id)

    override suspend fun currentCourse(
        now: LocalDateTime,
        semesterStart: LocalDate,
    ): Course? {
        val candidates = slots.activeAt(now.dayOfWeek.value, now.hour * 60 + now.minute).map { it.toModel() }
        val slot = Timetable.current(candidates, now, semesterStart) ?: return null
        return course(slot.courseId)
    }
}

/**
 * [KnowledgeRepository] 的 Room 实现 [FR-KG-02]、[FR-KG-03]。
 *
 * 合并去重本迭代只用归一化名称（去重第一道）；向量相似度（第二道）在 I4 接入端侧向量模型后加入。
 */
class RoomKnowledgeRepository(
    private val db: LingxiDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = uuidGenerator,
) : KnowledgeRepository {
    private val knowledge = db.knowledgeDao()

    override suspend fun mergeExtraction(
        lessonId: String,
        extraction: Extraction,
    ): MergeResult =
        db.withTransaction {
            val lesson = db.lessonDao().byId(lessonId) ?: throw IllegalArgumentException("课次不存在：$lessonId")
            val note = db.noteDao().byLesson(lessonId)
            val now = clock()
            val idMap = mutableMapOf<String, String>()
            val created = mutableSetOf<String>()
            extraction.points.forEach { p ->
                val norm = normalizeTitle(p.title)
                val match = knowledge.byNormTitle(lesson.courseId, norm)
                val id =
                    if (match != null) {
                        if (match.deletedAt != null) knowledge.upsertPoint(match.copy(deletedAt = null))
                        match.id
                    } else {
                        newId().also {
                            knowledge.upsertPoint(KnowledgePoint(it, p.title, p.summary, lesson.courseId).toEntity(now))
                            created += it
                        }
                    }
                idMap[p.id] = id
                if (note != null) {
                    val ref = JSONObject().put("sourceIds", JSONArray(p.sourceIds)).toString()
                    knowledge.insertSource(KpSourceEntity(id, note.id, ref))
                }
            }
            var rejected = 0
            extraction.edges.forEach { e ->
                val from = idMap[e.fromId]
                val to = idMap[e.toId]
                if (from == null || to == null || from == to || e.confidence !in 0.0..1.0) {
                    rejected++
                    return@forEach
                }
                val existing = knowledge.edge(from, to, e.relation.name)
                if (existing == null || existing.confidence < e.confidence) {
                    knowledge.upsertEdge(KnowledgeEdgeEntity(from, to, e.relation.name, e.confidence))
                }
            }
            MergeResult(idMap, created, rejected)
        }

    override suspend fun upsert(
        points: List<KnowledgePoint>,
        edges: List<KnowledgeEdge>,
    ) {
        db.withTransaction {
            val now = clock()
            points.forEach { knowledge.upsertPoint(it.toEntity(now)) }
            edges.forEach { knowledge.upsertEdge(KnowledgeEdgeEntity(it.fromId, it.toId, it.relation.name, 1.0)) }
        }
    }

    override suspend fun graph(courseId: String): KnowledgeGraph =
        KnowledgeGraph(
            points = knowledge.points(courseId).map { it.toModel() },
            edges = knowledge.edges(courseId).map { KnowledgeEdge(it.fromId, it.toId, RelationType.valueOf(it.relation)) },
        )

    override suspend fun prerequisites(kpId: String): List<KnowledgePoint> = knowledge.prerequisites(kpId).map { it.toModel() }

    override suspend fun createLesson(
        courseId: String,
        startedAtMillis: Long,
    ): String = newId().also { db.lessonDao().insert(LessonEntity(it, courseId, startedAtMillis)) }
}

/** [AiExecutionRepository] 的 Room 实现 [FR-AI-04]。 */
class RoomAiExecutionRepository(
    db: LingxiDatabase,
) : AiExecutionRepository {
    private val dao = db.aiExecutionDao()

    override suspend fun record(execution: AiExecution): Long =
        dao.insert(
            AiExecutionEntity(
                taskType = execution.taskType,
                location = execution.location,
                reason = execution.reason,
                startedAt = execution.startedAtMillis,
                durationMs = execution.durationMs,
                success = execution.success,
                errorCode = execution.errorCode,
                refId = execution.refId,
            ),
        )

    override suspend fun recent(limit: Int): List<AiExecution> =
        dao.recent(limit).map {
            AiExecution(it.taskType, it.location, it.reason, it.startedAt, it.durationMs, it.success, it.errorCode, it.refId, it.id)
        }
}

/** [PushRepository] 的 Room 实现 [FR-CTX-05]、[FR-CTX-08]。 */
class RoomPushRepository(
    db: LingxiDatabase,
) : PushRepository {
    private val dao = db.pushRecordDao()

    override suspend fun record(push: PushRecord) =
        dao.insert(
            PushRecordEntity(
                push.id,
                push.evaluationId,
                push.cardId,
                push.channel.name,
                push.sentAtMillis,
                push.outcome?.name,
                push.outcomeAtMillis,
            ),
        )

    override suspend fun setOutcome(
        pushId: String,
        outcome: PushOutcome,
        atMillis: Long,
    ) = dao.setOutcome(pushId, outcome.name, atMillis)

    override suspend fun countSince(sinceMillis: Long): Int = dao.countSince(sinceMillis)

    override suspend fun lastSentAt(): Long? = dao.lastSentAt()
}
