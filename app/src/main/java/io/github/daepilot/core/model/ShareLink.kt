package io.github.daepilot.core.model

import java.net.URLDecoder
import java.util.Base64

/**
 * Share-link helpers shared by the parsers, renderer and latency prober.
 *
 * Everything here must stay pure JVM so it can be unit tested without Android.
 */
object ShareLink {
    fun decodeBase64Lenient(input: String): String? {
        val cleaned = input.filterNot { it.isWhitespace() }
            .replace('-', '+')
            .replace('_', '/')
        if (cleaned.isEmpty()) return null
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            0 -> cleaned
            else -> return null
        }
        return try {
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun encodeBase64(input: ByteArray): String = Base64.getEncoder().encodeToString(input)

    fun encodeBase64Url(input: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(input)

    /** Protocol scheme as written in the link. */
    fun scheme(link: String): String? =
        link.trim().substringBefore("://", "").takeIf { it.isNotEmpty() }?.lowercase()

    /** dae expects hysteria2://; subscription links often use the hy2:// alias. */
    fun canonicalize(link: String): String {
        val trimmed = link.trim()
        return if (trimmed.startsWith("hy2://", ignoreCase = true)) {
            "hysteria2://" + trimmed.substringAfter("://")
        } else {
            trimmed
        }
    }

    /** Display name from the URL fragment, percent-decoded. */
    fun name(link: String): String? {
        val raw = link.trim().substringAfter('#', "")
        if (raw.isEmpty()) return null
        return try {
            URLDecoder.decode(raw, "UTF-8").trim().ifEmpty { null }
        } catch (_: Exception) {
            raw.trim().ifEmpty { null }
        }
    }

    /**
     * Host and port of the proxy server.
     *
     * Handles:
     *  - standard `scheme://userinfo@host:port`
     *  - `ss://base64(method:password)@host:port`
     *  - legacy `ss://base64(method:password@host:port)`
     *  - `vmess://base64(json)`
     */
    fun endpoint(link: String): ShareEndpoint? {
        val trimmed = link.trim()
        return when (scheme(trimmed)) {
            "vmess" -> vmessEndpoint(trimmed)
            "ss" -> ssEndpoint(trimmed)
            "ssr" -> ssrEndpoint(trimmed)
            null -> null
            else -> authorityEndpoint(trimmed)
        }
    }

    private fun authorityEndpoint(link: String): ShareEndpoint? {
        val rest = link.substringAfter("://", "")
        if (rest.isEmpty()) return null
        val beforeQuery = rest.substringBefore('?').substringBefore('#')
        val authority = beforeQuery.substringBefore('/')
        val hostPort = authority.substringAfterLast('@')
        return splitHostPort(hostPort)
    }

    private fun ssEndpoint(link: String): ShareEndpoint? {
        val rest = link.substringAfter("://", "").substringBefore('#')
        val beforeQuery = rest.substringBefore('?')
        if (beforeQuery.contains('@')) {
            val hostPort = beforeQuery.substringAfterLast('@')
            return splitHostPort(hostPort)
        }
        // Legacy form: the complete payload is base64.
        val decoded = decodeBase64Lenient(beforeQuery) ?: return null
        val hostPort = decoded.substringAfterLast('@')
        return splitHostPort(hostPort)
    }

    /**
     * `ssr://base64url(server:port:protocol:cipher:password:obfs)` keeps the host
     * inside the payload, so the authority parser would hand the whole blob back
     * as the hostname.
     */
    private fun ssrEndpoint(link: String): ShareEndpoint? {
        val payload = link.substringAfter("://", "").substringBefore('#').substringBefore('/')
        if (payload.isEmpty()) return null
        val decoded = decodeBase64Lenient(payload) ?: return null
        val firstColon = decoded.indexOf(':')
        if (firstColon <= 0) return null
        val bracketed = decoded.startsWith('[')
        val host = if (bracketed) {
            decoded.substringBefore(']').removePrefix("[")
        } else {
            decoded.substringBefore(':')
        }
        if (host.isEmpty()) return null
        val tail = decoded.substring(if (bracketed) decoded.indexOf(']') + 1 else firstColon)
        return ShareEndpoint(host, tail.removePrefix(":").takeWhile { it.isDigit() }.toIntOrNull())
    }

    private fun vmessEndpoint(link: String): ShareEndpoint? {
        val payload = link.substringAfter("://", "").substringBefore('#')
        val json = decodeBase64Lenient(payload) ?: return null
        val host = extractJsonString(json, "add") ?: return null
        val port = extractJsonString(json, "port")?.toIntOrNull()
        return ShareEndpoint(host, port)
    }

    fun splitHostPort(value: String): ShareEndpoint? {
        val v = value.trim()
        if (v.isEmpty()) return null
        if (v.startsWith("[")) {
            val end = v.indexOf(']')
            if (end <= 1) return null
            val host = v.substring(1, end)
            val port = v.substring(end + 1).removePrefix(":").toIntOrNull()
            return ShareEndpoint(host, port)
        }
        // A bare IPv6 address is never host:port; ports require brackets.
        if (v.count { it == ':' } > 1) return ShareEndpoint(v, null)
        val lastColon = v.lastIndexOf(':')
        if (lastColon > 0) {
            val host = v.substring(0, lastColon)
            val port = v.substring(lastColon + 1).toIntOrNull()
            if (port != null) return ShareEndpoint(host, port)
        }
        return ShareEndpoint(v, null)
    }

    private fun extractJsonString(json: String, key: String): String? {
        val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val match = regex.find(json) ?: return null
        return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
    }

    /** Build a `scheme://...` URI with percent-encoded credentials. */
    fun build(
        scheme: String,
        userInfo: String?,
        host: String,
        port: Int,
        query: Map<String, String?> = emptyMap(),
        name: String? = null,
    ): String {
        val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val queryString = query.entries
            .filter { !it.value.isNullOrBlank() }
            .joinToString("&") { (k, v) ->
                "${urlEncode(k)}=${urlEncode(v.orEmpty())}"
            }
        val fragment = name?.takeIf { it.isNotBlank() }?.let { "#${urlEncode(it)}" } ?: ""
        val auth = if (userInfo.isNullOrBlank()) "" else "${urlEncodeAll(userInfo)}@"
        return buildString {
            append(scheme).append("://").append(auth).append(hostPart).append(':').append(port)
            if (queryString.isNotEmpty()) append('?').append(queryString)
            append(fragment)
        }
    }

    fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Userinfo may contain `uuid:password`; the colon must survive. */
    private fun urlEncodeAll(value: String): String =
        value.split(':').joinToString(":") { urlEncode(it) }

    /**
     * Rewrites the proxy display name dae parses from the link.
     *
     * dae's `filter: name(...)` matches the dialer name (the link fragment or
     * vmess `ps`), not the node section tag, so group membership can only be
     * expressed reliably after the link names are made unique.
     * Returns null when the vmess payload cannot be rewritten safely.
     */
    fun withName(link: String, newName: String): String? {
        val trimmed = link.trim()
        if (newName.isBlank()) return trimmed
        if (scheme(trimmed) == "vmess" && !trimmed.substringAfter("://", "").contains('@')) {
            return renameVmess(trimmed, newName)
        }
        return trimmed.substringBefore('#') + "#" + urlEncode(newName)
    }

    private fun renameVmess(link: String, newName: String): String? {
        val payload = link.substringAfter("://", "").substringBefore('#')
        val json = decodeBase64Lenient(payload) ?: return null
        val escaped = newName.replace("\\", "\\\\").replace("\"", "\\\"")
        val psField = Regex("\"ps\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"")
        val updated = when {
            psField.containsMatchIn(json) ->
                json.replace(psField, "\"ps\":\"$escaped\"")
            json.trimEnd().endsWith("}") ->
                json.trimEnd().dropLast(1) + ",\"ps\":\"$escaped\"}"
            else -> return null
        }
        return "vmess://" + encodeBase64(updated.toByteArray(Charsets.UTF_8))
    }
}

/** Byte-level subscription decoding, kept separate so it is trivial to test. */
object SubscriptionDecoder {
    fun decode(bytes: ByteArray): String {
        val text = bytes.toString(Charsets.UTF_8)
        if (looksLikeLinkList(text)) return text
        val decoded = ShareLink.decodeBase64Lenient(text)
        if (decoded != null && looksLikeLinkList(decoded)) return decoded
        if (decoded != null && (decoded.trimStart().startsWith("{") || decoded.contains("proxies:"))) {
            return decoded
        }
        return text
    }

    fun looksLikeLinkList(text: String): Boolean =
        text.lineSequence().map { it.trim() }.any { line ->
            line.substringBefore("://", "").lowercase() in KNOWN_SCHEMES
        }

    val KNOWN_SCHEMES = setOf(
        "ss", "ssr", "vmess", "vless", "trojan", "hysteria", "hysteria2", "hy2",
        "tuic", "anytls", "socks", "socks5", "http", "https", "juicity", "wireguard",
    )
}
