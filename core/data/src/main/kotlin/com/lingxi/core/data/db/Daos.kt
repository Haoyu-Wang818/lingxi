package com.lingxi.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface CourseDao {
    @Query("SELECT * FROM course ORDER BY created_at, name")
    suspend fun all(): List<CourseEntity>

    @Query("SELECT * FROM course WHERE id = :id")
    suspend fun byId(id: String): CourseEntity?

    @Upsert
    suspend fun upsert(course: CourseEntity)

    @Query("DELETE FROM course WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM note n JOIN lesson l ON n.lesson_id = l.id WHERE l.course_id = :courseId")
    suspend fun noteCount(courseId: String): Int

    @Query(
        "SELECT COUNT(*) FROM card c JOIN knowledge_point k ON c.kp_id = k.id " +
            "WHERE k.course_id = :courseId AND c.status != 'DELETED'",
    )
    suspend fun cardCount(courseId: String): Int
}

@Dao
interface ScheduleSlotDao {
    @Query("SELECT * FROM schedule_slot ORDER BY weekday, start_min")
    suspend fun all(): List<ScheduleSlotEntity>

    @Query("SELECT * FROM schedule_slot WHERE course_id = :courseId ORDER BY weekday, start_min")
    suspend fun byCourse(courseId: String): List<ScheduleSlotEntity>

    /** 情境引擎与捕获页「正在上的课」候选，走 idx_slot_weekday。 */
    @Query("SELECT * FROM schedule_slot WHERE weekday = :weekday AND start_min <= :minute AND end_min > :minute")
    suspend fun activeAt(
        weekday: Int,
        minute: Int,
    ): List<ScheduleSlotEntity>

    @Upsert
    suspend fun upsert(slot: ScheduleSlotEntity)

    @Query("DELETE FROM schedule_slot WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface LessonDao {
    @Insert
    suspend fun insert(lesson: LessonEntity)

    @Query("SELECT * FROM lesson WHERE course_id = :courseId ORDER BY started_at DESC LIMIT 1")
    suspend fun latestForCourse(courseId: String): LessonEntity?

    @Query("SELECT * FROM lesson WHERE id = :id")
    suspend fun byId(id: String): LessonEntity?
}

@Dao
interface CaptureDao {
    @Insert
    suspend fun insert(capture: CaptureEntity)

    @Query("SELECT * FROM capture WHERE lesson_id = :lessonId ORDER BY created_at")
    suspend fun byLesson(lessonId: String): List<CaptureEntity>

    @Query("SELECT * FROM capture WHERE id = :id")
    suspend fun byId(id: String): CaptureEntity?

    @Query("SELECT * FROM capture WHERE id IN (:ids)")
    suspend fun byIds(ids: Collection<String>): List<CaptureEntity>

    @Query("UPDATE capture SET status = :status WHERE id = :id")
    suspend fun setStatus(
        id: String,
        status: String,
    )

    @Insert
    suspend fun insertText(text: RecognizedTextEntity)

    @Query("SELECT * FROM recognized_text WHERE capture_id = :captureId")
    suspend fun texts(captureId: String): List<RecognizedTextEntity>
}

/** 笔记列表一行。 */
data class NoteSummaryRow(
    @androidx.room.ColumnInfo(name = "lesson_id") val lessonId: String,
    @androidx.room.ColumnInfo(name = "course_name") val courseName: String,
    val title: String,
    val status: String,
    @androidx.room.ColumnInfo(name = "updated_at") val updatedAt: Long,
    @androidx.room.ColumnInfo(name = "capture_count") val captureCount: Int,
)

@Dao
interface NoteDao {
    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Query("SELECT * FROM note WHERE lesson_id = :lessonId")
    suspend fun byLesson(lessonId: String): NoteEntity?

    @Query(
        "SELECT n.lesson_id, c.name AS course_name, n.title, n.status, n.updated_at, " +
            "(SELECT COUNT(*) FROM capture p WHERE p.lesson_id = n.lesson_id) AS capture_count " +
            "FROM note n JOIN lesson l ON n.lesson_id = l.id JOIN course c ON l.course_id = c.id " +
            "ORDER BY n.updated_at DESC",
    )
    suspend fun summaries(): List<NoteSummaryRow>
}

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM knowledge_point WHERE course_id = :courseId AND deleted_at IS NULL ORDER BY created_at")
    suspend fun points(courseId: String): List<KnowledgePointEntity>

    @Query("SELECT * FROM knowledge_point WHERE id IN (:ids)")
    suspend fun pointsByIds(ids: Collection<String>): List<KnowledgePointEntity>

    @Query("SELECT * FROM knowledge_point WHERE course_id = :courseId AND norm_title = :normTitle")
    suspend fun byNormTitle(
        courseId: String,
        normTitle: String,
    ): KnowledgePointEntity?

    @Upsert
    suspend fun upsertPoint(point: KnowledgePointEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSource(source: KpSourceEntity)

    /** 同一 (from, to, relation) 冲突时由调用方决定保留置信度高者。 */
    @Upsert
    suspend fun upsertEdge(edge: KnowledgeEdgeEntity)

    @Query("SELECT * FROM knowledge_edge WHERE from_id = :fromId AND to_id = :toId AND relation = :relation")
    suspend fun edge(
        fromId: String,
        toId: String,
        relation: String,
    ): KnowledgeEdgeEntity?

    @Query(
        "SELECT e.* FROM knowledge_edge e JOIN knowledge_point f ON e.from_id = f.id " +
            "JOIN knowledge_point t ON e.to_id = t.id " +
            "WHERE f.course_id = :courseId AND f.deleted_at IS NULL AND t.deleted_at IS NULL",
    )
    suspend fun edges(courseId: String): List<KnowledgeEdgeEntity>

    @Query("SELECT * FROM knowledge_edge WHERE from_id IN (:ids) OR to_id IN (:ids)")
    suspend fun edgesTouching(ids: Collection<String>): List<KnowledgeEdgeEntity>

    @Query(
        "SELECT k.* FROM knowledge_point k JOIN knowledge_edge e ON e.from_id = k.id " +
            "WHERE e.to_id = :kpId AND e.relation = 'PREREQUISITE' AND k.deleted_at IS NULL",
    )
    suspend fun prerequisites(kpId: String): List<KnowledgePointEntity>
}

/** 出卡队列一行：卡片 + 记忆状态。 */
data class CardWithMemory(
    @Embedded val card: CardEntity,
    @Embedded(prefix = "m_") val memory: MemoryStateEntity?,
)

@Dao
interface CardDao {
    /** 今日队列：按到期时间升序，走 idx_memory_due（03-database.md 第 4 节）。 */
    @Query(
        "SELECT c.*, m.card_id AS m_card_id, m.state AS m_state, m.stability AS m_stability, " +
            "m.difficulty AS m_difficulty, m.due_at AS m_due_at, m.last_review_at AS m_last_review_at, " +
            "m.reps AS m_reps, m.lapses AS m_lapses " +
            "FROM card c JOIN memory_state m ON m.card_id = c.id " +
            "WHERE c.status = 'ACTIVE' AND m.due_at <= :until ORDER BY m.due_at, c.created_at LIMIT :cap",
    )
    suspend fun due(
        until: Long,
        cap: Int,
    ): List<CardWithMemory>

    @Upsert
    suspend fun upsert(card: CardEntity)

    @Query("SELECT COUNT(*) FROM card WHERE status != 'DELETED'")
    suspend fun count(): Int

    @Query("SELECT source_ref_json FROM card WHERE status != 'DELETED' AND source_ref_json IS NOT NULL")
    suspend fun sourceRefs(): List<String>

    @Query("SELECT * FROM card WHERE id = :id")
    suspend fun byId(id: String): CardEntity?

    @Query("SELECT * FROM memory_state WHERE card_id = :cardId")
    suspend fun memory(cardId: String): MemoryStateEntity?

    @Upsert
    suspend fun upsertMemory(memory: MemoryStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemoryIfAbsent(memory: MemoryStateEntity)

    @Insert
    suspend fun insertFeedback(feedback: CardFeedbackEntity)
}

@Dao
interface ReviewLogDao {
    @Insert
    suspend fun insert(log: ReviewLogEntity): Long

    @Query("SELECT * FROM review_log WHERE id = :id")
    suspend fun byId(id: Long): ReviewLogEntity?

    @Query("SELECT * FROM review_log WHERE message_id = :messageId")
    suspend fun byMessageId(messageId: String): ReviewLogEntity?

    @Query("DELETE FROM review_log WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM review_log WHERE counted = 1 AND reviewed_at >= :since")
    suspend fun countedSince(since: Long): Int

    /** 连续天数与热力图的原始数据：近一年的作答时间。 */
    @Query("SELECT reviewed_at FROM review_log WHERE reviewed_at >= :since")
    suspend fun reviewTimesSince(since: Long): List<Long>
}

@Dao
interface ContextEvaluationDao {
    @Insert
    suspend fun insert(evaluation: ContextEvaluationEntity): Long

    @Query("SELECT * FROM context_evaluation ORDER BY evaluated_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ContextEvaluationEntity>
}

@Dao
interface PushRecordDao {
    @Insert
    suspend fun insert(push: PushRecordEntity)

    @Query("UPDATE push_record SET outcome = :outcome, outcome_at = :at WHERE id = :id")
    suspend fun setOutcome(
        id: String,
        outcome: String,
        at: Long,
    )

    @Query("SELECT COUNT(*) FROM push_record WHERE sent_at >= :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT MAX(sent_at) FROM push_record")
    suspend fun lastSentAt(): Long?
}

@Dao
interface AiExecutionDao {
    @Insert
    suspend fun insert(execution: AiExecutionEntity): Long

    @Query("SELECT * FROM ai_execution ORDER BY started_at DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AiExecutionEntity>
}

@Dao
interface PendingTaskDao {
    /** (task_type, ref_id) 唯一：重复入队被忽略。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(task: PendingTaskEntity): Long

    @Query("SELECT * FROM pending_task WHERE next_attempt_at <= :now ORDER BY next_attempt_at")
    suspend fun ready(now: Long): List<PendingTaskEntity>

    @Query("SELECT * FROM pending_task ORDER BY next_attempt_at")
    suspend fun all(): List<PendingTaskEntity>

    @Query("SELECT * FROM pending_task WHERE id = :id")
    suspend fun byId(id: String): PendingTaskEntity?

    @androidx.room.Update
    suspend fun update(task: PendingTaskEntity)

    @Query("DELETE FROM pending_task WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PairedDeviceDao {
    @Upsert
    suspend fun upsert(device: PairedDeviceEntity)

    @Query("SELECT * FROM paired_device ORDER BY paired_at DESC")
    suspend fun all(): List<PairedDeviceEntity>
}
