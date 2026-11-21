# 2026-11-21 系统设计阶段

## 目标

完成系统设计阶段（2.1–2.7）：在需求规格基础上产出总体架构、数据库、接口、界面与核心算法详细设计、测试计划，
并在 2.7「代码对齐」中按设计搭好 Gradle 模块与接口骨架，使后续迭代 I1–I5 可以直接在既定边界内填充实现。

## 关键决定

- **手机优先**：完整学习闭环（捕获 → 笔记 → 卡片 → 复习）全部在手机上可用；手表是加分的碎片时间入口，
  只做两键作答的微卡片。没有手表时情境引擎按活动状态退化，不影响主流程。
- **Wear Data Layer 为初赛主链路，网关中继为备链路**（ADR 0007）：模拟器与有 Play 服务的设备走 Data Layer，
  国行手机等无 Play 服务的设备自动切到中继。Play 服务依赖被隔离在独立的 `sync-datalayer` 模块，
  业务代码只依赖 `core:sync` 的 `WatchLink` 接口。
- **模块划分**：`app/wear → feature → core`。算法与规则放纯 JVM 模块（`core:model`、`core:review`、`core:ai`、
  `core:sync`、新增 `core:quality`、`context-engine`），可在无 Android SDK 的机器上测试；Android 相关的数据、网络、
  端侧模型、通知、设计系统各成一个 core 模块；每个页面一个 feature 模块。core 之间只依赖 `core:model`。
- **抽取结果模型放进 `core:model`**（`Extraction`、`ExtractedPoint`、`ScoredEdge`）：网关客户端、质量校验与仓库层都要用，
  放在任何一个 core 模块都会造成 core 之间的横向依赖。
- **推送决策拆为两层**：`PushPolicy`（能不能打扰：预算与静音时段）与 `LearnableMomentDetector`（适不适合学），
  分别测试。本阶段只定义 `PushPolicy` 接口，未改动 `LearnableMomentDetector` 的行为。
- **`Scheduler` 先只定义接口**，现有 `FsrsScheduler` 暂不实现它，避免本阶段改动已验证的 FSRS 计算；I3 补 `predictIntervals` 后接入。
- 骨架阶段不引入 Room、ONNX Runtime、CameraX 等重依赖，放到 I1 及以后按需加入。

## 产出

- 设计文档：`docs/02-design/01、03、04、05、06`，测试计划 `docs/03-test/`。
- 新模块：JVM `core:quality`；Android `core:data`、`core:network`、`core:ondevice`、`core:notify`、`core:designsystem`、
  `sync-datalayer`、`feature:today/review/capture/note/graph/course/context/settings`。
- `core:model`：`ReviewCard` 增加 `options`、`correctIndex`、`watchPrompt`、`watchAnswer`、`sourceRef`；新增 `SourceRef`、`CardType.CLOZE`。
- 接口：`CardValidator`/`CardRule`/`RelationValidator`/`Violation`、`PushPolicy`/`PushDecision`/`PushSettings`/`PushRecord`、
  `Scheduler`、`CardRepository`、`KnowledgeRepository`、`GatewayApi`/`GatewayError`、`OcrEngine`/`AsrEngine`、`ReviewNotifier`、
  `DataLayerWatchLink`（骨架，`send` 恒返回 false，未接入装配）。
- 新增依赖：`play-services-wearable` 19.0.0（Google 专有，已在 `docs/open-source.md` 单列）。
- 校验：`ktlintCheck test :app:assembleDebug :wear:assembleDebug` 通过（JVM 单测 48 个）；网关 ruff 通过、pytest 11 个通过。

## 人工复核点

- 模块依赖是否与架构图一致：feature 模块目前未依赖 `core:designsystem`（图中无此边），I3 统一视觉时需在架构图补边。
- `ReviewCard` 新字段与数据库 `card` 表（`options_json` 含正确项下标）的映射方式。
- `PushSettings` 默认值（每日 3 次、间隔 120 分钟、两个静音段）与问卷结论是否一致。
- `GatewayError` 中 422 `LLM_INVALID_OUTPUT` 标记为可重试（进入待办队列）是否符合预期。
- `scripts/trace.py` 会把占位页面也计为「代码」引用，评审追溯矩阵时注意区分骨架与实现。
- `CLAUDE.md` 的模块表尚未补充新模块，建议同步更新。
