package com.lingxi.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.ForeignKey.Companion.SET_NULL
import androidx.room.Index
import androidx.room.PrimaryKey

// 18 张表与 docs/02-design/03-database.md 第 3 节一一对应，字段名保持 snake_case。
// SQLite 的 CHECK 约束 Room 无法声明，相应规则在仓库层与 core:quality 中校验。

/** 3.1 课程。 */
@Entity(tableName = "course")
data class CourseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val teacher: String?,
    val color: Int,
    @ColumnInfo(name = "exam_date") val examDate: Long?,
    @ColumnInfo(name = "target_retention", defaultValue = "0.9") val targetRetention: Double,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** 3.2 上课时段。 */
@Entity(
    tableName = "schedule_slot",
    foreignKeys = [ForeignKey(CourseEntity::class, ["id"], ["course_id"], onDelete = CASCADE)],
    indices = [Index("course_id"), Index(value = ["weekday", "start_min"], name = "idx_slot_weekday")],
)
data class ScheduleSlotEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "course_id") val courseId: String,
    val weekday: Int,
    @ColumnInfo(name = "start_min") val startMin: Int,
    @ColumnInfo(name = "end_min") val endMin: Int,
    @ColumnInfo(name = "week_from") val weekFrom: Int,
    @ColumnInfo(name = "week_to") val weekTo: Int,
    val location: String?,
)

/** 3.3 课次。 */
@Entity(
    tableName = "lesson",
    foreignKeys = [ForeignKey(CourseEntity::class, ["id"], ["course_id"], onDelete = CASCADE)],
    indices = [Index("course_id")],
)
data class LessonEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "course_id") val courseId: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
)

/** 3.4 捕获素材。 */
@Entity(
    tableName = "capture",
    foreignKeys = [ForeignKey(LessonEntity::class, ["id"], ["lesson_id"], onDelete = CASCADE)],
    indices = [Index("lesson_id")],
)
data class CaptureEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "lesson_id") val lessonId: String,
    val kind: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "corrected_path") val correctedPath: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "page_count") val pageCount: Int?,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** 3.5 识别文本。 */
@Entity(
    tableName = "recognized_text",
    foreignKeys = [ForeignKey(CaptureEntity::class, ["id"], ["capture_id"], onDelete = CASCADE)],
    indices = [Index("capture_id")],
)
data class RecognizedTextEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "capture_id") val captureId: String,
    val engine: String,
    val location: String,
    val text: String,
    val confidence: Double?,
    @ColumnInfo(name = "segments_json") val segmentsJson: String?,
)

/** 3.6 笔记：一个课次一份。 */
@Entity(
    tableName = "note",
    foreignKeys = [ForeignKey(LessonEntity::class, ["id"], ["lesson_id"], onDelete = CASCADE)],
    indices = [Index(value = ["lesson_id"], unique = true)],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "lesson_id") val lessonId: String,
    val title: String,
    val markdown: String,
    @ColumnInfo(name = "original_markdown") val originalMarkdown: String?,
    val status: String,
    @ColumnInfo(name = "prompt_version") val promptVersion: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** 3.7 知识点：同一课程内归一化名称唯一（去重第一道）。 */
@Entity(
    tableName = "knowledge_point",
    foreignKeys = [ForeignKey(CourseEntity::class, ["id"], ["course_id"], onDelete = CASCADE)],
    indices = [Index("course_id"), Index(value = ["course_id", "norm_title"], unique = true)],
)
data class KnowledgePointEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "course_id") val courseId: String,
    val title: String,
    @ColumnInfo(name = "norm_title") val normTitle: String,
    val summary: String,
    val embedding: ByteArray?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    // ByteArray 字段需要按内容比较，否则 data class 的 equals 只比较引用。
    override fun equals(other: Any?): Boolean =
        other is KnowledgePointEntity &&
            id == other.id &&
            courseId == other.courseId &&
            title == other.title &&
            normTitle == other.normTitle &&
            summary == other.summary &&
            embedding.contentEqualsNullable(other.embedding) &&
            deletedAt == other.deletedAt &&
            createdAt == other.createdAt

    override fun hashCode(): Int = id.hashCode()
}

/** 3.8 知识点来源。 */
@Entity(
    tableName = "kp_source",
    primaryKeys = ["kp_id", "note_id"],
    foreignKeys = [
        ForeignKey(KnowledgePointEntity::class, ["id"], ["kp_id"], onDelete = CASCADE),
        ForeignKey(NoteEntity::class, ["id"], ["note_id"], onDelete = CASCADE),
    ],
    indices = [Index("note_id")],
)
data class KpSourceEntity(
    @ColumnInfo(name = "kp_id") val kpId: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "source_ref_json") val sourceRefJson: String?,
)

/** 3.9 知识关系。 */
@Entity(
    tableName = "knowledge_edge",
    primaryKeys = ["from_id", "to_id", "relation"],
    foreignKeys = [
        ForeignKey(KnowledgePointEntity::class, ["id"], ["from_id"], onDelete = CASCADE),
        ForeignKey(KnowledgePointEntity::class, ["id"], ["to_id"], onDelete = CASCADE),
    ],
    indices = [Index("to_id")],
)
data class KnowledgeEdgeEntity(
    @ColumnInfo(name = "from_id") val fromId: String,
    @ColumnInfo(name = "to_id") val toId: String,
    val relation: String,
    val confidence: Double,
)

/** 3.10 卡片。 */
@Entity(
    tableName = "card",
    foreignKeys = [ForeignKey(KnowledgePointEntity::class, ["id"], ["kp_id"], onDelete = CASCADE)],
    indices = [Index("kp_id")],
)
data class CardEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "kp_id") val kpId: String,
    val type: String,
    val question: String,
    val answer: String,
    @ColumnInfo(name = "options_json") val optionsJson: String?,
    @ColumnInfo(name = "watch_prompt") val watchPrompt: String?,
    @ColumnInfo(name = "watch_answer") val watchAnswer: String?,
    @ColumnInfo(name = "source_ref_json") val sourceRefJson: String?,
    val status: String,
    @ColumnInfo(name = "quality_flags", defaultValue = "0") val qualityFlags: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** 3.11 记忆状态：一卡一状态。 */
@Entity(
    tableName = "memory_state",
    foreignKeys = [ForeignKey(CardEntity::class, ["id"], ["card_id"], onDelete = CASCADE)],
    indices = [Index(value = ["due_at"], name = "idx_memory_due")],
)
data class MemoryStateEntity(
    @PrimaryKey @ColumnInfo(name = "card_id") val cardId: String,
    val state: String,
    val stability: Double?,
    val difficulty: Double?,
    @ColumnInfo(name = "due_at") val dueAt: Long,
    @ColumnInfo(name = "last_review_at") val lastReviewAt: Long?,
    @ColumnInfo(defaultValue = "0") val reps: Int,
    @ColumnInfo(defaultValue = "0") val lapses: Int,
)

/** 3.12 复习日志。 */
@Entity(
    tableName = "review_log",
    foreignKeys = [ForeignKey(CardEntity::class, ["id"], ["card_id"], onDelete = CASCADE)],
    indices = [Index(value = ["card_id", "reviewed_at"]), Index(value = ["message_id"], unique = true)],
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "card_id") val cardId: String,
    @ColumnInfo(name = "reviewed_at") val reviewedAt: Long,
    val rating: Int,
    @ColumnInfo(name = "elapsed_ms") val elapsedMs: Long?,
    val device: String,
    val mode: String,
    val counted: Boolean,
    @ColumnInfo(name = "prev_state_json") val prevStateJson: String,
    @ColumnInfo(name = "message_id") val messageId: String?,
)

/** 3.13 卡片纠错。 */
@Entity(
    tableName = "card_feedback",
    foreignKeys = [ForeignKey(CardEntity::class, ["id"], ["card_id"], onDelete = CASCADE)],
    indices = [Index("card_id")],
)
data class CardFeedbackEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "card_id") val cardId: String,
    val kind: String,
    val comment: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** 3.14 情境评估。 */
@Entity(tableName = "context_evaluation", indices = [Index("evaluated_at")])
data class ContextEvaluationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "evaluated_at") val evaluatedAt: Long,
    val trigger: String,
    @ColumnInfo(name = "signals_json") val signalsJson: String,
    val learnable: Boolean,
    val score: Double?,
    @ColumnInfo(name = "suggested_cards") val suggestedCards: Int?,
    @ColumnInfo(name = "reasons_json") val reasonsJson: String,
    @ColumnInfo(name = "blocked_by") val blockedBy: String?,
)

/** 3.15 推送记录。 */
@Entity(
    tableName = "push_record",
    foreignKeys = [
        ForeignKey(ContextEvaluationEntity::class, ["id"], ["evaluation_id"], onDelete = SET_NULL),
        ForeignKey(CardEntity::class, ["id"], ["card_id"], onDelete = CASCADE),
    ],
    indices = [Index("evaluation_id"), Index("card_id"), Index("sent_at")],
)
data class PushRecordEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "evaluation_id") val evaluationId: Long?,
    @ColumnInfo(name = "card_id") val cardId: String,
    val channel: String,
    @ColumnInfo(name = "sent_at") val sentAt: Long,
    val outcome: String?,
    @ColumnInfo(name = "outcome_at") val outcomeAt: Long?,
)

/** 3.16 AI 执行记录：只记元数据，不含用户内容。 */
@Entity(tableName = "ai_execution", indices = [Index("ref_id")])
data class AiExecutionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "task_type") val taskType: String,
    val location: String,
    val reason: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    val success: Boolean,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "ref_id") val refId: String?,
)

/** 3.17 待办任务队列。 */
@Entity(
    tableName = "pending_task",
    indices = [Index(value = ["task_type", "ref_id"], unique = true), Index("next_attempt_at")],
)
data class PendingTaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_type") val taskType: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "ref_id") val refId: String,
    @ColumnInfo(defaultValue = "0") val attempts: Int,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long,
    @ColumnInfo(name = "last_error") val lastError: String?,
)

/** 3.18 配对设备。 */
@Entity(tableName = "paired_device")
data class PairedDeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val platform: String,
    @ColumnInfo(name = "node_id") val nodeId: String?,
    @ColumnInfo(name = "link_type") val linkType: String,
    @ColumnInfo(name = "pair_code") val pairCode: String?,
    @ColumnInfo(name = "public_key") val publicKey: ByteArray?,
    @ColumnInfo(name = "paired_at") val pairedAt: Long?,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long?,
) {
    override fun equals(other: Any?): Boolean =
        other is PairedDeviceEntity &&
            id == other.id &&
            name == other.name &&
            platform == other.platform &&
            nodeId == other.nodeId &&
            linkType == other.linkType &&
            pairCode == other.pairCode &&
            publicKey.contentEqualsNullable(other.publicKey) &&
            pairedAt == other.pairedAt &&
            lastSeenAt == other.lastSeenAt

    override fun hashCode(): Int = id.hashCode()
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null || other == null) this === other else contentEquals(other)
