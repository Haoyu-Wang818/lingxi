package com.lingxi.core.network

import com.lingxi.core.model.ErrorCoded

/**
 * 网关错误，与 04-api.md 1.2 节的错误码一一对应 [FR-GW-06]。
 *
 * [retryable] 决定是否进入待办队列按退避重试 [FR-AI-05]。
 */
sealed class GatewayError(
    override val code: String,
    val retryable: Boolean,
    message: String,
) : Exception(message),
    ErrorCoded {
    /** 400 参数非法，不重试。 */
    class BadRequest(
        message: String,
    ) : GatewayError("BAD_REQUEST", retryable = false, message)

    /** 401 设备令牌无效，提示重新配置网关。 */
    class Unauthorized(
        message: String,
    ) : GatewayError("UNAUTHORIZED", retryable = false, message)

    /** 413 文本超长，客户端分段后重试。 */
    class TextTooLong(
        message: String,
    ) : GatewayError("TEXT_TOO_LONG", retryable = false, message)

    /** 422 大模型输出重试后仍不合规，笔记标记「待整理」。 */
    class InvalidOutput(
        message: String,
    ) : GatewayError("LLM_INVALID_OUTPUT", retryable = true, message)

    /** 429 超过限流，按 [retryAfterSeconds] 退避。 */
    class RateLimited(
        val retryAfterSeconds: Long?,
        message: String,
    ) : GatewayError("RATE_LIMITED", retryable = true, message)

    /** 502 大模型服务错误或超时。 */
    class Upstream(
        message: String,
    ) : GatewayError("LLM_UPSTREAM", retryable = true, message)

    /** 503 网关未配置 API Key，提示「云端功能未启用」。 */
    class NotConfigured(
        message: String,
    ) : GatewayError("LLM_NOT_CONFIGURED", retryable = false, message)

    /** 网络不可达或超时，未拿到网关响应。 */
    class Network(
        cause: Throwable,
    ) : GatewayError("NETWORK", retryable = true, cause.message ?: "网络错误")
}
