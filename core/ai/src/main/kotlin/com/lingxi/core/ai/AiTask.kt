package com.lingxi.core.ai

/**
 * 应用内所有需要调用模型的任务类型。
 *
 * 每种任务声明它能否在端侧完成，以及更适合在哪里运行。路由策略据此做出第一判断，
 * 再结合网络、电量、隐私等运行时条件调整。
 *
 * @property onDeviceCapable 端侧小模型（0.5B–1.5B）能否给出可接受的结果。
 * @property cloudCapable 云端大模型能否处理（嵌入向量等只在端侧计算）。
 * @property heavy 是否为长文本生成类任务，端侧运行会明显耗电发热。
 */
enum class AiTaskType(
    val onDeviceCapable: Boolean,
    val cloudCapable: Boolean,
    val heavy: Boolean,
) {
    /** 计算文本向量，用于本地检索与知识点去重。 */
    EMBEDDING(onDeviceCapable = true, cloudCapable = false, heavy = false),

    /** 短文本分类，例如判断一段笔记属于哪门课。 */
    CLASSIFY(onDeviceCapable = true, cloudCapable = true, heavy = false),

    /** OCR 结果纠错与断句。 */
    OCR_CLEANUP(onDeviceCapable = true, cloudCapable = true, heavy = false),

    /** 生成一两句话的短摘要。 */
    SHORT_SUMMARY(onDeviceCapable = true, cloudCapable = true, heavy = false),

    /** 批改判断题、选择题等简单作答。 */
    ANSWER_GRADING(onDeviceCapable = true, cloudCapable = true, heavy = false),

    /** 从整节课的笔记中抽取知识点与关系。 */
    KNOWLEDGE_EXTRACTION(onDeviceCapable = false, cloudCapable = true, heavy = true),

    /** 根据知识点生成复习题。 */
    QUESTION_GENERATION(onDeviceCapable = true, cloudCapable = true, heavy = true),

    /** 学习智能体的多步规划与工具调用。 */
    AGENT_PLANNING(onDeviceCapable = false, cloudCapable = true, heavy = true),

    // 以下五类与 06-detailed-design.md 3.1 节、`ai_execution.task_type` 一致 [FR-AI-01]。

    /** 板书文字识别：端侧优先，端侧置信度不足且用户允许上传时可上云。 */
    OCR(onDeviceCapable = true, cloudCapable = true, heavy = false),

    /** 课堂录音转写：只在端侧，音频不出手机（NFR-PRIV-01）。 */
    ASR(onDeviceCapable = true, cloudCapable = false, heavy = false),

    /** 识别文本 → 笔记、知识点与关系：只在云端（网关 `/v1/extract`）。 */
    EXTRACT(onDeviceCapable = false, cloudCapable = true, heavy = true),

    /** 知识点 → 复习卡片：只在云端（网关 `/v1/cards`）。 */
    CARD_GEN(onDeviceCapable = false, cloudCapable = true, heavy = true),

    /** 围绕笔记答疑：初赛只在云端，端侧备用留到复赛。 */
    CHAT(onDeviceCapable = false, cloudCapable = true, heavy = true),
}

/**
 * 一次具体的模型调用请求的元信息（不含正文，正文见 [CompletionRequest]）。
 *
 * @property inputChars 输入文本长度，用于判断是否超出端侧模型的上下文能力。
 * @property containsPersonalData 是否含有隐私内容，例如录音转写、个人笔记原文。
 */
data class AiTask(
    val type: AiTaskType,
    val inputChars: Int,
    val containsPersonalData: Boolean = false,
) {
    init {
        require(inputChars >= 0) { "输入长度不能为负" }
    }
}
