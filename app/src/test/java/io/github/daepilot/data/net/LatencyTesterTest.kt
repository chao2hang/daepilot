package io.github.daepilot.data.net

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun probePacketIsAQuicLongHeaderWithAnUnknownVersion() {
        val packet = LatencyTester.QUIC_PROBE_PACKET
        assertEquals(1232, packet.size)
        // Long header flag plus the fixed bit.
        assertEquals(0xC0, packet[0].toInt() and 0xFF)
        assertContentEquals(byteArrayOf(0x0a, 0x0a, 0x0a, 0x0a), packet.copyOfRange(1, 5))
        // A >= 8 byte destination id is what lets the server answer at all.
        assertTrue(packet[5].toInt() >= 8)
    }

    @Test
    fun acceptsVersionNegotiationReply() {
        // Captured from a live hysteria2 node: long header, version 0, then ids.
        val reply = "e60000000014cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd".chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        assertTrue(LatencyTester.isVersionNegotiation(reply, reply.size))
    }

    @Test
    fun rejectsOtherPackets() {
        val initial = byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00, 0x01, 20, 1)
        assertFalse(LatencyTester.isVersionNegotiation(initial, initial.size))
        val short = byteArrayOf(0x40, 0, 0, 0, 0, 0, 0)
        assertFalse(LatencyTester.isVersionNegotiation(short, 4))
        assertFalse(LatencyTester.isVersionNegotiation(ByteArray(40), 40))
    }
}
