package com.lingxi.core.ai

/** 当前网络状况。 */
enum class NetworkType {
    NONE,

    /** 移动数据等按流量计费的网络。 */
    METERED,

    /** Wi-Fi 等不计流量的网络。 */
    UNMETERED,
}

/**
 * 做路由决策时的设备状态快照。
 *
 * @property batteryPercent 电量百分比，0–100。
 * @property thermalThrottled 系统是否已因发热降频（Android PowerManager 的热状态 ≥ SEVERE）。
 * @property allowCloudFor 用户允许含隐私内容上传云端的任务类型，由隐私设置换算而来 [FR-SET-01]。
 */
data class DeviceState(
    val network: NetworkType,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val thermalThrottled: Boolean = false,
    val allowCloudFor: Set<AiTaskType> = AiTaskType.entries.toSet(),
) {
    init {
        require(batteryPercent in 0..100) { "电量必须在 0 到 100 之间" }
    }
}

/** 提供实时设备状态。Android 端由系统服务实现，测试中用固定值替代。 */
fun interface DeviceStateProvider {
    fun current(): DeviceState
}

/**
 * 用户的隐私设置（03-database.md 第 5 节 `privacy.*`）[FR-SET-01]。
 *
 * @property uploadOcrText 是否允许把板书识别文本上传云端。
 * @property uploadTranscript 是否允许把录音转写文本上传云端（音频本身永远不上传）。
 */
data class PrivacySettings(
    val uploadOcrText: Boolean = true,
    val uploadTranscript: Boolean = true,
) {
    /**
     * 换算成允许上云的任务类型：OCR 类只看识别文本开关；抽取、出题、答疑的输入来自识别文本或转写，
     * 两个开关都关闭时不再上云（任一开启时由调用方只上传被允许的那部分文本）。
     */
    fun allowCloudFor(): Set<AiTaskType> {
        val anyText = uploadOcrText || uploadTranscript
        return AiTaskType.entries
            .filter { type ->
                when (type) {
                    AiTaskType.OCR, AiTaskType.OCR_CLEANUP -> uploadOcrText
                    AiTaskType.ASR -> uploadTranscript
                    AiTaskType.EXTRACT, AiTaskType.CARD_GEN, AiTaskType.CHAT,
                    AiTaskType.KNOWLEDGE_EXTRACTION, AiTaskType.QUESTION_GENERATION,
                    -> anyText
                    else -> true
                }
            }.toSet()
    }
}
