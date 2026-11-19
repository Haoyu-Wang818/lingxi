package com.lingxi.core.ai

/** 发给模型的正文。 */
data class CompletionRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val maxOutputTokens: Int = 512,
)

/** 模型返回。 */
data class CompletionResult(
    val text: String,
    val modelName: String,
)

/** 一个可以执行推理的模型后端，端侧（MNN-LLM）与云端（网关）各有一个实现。 */
interface ModelBackend {
    val route: Route

    suspend fun complete(request: CompletionRequest): CompletionResult
}

/**
 * 一次路由调用的完整记录，用于界面上的「路由面板」和性能统计。
 *
 * @property finalRoute 实际产出结果的路线；失败时为 null。
 * @property usedFallback 是否因主路线失败而改用备用路线。
 */
data class RoutedResult(
    val task: AiTask,
    val decision: RouteDecision,
    val finalRoute: Route?,
    val usedFallback: Boolean,
    val latencyMs: Long,
    val result: Result<CompletionResult>,
)

/** 路由失败时抛出的异常。 */
class RouteUnavailableException(
    message: String,
) : Exception(message)

/**
 * 端云协同推理入口：所有业务模块都通过它调用模型，而不是直接调用某个后端。
 *
 * 它先向 [policy] 询问路线，执行主路线；主路线抛出异常且存在备用路线时，自动重试备用路线。
 *
 * @param clock 返回当前毫秒时间，测试时可替换。
 */
class InferenceRouter(
    private val policy: RoutingPolicy,
    backends: List<ModelBackend>,
    private val deviceState: DeviceStateProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val backendByRoute: Map<Route, ModelBackend> = backends.associateBy { it.route }

    init {
        require(backendByRoute.size == backends.size) { "同一路线只能注册一个后端" }
    }

    suspend fun run(
        task: AiTask,
        request: CompletionRequest,
    ): RoutedResult {
        val start = clock()
        val decision = policy.decide(task, deviceState.current())

        if (decision is RouteDecision.Unavailable) {
            val error = RouteUnavailableException(decision.reasons.joinToString("；"))
            return RoutedResult(task, decision, null, false, clock() - start, Result.failure(error))
        }
        decision as RouteDecision.Run

        val primary = attempt(decision.primary, request)
        if (primary.isSuccess || decision.fallback == null) {
            val route = decision.primary.takeIf { primary.isSuccess }
            return RoutedResult(task, decision, route, false, clock() - start, primary)
        }

        val fallback = attempt(decision.fallback, request)
        val route = decision.fallback.takeIf { fallback.isSuccess }
        return RoutedResult(task, decision, route, true, clock() - start, fallback)
    }

    private suspend fun attempt(
        route: Route,
        request: CompletionRequest,
    ): Result<CompletionResult> {
        val backend = backendByRoute[route] ?: return Result.failure(RouteUnavailableException("未注册 $route 后端"))
        return try {
            Result.success(backend.complete(request))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Result.failure(e)
        }
    }
}
