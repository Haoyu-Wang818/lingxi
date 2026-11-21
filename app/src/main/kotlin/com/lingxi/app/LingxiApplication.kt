package com.lingxi.app

import android.app.Application
import com.lingxi.app.di.AppContainer
import com.lingxi.app.pipeline.OrganizeWorker
import kotlinx.coroutines.launch

/** 应用入口：创建进程内唯一的 [AppContainer]。 */
class LingxiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 上次退出时还有待整理的课次：联网后自动补做 [FR-AI-05]。
        container.scope.launch {
            if (container.pendingTasks.all().isNotEmpty()) OrganizeWorker.schedule(this@LingxiApplication)
        }
    }
}
