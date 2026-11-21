# 03 数据库设计

- 文档编号：LX-DES-03
- 版本：v0.1（待评审）
- 日期：2026-11-21
- 所属阶段：系统设计 2.3
- 上游：[SRS 第 5 节](../01-requirements/03-srs.md)、[ADR 0006 Room](../adr/0006-local-database.md)

## 1. 概述

- **存储位置**：全部用户数据保存在手机本地 SQLite（Room），数据库名 `lingxi.db`；素材文件（照片、录音）保存在应用私有目录 `files/captures/`，数据库只存相对路径。
- **网关不存用户数据**：服务端没有业务数据库（NFR-SEC-02）。
- **主键**：业务实体用客户端生成的 UUID 字符串（`TEXT`），便于离线创建、导出导入和将来多端同步；日志类高频表用自增 `INTEGER`。
- **时间**：统一用 UTC 毫秒（`INTEGER`），界面再按时区显示。
- **删除策略**：课程删除级联删除其下数据；知识点被用户删除时做软删除（`deleted_at`），保证复习日志可追溯。
- **设置项**不进数据库，使用 Jetpack DataStore（键值），见第 5 节。

## 2. ER 图

```plantuml
@startuml
hide circle
skinparam linetype ortho

entity course { *id : TEXT <<PK>>
  name ; teacher ; color ; exam_date }
entity schedule_slot { *id <<PK>>
  course_id <<FK>> ; weekday ; start_min ; end_min ; week_from ; week_to ; location }
entity lesson { *id <<PK>>
  course_id <<FK>> ; started_at }
entity capture { *id <<PK>>
  lesson_id <<FK>> ; kind ; file_path ; duration_ms ; page_count ; status }
entity recognized_text { *id <<PK>>
  capture_id <<FK>> ; engine ; location ; text ; confidence ; segments_json }
entity note { *id <<PK>>
  lesson_id <<FK>> ; title ; markdown ; status ; prompt_version }
entity knowledge_point { *id <<PK>>
  course_id <<FK>> ; title ; norm_title ; summary ; embedding ; deleted_at }
entity kp_source { *kp_id, note_id <<PK>>
  source_ref_json }
entity knowledge_edge { *from_id, to_id, relation <<PK>>
  confidence }
entity card { *id <<PK>>
  kp_id <<FK>> ; type ; question ; answer ; options_json ; watch_prompt ; watch_answer ; source_ref_json ; status }
entity memory_state { *card_id <<PK,FK>>
  stability ; difficulty ; due_at ; last_review_at ; reps ; lapses ; state }
entity review_log { *id : INTEGER <<PK>>
  card_id <<FK>> ; reviewed_at ; rating ; elapsed_ms ; device ; mode ; prev_state_json }
entity card_feedback { *id <<PK>>
  card_id <<FK>> ; kind ; comment ; created_at }
entity context_evaluation { *id : INTEGER <<PK>>
  evaluated_at ; signals_json ; learnable ; score ; reasons_json ; blocked_by }
entity push_record { *id <<PK>>
  evaluation_id <<FK>> ; card_id <<FK>> ; channel ; sent_at ; outcome ; outcome_at }
entity ai_execution { *id : INTEGER <<PK>>
  task_type ; location ; reason ; started_at ; duration_ms ; success ; error_code ; ref_id }
entity pending_task { *id <<PK>>
  task_type ; payload_json ; ref_id ; attempts ; next_attempt_at ; last_error }
entity paired_device { *id <<PK>>
  name ; platform ; link_type ; pair_code ; public_key ; paired_at ; last_seen_at }

course ||--o{ schedule_slot
course ||--o{ lesson
course ||--o{ knowledge_point
lesson ||--o{ capture
lesson ||--o| note
capture ||--o{ recognized_text
knowledge_point ||--o{ kp_source
note ||--o{ kp_source
knowledge_point ||--o{ knowledge_edge : from
knowledge_point ||--o{ knowledge_edge : to
knowledge_point ||--o{ card
card ||--|| memory_state
card ||--o{ review_log
card ||--o{ card_feedback
context_evaluation ||--o| push_record
card ||--o{ push_record
@enduml
```

## 3. 表结构

约定：`PK` 主键，`FK` 外键（`ON DELETE` 规则见各表），`NN` 非空，`UQ` 唯一，`IDX` 索引。

### 3.1 course 课程（FR-COURSE-01、FR-REV-16）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | TEXT | PK | UUID |
| name | TEXT | NN | 课程名 |
| teacher | TEXT | | 教师 |
| color | INTEGER | NN | ARGB，用于卡片与图谱着色 |
| exam_date | INTEGER | | 考试日期（考前模式） |
| target_retention | REAL | NN，默认 0.90 | FSRS 目标保持率，考前模式提至 0.95 |
| created_at | INTEGER | NN | |

### 3.2 schedule_slot 上课时段（FR-COURSE-02、03，FR-CTX-02）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | TEXT | PK | |
| course_id | TEXT | FK→course CASCADE，IDX | |
| weekday | INTEGER | NN，1–7 | 星期 |
| start_min / end_min | INTEGER | NN，CHECK start<end | 当日分钟数 |
| week_from / week_to | INTEGER | NN | 教学周范围 |
| location | TEXT | | 地点 |

索引 `idx_slot_weekday(weekday, start_min)`：情境引擎与捕获页判断「正在上的课」。

### 3.3 lesson 课次（FR-CAP-06）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | TEXT | PK | |
| course_id | TEXT | FK→course CASCADE，IDX | |
| started_at | INTEGER | NN | 课次开始时间；同一课程 2 小时内的捕获归入同一课次 |

### 3.4 capture 捕获素材（FR-CAP-01 至 06）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | TEXT | PK | |
| lesson_id | TEXT | FK→lesson CASCADE，IDX | |
| kind | TEXT | NN，CHECK IN (PHOTO, AUDIO, PDF) | 对应 `CaptureSource` |
| file_path | TEXT | NN | 相对私有目录路径 |
| corrected_path | TEXT | | 透视矫正后的图片 |
| duration_ms | INTEGER | AUDIO 时 NN | |
| page_count | INTEGER | PDF 时 NN | |
| status | TEXT | NN | NEW → RECOGNIZED → ORGANIZED / FAILED |
| created_at | INTEGER | NN | |

### 3.5 recognized_text 识别文本（FR-NOTE-01、02、05，FR-AI-04）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | TEXT | PK | |
| capture_id | TEXT | FK→capture CASCADE，IDX | |
| engine | TEXT | NN | PP-OCRv4 / sherpa-onnx / cloud-vl |
| location | TEXT | NN | ON_DEVICE / CLOUD |
| text | TEXT | NN | 合并文本 |
| confidence | REAL | | 平均置信度 |
| segments_json | TEXT | | 分段：OCR 为文本框坐标，转写为时间戳，用于溯源 |

### 3.6 note 笔记（FR-NOTE-03、04、06，FR-GW-06）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | TEXT | PK | |
| lesson_id | TEXT | FK→lesson CASCADE，UQ | 一个课次一份笔记 |
| title | TEXT | NN | |
| markdown | TEXT | NN | 结构化笔记 |
| original_markdown | TEXT | | 用户编辑前的版本 |
| status | TEXT | NN | PENDING / READY / FAILED |
| prompt_version | TEXT | | 生成所用提示词版本 |
| updated_at | INTEGER | NN | |

### 3.7 knowledge_point 知识点（FR-KG-01、02、07）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | TEXT | PK | |
| course_id | TEXT | FK→course CASCADE，IDX | |
| title | TEXT | NN | |
| norm_title | TEXT | NN，UQ(course_id, norm_title) | 归一化名称（去空白、全半角统一、同义词表），去重第一道 |
| summary | TEXT | NN | |
| embedding | BLOB | | 384 维 float16 向量，去重第二道（余弦相似度 ≥ 0.88 视为同一知识点） |
| deleted_at | INTEGER | | 软删除 |
| created_at | INTEGER | NN | |

### 3.8 kp_source 知识点来源（FR-KG-06、FR-NOTE-05）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| kp_id | TEXT | PK，FK→knowledge_point CASCADE | |
| note_id | TEXT | PK，FK→note CASCADE，IDX | |
| source_ref_json | TEXT | | `{captureId, page, box}` 或 `{captureId, startMs, endMs}` |

### 3.9 knowledge_edge 知识关系（FR-KG-01、03，FR-REV-15）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| from_id | TEXT | PK，FK→knowledge_point CASCADE | |
| to_id | TEXT | PK，FK→knowledge_point CASCADE，IDX | |
| relation | TEXT | PK，CHECK IN (PREREQUISITE, PART_OF, EXAMPLE_OF, RELATED) | |
| confidence | REAL | NN | 冲突时保留高者 |

约束：`CHECK(from_id <> to_id)`；先修无环由 `core:quality` 在写入前校验（SQL 无法表达）。
先修链查询使用递归 CTE：

```sql
WITH RECURSIVE prereq(id, depth) AS (
  SELECT from_id, 1 FROM knowledge_edge WHERE to_id = :kpId AND relation = 'PREREQUISITE'
  UNION
  SELECT e.from_id, p.depth + 1 FROM knowledge_edge e JOIN prereq p ON e.to_id = p.id
  WHERE e.relation = 'PREREQUISITE' AND p.depth < 5
)
SELECT * FROM prereq;
```

### 3.10 card 卡片（FR-REV-01、10、11，FR-WEAR-08）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | TEXT | PK | |
| kp_id | TEXT | FK→knowledge_point CASCADE，IDX | |
| type | TEXT | NN，CHECK IN (QUESTION_ANSWER, CLOZE, TRUE_FALSE, SINGLE_CHOICE) | |
| question | TEXT | NN | |
| answer | TEXT | NN | |
| options_json | TEXT | 单选题 NN | 选项数组，含正确项下标 |
| watch_prompt | TEXT | CHECK length ≤ 20 | 为空则不推手表 |
| watch_answer | TEXT | CHECK length ≤ 12 | |
| source_ref_json | TEXT | | 卡片背面「来自哪页板书」 |
| status | TEXT | NN | ACTIVE / SUSPENDED / DELETED |
| quality_flags | INTEGER | NN，默认 0 | 规则校验结果位图 |
| created_at / updated_at | INTEGER | NN | |

### 3.11 memory_state 记忆状态（FR-REV-02、03）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| card_id | TEXT | PK，FK→card CASCADE | 一卡一状态 |
| state | TEXT | NN | NEW / LEARNING / REVIEW / RELEARNING |
| stability | REAL | | FSRS 稳定度（天） |
| difficulty | REAL | | FSRS 难度 1–10 |
| due_at | INTEGER | NN | 下次到期 |
| last_review_at | INTEGER | | |
| reps / lapses | INTEGER | NN，默认 0 | |

索引 `idx_memory_due(due_at)`：今日队列查询。

### 3.12 review_log 复习日志（FR-REV-04、05、14，FR-WEAR-03）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | INTEGER | PK 自增 | |
| card_id | TEXT | FK→card CASCADE，IDX(card_id, reviewed_at) | |
| reviewed_at | INTEGER | NN | |
| rating | INTEGER | NN，1–4 | `Rating.grade` |
| elapsed_ms | INTEGER | | 反应时长（个性化记忆，FR-REV-06） |
| device | TEXT | NN | PHONE / WATCH / NOTIFICATION |
| mode | TEXT | NN | DAILY / FRAGMENT / RELEARN_IN_GROUP / EXAM |
| counted | INTEGER | NN | 是否计入 FSRS（组内回炉为 0） |
| prev_state_json | TEXT | NN | 自评前的记忆状态快照，用于撤销 |
| message_id | TEXT | UQ | 手表回传消息 id，去重（FR-WEAR-04） |

连续天数与热力图由 `review_log` 按本地日期聚合得到，不单独建表。

### 3.13 card_feedback 卡片纠错（FR-REV-10）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | TEXT | PK | |
| card_id | TEXT | FK→card CASCADE | |
| kind | TEXT | NN | WRONG_ANSWER / UNCLEAR / IRRELEVANT / EDITED / DELETED |
| comment | TEXT | | |
| created_at | INTEGER | NN | |

### 3.14 context_evaluation 情境评估（FR-CTX-03、05、06、08、09）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | INTEGER | PK 自增 | |
| evaluated_at | INTEGER | NN，IDX | |
| trigger | TEXT | NN | PERIODIC / ACTIVITY_CHANGE / MANUAL |
| signals_json | TEXT | NN | 当时的全部信号 |
| learnable | INTEGER | NN | 0 / 1 |
| score | REAL | | 证据加权得分 |
| suggested_cards | INTEGER | | 1–3 |
| reasons_json | TEXT | NN | 依据列表（中文描述 + 权重） |
| blocked_by | TEXT | | IN_CLASS / EXERCISING / QUIET_HOURS / BUDGET_DAILY / BUDGET_INTERVAL / IGNORED_TWICE |

### 3.15 push_record 推送记录（FR-CTX-05、08，FR-WEAR-05）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | TEXT | PK | |
| evaluation_id | INTEGER | FK→context_evaluation SET NULL | |
| card_id | TEXT | FK→card CASCADE | |
| channel | TEXT | NN | NOTIFICATION / DATA_LAYER / RELAY / WEAR_ENGINE |
| sent_at | INTEGER | NN，IDX | 打扰预算按当日计数与最近一次时间判断 |
| outcome | TEXT | | ANSWERED / OPENED / DISMISSED / IGNORED / SNOOZED |
| outcome_at | INTEGER | | |

### 3.16 ai_execution AI 执行记录（FR-AI-04）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | INTEGER | PK 自增 | |
| task_type | TEXT | NN | OCR / ASR / EXTRACT / CARDS / CHAT |
| location | TEXT | NN | ON_DEVICE / CLOUD / UNAVAILABLE |
| reason | TEXT | NN | 路由原因（中文） |
| started_at | INTEGER | NN | |
| duration_ms | INTEGER | | |
| success | INTEGER | NN | |
| error_code | TEXT | | |
| ref_id | TEXT | IDX | 关联的捕获或笔记 id |

### 3.17 pending_task 待办任务队列（FR-AI-05、FR-NOTE-06）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | TEXT | PK | |
| task_type | TEXT | NN | EXTRACT / CARDS |
| payload_json | TEXT | NN | |
| ref_id | TEXT | NN，UQ(task_type, ref_id) | 避免重复入队 |
| attempts | INTEGER | NN，默认 0 | 最多 5 次，指数退避 |
| next_attempt_at | INTEGER | NN，IDX | |
| last_error | TEXT | | |

### 3.18 paired_device 配对设备（FR-SYNC-01、02、04）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | TEXT | PK | |
| name | TEXT | NN | |
| platform | TEXT | NN | WEAR_OS / HUAWEI_LITE / OTHER |
| node_id | TEXT | | Data Layer 节点 id |
| link_type | TEXT | NN | DATA_LAYER / RELAY / WEAR_ENGINE / NOTIFICATION_ONLY |
| pair_code | TEXT | | 中继会话码 |
| public_key | BLOB | | 端到端加密公钥 |
| paired_at / last_seen_at | INTEGER | | |

## 4. 关键查询与性能

| 场景 | 查询 | 索引 | 预期规模 |
| --- | --- | --- | --- |
| 今日队列 | `memory_state JOIN card WHERE due_at <= :endOfDay AND status='ACTIVE' ORDER BY due_at LIMIT :cap` | `idx_memory_due` | 卡片 ≤ 5,000 |
| 正在上的课 | `schedule_slot WHERE weekday=? AND start_min<=? AND end_min>?` | `idx_slot_weekday` | ≤ 100 行 |
| 当日推送计数 | `push_record WHERE sent_at >= :startOfDay` | `sent_at` | 每日 ≤ 10 |
| 图谱加载 | 按课程取知识点与边 | `course_id`、`to_id` | 每课 ≤ 300 节点 |
| 先修链 | 递归 CTE，深度 ≤ 5 | `to_id` | — |
| 热力图 | `review_log GROUP BY date(reviewed_at)` 近 365 天 | `(card_id, reviewed_at)` | 每年 ≤ 50,000 行 |

事务边界：一次自评在同一事务内写 `memory_state` 与 `review_log`；一次整理在同一事务内写笔记、知识点、来源、关系、卡片和初始记忆状态。

## 5. 设置项（DataStore）

| 键 | 默认值 | 需求 |
| --- | --- | --- |
| `privacy.uploadTranscript` / `privacy.uploadOcrText` | true / true | FR-SET-01 |
| `push.enabled` | true | FR-SET-02 |
| `push.dailyLimit` / `push.minIntervalMin` | 3 / 120 | FR-CTX-08 |
| `push.quietHours` | 22:30–07:30、12:00–13:30 | FR-CTX-09 |
| `review.newPerDay` / `review.groupSize` | 10 / 6 | FR-REV-03、14 |
| `streak.freezeTokens` | 1（每周一补充） | FR-REV-09 |

## 6. 迁移与导出

- 版本从 1 开始，每次结构变更提供 `Migration` 与 Room 迁移测试；schema JSON 存放于 `core/data/schemas/`。
- 数据导出（FR-SET-03）：除 `ai_execution`、`context_evaluation` 外全部表导出为 JSON，素材文件打包为 zip；导入时按主键合并。

## 7. 与领域模型的映射

| 领域模型（`core:model`） | 表 | 变更 |
| --- | --- | --- |
| `CaptureSource` | capture | 无 |
| `Note` | note + recognized_text | `recognizedText` 拆为识别记录表 |
| `KnowledgePoint` | knowledge_point + kp_source | `sourceNoteIds` 改由关联表维护 |
| `KnowledgeEdge` / `RelationType` | knowledge_edge | 增加 `confidence` |
| `ReviewCard` / `CardType` | card | 增加 `watchPrompt`、`watchAnswer`、`sourceRef`、`options`、`CLOZE` |
| `Rating` | review_log.rating | 无 |
| FSRS 记忆状态 | memory_state | 无 |
