# 2026-11-21 I1 数据层与云端接入

## 目标

告别假数据：Room 本地数据库、课程与课表、复习进度持久化、网关客户端与通义千问接入、AI 执行记录。

本迭代的需求编号（`scripts/trace.py` 的 ITERATION["I1"]）：FR-COURSE-01、02、03，FR-AI-01、02、03，FR-GW-05，FR-SET-01。
顺带推进了 FR-REV-03、04、05、08（复习进度落库）、FR-AI-04（执行记录）、FR-GW-01（extract 请求格式与重试）。

## 关键决策

- **仓库接口放在 `core:model`**（纯 JVM、零第三方依赖，只用 `suspend`），实现放在 `core:data`（Room）。feature 模块只依赖接口，由 app 里手写的 `AppContainer` 注入（ADR 0004，不用 Hilt）。
- **新记忆状态由调用方计算**：分层规则规定 `core:data` 不能依赖 `core:review`，所以 `CardRepository.saveReview` 接收 FSRS 算好的新状态，不再接收评分后自己计算（与 04-api.md 第 4 节的签名不同，已在 PR 中说明）。仓库在同一事务里写入 `memory_state` 和 `review_log`，日志里存作答前的状态快照，用于撤销。
- **18 张表**按 03-database.md 建实体，开启 `exportSchema`，schema 存在 `core/data/schemas/`，并用 `MigrationTestHelper` 校验导出的 v1 schema。Room 不支持声明 CHECK 约束，相应规则放在 `Timetable`、仓库层和 `core:quality` 里校验。
- **`GatewayBackend` 放在 app**：它同时依赖 `core:ai` 和 `core:network`，放进任何 core 模块都会违反「core 之间只依赖 core:model」。
- **执行记录**：`InferenceRouter` 新增 `RouteObserver`，每次调用（包括不可用、回退）都通知观察者；app 把记录写入 `ai_execution`。记录里只有元数据，没有用户内容。
- **网关**：统一错误体 `{"error": {code, message, request_id}}`；没有 API Key 时返回 503 `LLM_NOT_CONFIGURED`，不会去调用上游；按设备（令牌 + X-Device-Id）每分钟限流，超限返回 429 和 Retry-After；调用日志只记路径、设备哈希、状态码和耗时。
- **测试运行方式**：数据库测试用 Robolectric 在本机 JVM 上运行；网关客户端只依赖 org.json，改用开源 org.json 实现，不需要 Robolectric。

## 过程中遇到的问题

- 本机经代理下载依赖很慢，Gradle 有一次因 TLS 握手中断而失败。KSP 和 Robolectric 的 Android 运行时（207 MB）是最大的两块。代理参数要以 `-D` 形式写在 gradlew 命令行上（GRADLE_OPTS 只作用于启动器，传不到守护进程）。Robolectric 的运行时用分段并行下载并校验 SHA-1 后放进 `~/.m2`。为此新增了可选的 Gradle 属性 `-ProbolectricProxy=host:port`，不在仓库里写死本机代理。
- 跨模块的公开属性不能智能转换（`CardMemory.stability`），改为先赋给局部变量。

## 模拟器验证（lingxi_phone，带窗口）

1. 清空数据后启动，首次写入种子数据，复习页显示「今日 8 张，约 2 分钟」。
2. 复习 3 张（记得、模糊、单选答对），顶部变成「今日 5 张 · 已复习 3 张」。
3. `am force-stop` 强杀后重启，进度仍在：从第 4 张（洛必达法则）开始，计数不变。
4. 课程页显示种子课程和两个上课时段，以及当前教学周（第 11 周）。
5. 设置页检查网关显示「未配置大模型 API Key」；发起一次答疑后得到 503，执行记录里出现一条「CHAT → 云端 ✗ LLM_NOT_CONFIGURED」。

截图：`docs/03-test/screenshots/i1-*.png`。

## 人工复核

- 在 `server/.env` 填入真实的通义千问 Key 后，在设置页试问一次，确认回答正常并有执行记录。
- 检查课程页的录入流程（冲突提示、仍然保存）。
