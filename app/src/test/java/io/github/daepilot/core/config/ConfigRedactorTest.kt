package io.github.daepilot.core.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.Base64

class ConfigRedactorTest {
    @Test
    fun masksNodeCredentialsButKeepsHost() {
        val config = """
            node {
              HK: 'vless://11111111-2222-3333-4444-555555555555@hk.example.com:443?security=tls&sni=hk.example.com&type=ws#HK'
              JP: 'hysteria2://secret-pass@jp.example.com:8443?sni=jp.example.com&obfs=salamander&obfs-password=abc#JP'
              SS: 'ss://YWVzLTI1Ni1nY206cGFzcw==@ss.example.com:8388#SS'
            }
        """.trimIndent()
        val out = ConfigRedactor.redact(config)
        assertFalse(out.contains("11111111-2222"))
        assertFalse(out.contains("secret-pass"))
        assertFalse(out.contains("abc#"))
        assertFalse(out.contains("YWVzLTI1Ni1nY206cGFzcw"))
        assertTrue(out.contains("hk.example.com:443"))
        assertTrue(out.contains("jp.example.com:8443"))
        assertTrue(out.contains("ss.example.com:8388"))
        assertTrue(out.contains("security=tls"))
    }

    @Test
    fun masksVmessUuidInsideBase64() {
        val uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val payload = """{"v":"2","ps":"HK","add":"hk.example.com","port":"443","id":"$uuid","net":"ws"}"""
        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray())
        val config = "  HK: 'vmess://$encoded#HK'"
        val out = ConfigRedactor.redact(config)
        assertFalse(out.contains(uuid))
        assertFalse(out.contains(encoded))
        val body = out.substringAfter("vmess://").substringBefore("#")
        val decoded = String(Base64.getDecoder().decode(body + "=".repeat((4 - body.length % 4) % 4)))
        assertTrue(decoded.contains("\"add\":\"hk.example.com\""))
        assertTrue(decoded.contains("<redacted>"))
        assertFalse(decoded.contains(uuid))
    }

    @Test
    fun masksSecretsInPlainFields() {
        val out = ConfigRedactor.redact("  password: 'hunter2'\n  token: \"abc\"")
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("abc"))
    }

    @Test
    fun masksSsrPayloadBecauseTheWholeBlobIsTheCredential() {
        val payload = "hk.example.com:443:auth_aes128_sha1:aes-256-cfnm:sup3rsecret:plain"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val out = ConfigRedactor.redact("  SSR_node: 'ssr://$encoded#SSR'")
        assertFalse(out.contains(encoded))
        assertTrue(out.contains("ssr://<redacted>"))
    }
}
