package com.lingxi.core.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resume

/** 中继里的角色，与网关 `/v1/relay/{pair_code}/{role}` 的 role 一致。 */
enum class RelayRole(
    val path: String,
) {
    PHONE("phone"),
    WATCH("watch"),
}

/**
 * 经由网关 WebSocket 中继的链路，不依赖 Google Play 服务。
 *
 * 断线后按指数退避自动重连（1 秒起，最长 30 秒）。网关返回的 `relay_error`（例如对端不在线）
 * 不会交给业务，只记录在 [lastRelayError] 里供诊断界面显示。
 *
 * @param gatewayUrl 网关地址，例如模拟器里访问开发机用 `http://10.0.2.2:8000`。
 */
class RelayWatchLink(
    gatewayUrl: String,
    pairCode: String,
    role: RelayRole,
    token: String = "",
    private val client: OkHttpClient = OkHttpClient(),
) : WatchLink {
    private val url: String =
        gatewayUrl
            .toHttpUrl()
            .newBuilder()
            .addPathSegments("v1/relay")
            .addPathSegment(pairCode)
            .addPathSegment(role.path)
            .apply { if (token.isNotEmpty()) addQueryParameter("token", token) }
            .build()
            .toString()

    private val inbox = Channel<WatchMessage>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(LinkState.DISCONNECTED)
    private var socket: WebSocket? = null
    private var loop: Job? = null

    override val transport: String = "relay"
    override val state: StateFlow<LinkState> = _state.asStateFlow()
    override val incoming: Flow<WatchMessage> = inbox.receiveAsFlow()

    /** 网关最近一次报告的错误，例如 "peer offline"。 */
    @Volatile
    var lastRelayError: String? = null
        private set

    /** 在 [scope] 中开始连接并保持重连，scope 取消时断开。重复调用无效。 */
    fun start(scope: CoroutineScope) {
        if (loop?.isActive == true) return
        loop =
            scope.launch {
                var backoffMs = INITIAL_BACKOFF_MS
                try {
                    while (true) {
                        _state.value = LinkState.CONNECTING
                        val connectedOnce = runOneConnection()
                        _state.value = LinkState.DISCONNECTED
                        if (connectedOnce) backoffMs = INITIAL_BACKOFF_MS
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    }
                } finally {
                    socket?.close(NORMAL_CLOSURE, null)
                    socket = null
                    _state.value = LinkState.DISCONNECTED
                }
            }
    }

    override suspend fun send(message: WatchMessage): Boolean {
        val ws = socket ?: return false
        if (_state.value != LinkState.CONNECTED) return false
        return ws.send(WatchMessageCodec.encode(message))
    }

    /** 建立一次连接并等到它断开，返回期间是否成功连上过。 */
    private suspend fun runOneConnection(): Boolean =
        suspendCancellableCoroutine { cont ->
            var opened = false
            val ws =
                client.newWebSocket(
                    Request.Builder().url(url).build(),
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            opened = true
                            _state.value = LinkState.CONNECTED
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            val relayError = RELAY_ERROR.find(text)
                            if (relayError != null) {
                                lastRelayError = relayError.groupValues[1]
                                return
                            }
                            WatchMessageCodec.decode(text)?.let { inbox.trySend(it) }
                        }

                        override fun onClosing(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            webSocket.close(NORMAL_CLOSURE, null)
                        }

                        override fun onClosed(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            if (cont.isActive) cont.resume(opened)
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            if (cont.isActive) cont.resume(opened)
                        }
                    },
                )
            socket = ws
            cont.invokeOnCancellation { ws.cancel() }
        }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        val RELAY_ERROR = Regex(""""type"\s*:\s*"relay_error".*?"detail"\s*:\s*"([^"]*)"""")
    }
}
