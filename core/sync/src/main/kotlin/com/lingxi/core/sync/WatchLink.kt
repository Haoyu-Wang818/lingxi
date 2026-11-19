package com.lingxi.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 链路连接状态。 */
enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * 手机与手表之间的一条通信链路。
 *
 * 业务代码只依赖这个接口。Android 端提供两种实现：基于 Google Play 服务的 Wear Data Layer，
 * 以及经由网关 `/v1/relay` 的 WebSocket 中继（模拟器和无 Google 服务的手机都能用）。
 */
interface WatchLink {
    /** 传输方式名称，用于日志和界面上的诊断信息，例如 "data-layer"、"relay"。 */
    val transport: String

    val state: StateFlow<LinkState>

    /** 对端发来的消息。 */
    val incoming: Flow<WatchMessage>

    /** 发送一条消息，返回是否已交给底层传输。返回 true 不代表对端已处理。 */
    suspend fun send(message: WatchMessage): Boolean
}
