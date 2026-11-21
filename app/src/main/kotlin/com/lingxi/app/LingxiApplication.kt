package com.lingxi.app

import android.app.Application
import com.lingxi.app.di.AppContainer

/** 应用入口：创建进程内唯一的 [AppContainer]。 */
class LingxiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
