package com.lingxi.sync.datalayer

import android.content.Context
import com.google.android.gms.wearable.Wearable
import com.lingxi.core.sync.LinkState
import com.lingxi.core.sync.WatchLink
import com.lingxi.core.sync.WatchMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 基于 Wear Data Layer 的手机与手表通信主链路 [FR-SYNC-02]、ADR 0007。
 *
 * 手机端与手表端共用本类。消息体与中继链路相同（[com.lingxi.core.sync.WatchMessageCodec] 编码的
 * UTF-8 JSON），经 `MessageClient` 路径 [MESSAGE_PATH] 收发；今日待复习数走 `DataClient`
 * 数据项 [TODAY_PATH]（见 04-api.md 2.1 节）。
 *
 * 本模块是全工程唯一依赖 Google Play 服务的地方；设备没有 Play 服务时由
 * [com.lingxi.core.sync.FailoverWatchLink] 自动切到中继链路。
 *
 * 骨架阶段：尚未接入发送与监听，[send] 恒返回 false、状态保持 [LinkState.DISCONNECTED]，
 * 因此即便被装配进 `FailoverWatchLink` 也会直接走备链路，不会影响现有功能。I5 迭代实现。
 */
class DataLayerWatchLink(
    context: Context,
) : WatchLink {
    private val appContext = context.applicationContext

    /** 延迟创建，避免在没有 Play 服务的设备上一构造就触发服务连接。 */
    @Suppress("unused")
    private val messageClient by lazy { Wearable.getMessageClient(appContext) }

    private val mutableState = MutableStateFlow(LinkState.DISCONNECTED)
    private val mutableIncoming = MutableSharedFlow<WatchMessage>(extraBufferCapacity = 16)

    override val transport: String = "data-layer"

    override val state: StateFlow<LinkState> = mutableState.asStateFlow()

    override val incoming: Flow<WatchMessage> = mutableIncoming.asSharedFlow()

    override suspend fun send(message: WatchMessage): Boolean {
        // I5 实现：通过 NodeClient 查找已连接节点，再用 messageClient.sendMessage(node, MESSAGE_PATH, bytes) 发送。
        return false
    }

    companion object {
        /** 双向消息路径。 */
        const val MESSAGE_PATH = "/lingxi/v1/msg"

        /** 今日摘要数据项路径。 */
        const val TODAY_PATH = "/lingxi/v1/today"
    }
}
