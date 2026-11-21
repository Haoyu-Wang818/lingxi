# ADR 0007：手表通信以系统通知镜像为主，WatchLink 中继为 Wear OS 增强

- 状态：提议
- 日期：2026-11-21
- 关联需求：FR-WEAR-05、FR-WEAR-08、FR-SYNC-02、C1
- 取代：`docs/architecture.md` 中「Data Layer 推到手表」的描述

## 背景

问卷显示 61% 的受访者不戴手表，手表是加分项；队友手表是华为 GT 5（非 Wear OS）；国内手机普遍没有 Google 服务，Wear Data Layer 不可用。

## 决定

1. **主通道：系统通知。** 复习推送是一条带两个操作按钮的手机通知，任何配对手表（华为、小米、OPPO、Wear OS）都会由厂商健康 App 自动镜像。零手表开发即可演示「抬腕看题」。
2. **增强：Wear OS 微卡片。** 已配对 Wear OS 手表时，经 `WatchLink` 的网关中继（WebSocket）推送微卡片，手表端两键作答、磁贴显示待复习数，作答回传更新 FSRS。
3. **复赛：华为 Wear Engine。** 新增 `WearEngineWatchLink` 实现，权限申请通过后开发。
4. **不使用 Wear Data Layer。**

## 影响

- 通知上的操作按钮能否在第三方手表上点击因厂商而异，需决赛前真机实测；点不了时手表上只看题，回手机作答。
- 链路探测顺序：中继（Wear OS）→ 通知镜像（兜底）。
