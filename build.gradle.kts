// Android Gradle 插件只在有 Android SDK 的环境加载（判断条件与 settings.gradle.kts 一致），
// 这样只跑算法测试的机器无需访问 Google Maven 仓库。放在根项目的 buildscript 中，
// 保证它和 Kotlin 插件位于同一个类加载器。
buildscript {
    val hasAndroidSdk = !System.getenv("ANDROID_HOME").isNullOrBlank() ||
        !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
    if (hasAndroidSdk) {
        repositories { google() }
        dependencies { classpath(libs.android.gradle.plugin) }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.kover) apply false
}

// 所有 JVM 模块共用的约定：Java 17、JUnit 5、ktlint。
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")

        // 统一编译目标为 Java 17（Android 端同样使用 17），不强制下载特定 JDK。
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }

        dependencies {
            add("testImplementation", platform(rootProject.libs.junit.bom))
            add("testImplementation", rootProject.libs.junit.jupiter)
            add("testRuntimeOnly", rootProject.libs.junit.launcher)
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging { events("failed") }
        }
    }
}
