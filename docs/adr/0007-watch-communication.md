# ADR 0007：初赛手表同步采用 Wear Data Layer，WatchLink 保留中继与通知兜底

- 状态：已采纳（2026-11-21，用户决定）
- 日期：2026-11-21
- 关联需求：FR-SYNC-01、FR-SYNC-02、FR-WEAR-01 至 05、C1
- 修订：取代本 ADR 2026-11-21 早先的「通知镜像为主」草案

## 背景

- 初赛不要求真机展示，演示环境是 Mac 上的手机模拟器（`google_apis_playstore` 镜像）和 Wear OS 模拟器，两者都带 Google Play 服务。
- Wear Data Layer（`MessageClient` / `DataClient`）是 Wear OS 官方同步方式：系统级配对、自动重连、手表离线时数据项自动补发，开发量比自建中继小。
- 国内真机（华为、OPPO 等）通常没有 Google 服务，Data Layer 在那里不可用；决赛是否能用，需要和组委会确认所提供的 OPPO 手机与手表的情况。

## 决定

1. **初赛主链路：Wear Data Layer。** 新建独立模块 `sync-datalayer`（Android 库），实现 `DataLayerWatchLink : WatchLink`。依赖 `play-services-wearable` 只出现在这个模块，`core:sync` 仍是纯 JVM。
2. **保留 `WatchLink` 抽象和主备切换。** `FailoverWatchLink` 以 Data Layer 为主、现有网关中继 `RelayWatchLink` 为备；检测不到 Play 服务时自动走中继。已有代码和测试不删除。
3. **通知仍是推送入口。** 复习推送先发手机通知（任何手表可镜像），Wear OS 手表同时收到微卡片；这一点不随同步方式变化。
4. **决赛前复核。** 进入决赛后和组委会确认真机是否有 Google 服务：有则继续用 Data Layer；没有则切到中继（已实现）或厂商 SDK（如华为 Wear Engine），业务代码不改。

## 备选

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| **Wear Data Layer** | 官方、系统配对、离线补发、开发量小 | 依赖 Play 服务，国内真机不可用 | 初赛采用 |
| 网关中继（已实现） | 不依赖 Google；跨厂商 | 需要网关在线、自建配对与重传 | 作为备链路 |
| 只用通知镜像 | 零手表开发 | 手表上无法作答回传 | 作为推送入口与兜底 |

## 影响

- CLAUDE.md 规则「Data Layer 只能作为可选主链路实现，放在独立模块里，缺失时自动走中继」与本决定一致，无需修改。
- 新增依赖 `com.google.android.gms:play-services-wearable`，在 `docs/open-source.md` 中标注为非开源的 Google 专有 SDK。
- 配对：Data Layer 使用系统配对（模拟器用 Android Studio 的 Wear OS 配对助手）；6 位配对码只在中继模式下使用。
