plugins {
    id("com.android.library")
}

android {
    namespace = "com.lingxi.core.ondevice"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // OCR 评测：测试板书图片作为仪器测试的资源（docs/03-test/fixtures，FR-NOTE-01 验收）。
    sourceSets {
        getByName("androidTest").assets.directories.add("$rootDir/docs/03-test/fixtures")
    }

    // 模型文件不压缩，读取时省去解压。
    androidResources {
        noCompress += listOf("onnx")
    }
}

dependencies {
    api(project(":core:ai"))
    api(libs.kotlinx.coroutines.core)
    // 端侧推理引擎（ADR 0003），MIT 许可证；不依赖 Google 服务。
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
