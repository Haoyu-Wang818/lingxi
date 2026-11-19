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
 */
data class DeviceState(
    val network: NetworkType,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val thermalThrottled: Boolean = false,
) {
    init {
        require(batteryPercent in 0..100) { "电量必须在 0 到 100 之间" }
    }
}

/** 提供实时设备状态。Android 端由系统服务实现，测试中用固定值替代。 */
fun interface DeviceStateProvider {
    fun current(): DeviceState
}
