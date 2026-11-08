package io.github.daepilot.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsUpstreamBuilderTest {
    @Test
    fun `presets round trip`() {
        DnsUpstreamBuilder.presets.forEach { preset ->
            val draft = DnsUpstreamBuilder.parse(preset.uri)
            assertFalse(preset.uri, draft.advanced)
            assertEquals(preset.uri, DnsUpstreamBuilder.build(draft).uri)
        }
    }

    @Test
    fun `builds doh with an ipv4 host and path`() {
        val result = DnsUpstreamBuilder.build(
            UpstreamDraft(
                protocol = UpstreamProtocol.DOH,
                host = "223.5.5.5",
                path = "/dns-query",
            ),
        )
        assertEquals("https://223.5.5.5/dns-query", result.uri)
    }

    @Test
    fun `builds dot with an optional port`() {
        assertEquals(
            "tls://1.1.1.1",
            DnsUpstreamBuilder.build(
                UpstreamDraft(protocol = UpstreamProtocol.DOT, host = "1.1.1.1"),
            ).uri,
        )
        assertEquals(
            "tls://1.1.1.1:8853",
            DnsUpstreamBuilder.build(
                UpstreamDraft(protocol = UpstreamProtocol.DOT, host = "1.1.1.1", port = "8853"),
            ).uri,
        )
    }

    @Test
    fun `builds udp and tcp+udp`() {
        assertEquals(
            "udp://8.8.8.8:53",
            DnsUpstreamBuilder.build(
                UpstreamDraft(protocol = UpstreamProtocol.UDP, host = "8.8.8.8", port = "53"),
            ).uri,
        )
        assertEquals(
            "tcp+udp://8.8.8.8",
            DnsUpstreamBuilder.build(
                UpstreamDraft(protocol = UpstreamProtocol.TCP_UDP, host = "8.8.8.8"),
            ).uri,
        )
    }

    @Test
    fun `brackets ipv6 hosts`() {
        val result = DnsUpstreamBuilder.build(
            UpstreamDraft(
                protocol = UpstreamProtocol.DOH,
                host = "2606:4700:4700::1111",
                path = "/dns-query",
            ),
        )
        assertEquals("https://[2606:4700:4700::1111]/dns-query", result.uri)
    }

    @Test
    fun `rejects invalid input`() {
        assertFalse(DnsUpstreamBuilder.build(UpstreamDraft(host = "bad host")).ok)
        assertFalse(DnsUpstreamBuilder.build(UpstreamDraft(host = "1.1.1.1", port = "70000")).ok)
        assertFalse(
            DnsUpstreamBuilder.build(
                UpstreamDraft(host = "1.1.1.1", path = "dns-query"),
            ).ok,
        )
    }

    @Test
    fun `accepts a pasted url in the host field`() {
        val result = DnsUpstreamBuilder.build(
            UpstreamDraft(host = "https://dns.google/dns-query", path = "/dns-query"),
        )
        assertEquals("https://dns.google/dns-query", result.uri)
    }

    @Test
    fun `keeps unknown schemes in advanced mode`() {
        val uri = "h3://dns.example/dns-query"
        val draft = DnsUpstreamBuilder.parse(uri)
        assertTrue(draft.advanced)
        assertEquals(uri, DnsUpstreamBuilder.build(draft).uri)
        assertEquals("自定义 · $uri", DnsUpstreamBuilder.describe(uri))
    }

    @Test
    fun `describes known upstreams`() {
        assertEquals("阿里 DoH", DnsUpstreamBuilder.describe("https://223.5.5.5/dns-query"))
        assertEquals("DoT · 1.0.0.1", DnsUpstreamBuilder.describe("tls://1.0.0.1"))
        assertEquals(
            "TCP+UDP · 8.8.8.8",
            DnsUpstreamBuilder.describe("tcp+udp://8.8.8.8:53"),
        )
    }

    @Test
    fun `converts unicode host names`() {
        val result = DnsUpstreamBuilder.build(
            UpstreamDraft(host = "例子.公司"),
        )
        assertEquals("https://xn--fsqu00a.xn--55qx5d", result.uri)
    }
}
