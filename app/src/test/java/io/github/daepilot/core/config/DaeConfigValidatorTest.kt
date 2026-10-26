package io.github.daepilot.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaeConfigValidatorTest {
    @Test
    fun acceptsSimpleRoutingRule() {
        assertTrue(DaeConfigValidator.validateRule("domain(geosite:cn)").ok)
        assertTrue(DaeConfigValidator.validateRule("dip(geoip:cn) && dport(443)").ok)
        assertTrue(DaeConfigValidator.validateRule("pname(com.example.app)").ok)
    }

    @Test
    fun rejectsEmptyRule() {
        assertFalse(DaeConfigValidator.validateRule("  ").ok)
    }

    @Test
    fun rejectsArrowAndBraces() {
        assertFalse(DaeConfigValidator.validateRule("domain(a.com) -> block").ok)
        assertFalse(DaeConfigValidator.validateRule("domain(a.com) { }").ok)
        assertFalse(DaeConfigValidator.validateRule("domain(a.com)\nblock").ok)
    }

    @Test
    fun rejectsUnknownFunction() {
        val result = DaeConfigValidator.validateRule("frobnicate(a.com)")
        assertFalse(result.ok)
        assertTrue(result.message.contains("frobnicate"))
    }

    @Test
    fun rejectsUnbalancedParentheses() {
        assertFalse(DaeConfigValidator.validateRule("domain(a.com").ok)
        assertFalse(DaeConfigValidator.validateRule("domain(a.com))").ok)
        assertFalse(DaeConfigValidator.validateRule("domain('a.com)").ok)
    }

    @Test
    fun requiresAtLeastOneFunction() {
        assertFalse(DaeConfigValidator.validateRule("geosite:cn").ok)
    }

    @Test
    fun dnsRulesAllowDnsFunctionsOnly() {
        assertTrue(DaeConfigValidator.validateDnsRule("qname(geosite:cn)").ok)
        assertFalse(DaeConfigValidator.validateDnsRule("dip(1.1.1.1)").ok)
    }

    @Test
    fun quickCheckRequiresAllSectionsAndBalancedBraces() {
        val valid = """
            global {}
            dns {}
            node {}
            group {}
            routing {}
        """.trimIndent()
        assertTrue(DaeConfigValidator.quickCheckConfig(valid).ok)

        val missing = "global {}\ndns {}\nnode {}\nroute {}"
        val result = DaeConfigValidator.quickCheckConfig(missing)
        assertFalse(result.ok)
        assertEquals("缺少 group { 段", result.message)

        assertFalse(DaeConfigValidator.quickCheckConfig("global { dns { node { group { routing {").ok)
    }
}
