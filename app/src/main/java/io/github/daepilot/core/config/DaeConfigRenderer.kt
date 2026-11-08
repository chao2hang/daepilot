package io.github.daepilot.core.config

import io.github.daepilot.core.model.DnsConfig
import io.github.daepilot.core.model.GroupPolicy
import io.github.daepilot.core.model.Node
import io.github.daepilot.core.model.OutboundGroup
import io.github.daepilot.core.model.RenderSpec
import io.github.daepilot.core.model.RoutingRule
import io.github.daepilot.core.model.RoutingTemplate
import io.github.daepilot.core.model.ShareEndpoint
import io.github.daepilot.core.model.ShareLink
import io.github.daepilot.core.model.daeKey
import java.security.MessageDigest

/**
 * Renders a complete dae v2 configuration.
 *
 * The output is deterministic: the same [RenderSpec] always produces the same
 * text, which lets us test golden fixtures and lets the app show a diff before
 * applying. Node links are written verbatim (single quotes escaped), so no
 * protocol-specific knowledge is needed here.
 */
object DaeConfigRenderer {
    private const val MAX_IDENTIFIER_LENGTH = 128
    private const val MAX_PROCESS_NAME_LENGTH = 15
    private val RESERVED = setOf("direct", "must_direct", "block")

    /**
     * Hosts Play uses for app/APK downloads. services.googleapis.cn is the
     * China download front end and is classified as a CN site, so it needs an
     * explicit proxy rule in bypass-mainland mode.
     */
    private val PLAY_DOWNLOAD_HOSTS = listOf(
        "services.googleapis.cn",
        "gvt1.com",
        "gvt2.com",
        "dl.google.com",
    )

    fun render(spec: RenderSpec): String = buildString(2048 + spec.nodes.size * 96) {
        val prepared = prepareNodes(spec.nodes)
        val groups = buildGroups(spec, prepared)

        appendGlobal(spec)
        appendDns(spec.dns, spec.nodes)
        append("subscription {\n}\n\n")
        appendNodes(prepared)
        appendGroups(groups)
        appendRouting(spec, groups)
    }

    private data class PreparedNode(
        val node: Node,
        val tag: String,
        val link: String,
        val displayName: String,
    )

    /**
     * Group filters in dae match the dialer's display name (the link fragment
     * or vmess `ps`), not the node section key. Rewrite the link name to the
     * unique tag so `filter: name(...)` selects exactly the intended nodes.
     */
    private fun prepareNodes(nodes: List<Node>): List<PreparedNode> {
        val used = HashSet<String>()
        return nodes.map { node ->
            val base = sanitizeIdentifier(node.name)
                .ifBlank { sanitizeIdentifier(node.id) }
                .ifBlank { "node" }
            var tag = base
            if (!used.add(tag)) {
                val suffix = node.id.filter { it.isLetterOrDigit() }.take(8).ifBlank { "x" }
                tag = "${base}_$suffix"
                var counter = 2
                while (!used.add(tag)) {
                    tag = "${base}_${suffix}_$counter"
                    counter++
                }
            }
            val canonical = ShareLink.canonicalize(node.link)
            val secured = if (node.skipCertVerify) {
                ShareLink.withInsecureTls(canonical)
            } else {
                canonical
            }
            val renamed = ShareLink.withName(secured, tag)
            val displayName = if (renamed != null) {
                tag
            } else {
                ShareLink.name(node.link)?.takeIf { it.isNotBlank() }
                    ?: node.name.takeIf { it.isNotBlank() }
                    ?: tag
            }
            PreparedNode(node, tag, renamed ?: secured, displayName)
        }
    }

    private fun StringBuilder.appendGlobal(spec: RenderSpec) {
        append("global {\n")
        append("  log_level: ").append(safeLogLevel(spec.logLevel)).append('\n')
        append("  tproxy_port: ").append(spec.tproxyPort.coerceIn(1, 65535)).append('\n')
        append("  wan_interface: ").append(safeInterface(spec.wanInterface)).append('\n')
        spec.lanInterface?.takeIf { it.isNotBlank() }?.let {
            append("  lan_interface: ").append(safeInterface(it)).append('\n')
        }
        append("  auto_config_kernel_parameter: ").append(spec.autoConfigKernelParameter).append('\n')
        append("  dial_mode: ").append(safeDialMode(spec.dialMode)).append('\n')
        append("  allow_insecure: false\n")
        // Keep health checks on IP literals: hostname checks need the same
        // bootstrap resolver as node domains, which can deadlock on Android.
        // dae parses these as addresses, so the port is mandatory: without it the
        // connectivity check is silently dropped ("missing port in address") and
        // a dead node is never marked unhealthy. When IPv6 is blocked the device
        // has no v6 route, so a v6 check address can never pass and only flaps the
        // node health state — keep the checks IPv4-only in that mode.
        if (spec.dns.blockIpv6) {
            append("  tcp_check_url: 'http://cp.cloudflare.com,1.1.1.1'\n")
            append("  udp_check_dns: '8.8.8.8:53'\n")
        } else {
            append("  tcp_check_url: 'http://cp.cloudflare.com,1.1.1.1,2606:4700:4700::1111'\n")
            append("  udp_check_dns: '8.8.8.8:53,[2001:4860:4860::8888]:53'\n")
        }
        append("  check_interval: ").append(checkIntervalSeconds(spec.nodes.size)).append("s\n")
        append("  check_tolerance: 50ms\n")
        append("  sniffing_timeout: 30ms\n")
        append("  tls_implementation: tls\n")
        append("  so_mark_from_dae: 0x100\n")
        // No bootstrap_resolver: dae then resolves node domains with the Go
        // resolver, which reads the chroot's /etc/resolv.conf. daepilotctl
        // fills that file from the active network (run/dns.servers), while a
        // hardcoded public resolver is dropped by many cellular carriers.
        append("}\n\n")
    }

    private fun StringBuilder.appendDns(dns: DnsConfig, nodes: List<Node>) {
        val domestic = dns.domesticUpstream.ifBlank { DnsConfig.DEFAULT_DOMESTIC_UPSTREAM }
        val foreign = dns.foreignUpstream.ifBlank { DnsConfig.DEFAULT_FOREIGN_UPSTREAM }
        // The module reads this marker before it injects its legacy AAAA
        // block, so an explicit "allow" is respected across dae restarts.
        append("# daepilot:ipv6=").append(if (dns.blockIpv6) "block" else "allow").append('\n')
        append("dns {\n")
        append("  upstream {\n")
        append("    alidns: '").append(escapeSingleQuoted(domestic)).append("'\n")
        append("    googledns: '").append(escapeSingleQuoted(foreign)).append("'\n")
        append("  }\n")
        append("  routing {\n")
        append("    request {\n")
        if (dns.useDomesticForCn) {
            append("      qname(geosite:cn) -> alidns\n")
        }
        if (dns.blockIpv6) {
            append("      qtype(AAAA) && !qname(geosite:cn) -> reject\n")
        }
        // Node server names must resolve without the tunnel: dae's own
        // bootstrap lookup is captured by its DNS ingress, which would send a
        // foreign name to googledns through the very proxy that needs the
        // address. That deadlocks every node with a hostname (VMess nodes on
        // *.jiedian.stream were unreachable with "bootstrap resolver returned
        // no usable address"). The domestic resolver answers directly.
        val nodeNames = nodeServerNames(nodes)
        if (nodeNames.isNotEmpty()) {
            append("      qname(")
                .append(nodeNames.joinToString(", ") { "suffix: $it" })
                .append(") -> alidns\n")
        }
        // Play's API and download frontends are served from CN caches. Resolving
        // them through the foreign DoH adds a proxy round trip that times out
        // whenever the node is slow, and the resulting SERVFAIL breaks Play
        // itself; the domestic resolver answers these directly and correctly.
        append(
            "      qname(suffix: play.googleapis.com, suffix: googleapis.cn, " +
                "suffix: services.googleapis.cn, suffix: gvt1.com, suffix: gvt2.com) -> alidns\n",
        )
        append("      fallback: googledns\n")
        append("    }\n")
        append("    response {\n")
        append("      upstream(googledns) -> accept\n")
        append("      ip(geoip:private) && !qname(geosite:cn) -> googledns\n")
        append("      fallback: accept\n")
        append("    }\n")
        append("  }\n")
        append("}\n\n")
    }

    /** Hostnames of node servers; IP endpoints need no bootstrap lookup. */
    private fun nodeServerNames(nodes: List<Node>): List<String> =
        nodes.mapNotNull { node ->
            val endpoint = ShareEndpoint.parse(node.link) ?: return@mapNotNull null
            if (endpoint.isIpv4 || endpoint.isIpv6) return@mapNotNull null
            endpoint.host.trim().trimEnd('.').lowercase()
        }
            .filter { host ->
                host.isNotEmpty() && host.length <= 253 &&
                    host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
            }
            .distinct()

    /**
     * Every node is probed (one TCP and one UDP round trip through the tunnel)
     * once per interval, so a 30 s cadence on a large subscription keeps the
     * modem busy all day for latency data nobody is looking at: measured 1% of
     * a core plus the radio tail while the phone was idle. Group policies still
     * get fresh enough samples at 60-90 s.
     */
    private fun checkIntervalSeconds(nodeCount: Int): Int = when {
        nodeCount <= 6 -> 30
        nodeCount <= 18 -> 60
        else -> 90
    }

    private fun StringBuilder.appendNodes(nodes: List<PreparedNode>) {
        append("node {\n")
        for (prepared in nodes) {
            append("  ").append(prepared.tag).append(": '")
                .append(escapeSingleQuoted(prepared.link)).append("'\n")
        }
        append("}\n\n")
    }

    private data class RenderedGroup(
        val name: String,
        val filter: List<String>,
        val policy: GroupPolicy,
        /** Rule outbounds that resolve here: stable key plus legacy display name. */
        val aliases: List<String> = emptyList(),
    )

    private fun buildGroups(
        spec: RenderSpec,
        nodes: List<PreparedNode>,
    ): List<RenderedGroup> {
        val result = mutableListOf<RenderedGroup>()
        if (nodes.isEmpty()) return result
        val byId = nodes.associateBy { it.node.id }

        // The default outbound is routing's fallback, so `proxy` mirrors the
        // chosen group. Legacy rules that target `proxy` keep working.
        val defaultGroup = spec.defaultGroupId
            ?.let { id -> spec.groups.firstOrNull { it.id == id && it.enabled } }
        val defaultMembers = defaultGroup
            ?.nodeIds.orEmpty()
            .mapNotNull { byId[it]?.displayName }
            .distinct()
        val selected = spec.selectedNodeId?.let { byId[it] }
        result += when {
            defaultGroup != null && defaultMembers.isNotEmpty() ->
                RenderedGroup("proxy", defaultMembers, defaultGroup.policy)
            selected != null ->
                RenderedGroup("proxy", listOf(selected.displayName), GroupPolicy.FIXED)
            else ->
                // No filter means "all nodes" in dae.
                RenderedGroup("proxy", emptyList(), GroupPolicy.MIN_MOVING_AVG)
        }

        val usedNames = mutableSetOf("proxy")
        for (group in spec.groups.filter { it.enabled }) {
            if (group.builtIn) continue
            val name = uniqueGroupName(group.daeKey(), group.id, usedNames) ?: continue
            val members = group.nodeIds.mapNotNull { byId[it]?.displayName }.distinct()
            if (members.isEmpty()) continue
            result += RenderedGroup(
                name = name,
                filter = members,
                policy = group.policy,
                aliases = listOf(group.daeKey(), group.name)
                    .filter { it.isNotBlank() }
                    .distinct(),
            )
        }
        return result
    }

    private fun uniqueGroupName(
        raw: String,
        groupId: String,
        used: MutableSet<String>,
    ): String? {
        var base = sanitizeIdentifier(raw)
        if (base.isBlank() || base.lowercase() in RESERVED) {
            base = "group_" + sanitizeIdentifier(groupId).ifBlank { "custom" }.take(24)
        }
        if (base.length > MAX_IDENTIFIER_LENGTH) base = base.take(MAX_IDENTIFIER_LENGTH)
        var candidate = base
        var counter = 2
        while (!used.add(candidate)) {
            candidate = "${base}_$counter"
            counter++
        }
        return candidate
    }

    private fun StringBuilder.appendGroups(groups: List<RenderedGroup>) {
        append("group {\n")
        for (group in groups) {
            append("  ").append(group.name).append(" {\n")
            if (group.filter.isNotEmpty()) {
                append("    filter: name(")
                append(group.filter.joinToString(", ") { quoteLiteral(it) })
                append(")\n")
            }
            when (group.policy) {
                // fixed/random are function calls, not plain values. `fixed`
                // takes the index inside the group's filter list.
                GroupPolicy.FIXED -> append("    policy: fixed(0)\n")
                GroupPolicy.RANDOM -> append("    policy: random\n")
                else -> append("    policy: ").append(group.policy.daeValue).append('\n')
            }
            append("  }\n")
        }
        append("}\n\n")
    }

    private fun StringBuilder.appendRouting(
        spec: RenderSpec,
        groups: List<RenderedGroup>,
    ) {
        val validTargets = buildSet {
            add("direct")
            add("must_direct")
            add("block")
            groups.forEach { add(it.name) }
        }
        val aliasTargets = groups
            .flatMap { group -> group.aliases.map { alias -> alias to group.name } }
            .toMap()
        val fallback = when (spec.template) {
            RoutingTemplate.WHITELIST, RoutingTemplate.DIRECT -> "direct"
            else -> if (groups.isNotEmpty()) groups.first().name else "direct"
        }

        append("routing {\n")
        // Multicast/broadcast and private networks must never enter the proxy.
        append("  dip(224.0.0.0/3, 'ff00::/8') -> direct\n")
        append("  dip(geoip:private) -> direct\n")

        if (spec.includeNodeBypassRules) {
            for (rule in buildNodeBypassRules(spec.nodes)) {
                append("  ").append(rule).append(" -> must_direct\n")
            }
        }

        // Android connectivity probes are owned by the system and should not be
        // proxied; a failed probe makes the OS report "no internet".
        append("  pname(")
            .append(pnamePattern(listOf("netd", "com.android.networkstack.process")))
            .append(") -> must_direct\n")

        if (spec.bypassPackages.isNotEmpty()) {
            // ROMs where dae's pname cgroup hook cannot attach still get a
            // working per-app bypass: the module marks the selected UIDs with
            // this DSCP value in netfilter.
            append("  dscp(").append(BYPASS_DSCP_LITERAL).append(") -> must_direct\n")
            val packages = pnamePattern(
                spec.bypassPackages
                    .map { it.trim() }
                    .filter { it.matches(Regex("[A-Za-z0-9_.:]+")) },
            )
            if (packages.isNotEmpty()) {
                append("  pname(").append(packages).append(") -> must_direct\n")
            }
        }

        for (rule in spec.customRules.filter { it.enabled }) {
            val expression = sanitizeRule(rule.expression) ?: continue
            val requested = rule.outbound.trim()
            val target = when {
                requested in validTargets -> requested
                else -> aliasTargets[requested]
                    ?: aliasTargets[sanitizeIdentifier(requested)]
                    ?: "direct"
            }
            append("  ").append(expression).append(" -> ").append(target).append('\n')
        }

        when (spec.template) {
            RoutingTemplate.BYPASS_MAINLAND -> {
                // Play downloads are served by services.googleapis.cn, whose
                // answers are China Mobile GGC cache IPs inside geoip:cn. That
                // direct path stalls (0 B/s) on some carriers while the same
                // cache answers normally through the proxy, so the download
                // hosts have to win over the CN direct rules.
                append("  domain(")
                    .append(PLAY_DOWNLOAD_HOSTS.joinToString(", ") { "suffix: $it" })
                    .append(") -> ").append(fallback).append('\n')
                append("  domain(geosite:cn) -> direct\n")
                append("  dip(geoip:cn) -> direct\n")
            }
            RoutingTemplate.WHITELIST -> {
                // Whitelist mode proxies only what custom rules selected; all
                // leftovers are direct, which is what fallback below does.
            }
            RoutingTemplate.GLOBAL, RoutingTemplate.DIRECT -> Unit
        }

        // The DNS layer already rejects AAAA for proxied names, but apps still
        // dial cached/hardcoded IPv6 (e.g. Telegram). Without an IPv6 egress the
        // proxy relay hangs on "network is unreachable"; block non-CN IPv6 here
        // so it fails fast. CN IPv6 matched dip(geoip:cn) above stays direct.
        if (spec.dns.blockIpv6) {
            append("  ipversion(6) -> block\n")
        }

        append("  fallback: ").append(fallback).append('\n')
        append("}\n")
    }

    /**
     * Node server endpoints must bypass the proxy. In tproxy mode dae also
     * captures its own outbound connection to the node; without these rules a
     * self-referential loop forms and every request times out.
     */
    fun buildNodeBypassRules(nodes: List<Node>): List<String> {
        val seen = LinkedHashSet<String>()
        for (node in nodes) {
            val endpoint = ShareEndpoint.parse(node.link) ?: continue
            if (endpoint.host.isBlank()) continue
            val target = when {
                endpoint.isIpv6 -> "dip(\"${endpoint.host}\")"
                endpoint.isIpv4 -> "dip(${endpoint.host})"
                else -> "domain(${endpoint.host})"
            }
            val expr = endpoint.port?.let { "$target && dport($it)" } ?: target
            seen += expr
        }
        return seen.toList()
    }

    /**
     * Android sets a task's comm to the LAST 15 characters of the process name,
     * while dae trims an over-long `pname` pattern from the front — so a full
     * package name such as `com.android.networkstack.process` (which dae shortens
     * to `com.android.netw`) can never match `rkstack.process`. Compare tails.
     */
    fun pnamePattern(names: List<String>): String =
        names.map { it.trim().takeLast(MAX_PROCESS_NAME_LENGTH) }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")

    /** dae keys allow ASCII letters/digits; everything else folds to `_`. */
    fun sanitizeIdentifier(raw: String): String {
        val out = StringBuilder()
        var lastUnderscore = false
        for (c in raw) {
            if (c.isLetterOrDigit() && c.code < 128) {
                out.append(c)
                lastUnderscore = false
            } else if (!lastUnderscore) {
                out.append('_')
                lastUnderscore = true
            }
        }
        var value = out.toString().trim('_')
        if (value.isEmpty()) return ""
        if (value.first().isDigit()) value = "id_$value"
        return value.take(MAX_IDENTIFIER_LENGTH)
    }

    private fun sanitizeRule(raw: String): String? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        if (line.any { it == '\n' || it == '\r' || it == '{' || it == '}' }) return null
        if (line.contains("->")) return null
        return line
    }

    private fun safeLogLevel(raw: String): String =
        raw.lowercase().takeIf { it in setOf("trace", "debug", "info", "warn", "error", "fatal") }
            ?: "info"

    private fun safeDialMode(raw: String): String =
        raw.takeIf { it in setOf("ip", "domain", "domain+", "domain++") } ?: "ip"

    private fun safeInterface(raw: String): String {
        val value = raw.trim()
        if (value.equals("auto", ignoreCase = true)) return "auto"
        if (value.isEmpty() || value.length > 64) return "auto"
        if (!value.all { it.isLetterOrDigit() || it in "_.:-" }) return "auto"
        return value
    }

    private fun escapeSingleQuoted(value: String): String =
        value.replace("\r", " ").replace("\n", " ")
            .replace("\\", "\\\\")
            .replace("'", "\\'")

    private fun quoteLiteral(value: String): String = "'" + escapeSingleQuoted(value) + "'"

    /** A short, stable id for a subscription-scoped node link. */
    fun nodeId(subscriptionId: String, link: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$subscriptionId\u0000$link".toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** User-facing rule alternatives shown by the rule editor. */
    val OUTBOUND_TARGETS = listOf("proxy", "direct", "block", "must_direct")

    /** DSCP value the module applies to UID-based bypass traffic. */
    const val BYPASS_DSCP = 0x2f
    private const val BYPASS_DSCP_LITERAL = "0x2f"
}
