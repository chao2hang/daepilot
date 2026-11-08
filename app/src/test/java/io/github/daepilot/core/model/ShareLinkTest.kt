package io.github.daepilot.core.model

import io.github.daepilot.core.subscription.ProxyUriFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ShareLinkTest {
    @Test
    fun decodesUrlSafeBase64WithoutPadding() {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("hello world".toByteArray())
        assertEquals("hello world", ShareLink.decodeBase64Lenient(encoded))
    }

    @Test
    fun rejectsInvalidBase64() {
        assertNull(ShareLink.decodeBase64Lenient("a"))
    }

    @Test
    fun parsesStandardAuthorityLink() {
        val endpoint = ShareLink.endpoint("vless://uuid@example.com:443?type=tcp#HK")
        assertNotNull(endpoint)
        assertEquals("example.com", endpoint!!.host)
        assertEquals(443, endpoint.port)
        assertFalse(endpoint.isIpv4)
        assertFalse(endpoint.isIpv6)
    }

    @Test
    fun parsesIpv4AndIpv6Hosts() {
        val v4 = ShareLink.endpoint("vless://uuid@1.2.3.4:8443").orThrow()
        assertTrue(v4.isIpv4)
        assertEquals(8443, v4.port)

        val v6 = ShareLink.endpoint("vless://uuid@[2001:db8::1]:443").orThrow()
        assertTrue(v6.isIpv6)
        assertEquals("2001:db8::1", v6.host)
        assertEquals(443, v6.port)
    }

    @Test
    fun parsesSsUserinfoForm() {
        val password = Base64.getEncoder().encodeToString("aes-256-gcm:pass".toByteArray())
        val endpoint = ShareLink.endpoint("ss://$password@1.2.3.4:8388#node").orThrow()
        assertEquals("1.2.3.4", endpoint.host)
        assertEquals(8388, endpoint.port)
    }

    @Test
    fun parsesLegacyBase64SsLink() {
        val payload = Base64.getEncoder()
            .encodeToString("aes-256-gcm:pass@1.2.3.4:8388".toByteArray())
        val endpoint = ShareLink.endpoint("ss://$payload#legacy").orThrow()
        assertEquals("1.2.3.4", endpoint.host)
        assertEquals(8388, endpoint.port)
    }

    @Test
    fun parsesVmessJson() {
        val json = """{"v":"2","ps":"HK","add":"example.org","port":"443","id":"uuid"}"""
        val payload = Base64.getEncoder().encodeToString(json.toByteArray())
        val endpoint = ShareLink.endpoint("vmess://$payload").orThrow()
        assertEquals("example.org", endpoint.host)
        assertEquals(443, endpoint.port)
    }

    @Test
    fun parsesBareIpv6WithoutPort() {
        val endpoint = ShareLink.splitHostPort("2001:db8::1").orThrow()
        assertEquals("2001:db8::1", endpoint.host)
        assertNull(endpoint.port)
    }

    @Test
    fun decodesPercentEncodedName() {
        assertEquals(
            "香港 节点",
            ShareLink.name("vless://uuid@example.com:443#%E9%A6%99%E6%B8%AF%20%E8%8A%82%E7%82%B9"),
        )
    }

    @Test
    fun readsVmessRemarkFromPsWhenNoFragment() {
        val link = ProxyUriFactory.vmess(
            server = "hk1.example.com",
            port = 8443,
            uuid = "uuid",
            name = "🇭🇰 香港1 (部分移动)",
        ).substringBefore('#')
        assertEquals("🇭🇰 香港1 (部分移动)", ShareLink.name(link))
    }

    @Test
    fun vmessFragmentTakesPrecedenceOverPs() {
        val base = ProxyUriFactory.vmess(
            server = "example.com",
            port = 443,
            uuid = "uuid",
            name = "内部备注",
        ).substringBefore('#')
        assertEquals("外部备注", ShareLink.name(base + "#" + ShareLink.urlEncode("外部备注")))
    }

    @Test
    fun decodesJsonUnicodeEscapesInVmessRemark() {
        val json = "{\"add\":\"example.com\",\"port\":\"443\",\"ps\":\"\\u9999\\u6e2f \\ud83c\\udded\\ud83c\\uddf0\"}"
        val link = "vmess://" + java.util.Base64.getEncoder()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        assertEquals("香港 🇭🇰", ShareLink.name(link))
    }

    @Test
    fun renamesLinkFragment() {
        val renamed = ShareLink.withName("vless://uuid@example.com:443?type=tcp#old", "HK_01")
        assertEquals("vless://uuid@example.com:443?type=tcp#HK_01", renamed)
        assertEquals("HK_01", ShareLink.name(renamed!!))
    }

    @Test
    fun renamesVmessPsField() {
        val link = ProxyUriFactory.vmess(
            server = "example.com",
            port = 443,
            uuid = "uuid",
            name = "旧名字",
        )
        val renamed = requireNotNull(ShareLink.withName(link, "HK_01"))
        val json = requireNotNull(ShareLink.decodeBase64Lenient(renamed.substringAfter("://")))
        assertTrue(json.contains("\"ps\":\"HK_01\""))
        assertEquals("example.com", ShareLink.endpoint(renamed)?.host)
    }

    @Test
    fun failsToRenameMalformedVmess() {
        assertNull(ShareLink.withName("vmess://not-base64", "x"))
    }

    @Test
    fun insecureTlsAddsVmessJsonFlag() {
        val link = ProxyUriFactory.vmess(
            server = "example.com",
            port = 443,
            uuid = "uuid",
            name = "香港 01",
            tls = true,
        )
        val marked = ShareLink.withInsecureTls(link)
        val json = requireNotNull(
            ShareLink.decodeBase64Lenient(marked.substringAfter("://").substringBefore('#')),
        )
        assertTrue(json.contains("\"allowInsecure\":true"))
        assertEquals("example.com", ShareLink.endpoint(marked)?.host)
        assertEquals("香港 01", ShareLink.name(marked))
    }

    @Test
    fun insecureTlsUsesTheProtocolSpecificQueryKey() {
        assertEquals(
            "vless://uuid@example.com:443?type=tcp&allowInsecure=1#HK",
            ShareLink.withInsecureTls("vless://uuid@example.com:443?type=tcp#HK"),
        )
        assertEquals(
            "trojan://pw@example.com:443?security=tls&allowInsecure=1",
            ShareLink.withInsecureTls("trojan://pw@example.com:443?security=tls"),
        )
        assertTrue(
            ShareLink.withInsecureTls("hysteria2://pw@example.com:443?sni=x#n")
                .contains("insecure=1"),
        )
        assertTrue(
            ShareLink.withInsecureTls("tuic://uuid:pw@example.com:443")
                .contains("allow_insecure=1"),
        )
        assertTrue(
            ShareLink.withInsecureTls("anytls://pw@example.com:443")
                .contains("insecure=1"),
        )
    }

    @Test
    fun insecureTlsReplacesExistingFlagWithoutDuplicating() {
        val marked = ShareLink.withInsecureTls(
            "vless://uuid@example.com:443?allowInsecure=0&type=tcp#HK",
        )
        assertEquals(
            "vless://uuid@example.com:443?type=tcp&allowInsecure=1#HK",
            marked,
        )
    }

    @Test
    fun insecureTlsLeavesProtocolsWithoutTlsVerificationUntouched() {
        val link = "ss://YWVzLTI1Ni1nY206cGFzcw==@1.1.1.1:8388#A"
        assertEquals(link, ShareLink.withInsecureTls(link))
    }

    @Test
    fun detectsTlsLinksAndTheirServerNames() {
        val vmess = ProxyUriFactory.vmess(
            server = "1.2.3.4",
            port = 443,
            uuid = "uuid",
            name = "n",
            tls = true,
            sni = "s.example.com",
        )
        assertTrue(ShareLink.usesTls(vmess))
        assertEquals("s.example.com", ShareLink.tlsServerName(vmess))

        val noSni = ProxyUriFactory.vmess(
            server = "example.com",
            port = 443,
            uuid = "uuid",
            name = "n",
            tls = true,
        )
        assertEquals("example.com", ShareLink.tlsServerName(noSni))

        assertTrue(ShareLink.usesTls("vless://uuid@example.com:443?security=tls&sni=s.example.com"))
        assertEquals(
            "s.example.com",
            ShareLink.tlsServerName("vless://uuid@example.com:443?security=tls&sni=s.example.com"),
        )
        assertTrue(ShareLink.usesTls("trojan://pw@example.com:443"))
        assertFalse(ShareLink.usesTls("vless://uuid@example.com:443?security=none"))
        assertFalse(ShareLink.usesTls("ss://YWVzLTI1Ni1nY206cGFzcw==@1.1.1.1:8388#A"))
    }

    @Test
    fun buildsIpv6LinkWithQuery() {
        val link = ShareLink.build(
            scheme = "vless",
            userInfo = "uuid",
            host = "2001:db8::1",
            port = 443,
            query = linkedMapOf("encryption" to "none", "skip" to ""),
            name = "节点 A",
        )
        assertEquals("vless://uuid@[2001:db8::1]:443?encryption=none#%E8%8A%82%E7%82%B9%20A", link)
    }

    @Test
    fun parsesSsrEndpointFromBase64Payload() {
        // ssr:// carries server:port:protocol:cipher:password:obfs, url-safe
        // base64'd with no separator the parser could otherwise key on.
        val payload = "hk.example.com:443:auth_aes128_sha1:aes-256-cfnm:sup3rsecret:plain"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val endpoint = ShareLink.endpoint("ssr://$encoded#SSR").orThrow()
        assertEquals("hk.example.com", endpoint.host)
        assertEquals(443, endpoint.port)
    }

    @Test
    fun rejectsSsrLinkWhosePayloadDoesNotDecode() {
        assertNull(ShareLink.endpoint("ssr://not-base64-at-all##"))
    }

    private fun ShareEndpoint?.orThrow(): ShareEndpoint =
        requireNotNull(this) { "endpoint should parse" }
}
