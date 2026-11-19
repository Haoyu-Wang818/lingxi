package com.lingxi.core.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RelayWatchLinkTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val received = LinkedBlockingQueue<String>()

    @Volatile
    private var serverSocket: WebSocket? = null

    private val serverListener =
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                serverSocket = webSocket
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                received.put(text)
            }
        }

    @BeforeEach
    fun setUp() {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(serverListener).build())
        server.start()
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun connectedLink(): RelayWatchLink =
        RelayWatchLink(server.url("/").toString(), "demo1", RelayRole.PHONE, token = "t0k").also { link ->
            link.start(scope)
            runBlocking { withTimeout(5_000) { link.state.first { it == LinkState.CONNECTED } } }
        }

    @Test
    @DisplayName("按配对码和角色拼出中继地址，并带上令牌")
    fun connectsToRelayPathWithToken() {
        connectedLink()
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/relay/demo1/phone?token=t0k", request.target)
    }

    @Test
    @DisplayName("发送的消息以 JSON 文本到达中继")
    fun sendsEncodedMessage() {
        val link = connectedLink()
        val result = WatchMessage.ReviewResult("r1", "c1", 3, 0, 0)
        runBlocking { link.send(result) }
        assertEquals(result, WatchMessageCodec.decode(received.poll(5, TimeUnit.SECONDS)!!))
    }

    @Test
    @DisplayName("中继转来的消息交给业务，中继自身的错误只记录不转交")
    fun deliversMessagesAndRecordsRelayErrors() {
        val link = connectedLink()
        val card = WatchMessage.PushCard("m1", "c1", "问题", listOf("对", "错"), "测试")
        serverSocket!!.send("""{"type":"relay_error","detail":"peer offline"}""")
        serverSocket!!.send(WatchMessageCodec.encode(card))

        assertEquals(card, runBlocking { withTimeout(5_000) { link.incoming.first() } })
        assertEquals("peer offline", link.lastRelayError)
    }
}
