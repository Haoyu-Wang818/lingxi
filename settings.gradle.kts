pluginManagement {
    repositories {
        // Room 的 Gradle 插件（导出 schema）只发布在 Google 仓库。
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
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

    // 核心层 Android 模块（见 docs/02-design/01-architecture.md 第 4 节）。
    include(":core:data")
    include(":core:network")
    include(":core:ondevice")
    include(":core:notify")
    include(":core:designsystem")
    // 唯一依赖 Google Play 服务的模块：Wear Data Layer 主链路。
    include(":sync-datalayer")

    // 功能层：每个页面一个模块，只依赖 core。
    include(":feature:today")
    include(":feature:review")
    include(":feature:capture")
    include(":feature:note")
    include(":feature:graph")
    include(":feature:course")
    include(":feature:context")
    include(":feature:settings")
} else {
    logger.lifecycle("未检测到 Android SDK，仅构建 JVM 模块（:core:*、:context-engine）。")
}
