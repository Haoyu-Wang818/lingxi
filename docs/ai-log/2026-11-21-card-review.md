# 2026-11-21 卡片式复习页原型（I0）

## 目标

按 `docs/02-design/card-review-ux.md` 第 2 节实现手机端卡片式复习页原型，替换原型阶段的旧 ReviewScreen。

## 关键提示与决策

- `FsrsScheduler` 实现 `Scheduler` 接口，`predictIntervals` 对四档评分分别模拟一次调度，并强制结果单调（AGAIN ≤ HARD ≤ GOOD ≤ EASY），避免极端参数下按钮间隔倒挂。
- 复习状态全部放进 `core:review` 的纯 Kotlin 不可变状态机 `ReviewSession`：每个操作返回新会话，撤销就是回到上一个快照，不需要逆向计算 FSRS；界面只做展示与手势，状态逻辑都能在 JVM 上单测。
- 组末回炉的卡不再写入 FSRS（同日重复不重复计分），回炉时再答「忘了」也不会无限追加。
- 客观题点选即判分并翻到背面，答对后可以点「其实是蒙的」改为 HARD；客观题不允许直接翻面，也不响应滑动手势。
- 正面滑动会回弹，只有翻到背面后滑动才计分，保证先回忆后评分。
- 示例数据换成高数第 5 讲「泰勒公式」的 8 张卡（问答、挖空、判断、单选各 2 张），后 4 张模拟 3 天前学过一次，让按钮上的间隔和新卡不同。

## 模拟器发现的问题

挖空正则 `\{\{c1::(.+?)}}` 在桌面 JVM 上能用，在 Android（ICU 正则）上却抛 `PatternSyntaxException`，打开复习页就闪退。JVM 单测发现不了这个问题，改为转义右花括号后修复。以后在 JVM 模块里写正则要考虑 Android 的兼容性。

## 产出

- `core:review`：`Fsrs.kt`（实现 `Scheduler`）、`DeckCard.kt`（`DeckCard`、`CardText`）、`ReviewSession.kt`，新增测试 15 个（模块共 22 个）。
- `feature:review`：`ReviewDeckScreen`。
- `app`：复习 tab 接入 `ReviewDeckScreen`，删除旧 `ReviewScreen`，扩充 `SampleData`。
- 截图：`docs/03-test/screenshots/review-*.png`（lingxi_phone 模拟器）。

## 人工复核

待团队核对 8 张卡片的数学内容，并在真机上试一遍手势的手感。
