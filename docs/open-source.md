# 开源组件清单

按赛题要求，列出作品中使用的开源模型、库与算法，以及自研部分。

## 已使用

| 组件 | 用途 | 许可证 |
| --- | --- | --- |
| Kotlin / kotlinx.coroutines / kotlinx.serialization | 语言、协程与 JSON 序列化 | Apache-2.0 |
| OkHttp（含 MockWebServer） | 手机与手表连接网关中继的 WebSocket 客户端及其测试 | Apache-2.0 |
| Jetpack Compose、AndroidX、Compose for Wear OS | 手机与手表界面 | Apache-2.0 |
| JUnit 6 | 单元测试 | EPL-2.0 |
| ktlint（Gradle 插件） | 代码风格检查 | MIT |
| FSRS-4.5 算法与默认参数（open-spaced-repetition） | 间隔重复，`core:review` 中按公开公式自行实现 | MIT |
| FastAPI、Uvicorn、httpx、Pydantic、websockets | 云端网关与手表中继 | MIT / BSD-3-Clause |
| pytest、ruff | 网关测试与检查 | MIT |
| AndroidX Room（含 room-testing）与 KSP | 手机端本地数据库 `lingxi.db`、编译期 SQL 校验与迁移测试（ADR 0006） | Apache-2.0 |
| AndroidX DataStore（Preferences） | 设置项：隐私开关、学期开始日期、设备 id | Apache-2.0 |
| Robolectric、AndroidX Test、JUnit 4 | 在 Mac 本机 JVM 上运行数据库与网关客户端测试，无需模拟器 | MIT / Apache-2.0 / EPL-1.0 |
| Kotlinx Kover | 仓库层测试覆盖率报告 | Apache-2.0 |
| JSON-java（org.json） | 网关客户端本地单测中替代 Android 自带的 org.json | Public Domain |

## 非开源依赖

| 组件 | 用途 | 许可证 |
| --- | --- | --- |
| Google Play 服务 Wearable（`com.google.android.gms:play-services-wearable` 19.0.0） | Wear Data Layer 手机与手表主链路，只在 `sync-datalayer` 模块引入；设备无 Play 服务时自动走自研中继（ADR 0007） | Google 专有（Android Software Development Kit License），**非开源** |

## 计划使用（接入时更新本表）

| 组件 | 用途 | 许可证 |
| --- | --- | --- |
| PaddleOCR（PP-OCR 模型，转 ONNX 后由 ONNX Runtime 运行） | 端侧文字识别 | Apache-2.0 / MIT |
| sherpa-onnx | 端侧离线语音识别 | Apache-2.0 |
| bge-small-zh | 文本向量 | MIT |
| MNN / MNN-LLM | 端侧推理引擎 | Apache-2.0 |
| Qwen2.5 小尺寸模型 | 端侧语言模型 | 以模型发布页许可证为准 |
| 云端大模型 API（通义千问 / DeepSeek） | 云端生成 | 商业服务，非开源 |

## 自研部分

- 端云协同路由策略与带回退的推理入口（`core:ai`）
- 手机与手表的传输无关消息协议、主备双链路与按 id 去重（`core:sync`），以及网关中继（`server/app/relay.py`）
- 碎片时间情境引擎与可解释打分（`context-engine`）
- 知识抽取提示词与输出校验（`server/app/extraction.py`）
- 手机端、手表端全部界面与交互
