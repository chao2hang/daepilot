package io.github.daepilot.core.model

import kotlinx.serialization.Serializable

/** Proxy protocols dae can dial from a share link. */
enum class NodeProtocol(val scheme: String, val label: String) {
    SHADOWSOCKS("ss", "Shadowsocks"),
    SHADOWSOCKSR("ssr", "ShadowsocksR"),
    VMESS("vmess", "VMess"),
    VLESS("vless", "VLESS"),
    TROJAN("trojan", "Trojan"),
    HYSTERIA2("hysteria2", "Hysteria2"),
    TUIC("tuic", "TUIC"),
    ANYTLS("anytls", "AnyTLS"),
    SOCKS("socks5", "Socks5"),
    HTTP("http", "HTTP"),
    UNKNOWN("", "未知");

    /** hysteria2/tuic run over QUIC: a TCP connect test cannot reach them. */
    val isUdpOnly: Boolean get() = this == HYSTERIA2 || this == TUIC

    companion object {
        fun fromScheme(raw: String): NodeProtocol {
            val scheme = raw.trim().lowercase().substringBefore("://")
            return entries.firstOrNull { it.scheme == scheme }
                ?: when (scheme) {
                    "hy2" -> HYSTERIA2
                    "socks5h", "socks4", "socks4a" -> SOCKS
                    "https" -> HTTP
                    else -> UNKNOWN
                }
        }
    }
}

/** One imported node. [link] is what gets written into config.dae. */
@Serializable
data class Node(
    val id: String,
    val name: String,
    val protocol: NodeProtocol,
    val link: String,
    val subscriptionId: String? = null,
    val latencyMs: Int? = null,
    val lastTestedAt: Long = 0L,
    val favorite: Boolean = false,
    val createdAt: Long = 0L,
    val countryCode: String? = null,
) {
    /** Host used for the proxy-loop bypass rule and direct latency probes. */
    val endpoint: ShareEndpoint? get() = ShareEndpoint.parse(link)
}

/** Host/port of a node's server, extracted without trusting display names. */
data class ShareEndpoint(val host: String, val port: Int?) {
    val isIpv4: Boolean
        get() = host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' }
    val isIpv6: Boolean get() = host.contains(':')

    companion object {
        fun parse(link: String): ShareEndpoint? = ShareLink.endpoint(link)
    }
}

@Serializable
data class TrafficInfo(
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val totalBytes: Long = 0,
    val expireAtEpochSeconds: Long = 0,
) {
    val usedBytes: Long get() = uploadBytes + downloadBytes
    val remainingBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0)
    val usageFraction: Float
        get() = if (totalBytes <= 0) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

@Serializable
data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val autoUpdate: Boolean = true,
    val updateIntervalHours: Int = 24,
    val lastUpdatedAt: Long = 0L,
    val lastError: String? = null,
    val traffic: TrafficInfo? = null,
    val nodeCount: Int = 0,
    val updateIntervalFromProviderHours: Int? = null,
)

/** Outbound group written to dae's `group {}` section. */
@Serializable
data class OutboundGroup(
    val id: String,
    val name: String,
    val policy: GroupPolicy = GroupPolicy.MIN_MOVING_AVG,
    val nodeIds: List<String> = emptyList(),
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
)

enum class GroupPolicy(val daeValue: String, val label: String) {
    MIN_MOVING_AVG("min_moving_avg", "最低移动平均延迟"),
    MIN_AVG10("min_avg10", "最低 10 次平均延迟"),
    MIN("min", "最低上次延迟"),
    RANDOM("random", "随机"),
    FIXED("fixed", "固定顺序"),
}

@Serializable
data class RoutingRule(
    val id: String,
    val expression: String,
    val outbound: String,
    val enabled: Boolean = true,
)

enum class RoutingTemplate(val label: String, val description: String) {
    BYPASS_MAINLAND("绕过大陆", "大陆直连，其余代理"),
    GLOBAL("全局代理", "全部走代理"),
    WHITELIST("白名单", "仅规则命中走代理"),
    DIRECT("仅直连", "全部直连"),
}

@Serializable
data class DnsConfig(
    // Android's dae build resolves hostname upstreams through the bootstrap
    // resolver it also needs for node domains. IP literals keep DNS working
    // even when that bootstrap lookup is degraded on cellular networks.
    val domesticUpstream: String = "udp://119.29.29.29:53",
    val foreignUpstream: String = "tcp+udp://8.8.8.8:53",
    val useDomesticForCn: Boolean = true,
)

/** Everything the renderer needs, independent from Android APIs. */
@Serializable
data class RenderSpec(
    val nodes: List<Node>,
    val groups: List<OutboundGroup> = emptyList(),
    val selectedNodeId: String? = null,
    val template: RoutingTemplate = RoutingTemplate.BYPASS_MAINLAND,
    val customRules: List<RoutingRule> = emptyList(),
    val dns: DnsConfig = DnsConfig(),
    val tproxyPort: Int = 12345,
    val logLevel: String = "info",
    val wanInterface: String = "auto",
    val lanInterface: String? = null,
    val autoConfigKernelParameter: Boolean = true,
    // Dial by IP. With `domain` the node resolves the sniffed hostname itself;
    // one bad server-side answer stalls the whole connection for tens of
    // seconds (measured 1.3 KB/s vs 2.5 MB/s on the same node). Routing still
    // matches on the sniffed domain, so geosite rules keep working.
    val dialMode: String = "ip",
    val bypassPackages: List<String> = emptyList(),
    val nodeBypassRules: Boolean = true,
    val includeNodeBypassRules: Boolean = true,
)

@Serializable
data class DaemonStatus(
    val running: Boolean = false,
    val pid: Int? = null,
    val startedAt: Long = 0L,
    val version: String? = null,
    val configValid: Boolean? = null,
    val lastError: String? = null,
    val mode: String = "unknown",
    val rssKb: Long = 0L,
    val bootEnabled: Boolean = false,
    val watchdog: Boolean = false,
    val desiredRunning: Boolean = false,
    val wan: String? = null,
    val wanIp: String? = null,
    val node: String? = null,
    val dns: String? = null,
    val dnsMode: String? = null,
    val ipv6Mode: String? = null,
)

@Serializable
data class CompatibilityReport(
    val kernelRelease: String = "",
    val kernelMajor: Int = 0,
    val kernelMinor: Int = 0,
    val kernelOk: Boolean = false,
    val btfPresent: Boolean = false,
    val cgroup2Mounted: Boolean = false,
    val bpfJitEnabled: Boolean = false,
    val memAvailableMb: Long = 0,
    val memoryOk: Boolean = false,
    val rootAvailable: Boolean = false,
    val moduleInstalled: Boolean = false,
    val daeBinaryPresent: Boolean = false,
    val daeVersion: String? = null,
    val conflicts: List<String> = emptyList(),
    val selinuxMode: String = "",
    val note: String? = null,
) {
    val ready: Boolean
        get() = rootAvailable && moduleInstalled && daeBinaryPresent &&
            kernelOk && btfPresent && cgroup2Mounted && memoryOk && conflicts.isEmpty()
}

@Serializable
data class AppSettings(
    val bootStart: Boolean = true,
    val keepServiceRunning: Boolean = true,
    val bypassPackages: List<String> = emptyList(),
    val tproxyPort: Int = 12345,
    val logLevel: String = "info",
    val subscriptionAutoUpdate: Boolean = true,
    val allowMeteredUpdates: Boolean = true,
    val themeMode: String = "system",
    val dayNightLogLimit: Int = 2000,
)

@Serializable
data class AppSnapshot(
    val subscriptions: List<Subscription> = emptyList(),
    val nodes: List<Node> = emptyList(),
    val groups: List<OutboundGroup> = emptyList(),
    val rules: List<RoutingRule> = emptyList(),
    val template: RoutingTemplate = RoutingTemplate.BYPASS_MAINLAND,
    val selectedNodeId: String? = null,
    val settings: AppSettings = AppSettings(),
    val dns: DnsConfig = DnsConfig(),
)

/** Small, user-editable portion of the state, persisted as one JSON blob. */
@Serializable
data class UserConfig(
    val settings: AppSettings = AppSettings(),
    val groups: List<OutboundGroup> = emptyList(),
    val rules: List<RoutingRule> = emptyList(),
    val template: RoutingTemplate = RoutingTemplate.BYPASS_MAINLAND,
    val selectedNodeId: String? = null,
    val dns: DnsConfig = DnsConfig(),
)

/** A parsed node that has not been assigned an id yet. */
data class ParsedNode(
    val name: String,
    val protocol: NodeProtocol,
    val link: String,
    val warning: String? = null,
)

data class ParsedSubscription(
    val nodes: List<ParsedNode>,
    val warnings: List<String> = emptyList(),
    val format: String = "unknown",
)
