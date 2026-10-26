package io.github.daepilot.core.subscription

import io.github.daepilot.core.model.NodeProtocol
import io.github.daepilot.core.model.ParsedNode
import io.github.daepilot.core.model.ParsedSubscription
import io.github.daepilot.core.model.ShareLink
import io.github.daepilot.core.model.SubscriptionDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Parses the three subscription shapes seen in the wild:
 *  - plain/base64 share-link lists (what dae natively understands),
 *  - Clash / Clash.Meta YAML (`proxies:`),
 *  - sing-box JSON (`outbounds:`).
 */
object SubscriptionParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: ByteArray, subscriptionName: String? = null): ParsedSubscription {
        val text = SubscriptionDecoder.decode(raw).trim()
        if (text.isEmpty()) return ParsedSubscription(emptyList(), listOf("订阅内容为空"), "empty")

        val yamlProxies = tryClash(text)
        if (yamlProxies != null) {
            val prefix = subscriptionName?.takeIf { it.isNotBlank() }
            val nodes = yamlProxies.mapNotNull { (map, warning) ->
                clashProxyToNode(map, prefix, warning)
            }
            return ParsedSubscription(
                nodes = dedupe(nodes),
                warnings = yamlProxies.mapNotNull { it.second }.distinct().take(20),
                format = "clash",
            )
        }

        val singBox = trySingBox(text)
        if (singBox != null) {
            val prefix = subscriptionName?.takeIf { it.isNotBlank() }
            val nodes = singBox.mapNotNull { (obj, warning) -> singBoxOutboundToNode(obj, prefix, warning) }
            return ParsedSubscription(
                nodes = dedupe(nodes),
                warnings = singBox.mapNotNull { it.second }.distinct().take(20),
                format = "sing-box",
            )
        }

        return parseLinkList(text, subscriptionName)
    }

    fun parseLinkList(text: String, subscriptionName: String? = null): ParsedSubscription {
        val prefix = subscriptionName?.takeIf { it.isNotBlank() }
        val nodes = mutableListOf<ParsedNode>()
        val warnings = mutableListOf<String>()
        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachIndexed
            val scheme = ShareLink.scheme(line)
            if (scheme !in SubscriptionDecoder.KNOWN_SCHEMES) return@forEachIndexed
            val protocol = NodeProtocol.fromScheme(line)
            if (protocol == NodeProtocol.UNKNOWN) {
                warnings += "跳过未知协议：${line.take(32)}…"
                return@forEachIndexed
            }
            if (ShareLink.endpoint(line) == null) {
                warnings += "第 ${index + 1} 行无法解析服务器地址"
                return@forEachIndexed
            }
            val linkName = ShareLink.name(line)
            val host = ShareLink.endpoint(line)?.host
            val fallbackName = listOfNotNull(protocol.label, host)
                .joinToString(" ")
                .ifBlank { "节点 ${index + 1}" }
            val name = listOfNotNull(prefix, linkName ?: fallbackName).joinToString(" · ")
            nodes += ParsedNode(name = name, protocol = protocol, link = line)
        }
        if (nodes.isEmpty()) warnings += "未找到可用的节点链接"
        return ParsedSubscription(dedupe(nodes), warnings.distinct(), "link-list")
    }

    private fun dedupe(nodes: List<ParsedNode>): List<ParsedNode> {
        val seen = HashSet<String>()
        return nodes.filter { seen.add(it.link) }
    }

    private fun tryClash(text: String): List<Pair<Map<String, Any?>, String?>>? {
        if (!text.contains("proxies:") && !text.trimStart().startsWith("proxies")) {
            // A YAML document can still contain a proxies key deeper down, but
            // scanning every base64 blob as YAML is wasteful and error-prone.
            return if (text.trimStart().startsWith("proxy-groups:")) null else null
        }
        return try {
            val yaml = Yaml(SafeConstructor(LoaderOptions()))
            val root = yaml.load<Any?>(text) as? Map<*, *> ?: return null
            val proxies = root["proxies"] as? List<*> ?: return null
            proxies.mapNotNull { entry ->
                @Suppress("UNCHECKED_CAST")
                val map = entry as? Map<*, *> ?: return@mapNotNull null
                val normalized = normalizeMap(map)
                val type = normalized["type"]?.toString()?.lowercase()
                val warning = when (type) {
                    null -> "缺少 type 字段的 Clash 节点"
                    "ssr" -> "dae 不支持从 Clash SSR 定义转换：${normalized["name"]}"
                    "wireguard", "snell", "hysteria" -> "暂不支持 ${type} 节点：${normalized["name"]}"
                    else -> null
                }
                normalized to warning
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun trySingBox(text: String): List<Pair<JsonObject, String?>>? {
        if (!text.trimStart().startsWith("{")) return null
        return try {
            val root = json.parseToJsonElement(text).jsonObject
            val outbounds = root["outbounds"]?.jsonArray ?: return null
            outbounds.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val type = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return@mapNotNull null
                if (type in setOf("direct", "block", "dns", "selector", "urltest")) return@mapNotNull null
                val warning = if (type in setOf("wireguard", "ssh", "tor", "shadowsocksr")) {
                    "暂不支持 sing-box $type 节点：${obj["tag"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
                } else {
                    null
                }
                obj to warning
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun clashProxyToNode(
        map: Map<String, Any?>,
        prefix: String?,
        warning: String?,
    ): ParsedNode? {
        if (warning != null) return null
        val type = map.str("type")?.lowercase() ?: return null
        val server = map.str("server") ?: return null
        val port = map.int("port") ?: return null
        val displayName = map.str("name") ?: "$server:$port"
        val name = listOfNotNull(prefix, displayName).joinToString(" · ")

        val link = when (type) {
            "ss", "shadowsocks" -> {
                val cipher = map.str("cipher") ?: map.str("method") ?: return null
                val password = map.str("password") ?: return null
                val plugin = ProxyUriFactory.sip003Plugin(
                    map.str("plugin"),
                    map.map("plugin-opts"),
                )
                ProxyUriFactory.shadowsocks(server, port, cipher, password, plugin, name)
            }
            "vmess" -> {
                val uuid = map.str("uuid") ?: return null
                val ws = map.map("ws-opts")
                val headers = ws?.map("headers")
                val grpc = map.map("grpc-opts")
                val network = map.str("network") ?: "tcp"
                ProxyUriFactory.vmess(
                    server = server,
                    port = port,
                    uuid = uuid,
                    name = name,
                    alterId = map.int("alterId") ?: map.int("alterid") ?: 0,
                    cipher = map.str("cipher") ?: "auto",
                    network = if (network == "grpc") "grpc" else network,
                    wsPath = ws?.str("path") ?: grpc?.str("grpc-service-name"),
                    wsHost = headers?.str("Host") ?: headers?.str("host"),
                    tls = map.bool("tls") == true,
                    sni = map.str("servername") ?: map.str("sni"),
                    alpn = map.stringList("alpn"),
                    fingerprint = map.str("client-fingerprint"),
                )
            }
            "vless" -> {
                val uuid = map.str("uuid") ?: return null
                val ws = map.map("ws-opts")
                val grpc = map.map("grpc-opts")
                val reality = map.map("reality-opts")
                val network = map.str("network") ?: "tcp"
                ProxyUriFactory.vless(
                    server = server,
                    port = port,
                    uuid = uuid,
                    name = name,
                    flow = map.str("flow"),
                    security = when {
                        reality != null -> "reality"
                        map.bool("tls") == true -> "tls"
                        else -> "none"
                    },
                    sni = map.str("servername") ?: map.str("sni"),
                    fingerprint = map.str("client-fingerprint"),
                    publicKey = reality?.str("public-key"),
                    shortId = reality?.str("short-id"),
                    network = network,
                    path = ws?.str("path"),
                    host = ws?.map("headers")?.str("Host") ?: ws?.map("headers")?.str("host"),
                    serviceName = grpc?.str("grpc-service-name"),
                    alpn = map.stringList("alpn"),
                    allowInsecure = map.bool("skip-cert-verify") == true,
                )
            }
            "trojan" -> {
                val password = map.str("password") ?: return null
                val ws = map.map("ws-opts")
                ProxyUriFactory.trojan(
                    server = server,
                    port = port,
                    password = password,
                    name = name,
                    sni = map.str("sni") ?: map.str("servername"),
                    network = map.str("network") ?: "tcp",
                    path = ws?.str("path"),
                    host = ws?.map("headers")?.str("Host"),
                    alpn = map.stringList("alpn"),
                    allowInsecure = map.bool("skip-cert-verify") == true,
                )
            }
            "hysteria2", "hy2" -> {
                val password = map.str("password") ?: map.str("auth") ?: return null
                ProxyUriFactory.hysteria2(
                    server = server,
                    port = port,
                    password = password,
                    name = name,
                    sni = map.str("sni") ?: map.str("servername"),
                    allowInsecure = map.bool("skip-cert-verify") == true,
                    obfs = map.str("obfs"),
                    obfsPassword = map.str("obfs-password"),
                    upMbps = map.int("up") ?: map.int("up-mbps"),
                    downMbps = map.int("down") ?: map.int("down-mbps"),
                )
            }
            "tuic" -> {
                val uuid = map.str("uuid") ?: return null
                val password = map.str("password") ?: return null
                ProxyUriFactory.tuic(
                    server = server,
                    port = port,
                    uuid = uuid,
                    password = password,
                    name = name,
                    sni = map.str("sni") ?: map.str("servername"),
                    congestionControl = map.str("congestion-controller"),
                    udpRelayMode = map.str("udp-relay-mode"),
                    allowInsecure = map.bool("skip-cert-verify") == true,
                    alpn = map.stringList("alpn"),
                )
            }
            "anytls" -> {
                val password = map.str("password") ?: return null
                ProxyUriFactory.anytls(
                    server = server,
                    port = port,
                    password = password,
                    name = name,
                    sni = map.str("sni") ?: map.str("servername"),
                    allowInsecure = map.bool("skip-cert-verify") == true,
                )
            }
            "socks5" -> ProxyUriFactory.socks(
                server, port,
                username = map.str("username"),
                password = map.str("password"),
                name = name,
                tls = map.bool("tls") == true,
            )
            "http" -> ProxyUriFactory.http(
                server, port,
                username = map.str("username"),
                password = map.str("password"),
                name = name,
                tls = map.bool("tls") == true,
            )
            else -> return null
        }
        return ParsedNode(name, NodeProtocol.fromScheme(link), link)
    }

    private fun singBoxOutboundToNode(
        obj: JsonObject,
        prefix: String?,
        warning: String?,
    ): ParsedNode? {
        if (warning != null) return null
        val type = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return null
        val server = obj["server"]?.jsonPrimitive?.contentOrNull ?: return null
        val port = obj["server_port"]?.jsonPrimitive?.intOrNull ?: return null
        val tag = obj["tag"]?.jsonPrimitive?.contentOrNull ?: "$server:$port"
        val name = listOfNotNull(prefix, tag).joinToString(" · ")
        val tls = obj["tls"] as? JsonObject
        val transport = obj["transport"] as? JsonObject
        val transportType = transport?.get("type")?.jsonPrimitive?.contentOrNull
        val tlsEnabled = tls?.get("enabled")?.jsonPrimitive?.booleanOrNull == true
        val sni = tls?.get("server_name")?.jsonPrimitive?.contentOrNull
        val insecure = tls?.get("insecure")?.jsonPrimitive?.booleanOrNull == true
        val alpn = (tls?.get("alpn") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val utlsFp = (tls?.get("utls") as? JsonObject)
            ?.get("fingerprint")?.jsonPrimitive?.contentOrNull

        val link = when (type) {
            "shadowsocks" -> {
                val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return null
                val password = obj["password"]?.jsonPrimitive?.contentOrNull ?: return null
                val plugin = obj["plugin"]?.jsonPrimitive?.contentOrNull
                val pluginOpts = obj["plugin_opts"]?.jsonPrimitive?.contentOrNull
                ProxyUriFactory.shadowsocks(
                    server, port, method, password,
                    plugin = listOfNotNull(plugin, pluginOpts).joinToString(";").ifBlank { null },
                    name = name,
                )
            }
            "vmess" -> {
                val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return null
                ProxyUriFactory.vmess(
                    server = server,
                    port = port,
                    uuid = uuid,
                    name = name,
                    alterId = obj["alter_id"]?.jsonPrimitive?.intOrNull ?: 0,
                    cipher = obj["security"]?.jsonPrimitive?.contentOrNull ?: "auto",
                    network = transportType ?: "tcp",
                    wsPath = transport?.get("path")?.jsonPrimitive?.contentOrNull,
                    wsHost = (transport?.get("headers") as? JsonObject)
                        ?.get("Host")?.jsonPrimitive?.contentOrNull,
                    tls = tlsEnabled,
                    sni = sni,
                    alpn = alpn,
                    fingerprint = utlsFp,
                )
            }
            "vless" -> {
                val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return null
                val reality = tls?.get("reality") as? JsonObject
                ProxyUriFactory.vless(
                    server = server,
                    port = port,
                    uuid = uuid,
                    name = name,
                    flow = obj["flow"]?.jsonPrimitive?.contentOrNull,
                    security = when {
                        reality != null -> "reality"
                        tlsEnabled -> "tls"
                        else -> "none"
                    },
                    sni = sni,
                    fingerprint = utlsFp,
                    publicKey = reality?.get("public_key")?.jsonPrimitive?.contentOrNull,
                    shortId = reality?.get("short_id")?.jsonPrimitive?.contentOrNull,
                    network = transportType ?: "tcp",
                    path = transport?.get("path")?.jsonPrimitive?.contentOrNull,
                    host = (transport?.get("headers") as? JsonObject)
                        ?.get("Host")?.jsonPrimitive?.contentOrNull,
                    serviceName = transport?.get("service_name")?.jsonPrimitive?.contentOrNull,
                    alpn = alpn,
                    allowInsecure = insecure,
                )
            }
            "trojan" -> {
                val password = obj["password"]?.jsonPrimitive?.contentOrNull ?: return null
                ProxyUriFactory.trojan(
                    server, port, password, name,
                    sni = sni,
                    network = transportType ?: "tcp",
                    path = transport?.get("path")?.jsonPrimitive?.contentOrNull,
                    host = (transport?.get("headers") as? JsonObject)
                        ?.get("Host")?.jsonPrimitive?.contentOrNull,
                    alpn = alpn,
                    allowInsecure = insecure,
                )
            }
            "hysteria2" -> {
                val password = obj["password"]?.jsonPrimitive?.contentOrNull ?: return null
                val obfs = obj["obfs"] as? JsonObject
                ProxyUriFactory.hysteria2(
                    server, port, password, name,
                    sni = sni,
                    allowInsecure = insecure,
                    obfs = obfs?.get("type")?.jsonPrimitive?.contentOrNull,
                    obfsPassword = obfs?.get("password")?.jsonPrimitive?.contentOrNull,
                    upMbps = obj["up_mbps"]?.jsonPrimitive?.intOrNull,
                    downMbps = obj["down_mbps"]?.jsonPrimitive?.intOrNull,
                )
            }
            "tuic" -> {
                val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return null
                val password = obj["password"]?.jsonPrimitive?.contentOrNull ?: return null
                ProxyUriFactory.tuic(
                    server, port, uuid, password, name,
                    sni = sni,
                    congestionControl = obj["congestion_control"]?.jsonPrimitive?.contentOrNull,
                    udpRelayMode = obj["udp_relay_mode"]?.jsonPrimitive?.contentOrNull,
                    allowInsecure = insecure,
                    alpn = alpn,
                )
            }
            "anytls" -> {
                val password = obj["password"]?.jsonPrimitive?.contentOrNull ?: return null
                ProxyUriFactory.anytls(
                    server, port, password, name,
                    sni = sni,
                    allowInsecure = insecure,
                )
            }
            "socks" -> ProxyUriFactory.socks(
                server, port,
                username = obj["username"]?.jsonPrimitive?.contentOrNull,
                password = obj["password"]?.jsonPrimitive?.contentOrNull,
                name = name,
                tls = tlsEnabled,
            )
            "http" -> ProxyUriFactory.http(
                server, port,
                username = obj["username"]?.jsonPrimitive?.contentOrNull,
                password = obj["password"]?.jsonPrimitive?.contentOrNull,
                name = name,
                tls = tlsEnabled,
            )
            else -> return null
        }
        return ParsedNode(name, NodeProtocol.fromScheme(link), link)
    }

    private fun normalizeMap(map: Map<*, *>): Map<String, Any?> =
        map.entries.associate { (k, v) ->
            k.toString() to when (v) {
                is Map<*, *> -> normalizeMap(v)
                is List<*> -> v.map { if (it is Map<*, *>) normalizeMap(it) else it }
                else -> v
            }
        }

    private fun Map<String, Any?>.str(key: String): String? =
        this[key]?.toString()?.takeIf { it.isNotBlank() && it != "null" }

    private fun Map<String, Any?>.int(key: String): Int? = when (val v = this[key]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    private fun Map<String, Any?>.bool(key: String): Boolean? = when (val v = this[key]) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true)
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.map(key: String): Map<String, Any?>? =
        this[key] as? Map<String, Any?>

    private fun Map<String, Any?>.stringList(key: String): List<String> = when (val v = this[key]) {
        is List<*> -> v.mapNotNull { it?.toString() }
        is String -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }
}
