package com.lingxi.core.sync

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 内存中的一对链路，用于单元测试和桌面端情境回放，不经过任何网络。
 * 消息先编码成 JSON 再解码，保证测试覆盖真实的序列化路径。
 */
class InMemoryWatchLink private constructor(
    override val transport: String,
) : WatchLink {
    private val inbox = Channel<WatchMessage>(Channel.UNLIMITED)
    private lateinit var peer: InMemoryWatchLink

    override val state = MutableStateFlow(LinkState.CONNECTED)

    override val incoming: Flow<WatchMessage> = inbox.receiveAsFlow()

    override suspend fun send(message: WatchMessage): Boolean {
        if (state.value != LinkState.CONNECTED) return false
        val decoded = WatchMessageCodec.decode(WatchMessageCodec.encode(message)) ?: return false
        peer.inbox.send(decoded)
        return true
    }

    /** 模拟断线或恢复。 */
    fun setConnected(connected: Boolean) {
        state.value = if (connected) LinkState.CONNECTED else LinkState.DISCONNECTED
    }

    companion object {
        /** 创建互相连通的一对链路，返回（手机端，手表端）。 */
        fun pair(transport: String = "memory"): Pair<InMemoryWatchLink, InMemoryWatchLink> {
            val phone = InMemoryWatchLink(transport)
            val watch = InMemoryWatchLink(transport)
            phone.peer = watch
            watch.peer = phone
            return phone to watch
        }
    }
}
