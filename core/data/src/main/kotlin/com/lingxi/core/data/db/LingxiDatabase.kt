package com.lingxi.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * 手机端唯一的本地数据库 `lingxi.db`（ADR 0006，03-database.md）。
 *
 * 迁移策略：版本从 1 开始，每次结构变更 +1，并在 [MIGRATIONS] 中提供对应 [Migration] 与迁移测试；
 * schema JSON 导出在 `core/data/schemas/`。不使用破坏性迁移，避免升级后用户的复习记录丢失。
 */
@Database(
    version = LingxiDatabase.VERSION,
    exportSchema = true,
    entities = [
        CourseEntity::class,
        ScheduleSlotEntity::class,
        LessonEntity::class,
        CaptureEntity::class,
        RecognizedTextEntity::class,
        NoteEntity::class,
        KnowledgePointEntity::class,
        KpSourceEntity::class,
        KnowledgeEdgeEntity::class,
        CardEntity::class,
        MemoryStateEntity::class,
        ReviewLogEntity::class,
        CardFeedbackEntity::class,
        ContextEvaluationEntity::class,
        PushRecordEntity::class,
        AiExecutionEntity::class,
        PendingTaskEntity::class,
        PairedDeviceEntity::class,
    ],
)
abstract class LingxiDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao

    abstract fun scheduleSlotDao(): ScheduleSlotDao

    abstract fun lessonDao(): LessonDao

    abstract fun captureDao(): CaptureDao

    abstract fun noteDao(): NoteDao

    abstract fun knowledgeDao(): KnowledgeDao

    abstract fun cardDao(): CardDao

    abstract fun reviewLogDao(): ReviewLogDao

    abstract fun contextEvaluationDao(): ContextEvaluationDao

    abstract fun pushRecordDao(): PushRecordDao

    abstract fun aiExecutionDao(): AiExecutionDao

    abstract fun pendingTaskDao(): PendingTaskDao

    abstract fun pairedDeviceDao(): PairedDeviceDao

    companion object {
        const val VERSION = 1
        const val NAME = "lingxi.db"

        /** 全部版本迁移，按版本顺序追加。 */
        val MIGRATIONS: Array<Migration> = emptyArray()

        /** 打开应用数据库（外键约束由 Room 在每次连接时开启）。 */
        fun open(context: Context): LingxiDatabase =
            Room
                .databaseBuilder(context.applicationContext, LingxiDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
