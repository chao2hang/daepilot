package io.github.daepilot.core.config

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

enum class UpstreamProtocol(
    val label: String,
    val scheme: String,
    val defaultPort: Int,
) {
    DOH("DoH", "https", 443),
    DOT("DoT", "tls", 853),
    UDP("UDP", "udp", 53),
    TCP("TCP", "tcp", 53),
    TCP_UDP("TCP+UDP", "tcp+udp", 53),
}

data class UpstreamPreset(val name: String, val uri: String)

/** Editor state for one DNS upstream. */
data class UpstreamDraft(
    val protocol: UpstreamProtocol = UpstreamProtocol.DOH,
    val host: String = "",
    val port: String = "",
    val path: String = "",
    val advanced: Boolean = false,
    val raw: String = "",
)

data class UpstreamBuild(val uri: String = "", val error: String? = null) {
    val ok: Boolean get() = error == null && uri.isNotBlank()
}

/**
 * Builds and parses dae DNS upstream URLs. The guided fields cover the common
 * schemes; anything else is kept verbatim in advanced mode.
 */
object DnsUpstreamBuilder {
    private val domains = Regex("""^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)*$""")

    val presets = listOf(
        UpstreamPreset("阿里 DoH", "https://223.5.5.5/dns-query"),
        UpstreamPreset("Cloudflare DoH", "https://1.1.1.1/dns-query"),
        UpstreamPreset("Google DoH", "https://8.8.8.8/dns-query"),
        UpstreamPreset("腾讯 DoH", "https://doh.pub/dns-query"),
        UpstreamPreset("Cloudflare DoT", "tls://1.1.1.1"),
        UpstreamPreset("阿里 DoT", "tls://223.5.5.5"),
    )

    fun build(draft: UpstreamDraft): UpstreamBuild {
        if (draft.advanced) {
            val raw = draft.raw.trim()
            if (raw.isEmpty()) return UpstreamBuild(error = "请输入 DNS 上游地址")
            if (!raw.contains("://")) return UpstreamBuild(error = "地址需要包含协议，例如 https://dns.example/dns-query")
            if (raw.any { it.isWhitespace() || it == '\'' }) {
                return UpstreamBuild(error = "地址不能包含空格或单引号")
            }
            return UpstreamBuild(raw)
        }
        val host = normalizeHost(draft.host)
            ?: return UpstreamBuild(error = "请输入有效的服务器地址")
        val portText = draft.port.trim()
        val port = if (portText.isEmpty()) {
            null
        } else {
            portText.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: return UpstreamBuild(error = "端口需在 1-65535 之间")
        }
        val hostPart = if (isIpv6(host)) "[$host]" else host
        val portPart = port?.let { ":$it" } ?: ""
        val pathPart = if (draft.protocol == UpstreamProtocol.DOH) {
            val path = draft.path.trim()
            when {
                path.isEmpty() -> ""
                !path.startsWith("/") -> return UpstreamBuild(error = "DoH 路径需以 / 开头")
                path.any { it.isWhitespace() || it == '\'' } ->
                    return UpstreamBuild(error = "DoH 路径不能包含空格或单引号")
                else -> path
            }
        } else {
            ""
        }
        return UpstreamBuild("${draft.protocol.scheme}://$hostPart$portPart$pathPart")
    }

    fun parse(uri: String): UpstreamDraft {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return UpstreamDraft()
        val scheme = trimmed.substringBefore("://", "").lowercase()
        val protocol = UpstreamProtocol.entries.firstOrNull { it.scheme == scheme }
            ?: return UpstreamDraft(advanced = true, raw = trimmed)
        val parsed = runCatching { URI(trimmed) }.getOrNull()
            ?: return UpstreamDraft(advanced = true, raw = trimmed)
        val host = parsed.host ?: return UpstreamDraft(advanced = true, raw = trimmed)
        val path = parsed.path.orEmpty()
        return UpstreamDraft(
            protocol = protocol,
            host = host.removeSurrounding("[", "]"),
            port = parsed.port.takeIf { it > 0 }?.toString().orEmpty(),
            path = if (protocol == UpstreamProtocol.DOH && path.isNotEmpty() && path != "/") path else "",
        )
    }

    fun describe(uri: String): String {
        val trimmed = uri.trim()
        presets.firstOrNull { it.uri == trimmed }?.let { return it.name }
        val draft = parse(trimmed)
        if (draft.advanced) return "自定义 · $trimmed"
        val host = draft.host.ifBlank { "未设置" }
        val port = draft.port.toIntOrNull()?.takeIf { it != draft.protocol.defaultPort }
        return buildString {
            append(draft.protocol.label)
            append(" · ")
            append(host)
            if (port != null) append(":").append(port)
        }
    }

    private fun normalizeHost(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        if (value.contains("://")) {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            value = uri.host ?: return null
        }
        value = value.trimEnd('.')
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end <= 0) return null
            value = value.substring(1, end)
        } else {
            value = value.substringBefore('/')
            if (value.count { it == ':' } == 1) value = value.substringBefore(':')
        }
        value = value.trimEnd('.').lowercase()
        if (isIpv4(value) || isIpv6(value)) return value
        if (value.any { it.code > 127 }) {
            value = runCatching { IDN.toASCII(value) }.getOrNull() ?: return null
        }
        return value.takeIf { domains.matches(it) }
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
                (part.length == 1 || !part.startsWith("0")) && part.toInt() in 0..255
        }
    }

    private fun isIpv6(value: String): Boolean {
        if (value.count { it == ':' } < 2) return false
        if (value.any { !(it.isDigit() || it in "abcdefABCDEF:.") }) return false
        return runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)
    }
}
