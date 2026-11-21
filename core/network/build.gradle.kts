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

}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)

    // Android 自带的 org.json 在本地单测中是空实现，测试时换成同名开源实现，无需 Robolectric。
    testImplementation(libs.junit4)
    testImplementation(libs.org.json)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

