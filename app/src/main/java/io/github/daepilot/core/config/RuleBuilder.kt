package io.github.daepilot.core.config

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/** A rule shape the editor can build without the user writing dae syntax. */
enum class RuleKind(val label: String) {
    DOMAIN("域名"),
    IP("IP / 网段"),
    PRESET("内置清单"),
    PORT("端口"),
    ADVANCED("高级"),
}

enum class DomainMatch(val label: String) {
    SUFFIX("后缀"),
    FULL("完整"),
    KEYWORD("关键词"),
    REGEX("正则"),
}

enum class PresetSource(val label: String) {
    GEOSITE("域名清单"),
    GEOIP("IP 清单"),
}

/**
 * Editor state for one rule. Fields that do not belong to [kind] are kept so
 * switching types back and forth does not lose what the user typed.
 */
data class RuleDraft(
    val kind: RuleKind = RuleKind.DOMAIN,
    val domainMatch: DomainMatch = DomainMatch.SUFFIX,
    val domain: String = "",
    val presetSource: PresetSource = PresetSource.GEOSITE,
    val preset: String = "",
    val ip: String = "",
    val ports: String = "",
    val advanced: String = "",
)

data class RuleBuild(val expression: String = "", val error: String? = null) {
    val ok: Boolean get() = error == null && expression.isNotBlank()
}

/**
 * Translates the structured editor into dae routing expressions and back.
 *
 * Only the shapes this editor can generate are parsed back; anything else is
 * treated as an advanced rule, so editing a hand-written expression never
 * silently rewrites it.
 */
object RuleBuilder {
    private val hosts = Regex("""^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)*$""")
    private val presets = Regex("""^[a-z0-9][a-z0-9_!-]*$""")
    private val splitter = Regex("[,;\\s]+")

    val geositeSuggestions = listOf(
        "cn", "geolocation-!cn", "openai", "google", "youtube",
        "telegram", "netflix", "github", "category-ads-all",
    )
    val geoipSuggestions = listOf("cn", "private", "us", "jp", "hk", "sg")

    fun build(draft: RuleDraft): RuleBuild {
        val raw = when (draft.kind) {
            RuleKind.DOMAIN -> buildDomain(draft)
            RuleKind.IP -> buildIp(draft)
            RuleKind.PRESET -> buildPreset(draft)
            RuleKind.PORT -> buildPorts(draft)
            RuleKind.ADVANCED -> buildAdvanced(draft)
        }
        if (raw.error != null) return raw
        val validation = DaeConfigValidator.validateRule(raw.expression)
        return if (validation.ok) raw else RuleBuild(error = validation.message)
    }

    private fun buildDomain(draft: RuleDraft): RuleBuild {
        val raw = draft.domain.trim()
        if (raw.isEmpty()) return RuleBuild(error = "请输入至少一个域名")
        if (draft.domainMatch == DomainMatch.REGEX) {
            if (raw.any { it == '\'' || it == '\n' || it == '\r' }) {
                return RuleBuild(error = "正则不能包含单引号或换行")
            }
            return RuleBuild("domain(regex: '$raw')")
        }
        val tokens = tokens(raw)
        if (tokens.isEmpty()) return RuleBuild(error = "请输入至少一个域名")
        val values = mutableListOf<String>()
        for (token in tokens) {
            when (draft.domainMatch) {
                DomainMatch.KEYWORD -> {
                    if (!token.all { it.isLetterOrDigit() || it in "._-*" }) {
                        return RuleBuild(error = "关键词「$token」包含无效字符")
                    }
                    values += token.lowercase()
                }
                else -> {
                    if (token.lowercase().startsWith("geosite:")) {
                        return RuleBuild(error = "请切换到「内置清单」选择 geosite 清单")
                    }
                    if (isIpLiteral(token)) {
                        return RuleBuild(error = "「$token」是 IP，请切换到「IP / 网段」")
                    }
                    values += normalizeHost(token)
                        ?: return RuleBuild(error = "「$token」不是有效域名")
                }
            }
        }
        val prefix = when (draft.domainMatch) {
            DomainMatch.SUFFIX -> "suffix"
            DomainMatch.FULL -> "full"
            DomainMatch.KEYWORD -> "keyword"
            DomainMatch.REGEX -> "regex"
        }
        return RuleBuild("domain(" + values.joinToString(", ") { "$prefix: $it" } + ")")
    }

    private fun buildIp(draft: RuleDraft): RuleBuild {
        val tokens = tokens(draft.ip)
        if (tokens.isEmpty()) return RuleBuild(error = "请输入至少一个 IP 或 CIDR")
        val values = tokens.map { token ->
            if (token.lowercase().startsWith("geoip:")) {
                return RuleBuild(error = "请切换到「内置清单」选择 geoip 清单")
            }
            normalizeCidr(token) ?: return RuleBuild(error = "「$token」不是有效的 IP 或 CIDR")
        }
        return RuleBuild("dip(" + values.joinToString(", ") + ")")
    }

    private fun buildPreset(draft: RuleDraft): RuleBuild {
        val tokens = tokens(draft.preset)
        if (tokens.isEmpty()) return RuleBuild(error = "请输入至少一个清单代码")
        val prefix = if (draft.presetSource == PresetSource.GEOSITE) "geosite" else "geoip"
        val codes = tokens.map { token ->
            val code = token.lowercase()
            if (!presets.matches(code)) {
                return RuleBuild(error = "清单代码「$token」无效；只填代码，不要带 $prefix: 前缀")
            }
            code
        }
        val function = if (draft.presetSource == PresetSource.GEOSITE) "domain" else "dip"
        return RuleBuild("$function(" + codes.joinToString(", ") { "$prefix:$it" } + ")")
    }

    private fun buildPorts(draft: RuleDraft): RuleBuild {
        val tokens = tokens(draft.ports)
        if (tokens.isEmpty()) return RuleBuild(error = "请输入至少一个端口")
        val values = tokens.map { token ->
            normalizePort(token) ?: return RuleBuild(error = "「$token」不是有效端口或端口段")
        }
        return RuleBuild("dport(" + values.joinToString(", ") + ")")
    }

    private fun buildAdvanced(draft: RuleDraft): RuleBuild {
        val expression = draft.advanced.trim()
        val validation = DaeConfigValidator.validateRule(expression)
        return if (validation.ok) {
            RuleBuild(expression)
        } else {
            RuleBuild(error = validation.message)
        }
    }

    /** Null when the expression is not one of the shapes this editor generates. */
    fun parse(expression: String): RuleDraft? {
        val call = parseCall(expression) ?: return null
        return when (call.name) {
            "domain" -> parseDomainCall(call.args)
            "dip" -> parseDipCall(call.args)
            "dport" -> parsePortCall(call.args)
            else -> null
        }
    }

    /** Short, human-readable label for rule lists. */
    fun describe(expression: String): String {
        val draft = parse(expression) ?: return expression.trim()
        return when (draft.kind) {
            RuleKind.DOMAIN -> when (draft.domainMatch) {
                DomainMatch.SUFFIX -> "域名 · ${draft.domain}"
                DomainMatch.FULL -> "完整域名 · ${draft.domain}"
                DomainMatch.KEYWORD -> "域名关键词 · ${draft.domain}"
                DomainMatch.REGEX -> "域名正则 · ${draft.domain}"
            }
            RuleKind.IP -> "IP / 网段 · ${draft.ip}"
            RuleKind.PRESET -> if (draft.presetSource == PresetSource.GEOSITE) {
                "域名清单 · ${draft.preset}"
            } else {
                "IP 清单 · ${draft.preset}"
            }
            RuleKind.PORT -> "端口 · ${draft.ports}"
            RuleKind.ADVANCED -> expression.trim()
        }
    }

    private data class Call(val name: String, val args: List<String>)

    private fun parseCall(expression: String): Call? {
        val text = expression.trim()
        val open = text.indexOf('(')
        if (open <= 0 || !text.endsWith(')')) return null
        if (!text.substring(0, open).trim().matches(Regex("[a-z_]+"))) return null
        val argsText = text.substring(open + 1, text.length - 1)
        if (!balanced(argsText) || hasTopLevelOperator(argsText)) return null
        val args = splitArgs(argsText)
        if (args.isEmpty()) return null
        return Call(text.substring(0, open).trim(), args)
    }

    private fun parseDomainCall(args: List<String>): RuleDraft? {
        val entries = args.map { raw ->
            val value = unquote(raw)
            val colon = value.indexOf(':')
            if (colon > 0) {
                val prefix = value.substring(0, colon).trim().lowercase()
                if (prefix in setOf("suffix", "full", "keyword", "regex", "geosite")) {
                    prefix to value.substring(colon + 1).trim().removeSurrounding("'")
                } else {
                    return null
                }
            } else {
                null to value
            }
        }
        val prefixes = entries.map { it.first }.distinct()
        if (prefixes == listOf("geosite")) {
            val codes = entries.map { it.second }
            if (codes.any { !presets.matches(it) }) return null
            return RuleDraft(
                kind = RuleKind.PRESET,
                presetSource = PresetSource.GEOSITE,
                preset = codes.joinToString(", "),
            )
        }
        val values = entries.map { it.second }
        return when {
            prefixes == listOf<String?>(null) || prefixes == listOf("suffix") ->
                RuleDraft(kind = RuleKind.DOMAIN, domainMatch = DomainMatch.SUFFIX, domain = values.joinToString(", "))
            prefixes == listOf("full") ->
                RuleDraft(kind = RuleKind.DOMAIN, domainMatch = DomainMatch.FULL, domain = values.joinToString(", "))
            prefixes == listOf("keyword") ->
                RuleDraft(kind = RuleKind.DOMAIN, domainMatch = DomainMatch.KEYWORD, domain = values.joinToString(", "))
            prefixes == listOf("regex") && values.size == 1 ->
                RuleDraft(kind = RuleKind.DOMAIN, domainMatch = DomainMatch.REGEX, domain = values.single())
            else -> null
        }
    }

    private fun parseDipCall(args: List<String>): RuleDraft? {
        val values = args.map { unquote(it) }
        if (values.all { it.startsWith("geoip:") }) {
            val codes = values.map { it.removePrefix("geoip:") }
            if (codes.any { !presets.matches(it) }) return null
            return RuleDraft(
                kind = RuleKind.PRESET,
                presetSource = PresetSource.GEOIP,
                preset = codes.joinToString(", "),
            )
        }
        if (values.any { it.startsWith("geoip:") }) return null
        return RuleDraft(kind = RuleKind.IP, ip = values.joinToString(", "))
    }

    private fun parsePortCall(args: List<String>): RuleDraft? {
        val values = args.map { unquote(it) }
        if (values.any { normalizePort(it) == null }) return null
        return RuleDraft(kind = RuleKind.PORT, ports = values.joinToString(", "))
    }

    private fun tokens(raw: String): List<String> =
        raw.split(splitter).map { it.trim() }.filter { it.isNotEmpty() }

    private fun normalizeHost(raw: String): String? {
        var value = raw.trim().trimEnd('.').lowercase()
        if (value.isEmpty()) return null
        if (value.startsWith("*.")) value = value.removePrefix("*.")
        if (value.contains("://")) {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            value = uri.host ?: return null
        } else {
            value = value.substringBefore('/')
            if (value.count { it == ':' } == 1) value = value.substringBefore(':')
        }
        value = value.trimEnd('.').lowercase()
        if (value.any { it.code > 127 }) {
            value = runCatching { IDN.toASCII(value) }.getOrNull() ?: return null
        }
        if (!hosts.matches(value)) return null
        return value
    }

    private fun normalizeCidr(raw: String): String? {
        val value = unquote(raw).trim()
        if (value.isEmpty()) return null
        val slash = value.indexOf('/')
        val address = if (slash >= 0) value.substring(0, slash) else value
        val prefix = if (slash >= 0) value.substring(slash + 1) else null
        if (slash >= 0 && (prefix.isNullOrEmpty() || prefix.toIntOrNull() == null)) return null
        if (isIpv4(address)) {
            val bits = prefix?.toIntOrNull()
            if (bits != null && bits !in 0..32) return null
            return if (bits != null) "$address/$bits" else address
        }
        if (isIpv6(address)) {
            val bits = prefix?.toIntOrNull()
            if (bits != null && bits !in 0..128) return null
            return if (bits != null) "'$address/$bits'" else "'$address'"
        }
        return null
    }

    private fun normalizePort(token: String): String? {
        val value = token.trim()
        if (value.isEmpty()) return null
        val parts = value.split('-', limit = 2)
        val start = parts[0].toIntOrNull() ?: return null
        if (start !in 1..65535) return null
        if (parts.size == 1) return start.toString()
        val end = parts[1].toIntOrNull() ?: return null
        if (end !in start..65535) return null
        return "$start-$end"
    }

    private fun isIpLiteral(value: String): Boolean {
        val address = value.substringBefore('/')
        return isIpv4(address) || isIpv6(address)
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

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length >= 2 &&
            ((trimmed.first() == '\'' && trimmed.last() == '\'') ||
                (trimmed.first() == '"' && trimmed.last() == '"'))
        ) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        return trimmed
    }

    private fun splitArgs(input: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var depth = 0
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                quote != null -> {
                    current.append(c)
                    if (c == '\\' && i + 1 < input.length) {
                        current.append(input[i + 1])
                        i++
                    } else if (c == quote) {
                        quote = null
                    }
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    current.append(c)
                }
                c == '(' -> {
                    depth++
                    current.append(c)
                }
                c == ')' -> {
                    depth--
                    current.append(c)
                }
                c == ',' && depth == 0 -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        result += current.toString()
        return result.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun balanced(input: String): Boolean {
        var depth = 0
        var quote: Char? = null
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                quote != null -> {
                    if (c == '\\') i++ else if (c == quote) quote = null
                }
                c == '\'' || c == '"' -> quote = c
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
            i++
        }
        return depth == 0 && quote == null
    }

    private fun hasTopLevelOperator(input: String): Boolean {
        var quote: Char? = null
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                quote != null -> {
                    if (c == '\\') i++ else if (c == quote) quote = null
                }
                c == '\'' || c == '"' -> quote = c
                c == '&' || c == '|' || c == '-' -> {
                    if (i + 1 < input.length && input[i + 1] == c) return true
                    if (c == '-' && i + 1 < input.length && input[i + 1] == '>') return true
                }
            }
            i++
        }
        return false
    }
}
