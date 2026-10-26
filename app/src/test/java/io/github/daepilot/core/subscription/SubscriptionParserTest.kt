package io.github.daepilot.core.subscription

import io.github.daepilot.core.model.NodeProtocol
import io.github.daepilot.core.model.ShareLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionParserTest {
    private val linkList = """
        ss://YWVzLTI1Ni1nY206cGFzcw==@1.1.1.1:8388#A
        trojan://pw@example.com:443#B
    """.trimIndent()

    @Test
    fun parsesPlainLinkList() {
        val parsed = SubscriptionParser.parse(linkList.toByteArray())
        assertEquals("link-list", parsed.format)
        assertEquals(2, parsed.nodes.size)
        assertEquals(NodeProtocol.SHADOWSOCKS, parsed.nodes[0].protocol)
        assertEquals(NodeProtocol.TROJAN, parsed.nodes[1].protocol)
        assertEquals("A", parsed.nodes[0].name)
        assertEquals("B", parsed.nodes[1].name)
    }

    @Test
    fun decodesBase64EncodedLinkList() {
        val encoded = ShareLink.encodeBase64(linkList.toByteArray())
        val parsed = SubscriptionParser.parse(encoded.toByteArray())
        assertEquals("link-list", parsed.format)
        assertEquals(2, parsed.nodes.size)
    }

    @Test
    fun prefixesSubscriptionName() {
        val parsed = SubscriptionParser.parse(linkList.toByteArray(), "机场")
        assertEquals("机场 · A", parsed.nodes[0].name)
    }

    @Test
    fun deduplicatesIdenticalLinks() {
        val text = "vless://uuid@example.com:443#same\nvless://uuid@example.com:443#same"
        assertEquals(1, SubscriptionParser.parseLinkList(text).nodes.size)
    }

    @Test
    fun parsesClashYamlAndWarnsAboutUnsupportedTypes() {
        val yaml = """
            proxies:
              - name: hk
                type: ss
                server: 1.2.3.4
                port: 8388
                cipher: aes-128-gcm
                password: pw
              - name: wg
                type: wireguard
                server: 5.6.7.8
                port: 51820
            proxy-groups:
              - name: auto
                type: url-test
        """.trimIndent()
        val parsed = SubscriptionParser.parse(yaml.toByteArray(), "Sub")
        assertEquals("clash", parsed.format)
        assertEquals(1, parsed.nodes.size)
        val node = parsed.nodes.first()
        assertEquals("Sub · hk", node.name)
        assertEquals(NodeProtocol.SHADOWSOCKS, node.protocol)
        assertEquals("1.2.3.4", ShareLink.endpoint(node.link)?.host)
        assertTrue(parsed.warnings.any { it.contains("wireguard") })
    }

    @Test
    fun parsesSingBoxOutbounds() {
        val json = """
            {
              "outbounds": [
                {"type": "direct", "tag": "direct"},
                {"type": "selector", "tag": "select", "outbounds": ["t"]},
                {"type": "trojan", "tag": "t", "server": "example.com", "server_port": 443, "password": "pw"},
                {"type": "shadowsocksr", "tag": "ssr", "server": "x", "server_port": 1}
              ]
            }
        """.trimIndent()
        val parsed = SubscriptionParser.parse(json.toByteArray(), "SB")
        assertEquals("sing-box", parsed.format)
        assertEquals(1, parsed.nodes.size)
        assertEquals(NodeProtocol.TROJAN, parsed.nodes.first().protocol)
        assertEquals("SB · t", parsed.nodes.first().name)
        assertTrue(parsed.warnings.any { it.contains("shadowsocksr") })
    }

    @Test
    fun reportsEmptyInput() {
        val parsed = SubscriptionParser.parse(ByteArray(0))
        assertEquals("empty", parsed.format)
        assertTrue(parsed.nodes.isEmpty())
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test
    fun reportsListWithoutLinks() {
        val parsed = SubscriptionParser.parse("not a subscription".toByteArray())
        assertEquals("link-list", parsed.format)
        assertTrue(parsed.nodes.isEmpty())
    }
}
