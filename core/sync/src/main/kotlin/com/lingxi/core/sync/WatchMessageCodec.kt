package com.lingxi.core.sync

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 把 [WatchMessage] 编解码为 JSON 文本，所有传输方式共用同一种格式。
 *
 * 解码失败时返回 null 而不是抛异常：链路上可能出现旧版本或损坏的消息，丢弃即可，不应让接收端崩溃。
 */
object WatchMessageCodec {
    private val json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(message: WatchMessage): String = json.encodeToString(WatchMessage.serializer(), message)

    fun decode(text: String): WatchMessage? =
        try {
            json.decodeFromString(WatchMessage.serializer(), text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
}
