package com.lingxi.context

/**
 * 碎片时间情境引擎：判断「现在是否适合推送一次微复习」以及「推多少」。
 *
 * 采用可解释的规则打分，而不是黑盒模型，原因有二：一是早期没有足够的标注数据训练分类器；
 * 二是每次判断都能给出依据，便于用户理解和调参，也便于现场演示。后续可用用户的
 * 接受/忽略反馈作为标签，训练轻量分类器替换 [score] 的计算，接口保持不变。
 *
 * 判断分三步：
 * 1. 硬性阻止：上课、驾驶、免打扰、深夜、距上次推送不足冷却时间，直接返回不可学习。
 * 2. 证据打分：从 0.5 的中性分出发，累加每条证据的权重并截断到 0–1。
 * 3. 负荷评估：由心率相对静息心率的升高程度与活动状态估计认知负荷，决定卡片数量。
 *
 * @property threshold 分数达到该值才判定为可学习时刻。
 * @property cooldownMinutes 两次推送之间的最短间隔。
 * @property quietHours 不打扰的时段（小时）。
 */
class LearnableMomentDetector(
    private val threshold: Double = 0.6,
    private val cooldownMinutes: Int = 30,
    private val quietHours: Set<Int> = setOf(23) + (0..6),
) {
    fun evaluate(signals: ContextSignals): ContextAssessment {
        val load = estimateLoad(signals)
        blockReason(signals)?.let { reason ->
            return ContextAssessment(
                learnable = false,
                score = 0.0,
                load = load,
                evidence = emptyList(),
                blockedBy = reason,
            )
        }

        val evidence = collectEvidence(signals, load)
        val score = (NEUTRAL + evidence.sumOf { it.weight }).coerceIn(0.0, 1.0)
        val learnable = score >= threshold && load != CognitiveLoad.HIGH
        return ContextAssessment(
            learnable = learnable,
            score = score,
            load = load,
            evidence = evidence,
            suggestedCards = if (learnable) cardsFor(load) else 0,
        )
    }

    private fun blockReason(s: ContextSignals): String? =
        when {
            s.inClass -> "正在上课"
            s.activity == Activity.IN_VEHICLE -> "可能在驾驶或乘车颠簸中"
            s.activity == Activity.RUNNING -> "正在跑步"
            s.doNotDisturb -> "已开启免打扰"
            s.localHour in quietHours -> "处于夜间休息时段"
            s.minutesSinceLastPrompt != null && s.minutesSinceLastPrompt < cooldownMinutes ->
                "距上次推送仅 ${s.minutesSinceLastPrompt} 分钟"
            else -> null
        }

    private fun collectEvidence(
        s: ContextSignals,
        load: CognitiveLoad,
    ): List<Evidence> =
        buildList {
            if (s.previousActivity == Activity.WALKING && s.activity == Activity.STILL) {
                add(Evidence("刚从走动转为静止，可能在排队或等车", 0.25))
            } else if (s.activity == Activity.STILL) {
                add(Evidence("处于静止状态", 0.05))
            } else if (s.activity == Activity.WALKING) {
                add(Evidence("正在走动，只适合手表上的快速题", -0.05))
            }

            when (s.foregroundApp) {
                AppCategory.SHORT_VIDEO, AppCategory.SOCIAL ->
                    add(Evidence("正在刷短视频或社交应用，适合用复习替代", 0.2))
                AppCategory.STUDY -> add(Evidence("正在使用学习类应用，不打断当前学习", -0.4))
                AppCategory.GAME -> add(Evidence("正在玩游戏，推送容易被忽略", -0.3))
                AppCategory.NAVIGATION -> add(Evidence("正在导航", -0.4))
                AppCategory.OTHER -> if (s.screenOn) add(Evidence("屏幕亮着且没有专注任务", 0.05))
                AppCategory.NONE -> Unit
            }

            when (load) {
                CognitiveLoad.LOW -> add(Evidence("心率接近静息水平，状态放松", 0.1))
                CognitiveLoad.HIGH -> add(Evidence("心率明显升高，暂不增加负担", -0.3))
                CognitiveLoad.MEDIUM -> Unit
            }

            if (s.minutesSinceLastPrompt == null || s.minutesSinceLastPrompt >= LONG_GAP_MINUTES) {
                add(Evidence("已较长时间没有复习", 0.1))
            }
        }

    /** 心率升高 ≥ 25% 视为高负荷，≤ 10% 视为低负荷；没有心率时按活动状态粗略估计。 */
    internal fun estimateLoad(s: ContextSignals): CognitiveLoad {
        val hr = s.heartRateBpm
        val rest = s.restingHeartRateBpm
        if (hr != null && rest != null && rest > 0) {
            val rise = (hr - rest).toDouble() / rest
            return when {
                rise >= HIGH_LOAD_RISE -> CognitiveLoad.HIGH
                rise <= LOW_LOAD_RISE -> CognitiveLoad.LOW
                else -> CognitiveLoad.MEDIUM
            }
        }
        return when (s.activity) {
            Activity.RUNNING -> CognitiveLoad.HIGH
            Activity.WALKING, Activity.IN_VEHICLE, Activity.UNKNOWN -> CognitiveLoad.MEDIUM
            Activity.STILL -> CognitiveLoad.LOW
        }
    }

    private fun cardsFor(load: CognitiveLoad): Int =
        when (load) {
            CognitiveLoad.LOW -> 3
            CognitiveLoad.MEDIUM -> 1
            CognitiveLoad.HIGH -> 0
        }

    private companion object {
        const val NEUTRAL = 0.5
        const val LONG_GAP_MINUTES = 120
        const val HIGH_LOAD_RISE = 0.25
        const val LOW_LOAD_RISE = 0.10
    }
}
