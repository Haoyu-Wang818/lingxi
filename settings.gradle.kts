pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Google 仓库只提供 Android 相关依赖，限定分组避免其他依赖也去查询它。
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "lingxi"

// 纯 Kotlin/JVM 模块：核心算法，不依赖 Android，任何有 JDK 的机器都能编译和测试。
include(":core:model")
include(":core:ai")
include(":core:review")
include(":core:sync")
include(":context-engine")
include(":core:quality")

// Android 模块：需要 Android SDK（Android Studio 自带，或 CI 中安装）。
// 没有 SDK 的环境（例如只跑算法测试的服务器）会自动跳过，避免整个构建失败。
val localProps = file("local.properties")
val hasAndroidSdk = !System.getenv("ANDROID_HOME").isNullOrBlank() ||
    !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() ||
    (localProps.exists() && localProps.readText().contains("sdk.dir"))

if (hasAndroidSdk) {
    include(":app")
    include(":wear")
} else {
    logger.lifecycle("未检测到 Android SDK，仅构建 JVM 模块（:core:*、:context-engine）。")
}
