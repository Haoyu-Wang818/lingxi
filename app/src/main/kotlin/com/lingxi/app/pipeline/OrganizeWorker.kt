package com.lingxi.app.pipeline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lingxi.app.LingxiApplication
import com.lingxi.core.model.repository.NoteStatus
import com.lingxi.core.model.repository.PendingTaskType
import java.util.concurrent.TimeUnit

/**
 * 补做待办队列里的整理任务 [FR-AI-05]、[FR-NOTE-06]：联网时由 WorkManager 运行，
 * 逐个重试到期任务；失败按 1、2、4、8、16 分钟退避，5 次后标记笔记「整理失败」，用户可手动重试。
 */
class OrganizeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as LingxiApplication).container
        val now = System.currentTimeMillis()
        for (task in container.pendingTasks.ready(now)) {
            if (task.type != PendingTaskType.EXTRACT) continue
            when (val outcome = container.pipeline.organizeLesson(task.refId)) {
                is OrganizeOutcome.Done -> container.pendingTasks.remove(task.id)
                is OrganizeOutcome.Failed -> container.pendingTasks.remove(task.id)
                is OrganizeOutcome.Pending ->
                    if (!container.pendingTasks.recordFailure(task.id, outcome.reason, System.currentTimeMillis())) {
                        container.notes.byLesson(task.refId)?.let { container.notes.save(it.copy(status = NoteStatus.FAILED, title = "整理失败")) }
                    }
            }
        }
        // 还有未到期的任务时，按最早的下次尝试时间再排一次。
        container.pendingTasks.all().minOfOrNull { it.nextAttemptAtMillis }?.let { schedule(applicationContext, it - System.currentTimeMillis()) }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "lingxi-organize"

        /** 安排一次补做；只在有网络时运行，同名任务会被替换，不会重复排队。 */
        fun schedule(
            context: Context,
            delayMillis: Long = 0,
        ) {
            val request =
                OneTimeWorkRequestBuilder<OrganizeWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
