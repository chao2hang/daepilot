package io.github.daepilot.core.config

import io.github.daepilot.core.model.DnsConfig
import io.github.daepilot.core.model.GroupPolicy
import io.github.daepilot.core.model.Node
import io.github.daepilot.core.model.NodeProtocol
import io.github.daepilot.core.model.OutboundGroup
import io.github.daepilot.core.model.RenderSpec
import io.github.daepilot.core.model.RoutingRule
import io.github.daepilot.core.model.RoutingTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaeConfigRendererTest {
    private fun node(id: String, name: String, link: String) =
        Node(id = id, name = name, protocol = NodeProtocol.fromScheme(link), link = link)

    private val hk = node("n1", "香港 01", "vless://uuid@1.2.3.4:443?type=tcp#HK")
    private val jp = node("n2", "香港 01", "trojan://pw@example.com:443#JP")

    private fun render(spec: RenderSpec) = DaeConfigRenderer.render(spec)

    @Test
    fun rewritesNodeNamesAndKeepsTagsUnique() {
        val config = render(RenderSpec(nodes = listOf(hk, jp)))
        assertTrue(config.contains("node {\n  id_01: 'vless://uuid@1.2.3.4:443?type=tcp#id_01'\n"))
        assertTrue(config.contains("id_01_n2: 'trojan://pw@example.com:443#id_01_n2'"))
    }

    @Test
    fun selectedNodeUsesFixedPolicyOnItsDisplayName() {
        val config = render(RenderSpec(nodes = listOf(hk, jp), selectedNodeId = "n1"))
        assertTrue(
            config.contains(
                """
                group {
                  proxy {
                    filter: name('id_01')
                    policy: fixed(0)
                  }
                }
                """.trimIndent(),
            ),
        )
        assertTrue(config.contains("fallback: proxy"))
    }

    @Test
    fun withoutSelectionProxyContainsAllNodes() {
        val config = render(RenderSpec(nodes = listOf(hk, jp)))
        assertTrue(
            config.contains(
                """
                group {
                  proxy {
                    policy: min_moving_avg
                  }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun customGroupIsSanitizedAndUsesDisplayNames() {
        val group = OutboundGroup(
            id = "g2",
            name = "direct",
            policy = GroupPolicy.RANDOM,
            nodeIds = listOf("n2"),
        )
        val config = render(RenderSpec(nodes = listOf(hk, jp), groups = listOf(group)))
        assertTrue(
            config.contains(
                "  group_g2 {\n" +
                    "    filter: name('id_01_n2')\n" +
                    "    policy: random\n" +
                    "  }",
            ),
        )
    }

    @Test
    fun routingRulesAreValidatedAndUnknownOutboundsFallBackToDirect() {
        val rules = listOf(
            RoutingRule("r1", "domain(geosite:cn)", "proxy"),
            RoutingRule("r2", "dip(8.8.8.8)", "ghost"),
            RoutingRule("r3", "domain(a.com) -> block", "proxy"),
            RoutingRule("r4", "domain(b.com)\nblock {}", "proxy"),
        )
        val config = render(RenderSpec(nodes = listOf(hk), customRules = rules))
        assertTrue(config.contains("domain(geosite:cn) -> proxy"))
        assertTrue(config.contains("dip(8.8.8.8) -> direct"))
        assertFalse(config.contains("domain(a.com)"))
        assertFalse(config.contains("domain(b.com)"))
    }

    @Test
    fun nodeEndpointsAreBypassedBeforeRouting() {
        val v6 = node("n3", "v6", "vless://uuid@[2001:db8::1]:443#V6")
        val domain = node("n4", "plain", "trojan://pw@example.com#P")
        val config = render(RenderSpec(nodes = listOf(hk, v6, domain)))
        assertTrue(config.contains("dip(1.2.3.4) && dport(443) -> must_direct"))
        assertTrue(config.contains("dip(\"2001:db8::1\") && dport(443) -> must_direct"))
        assertTrue(config.contains("domain(example.com) -> must_direct"))
    }

    @Test
    fun bypassRulesAreDeduplicated() {
        val rules = DaeConfigRenderer.buildNodeBypassRules(listOf(hk, hk))
        assertEquals(listOf("dip(1.2.3.4) && dport(443)"), rules)
    }

    @Test
    fun bypassPackageListIsSanitized() {
        val config = render(
            RenderSpec(
                nodes = listOf(hk),
                bypassPackages = listOf(" com.example.app ", "bad name", "com.example.app"),
            ),
        )
        assertTrue(config.contains("pname(com.example.app) -> must_direct"))
        assertFalse(config.contains("bad name"))
    }

    @Test
    fun whitelistAndDirectTemplatesFallBackToDirect() {
        val whitelist = render(RenderSpec(nodes = listOf(hk), template = RoutingTemplate.WHITELIST))
        assertTrue(whitelist.contains("fallback: direct"))
        assertFalse(whitelist.contains("domain(geosite:cn) -> direct"))

        val direct = render(RenderSpec(nodes = listOf(hk), template = RoutingTemplate.DIRECT))
        assertTrue(direct.contains("fallback: direct"))
    }

    @Test
    fun bypassMainlandTemplateAddsCnRules() {
        val config = render(RenderSpec(nodes = listOf(hk), template = RoutingTemplate.BYPASS_MAINLAND))
        assertTrue(config.contains("domain(geosite:cn) -> direct"))
        assertTrue(config.contains("dip(geoip:cn) -> direct"))
    }

    @Test
    fun dnsUsesRequestAndResponseSections() {
        val config = render(RenderSpec(nodes = listOf(hk)))
        assertTrue(config.contains("    request {\n      qname(geosite:cn) -> alidns\n      fallback: googledns\n    }"))
        assertTrue(config.contains("    response {\n      upstream(googledns) -> accept\n"))

        val noCn = render(RenderSpec(nodes = listOf(hk), dns = DnsConfig(useDomesticForCn = false)))
        assertFalse(noCn.contains("qname(geosite:cn) -> alidns"))
    }

    @Test
    fun singleQuotesInLinksAreEscaped() {
        val tricky = node("n5", "q", "vless://uuid@example.com:443?type=ws&path=%2Fa'b#Q")
        val config = render(RenderSpec(nodes = listOf(tricky)))
        assertTrue(config.contains("path=%2Fa\\'b"))
    }

    @Test
    fun sanitizesIdentifiersToAscii() {
        assertEquals("id_01", DaeConfigRenderer.sanitizeIdentifier("香港 01"))
        assertEquals("A", DaeConfigRenderer.sanitizeIdentifier("节点 A"))
        assertEquals("", DaeConfigRenderer.sanitizeIdentifier("节点"))
        assertEquals("id_123", DaeConfigRenderer.sanitizeIdentifier("123"))
    }

    @Test
    fun renderedConfigPassesStructuralCheckAndSetsDaeSocketMark() {
        val config = render(RenderSpec(nodes = listOf(hk, jp), selectedNodeId = "n1"))
        assertTrue(DaeConfigValidator.quickCheckConfig(config).ok)
        assertTrue(config.contains("subscription {\n}"))
        assertTrue(config.contains("so_mark_from_dae: 0x100"))
    }

    @Test
    fun dialsByIpUnlessAnExplicitModeIsGiven() {
        assertTrue(render(RenderSpec(nodes = listOf(hk))).contains("  dial_mode: ip\n"))
        assertTrue(
            render(RenderSpec(nodes = listOf(hk), dialMode = "domain"))
                .contains("  dial_mode: domain\n"),
        )
        assertTrue(
            render(RenderSpec(nodes = listOf(hk), dialMode = "bogus"))
                .contains("  dial_mode: ip\n"),
        )
    }

    @Test
    fun emptyNodeListRendersWithoutGroups() {
        val config = render(RenderSpec(nodes = emptyList()))
        assertTrue(config.contains("node {\n}"))
        assertTrue(config.contains("group {\n}"))
        assertTrue(config.contains("fallback: direct"))
    }

    @Test
    fun rewritesHy2AliasToDaeScheme() {
        val hy2 = node("n3", "HY", "hy2://pw@example.com:8443?sni=example.com#HY")
        val config = render(RenderSpec(nodes = listOf(hy2)))
        assertTrue(config.contains("hysteria2://pw@example.com:8443?sni=example.com#HY"))
        assertFalse(config.contains("'hy2://"))
    }
}
