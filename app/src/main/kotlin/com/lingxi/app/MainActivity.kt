package com.lingxi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lingxi.app.ui.LingxiApp

/** 应用唯一的 Activity，界面全部由 Compose 构建。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LingxiApp((application as LingxiApplication).container) }
    }
}
