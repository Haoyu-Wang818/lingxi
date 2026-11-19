# 灵犀学伴 · AI 编程约定

本项目全部代码由 AI 辅助生成。每次生成或修改代码前，先读完本文件并严格遵守。

## 项目是什么

手机 + 手表协同的学习智能体：把课堂板书、录音整理成个人知识图谱，在碎片时间通过手表推送微复习。
三个必做创新点：碎片时间情境引擎（`context-engine`）、课堂内容到知识图谱（`server/app/extraction.py` + 待建 `feature:graph`）、
端云协同隐私路由（`core:ai`）。

## 目录与分层

| 模块 | 类型 | 职责 |
| --- | --- | --- |
| `core:model` | Kotlin/JVM | 领域模型，零依赖 |
| `core:ai` | Kotlin/JVM | 路由策略 `RoutingPolicy`、推理入口 `InferenceRouter`、后端接口 `ModelBackend` |
| `core:review` | Kotlin/JVM | FSRS 间隔重复算法 |
| `context-engine` | Kotlin/JVM | 可学习时刻判断 `LearnableMomentDetector` |
| `core:sync` | Kotlin/JVM | 手机与手表通信：消息 `WatchMessage`、链路接口 `WatchLink`、主备双链路、中继客户端 |
| `app` | Android | 手机端 Compose 界面 |
| `wear` | Android (Wear OS) | 手表端 |
| `server` | Python FastAPI | 云端网关，托管 API Key |

规则：

- **算法放 JVM 模块，界面放 Android 模块。** 任何不依赖 Android API 的逻辑都放进 `core:*` 或 `context-engine`，以便在没有 SDK 的机器上测试。
- 依赖方向只能是 `app/wear → feature → core`，`core` 之间只能依赖 `core:model`。
- 业务代码调用模型一律通过 `InferenceRouter`，不得直接调用某个 `ModelBackend`。
- 手机端不得持有大模型 API Key，云端调用只走 `server` 网关。
- 国行手机（华为、OPPO 等）通常没有 Google 服务：禁止引入 ML Kit、Firebase。手表通信一律通过 `WatchLink`，
  Wear Data Layer（依赖 Play Services）只能作为可选的主链路实现，放在独立模块里，缺失时自动走中继。

## 编码规范

- Kotlin 遵循 ktlint 官方风格（`./gradlew ktlintCheck`）；Python 遵循 ruff（`uv run ruff check .`）。
- 公开类与公开函数写 KDoc / docstring，用中文说明「做什么、为什么」，不复述代码。
- 测试方法用英文 camelCase 命名，并用 `@DisplayName("中文描述")` 说明场景（中文方法名在部分系统上会导致编译失败）。
- 新增依赖先写进 `gradle/libs.versions.toml`，并在 `docs/open-source.md` 登记许可证。

## 提交前必须通过

```bash
./gradlew ktlintCheck test          # JVM 模块
cd server && uv run ruff check . && uv run pytest
```

有 Android SDK 时另跑 `./gradlew :app:assembleDebug :wear:assembleDebug`。

全部开发和测试都在本地 Mac 上进行，仓库不使用云端 CI。启动模拟器用 `scripts/emulator.sh`。

## 竞赛硬性要求（违反会被取消资格）

- 代码、文档、提交信息中不得出现学校名称和成员姓名（匿名评审）。
- 使用的开源模型、库必须在 `docs/open-source.md` 中写明（评测会扫描开源占比）。
- 大赛鼓励提交 AI 编程交互记录：每次 AI 会话结束后，把会话导出或摘要存到 `docs/ai-log/`，并保持小步提交。
