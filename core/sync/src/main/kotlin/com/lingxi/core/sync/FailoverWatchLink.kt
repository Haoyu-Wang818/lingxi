package com.lingxi.core.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

/**
 * 主备双链路：优先走 [primary]，它未连接或发送失败时改走 [fallback]。
 *
 * 两条链路收到的消息合并后按 id 去重，所以同一条消息从两条链路都到达时只交给业务一次。
 * 典型用法是 Data Layer 作主链路、WebSocket 中继作备用链路。
 */
class FailoverWatchLink(
    private val primary: WatchLink,
    private val fallback: WatchLink,
    scope: CoroutineScope,
    dedupCapacity: Int = 512,
) : WatchLink {
    private val seen = RecentIds(dedupCapacity)

    override val transport: String
        get() {
            val primaryUp = primary.state.value == LinkState.CONNECTED
            return if (primaryUp) primary.transport else fallback.transport
        }

    override val state: StateFlow<LinkState> =
        combine(primary.state, fallback.state) { a, b ->
            when {
                a == LinkState.CONNECTED || b == LinkState.CONNECTED -> LinkState.CONNECTED
                a == LinkState.CONNECTING || b == LinkState.CONNECTING -> LinkState.CONNECTING
                else -> LinkState.DISCONNECTED
            }
        }.stateIn(scope, SharingStarted.Eagerly, LinkState.DISCONNECTED)

    override val incoming: Flow<WatchMessage> =
        merge(primary.incoming, fallback.incoming).filter { seen.add(it.id) }

    override suspend fun send(message: WatchMessage): Boolean {
        if (primary.state.value == LinkState.CONNECTED && primary.send(message)) return true
        return fallback.state.value == LinkState.CONNECTED && fallback.send(message)
    }
}

/** 记住最近 [capacity] 个消息 id，超出后淘汰最早的。线程安全。 */
internal class RecentIds(
    private val capacity: Int,
) {
    private val ids = LinkedHashSet<String>()

    /** 首次出现返回 true，重复返回 false。 */
    @Synchronized
    fun add(id: String): Boolean {
        if (!ids.add(id)) return false
        if (ids.size > capacity) ids.remove(ids.first())
        return true
    }
}
