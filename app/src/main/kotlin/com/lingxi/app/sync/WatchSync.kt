package com.lingxi.app.sync

import com.lingxi.app.BuildConfig
import com.lingxi.core.sync.RelayRole
import com.lingxi.core.sync.RelayWatchLink
import com.lingxi.core.sync.WatchLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手机端与手表的链路，进程内唯一。
 *
 * 目前只接了 WebSocket 中继，模拟器和没有 Google 服务的手机都能用；
 * 接入 Data Layer 后改为 FailoverWatchLink(dataLayer, relay)，业务代码不需要改动。
 */
object WatchSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val relay: RelayWatchLink by lazy {
        RelayWatchLink(BuildConfig.GATEWAY_URL, BuildConfig.PAIR_CODE, RelayRole.PHONE).also { it.start(scope) }
    }

    val link: WatchLink get() = relay
}
