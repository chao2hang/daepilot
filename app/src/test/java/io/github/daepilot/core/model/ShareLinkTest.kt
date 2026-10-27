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
