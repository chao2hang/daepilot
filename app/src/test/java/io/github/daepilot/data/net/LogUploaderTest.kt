package io.github.daepilot.data.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogUploaderTest {
    @Test
    fun masksCredentialsInUrls() {
        val raw = "connect https://sub.example/api?token=abc123&key=zzz&foo=bar"
        val clean = LogUploader.sanitizeLog(raw)
        assertFalse(clean.contains("abc123"))
        assertFalse(clean.contains("zzz"))
        assertTrue(clean.contains("foo=bar"))
    }

    @Test
    fun masksUserInfoCredentialsInUrls() {
        val raw = "dae log: hysteria2://user:secret@example.com:443/?sni=example.com"
        val clean = LogUploader.sanitizeLog(raw)
        assertFalse(clean.contains("user:secret"))
        assertTrue(clean.contains("<redacted>@example.com:443"))
        assertTrue(clean.contains("sni=example.com"))
    }

    @Test
    fun stripsAnsiAndTruncates() {
        val raw = "\u001B[31merror\u001B[0m " + "x".repeat(50)
        val clean = LogUploader.sanitizeLog(raw, maxChars = 20)
        assertFalse(clean.contains("\u001B"))
        assertTrue(clean.endsWith("…（已截断）"))
    }
}
