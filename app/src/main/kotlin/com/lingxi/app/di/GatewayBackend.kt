package com.lingxi.app.di

import com.lingxi.core.ai.CompletionRequest
import com.lingxi.core.ai.CompletionResult
import com.lingxi.core.ai.ModelBackend
import com.lingxi.core.ai.Route
import com.lingxi.core.network.GatewayApi

/**
 * `InferenceRouter` 的云端路线：经网关 `/v1/chat` 调用通义千问 [FR-AI-02]。
 *
 * 放在 app 而不是 core 模块：它同时依赖 `core:ai` 与 `core:network`，而 core 模块之间只允许依赖 `core:model`。
 * 网关错误原样抛出（[com.lingxi.core.network.GatewayError]），路由器据此切换备用路线并记录错误码。
 */
class GatewayBackend(
    private val gateway: suspend () -> GatewayApi,
) : ModelBackend {
    override val route = Route.CLOUD

    override suspend fun complete(request: CompletionRequest): CompletionResult {
        val reply = gateway().chat(request.systemPrompt, request.userPrompt, request.maxOutputTokens).getOrThrow()
        return CompletionResult(reply.text, reply.model)
    }
}
