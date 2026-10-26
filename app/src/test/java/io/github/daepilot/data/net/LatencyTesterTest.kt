package io.github.daepilot.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LatencyTesterTest {
    @Test
    fun parsesPingTime() {
        val output = """
            PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.
            64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=12.4 ms
        """.trimIndent()
        assertEquals(12, LatencyTester.parsePingMillis(output))
    }

    @Test
    fun parsesSubMillisecondTime() {
        val output = "64 bytes from 127.0.0.1: icmp_seq=1 ttl=64 time<1 ms"
        assertEquals(1, LatencyTester.parsePingMillis(output))
    }

    @Test
    fun returnsNullWhenNoReply() {
        assertNull(LatencyTester.parsePingMillis("1 packets transmitted, 0 received, 100% packet loss"))
    }
}
