package com.lingxi.core.ai

/** 模型运行位置。 */
enum class Route {
    ON_DEVICE,
    CLOUD,
}

/** 路由决策结果。附带人类可读的理由，会直接展示在应用的「路由面板」中。 */
sealed interface RouteDecision {
    val reasons: List<String>

    /**
     * 可以执行。
     *
     * @property fallback 主路线失败时可以尝试的备用路线，为 null 表示没有备用。
     */
    data class Run(
        val primary: Route,
        val fallback: Route?,
        override val reasons: List<String>,
    ) : RouteDecision

    /** 当前条件下无法执行，例如断网时请求只能在云端完成的任务。 */
    data class Unavailable(
        override val reasons: List<String>,
    ) : RouteDecision
}

/** 决定一次模型调用在哪里运行。 */
fun interface RoutingPolicy {
    fun decide(
        task: AiTask,
        device: DeviceState,
    ): RouteDecision
}

/**
 * 默认的端云协同路由策略。规则按优先级依次判断，命中即返回：
 *
 * 1. 只有一侧有能力的任务直接走那一侧（断网且只能上云 → 不可用）。
 * 2. 断网 → 端侧。
 * 3. 含隐私内容且端侧能做 → 端侧，不让原文离开手机。
 * 4. 输入超过端侧模型能力 → 云端。
 * 5. 设备发热降频，或低电量未充电时的重任务 → 云端，保护续航与体验。
 * 6. 轻任务 → 端侧（更快、免流量）；重任务 → 云端（质量更好），端侧作为备用。
 *
 * @property onDeviceMaxInputChars 端侧小模型能可靠处理的最大输入长度。
 * @property lowBatteryPercent 低于此电量视为低电量。
 */
class DefaultRoutingPolicy(
    private val onDeviceMaxInputChars: Int = 1_500,
    private val lowBatteryPercent: Int = 20,
) : RoutingPolicy {
    override fun decide(
        task: AiTask,
        device: DeviceState,
    ): RouteDecision {
        val type = task.type
        val online = device.network != NetworkType.NONE

        if (!type.cloudCapable) {
            return RouteDecision.Run(Route.ON_DEVICE, null, listOf("${type.name} 只在端侧计算"))
        }
        if (!type.onDeviceCapable) {
            return if (online) {
                RouteDecision.Run(Route.CLOUD, null, listOf("${type.name} 需要云端大模型"))
            } else {
                RouteDecision.Unavailable(listOf("${type.name} 需要云端大模型，但当前无网络"))
            }
        }

        // 以下任务两侧都能做。
        if (!online) {
            return RouteDecision.Run(Route.ON_DEVICE, null, listOf("当前无网络，使用端侧模型"))
        }
        if (task.containsPersonalData) {
            return RouteDecision.Run(Route.ON_DEVICE, Route.CLOUD, listOf("内容含隐私，优先在端侧处理"))
        }
        if (task.inputChars > onDeviceMaxInputChars) {
            return RouteDecision.Run(
                Route.CLOUD,
                null,
                listOf("输入 ${task.inputChars} 字，超过端侧上限 $onDeviceMaxInputChars 字"),
            )
        }
        if (device.thermalThrottled) {
            return RouteDecision.Run(Route.CLOUD, Route.ON_DEVICE, listOf("设备发热降频，转到云端"))
        }
        val lowBattery = device.batteryPercent < lowBatteryPercent && !device.isCharging
        if (lowBattery && type.heavy) {
            return RouteDecision.Run(
                Route.CLOUD,
                Route.ON_DEVICE,
                listOf("电量 ${device.batteryPercent}%，重任务转到云端以节省电量"),
            )
        }
        return if (type.heavy) {
            RouteDecision.Run(Route.CLOUD, Route.ON_DEVICE, listOf("生成类任务，云端质量更好"))
        } else {
            RouteDecision.Run(Route.ON_DEVICE, Route.CLOUD, listOf("轻量任务，端侧更快且不耗流量"))
        }
    }
}
