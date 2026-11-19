# ADR 0001：核心算法放在纯 Kotlin/JVM 模块

- 状态：已采纳
- 日期：2026-11-19

## 背景

项目全部由 AI 辅助编写，需要能在任何机器（包括没有 Android SDK 的云端开发环境和 CI）上快速验证生成的代码。
路由、情境判断、间隔重复都是纯逻辑，不需要 Android API。

## 决定

`core:model`、`core:ai`、`core:review`、`context-engine` 使用 `kotlin("jvm")` 插件，不依赖 Android。
Android 模块（`app`、`wear`）只在检测到 SDK 时才加入构建。

## 影响

- 核心算法测试在普通 JDK 上几秒内跑完，便于 AI 生成代码后立即验证。
- 依赖 Android 的能力（传感器、CameraX、Room）通过接口注入，例如 `DeviceStateProvider`。
