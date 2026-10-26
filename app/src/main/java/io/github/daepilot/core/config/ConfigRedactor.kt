package io.github.daepilot.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Base64

/**
 * The rendered config contains node links with passwords/UUIDs verbatim. Uploads
 * keep protocol, host, port and most options so validation failures are still
 * diagnosable, but every credential is replaced before it leaves the device.
 */
object ConfigRedactor {
    private val json = Json { ignoreUnknownKeys = true }
    private val nodeLine = Regex("""^(\s*[A-Za-z0-9_]+:\s*')([a-zA-Z][a-zA-Z0-9+.-]*://.*)('\s*)$""")
    private val querySecret = Regex(
        """(?i)([?&](?:token|key|password|passwd|pwd|auth|uuid|secret|psk|obfs-password)=)[^&\s']+""",
    )
    private val quotedSecret = Regex(
        """(?i)(\b(?:password|passwd|pwd|auth|token|uuid|secret|psk|obfs-password)\s*:\s*['"])[^'"]*(['"])""",
    )
    private val userInfo = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*://)([^@/?#\s]+@)(.*)$""")

    fun redact(config: String, maxChars: Int = 150_000): String {
        val out = config.lineSequence().joinToString("\n") { line ->
            val match = nodeLine.matchEntire(line)
            if (match == null) {
                redactInline(line)
            } else {
                match.groupValues[1] + redactLink(match.groupValues[2]) + match.groupValues[3]
            }
        }
        return if (out.length <= maxChars) out else out.take(maxChars) + "\n…（已截断）"
    }

    fun redactLink(link: String): String {
        val scheme = link.substringBefore("://", "").lowercase()
        if (scheme == "vmess") return redactVmess(link)
        val inline = redactInline(link)
        val masked = inline.replace(userInfo) { "${it.groupValues[1]}<redacted>@${it.groupValues[3]}" }
        if (scheme == "ss" && !inline.contains("@")) {
            return "ss://<redacted>"
        }
        return masked
    }

    private fun redactInline(line: String): String =
        line.replace(querySecret, "${'$'}1<redacted>")
            .replace(quotedSecret, "${'$'}1<redacted>${'$'}2")

    private fun redactVmess(link: String): String {
        val body = link.removePrefix("vmess://").substringBefore('#')
        val fragment = link.substringAfter('#', "")
        val decoded = decodeBase64(body) ?: return "vmess://<redacted>"
        val element = runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull()
            ?: return "vmess://<redacted>"
        val redacted = JsonObject(
            element.mapValues { (key, value) ->
                if (key.equals("id", ignoreCase = true)) JsonPrimitive("<redacted>") else value
            },
        )
        val encoded = Base64.getEncoder()
            .encodeToString(redacted.toString().toByteArray(Charsets.UTF_8))
            .trimEnd('=')
        val suffix = if (fragment.isBlank()) "" else "#$fragment"
        return "vmess://$encoded$suffix"
    }

    private fun decodeBase64(raw: String): String? {
        val normalized = raw.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching {
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull()
    }
}
