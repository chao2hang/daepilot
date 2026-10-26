package io.github.daepilot.core.subscription

import io.github.daepilot.core.model.ShareLink
import java.util.Base64

/**
 * Converts Clash / sing-box structured proxy definitions into the share-link
 * formats dae's outbound library understands.
 *
 * dae only consumes share links, while most commercial subscriptions publish
 * Clash YAML or sing-box JSON. Keeping the conversion here (instead of asking
 * users to find a link-list endpoint) is the main feature differentiator.
 */
object ProxyUriFactory {
    fun shadowsocks(
        server: String,
        port: Int,
        cipher: String,
        password: String,
        plugin: String? = null,
        name: String? = null,
    ): String {
        val userInfo = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$cipher:$password".toByteArray(Charsets.UTF_8))
        val query = linkedMapOf<String, String?>()
        if (!plugin.isNullOrBlank()) query["plugin"] = plugin
        return ShareLink.build("ss", userInfo, server, port, query, name)
    }

    fun vmess(
        server: String,
        port: Int,
        uuid: String,
        name: String? = null,
        alterId: Int = 0,
        cipher: String = "auto",
        network: String = "tcp",
        wsPath: String? = null,
        wsHost: String? = null,
        tls: Boolean = false,
        sni: String? = null,
        alpn: List<String> = emptyList(),
        fingerprint: String? = null,
    ): String {
        val json = buildString {
            append('{')
            append("\"v\":\"2\",")
            append("\"ps\":\"").append(jsonEscape(name ?: "")).append("\",")
            append("\"add\":\"").append(jsonEscape(server)).append("\",")
            append("\"port\":\"").append(port).append("\",")
            append("\"id\":\"").append(jsonEscape(uuid)).append("\",")
            append("\"aid\":\"").append(alterId).append("\",")
            append("\"scy\":\"").append(jsonEscape(cipher)).append("\",")
            append("\"net\":\"").append(jsonEscape(network)).append("\",")
            append("\"type\":\"none\",")
            append("\"host\":\"").append(jsonEscape(wsHost ?: "")).append("\",")
            append("\"path\":\"").append(jsonEscape(wsPath ?: "")).append("\",")
            append("\"tls\":\"").append(if (tls) "tls" else "").append("\",")
            append("\"sni\":\"").append(jsonEscape(sni ?: "")).append("\",")
            append("\"alpn\":\"").append(jsonEscape(alpn.joinToString(","))).append("\",")
            append("\"fp\":\"").append(jsonEscape(fingerprint ?: "")).append("\"")
            append('}')
        }
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return "vmess://$encoded" + (name?.takeIf { it.isNotBlank() }?.let { "#${ShareLink.urlEncode(it)}" } ?: "")
    }

    fun vless(
        server: String,
        port: Int,
        uuid: String,
        name: String? = null,
        flow: String? = null,
        security: String? = null,
        sni: String? = null,
        fingerprint: String? = null,
        publicKey: String? = null,
        shortId: String? = null,
        network: String = "tcp",
        path: String? = null,
        host: String? = null,
        serviceName: String? = null,
        alpn: List<String> = emptyList(),
        allowInsecure: Boolean = false,
    ): String {
        val query = linkedMapOf<String, String?>(
            "encryption" to "none",
            "type" to network,
        )
        security?.takeIf { it.isNotBlank() }?.let { query["security"] = it }
        sni?.takeIf { it.isNotBlank() }?.let { query["sni"] = it }
        fingerprint?.takeIf { it.isNotBlank() }?.let { query["fp"] = it }
        publicKey?.takeIf { it.isNotBlank() }?.let { query["pbk"] = it }
        shortId?.takeIf { it.isNotBlank() }?.let { query["sid"] = it }
        flow?.takeIf { it.isNotBlank() }?.let { query["flow"] = it }
        path?.takeIf { it.isNotBlank() }?.let { query["path"] = it }
        host?.takeIf { it.isNotBlank() }?.let { query["host"] = it }
        serviceName?.takeIf { it.isNotBlank() }?.let { query["serviceName"] = it }
        if (alpn.isNotEmpty()) query["alpn"] = alpn.joinToString(",")
        if (allowInsecure) query["allowInsecure"] = "1"
        return ShareLink.build("vless", uuid, server, port, query, name)
    }

    fun trojan(
        server: String,
        port: Int,
        password: String,
        name: String? = null,
        sni: String? = null,
        network: String = "tcp",
        path: String? = null,
        host: String? = null,
        alpn: List<String> = emptyList(),
        allowInsecure: Boolean = false,
    ): String {
        val query = linkedMapOf<String, String?>(
            "security" to "tls",
            "type" to network,
        )
        sni?.takeIf { it.isNotBlank() }?.let { query["sni"] = it }
        path?.takeIf { it.isNotBlank() }?.let { query["path"] = it }
        host?.takeIf { it.isNotBlank() }?.let { query["host"] = it }
        if (alpn.isNotEmpty()) query["alpn"] = alpn.joinToString(",")
        if (allowInsecure) query["allowInsecure"] = "1"
        return ShareLink.build("trojan", password, server, port, query, name)
    }

    fun hysteria2(
        server: String,
        port: Int,
        password: String,
        name: String? = null,
        sni: String? = null,
        allowInsecure: Boolean = false,
        obfs: String? = null,
        obfsPassword: String? = null,
        upMbps: Int? = null,
        downMbps: Int? = null,
    ): String {
        val query = linkedMapOf<String, String?>()
        sni?.takeIf { it.isNotBlank() }?.let { query["sni"] = it }
        if (allowInsecure) query["insecure"] = "1"
        obfs?.takeIf { it.isNotBlank() }?.let { query["obfs"] = it }
        obfsPassword?.takeIf { it.isNotBlank() }?.let { query["obfs-password"] = it }
        upMbps?.takeIf { it > 0 }?.let { query["upmbps"] = it.toString() }
        downMbps?.takeIf { it > 0 }?.let { query["downmbps"] = it.toString() }
        return ShareLink.build("hysteria2", password, server, port, query, name)
    }

    fun tuic(
        server: String,
        port: Int,
        uuid: String,
        password: String,
        name: String? = null,
        sni: String? = null,
        congestionControl: String? = null,
        udpRelayMode: String? = null,
        allowInsecure: Boolean = false,
        alpn: List<String> = emptyList(),
    ): String {
        val query = linkedMapOf<String, String?>()
        sni?.takeIf { it.isNotBlank() }?.let { query["sni"] = it }
        congestionControl?.takeIf { it.isNotBlank() }?.let { query["congestion_control"] = it }
        udpRelayMode?.takeIf { it.isNotBlank() }?.let { query["udp_relay_mode"] = it }
        if (allowInsecure) query["allow_insecure"] = "1"
        if (alpn.isNotEmpty()) query["alpn"] = alpn.joinToString(",")
        return ShareLink.build("tuic", "$uuid:$password", server, port, query, name)
    }

    fun anytls(
        server: String,
        port: Int,
        password: String,
        name: String? = null,
        sni: String? = null,
        allowInsecure: Boolean = false,
    ): String {
        val query = linkedMapOf<String, String?>()
        sni?.takeIf { it.isNotBlank() }?.let { query["sni"] = it }
        if (allowInsecure) query["insecure"] = "1"
        return ShareLink.build("anytls", password, server, port, query, name)
    }

    fun socks(
        server: String,
        port: Int,
        username: String? = null,
        password: String? = null,
        name: String? = null,
        tls: Boolean = false,
    ): String {
        val userInfo = when {
            !username.isNullOrBlank() -> "$username:${password.orEmpty()}"
            else -> null
        }
        return ShareLink.build(if (tls) "https" else "socks5", userInfo, server, port, name = name)
    }

    fun http(
        server: String,
        port: Int,
        username: String? = null,
        password: String? = null,
        name: String? = null,
        tls: Boolean = false,
    ): String {
        val userInfo = when {
            !username.isNullOrBlank() -> "$username:${password.orEmpty()}"
            else -> null
        }
        return ShareLink.build(if (tls) "https" else "http", userInfo, server, port, name = name)
    }

    /** Clash SIP003 plugin fields -> dae/ss `plugin` query value. */
    fun sip003Plugin(name: String?, options: Map<String, Any?>?): String? {
        if (name.isNullOrBlank()) return null
        val parts = mutableListOf(name)
        val opts = options.orEmpty()
        when (name.lowercase()) {
            "obfs" -> {
                (opts["mode"] as? String)?.let { parts += "obfs=$it" } ?: parts.add("obfs=http")
                (opts["host"] as? String)?.let { parts += "obfs-host=$it" }
            }
            "v2ray-plugin" -> {
                (opts["mode"] as? String)?.let { parts += "mode=$it" }
                (opts["host"] as? String)?.let { parts += "host=$it" }
                (opts["path"] as? String)?.let { parts += "path=$it" }
                if (opts["tls"] == true) parts += "tls"
            }
            "shadow-tls", "shadowtls" -> {
                (opts["host"] as? String)?.let { parts += "host=$it" }
                (opts["password"] as? String)?.let { parts += "password=$it" }
                (opts["version"] as? Number)?.let { parts += "version=$it" }
            }
            else -> {
                opts.forEach { (k, v) ->
                    if (v != null && v.toString().isNotBlank()) parts += "$k=$v"
                }
            }
        }
        return parts.joinToString(";")
    }

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
}
