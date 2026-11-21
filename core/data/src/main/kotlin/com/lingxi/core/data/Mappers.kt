package com.lingxi.core.data

import com.lingxi.core.data.db.CardEntity
import com.lingxi.core.data.db.CourseEntity
import com.lingxi.core.data.db.KnowledgePointEntity
import com.lingxi.core.data.db.MemoryStateEntity
import com.lingxi.core.data.db.ScheduleSlotEntity
import com.lingxi.core.model.CardType
import com.lingxi.core.model.Course
import com.lingxi.core.model.KnowledgePoint
import com.lingxi.core.model.ReviewCard
import com.lingxi.core.model.ScheduleSlot
import com.lingxi.core.model.SourceRef
import com.lingxi.core.model.repository.CardMemory
import com.lingxi.core.model.repository.MemoryPhase
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

// 领域模型与表行之间的转换。JSON 字段用 Android 自带的 org.json 读写，不额外引入序列化库。

internal fun CourseEntity.toModel() = Course(id, name, teacher, color, examDate, targetRetention, createdAt)

internal fun Course.toEntity(nowMillis: Long) =
    CourseEntity(id, name, teacher, color, examDateMillis, targetRetention, if (createdAtMillis > 0) createdAtMillis else nowMillis)

internal fun ScheduleSlotEntity.toModel() = ScheduleSlot(id, courseId, weekday, startMin, endMin, weekFrom, weekTo, location)

internal fun ScheduleSlot.toEntity() = ScheduleSlotEntity(id, courseId, weekday, startMin, endMin, weekFrom, weekTo, location)

internal fun KnowledgePointEntity.toModel() = KnowledgePoint(id, title, summary, courseId)

internal fun KnowledgePoint.toEntity(nowMillis: Long) =
    KnowledgePointEntity(id, courseId, title, normalizeTitle(title), summary, null, null, nowMillis)

internal fun MemoryStateEntity.toModel() =
    CardMemory(MemoryPhase.valueOf(state), stability, difficulty, dueAt, lastReviewAt, reps, lapses)

internal fun CardMemory.toEntity(cardId: String) =
    MemoryStateEntity(cardId, phase.name, stability, difficulty, dueAtMillis, lastReviewMillis, reps, lapses)

internal fun CardEntity.toModel() =
    ReviewCard(
        id = id,
        knowledgePointId = kpId,
        question = question,
        answer = answer,
        type = CardType.valueOf(type),
        options = optionsJson?.let { optionsFromJson(it).first } ?: emptyList(),
        correctIndex = optionsJson?.let { optionsFromJson(it).second },
        watchPrompt = watchPrompt,
        watchAnswer = watchAnswer,
        sourceRef = sourceRefJson?.let { sourceRefFromJson(it) },
    )

internal fun ReviewCard.toEntity(
    status: String,
    createdAt: Long,
    updatedAt: Long,
) = CardEntity(
    id = id,
    kpId = knowledgePointId,
    type = type.name,
    question = question,
    answer = answer,
    optionsJson = if (options.isEmpty()) null else optionsToJson(options, correctIndex),
    watchPrompt = watchPrompt,
    watchAnswer = watchAnswer,
    sourceRefJson = sourceRef?.let { sourceRefToJson(it) },
    status = status,
    qualityFlags = 0,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** 选项 JSON：`{"options":[…],"correct":0}`（03-database.md 3.10「选项数组，含正确项下标」）。 */
internal fun optionsToJson(
    options: List<String>,
    correct: Int?,
): String =
    JSONObject()
        .put("options", JSONArray(options))
        .apply { if (correct != null) put("correct", correct) }
        .toString()

internal fun optionsFromJson(json: String): Pair<List<String>, Int?> {
    val obj = JSONObject(json)
    val arr = obj.getJSONArray("options")
    val options = (0 until arr.length()).map { arr.getString(it) }
    return options to if (obj.has("correct")) obj.getInt("correct") else null
}

internal fun sourceRefToJson(ref: SourceRef): String =
    JSONObject()
        .put("captureId", ref.captureId)
        .apply {
            ref.page?.let { put("page", it) }
            if (ref.box.isNotEmpty()) put("box", JSONArray(ref.box))
            ref.startMs?.let { put("startMs", it) }
            ref.endMs?.let { put("endMs", it) }
        }.toString()

internal fun sourceRefFromJson(json: String): SourceRef {
    val o = JSONObject(json)
    val box = o.optJSONArray("box")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList()
    return SourceRef(
        captureId = o.getString("captureId"),
        page = if (o.has("page")) o.getInt("page") else null,
        box = box,
        startMs = if (o.has("startMs")) o.getLong("startMs") else null,
        endMs = if (o.has("endMs")) o.getLong("endMs") else null,
    )
}

/** 记忆状态快照 JSON，写入 `review_log.prev_state_json` 用于撤销；新卡（无状态）为 `{}`。 */
internal fun memoryToJson(memory: CardMemory?): String {
    if (memory == null) return "{}"
    return JSONObject()
        .put("state", memory.phase.name)
        .put("dueAt", memory.dueAtMillis)
        .put("reps", memory.reps)
        .put("lapses", memory.lapses)
        .apply {
            memory.stability?.let { put("stability", it) }
            memory.difficulty?.let { put("difficulty", it) }
            memory.lastReviewMillis?.let { put("lastReviewAt", it) }
        }.toString()
}

internal fun memoryFromJson(json: String): CardMemory? {
    val o = JSONObject(json)
    if (!o.has("state")) return null
    return CardMemory(
        phase = MemoryPhase.valueOf(o.getString("state")),
        stability = if (o.has("stability")) o.getDouble("stability") else null,
        difficulty = if (o.has("difficulty")) o.getDouble("difficulty") else null,
        dueAtMillis = o.getLong("dueAt"),
        lastReviewMillis = if (o.has("lastReviewAt")) o.getLong("lastReviewAt") else null,
        reps = o.getInt("reps"),
        lapses = o.getInt("lapses"),
    )
}

/**
 * 知识点名称归一化（去重第一道，FR-KG-02）：全半角统一（NFKC）、去掉所有空白、英文小写。
 * 「泰勒 展开」「泰勒展开」「ＴＡＹＬＯＲ」都会得到相同结果（TC-KG-01）。
 */
fun normalizeTitle(title: String): String =
    Normalizer
        .normalize(title, Normalizer.Form.NFKC)
        .replace(Regex("\\s+"), "")
        .lowercase()
