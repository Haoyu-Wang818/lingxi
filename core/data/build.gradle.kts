plugins {
    id("com.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.lingxi.core.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Robolectric 需要合并后的资源与清单；schema JSON 作为测试资源，供迁移测试读取。
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets {
        getByName("test").assets.directories.add("$projectDir/schemas")
    }
}

// 每个数据库版本的 schema 导出到仓库中，用于迁移测试与设计文档核对（03-database.md 第 6 节）。
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}

// 覆盖率只统计仓库层与数据库映射，排除 Room 生成的代码。
kover {
    reports {
        filters {
            excludes {
                classes("*_Impl", "*_Impl\$*", "*.BuildConfig")
            }
        }
    }
}

// Robolectric 首次运行会在测试进程里下载 Android 运行时。需要代理时：./gradlew test -ProbolectricProxy=127.0.0.1:7890
tasks.withType<Test>().configureEach {
    providers.gradleProperty("robolectricProxy").orNull?.split(":")?.let { (host, port) ->
        systemProperty("robolectric.dependency.proxy.host", host)
        systemProperty("robolectric.dependency.proxy.port", port)
    }
}
