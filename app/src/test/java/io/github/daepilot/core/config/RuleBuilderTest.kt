package io.github.daepilot.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBuilderTest {
    @Test
    fun `builds suffix rules from hosts and urls`() {
        val result = RuleBuilder.build(
            RuleDraft(
                kind = RuleKind.DOMAIN,
                domain = "openai.com, https://chat.openai.com/",
            ),
        )
        assertEquals("domain(suffix: openai.com, suffix: chat.openai.com)", result.expression)
    }

    @Test
    fun `builds full and keyword domain rules`() {
        assertEquals(
            "domain(full: dns.google)",
            RuleBuilder.build(
                RuleDraft(RuleKind.DOMAIN, DomainMatch.FULL, domain = "dns.google"),
            ).expression,
        )
        assertEquals(
            "domain(keyword: facebook, keyword: instagram)",
            RuleBuilder.build(
                RuleDraft(RuleKind.DOMAIN, DomainMatch.KEYWORD, domain = "facebook instagram"),
            ).expression,
        )
    }

    @Test
    fun `builds a quoted regex rule`() {
        val result = RuleBuilder.build(
            RuleDraft(RuleKind.DOMAIN, DomainMatch.REGEX, domain = """\.goo.*\.com$"""),
        )
        assertEquals("""domain(regex: '\.goo.*\.com$')""", result.expression)
    }

    @Test
    fun `rejects ips in the domain editor`() {
        val result = RuleBuilder.build(RuleDraft(RuleKind.DOMAIN, domain = "1.2.3.4"))
        assertTrue(!result.ok)
        assertTrue(result.error!!.contains("IP"))
    }

    @Test
    fun `builds ip and cidr rules with quoted ipv6`() {
        val result = RuleBuilder.build(
            RuleDraft(RuleKind.IP, ip = "1.1.1.1, 10.0.0.0/8\n2001:db8::/32"),
        )
        assertEquals("dip(1.1.1.1, 10.0.0.0/8, '2001:db8::/32')", result.expression)
    }

    @Test
    fun `rejects invalid ip and prefix length`() {
        assertNotNull(RuleBuilder.build(RuleDraft(RuleKind.IP, ip = "999.1.1.1")).error)
        assertNotNull(RuleBuilder.build(RuleDraft(RuleKind.IP, ip = "10.0.0.0/33")).error)
    }

    @Test
    fun `builds geosite and geoip presets`() {
        assertEquals(
            "domain(geosite:openai, geosite:google)",
            RuleBuilder.build(
                RuleDraft(RuleKind.PRESET, preset = "openai, google"),
            ).expression,
        )
        assertEquals(
            "dip(geoip:cn, geoip:private)",
            RuleBuilder.build(
                RuleDraft(
                    kind = RuleKind.PRESET,
                    presetSource = PresetSource.GEOIP,
                    preset = "cn private",
                ),
            ).expression,
        )
    }

    @Test
    fun `rejects a preset that already carries its prefix`() {
        val result = RuleBuilder.build(RuleDraft(RuleKind.PRESET, preset = "geosite:openai"))
        assertTrue(!result.ok)
        assertTrue(result.error!!.contains("前缀"))
    }

    @Test
    fun `builds single ports and ranges`() {
        assertEquals(
            "dport(443, 80, 1000-2000)",
            RuleBuilder.build(RuleDraft(RuleKind.PORT, ports = "443, 80 1000-2000")).expression,
        )
        assertNotNull(RuleBuilder.build(RuleDraft(RuleKind.PORT, ports = "70000")).error)
        assertNotNull(RuleBuilder.build(RuleDraft(RuleKind.PORT, ports = "2000-1000")).error)
    }

    @Test
    fun `advanced expressions are validated`() {
        assertEquals(
            "domain(geosite:openai) && !dport(443)",
            RuleBuilder.build(
                RuleDraft(RuleKind.ADVANCED, advanced = "domain(geosite:openai) && !dport(443)"),
            ).expression,
        )
        assertNotNull(
            RuleBuilder.build(RuleDraft(RuleKind.ADVANCED, advanced = "dport(443) -> block")).error,
        )
    }

    @Test
    fun `round trips generated rules back into editor state`() {
        val expressions = listOf(
            "domain(suffix: openai.com)",
            "domain(full: dns.google)",
            "domain(keyword: google)",
            "domain(regex: '\\.goo.*\\.com$')",
            "dip(1.1.1.1, '2001:db8::/32')",
            "domain(geosite:openai)",
            "dip(geoip:cn)",
            "dport(443, 1000-2000)",
        )
        expressions.forEach { expression ->
            val draft = RuleBuilder.parse(expression)
            assertNotNull("unparsed: $expression", draft)
            assertEquals(expression, RuleBuilder.build(draft!!).expression)
        }
    }

    @Test
    fun `hand written expressions stay in advanced mode`() {
        assertNull(RuleBuilder.parse("domain(geosite:openai) && !dport(443)"))
        assertNull(RuleBuilder.parse("l4proto(tcp)"))
        assertNull(RuleBuilder.parse("pname(curl)"))
    }

    @Test
    fun `describes known rules for the list`() {
        assertEquals("域名 · openai.com", RuleBuilder.describe("domain(suffix: openai.com)"))
        assertEquals("IP / 网段 · 1.1.1.1", RuleBuilder.describe("dip(1.1.1.1)"))
        assertEquals("域名清单 · openai", RuleBuilder.describe("domain(geosite:openai)"))
        assertEquals("端口 · 443", RuleBuilder.describe("dport(443)"))
        assertEquals(
            "domain(geosite:openai) && !dport(443)",
            RuleBuilder.describe("domain(geosite:openai) && !dport(443)"),
        )
    }

    @Test
    fun `converts unicode domains to punycode`() {
        assertEquals(
            "domain(suffix: xn--fsqu00a.xn--55qx5d)",
            RuleBuilder.build(RuleDraft(RuleKind.DOMAIN, domain = "例子.公司")).expression,
        )
    }

    @Test
    fun `points prefixed input at the right editor type`() {
        assertTrue(
            RuleBuilder.build(RuleDraft(RuleKind.DOMAIN, domain = "geosite:openai"))
                .error!!.contains("内置清单"),
        )
        assertTrue(
            RuleBuilder.build(RuleDraft(RuleKind.IP, ip = "geoip:cn"))
                .error!!.contains("内置清单"),
        )
    }

    @Test
    fun `keyword input with invalid characters is rejected`() {
        assertNotNull(
            RuleBuilder.build(RuleDraft(RuleKind.DOMAIN, DomainMatch.KEYWORD, domain = "foo(bar)")).error,
        )
    }
}
