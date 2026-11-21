plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lingxi.wear"
    compileSdk = 37

    defaultConfig {
        // 与手机端保持一致，见 app/build.gradle.kts。
        applicationId = "com.lingxi.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // 开发期默认连 Mac 本机网关；真机调试时用 -PgatewayUrl=http://<Mac 局域网 IP>:8000 覆盖。
        val gatewayUrl = (project.findProperty("gatewayUrl") as String?) ?: "http://10.0.2.2:8000"
        buildConfigField("String", "GATEWAY_URL", "\"$gatewayUrl\"")
        buildConfigField("String", "PAIR_CODE", "\"${project.findProperty("pairCode") ?: "demo"}\"")
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
    implementation(project(":core:model"))
    implementation(project(":core:sync"))
    implementation(project(":sync-datalayer"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
}
