package com.lingxi.core.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FailoverWatchLinkTest {
    private val card = WatchMessage.PushCard("m1", "c1", "问题", listOf("对", "错"), "测试")

    @Test
    @DisplayName("主链路连通时消息走主链路")
    fun usesPrimaryWhenConnected() =
        runTest {
            val (phonePrimary, watchPrimary) = InMemoryWatchLink.pair("data-layer")
            val (phoneRelay, _) = InMemoryWatchLink.pair("relay")
            val link = FailoverWatchLink(phonePrimary, phoneRelay, backgroundScope)

            assertTrue(link.send(card))
            assertEquals("data-layer", link.transport)
            assertEquals(card, withTimeout(1000) { watchPrimary.incoming.first() })
        }

    @Test
    @DisplayName("主链路断开时自动改走中继，例如手机没有 Google Play 服务")
    fun fallsBackToRelayWhenPrimaryDown() =
        runTest {
            val (phonePrimary, _) = InMemoryWatchLink.pair("data-layer")
            val (phoneRelay, watchRelay) = InMemoryWatchLink.pair("relay")
            phonePrimary.setConnected(false)
            val link = FailoverWatchLink(phonePrimary, phoneRelay, backgroundScope)

            assertTrue(link.send(card))
            assertEquals("relay", link.transport)
            assertEquals(card, withTimeout(1000) { watchRelay.incoming.first() })
        }

    @Test
    @DisplayName("两条链路都断开时发送失败，交由上层稍后重试")
    fun failsWhenBothLinksDown() =
        runTest {
            val (phonePrimary, _) = InMemoryWatchLink.pair()
            val (phoneRelay, _) = InMemoryWatchLink.pair()
            phonePrimary.setConnected(false)
            phoneRelay.setConnected(false)
            val link = FailoverWatchLink(phonePrimary, phoneRelay, backgroundScope)

            assertFalse(link.send(card))
        }

    @Test
    @DisplayName("同一条消息从两条链路都到达时只交给业务一次")
    fun deduplicatesMessagesArrivingOnBothLinks() =
        runTest {
            val (phonePrimary, watchPrimary) = InMemoryWatchLink.pair()
            val (phoneRelay, watchRelay) = InMemoryWatchLink.pair()
            val link = FailoverWatchLink(phonePrimary, phoneRelay, backgroundScope)
            val result = WatchMessage.ReviewResult("r1", "c1", 3, 0, 0)
            val other = WatchMessage.ReviewResult("r2", "c2", 4, 0, 0)

            watchPrimary.send(result)
            watchRelay.send(result)
            watchRelay.send(other)

            assertEquals(listOf("r1", "r2"), withTimeout(1000) { link.incoming.take(2).toList() }.map { it.id })
        }

    @Test
    @DisplayName("去重表超出容量后淘汰最早的 id")
    fun recentIdsEvictsOldest() {
        val ids = RecentIds(capacity = 2)
        assertTrue(ids.add("a"))
        assertTrue(ids.add("b"))
        assertFalse(ids.add("a"))
        assertTrue(ids.add("c"))
        assertTrue(ids.add("a"))
    }
}
