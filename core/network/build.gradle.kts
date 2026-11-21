plugins {
    id("com.android.library")
    alias(libs.plugins.kover)
}

android {
    namespace = "com.lingxi.core.network"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)

    // 解析 JSON 用的 org.json 在本地单测中是空实现，所以用 Robolectric 运行网关客户端测试。
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Robolectric 首次运行会在测试进程里下载 Android 运行时。需要代理时：./gradlew test -ProbolectricProxy=127.0.0.1:7890
tasks.withType<Test>().configureEach {
    providers.gradleProperty("robolectricProxy").orNull?.split(":")?.let { (host, port) ->
        systemProperty("robolectric.dependency.proxy.host", host)
        systemProperty("robolectric.dependency.proxy.port", port)
    }
}
