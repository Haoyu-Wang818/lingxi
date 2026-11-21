plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lingxi.app"
    compileSdk = 37

    defaultConfig {
        // 手机端与手表端使用相同的 applicationId，Wear Data Layer 才能互相发现。
        applicationId = "com.lingxi.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // 开发期默认连 Mac 本机网关；真机调试时用 -PgatewayUrl=http://<Mac 局域网 IP>:8000 覆盖。
        val gatewayUrl = (project.findProperty("gatewayUrl") as String?) ?: "http://10.0.2.2:8000"
        buildConfigField("String", "GATEWAY_URL", "\"$gatewayUrl\"")
        buildConfigField("String", "PAIR_CODE", "\"${project.findProperty("pairCode") ?: "demo"}\"")
        // 设备访问网关的令牌（对应 server/.env 的 GATEWAY_TOKEN），不是大模型 API Key；手机端从不持有 Key。
        buildConfigField("String", "GATEWAY_TOKEN", "\"${project.findProperty("gatewayToken") ?: ""}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:ai"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:review"))
    implementation(project(":context-engine"))
    implementation(project(":core:sync"))
    implementation(project(":sync-datalayer"))

    // 功能模块（骨架阶段只有占位页面，原型页面仍在 app 内，逐步迁移）。
    implementation(project(":feature:today"))
    implementation(project(":feature:review"))
    implementation(project(":feature:capture"))
    implementation(project(":feature:note"))
    implementation(project(":feature:graph"))
    implementation(project(":feature:course"))
    implementation(project(":feature:context"))
    implementation(project(":feature:settings"))

    implementation(libs.datastore.preferences)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
}
