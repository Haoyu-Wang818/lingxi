# ADR 0005：知识图谱用 Compose Canvas 自绘力导向布局

- 状态：提议
- 日期：2026-11-21
- 关联需求：FR-KG-04、FR-KG-05、NFR-PERF-04

## 背景

需要在手机上渲染 100–200 个节点，支持缩放、拖动、点击和按记忆状态着色。

## 备选

| 方案 | 结论 |
| --- | --- |
| WebView + ECharts / D3 | 效果成熟，但引入 Web 栈、与 Compose 交互繁琐、首屏慢 |
| 第三方 Compose 图谱库 | 生态中缺少维护良好的力导向库 |
| **Compose Canvas + 自研力导向（Fruchterman-Reingold）** | 无额外依赖；布局算法放 JVM 模块可单测；渲染可控 |

## 决定

布局算法 `ForceLayout` 放在 `core:model` 同级的纯 JVM 代码中（`feature:graph` 内部 `layout` 包，若复用再抽模块），渲染用 Canvas，`transformable` 处理缩放平移。超过 200 节点时按课程分片显示。
