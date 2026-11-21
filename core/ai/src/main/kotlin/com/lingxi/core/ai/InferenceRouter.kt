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
 * @param observer 每次调用结束后收到完整记录，应用用它写入 `ai_execution` 表 [FR-AI-04]。
 */
class InferenceRouter(
    private val policy: RoutingPolicy,
    backends: List<ModelBackend>,
    private val deviceState: DeviceStateProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    private val observer: RouteObserver? = null,
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
        val result = route(task, request, start)
        observer?.let {
            // 记录失败不能影响业务结果。
            runCatching { it.onRouted(result, start) }.exceptionOrNull()?.let { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        return result
    }

    private suspend fun route(
        task: AiTask,
        request: CompletionRequest,
        start: Long,
    ): RoutedResult {
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

/** 推理调用的观察者，用于持久化执行记录 [FR-AI-04]。 */
fun interface RouteObserver {
    suspend fun onRouted(
        result: RoutedResult,
        startedAtMillis: Long,
    )
}

/**
 * 转换为 `ai_execution` 表的一行：位置、原因（含是否回退）、耗时、结果与错误码；不含任何用户内容。
 * 错误实现了 [com.lingxi.core.model.ErrorCoded]（如网关错误）时记录其错误码，否则记录异常类名。
 */
fun RoutedResult.toExecution(
    startedAtMillis: Long,
    refId: String? = null,
): com.lingxi.core.model.repository.AiExecution {
    val location =
        when {
            finalRoute != null -> finalRoute.name
            decision is RouteDecision.Run -> decision.primary.name
            else -> "UNAVAILABLE"
        }
    val reason = (decision.reasons + listOfNotNull("主路线失败，已切换备用路线".takeIf { usedFallback })).joinToString("；")
    val error = result.exceptionOrNull()
    val code = (error as? com.lingxi.core.model.ErrorCoded)?.code ?: error?.let { it::class.simpleName ?: "ERROR" }
    return com.lingxi.core.model.repository.AiExecution(
        taskType = task.type.name,
        location = location,
        reason = reason,
        startedAtMillis = startedAtMillis,
        durationMs = latencyMs,
        success = result.isSuccess,
        errorCode = code,
        refId = refId,
    )
}
