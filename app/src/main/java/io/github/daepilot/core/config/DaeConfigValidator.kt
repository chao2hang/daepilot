package io.github.daepilot.core.config

/**
 * Cheap client-side checks for the rule editor. The authoritative check is
 * `dae validate`, which the module runs before replacing the live config; this
 * class only exists to catch mistakes while the user types.
 */
object DaeConfigValidator {
    data class Validation(val ok: Boolean, val message: String = "")

    private val routingFunctions = setOf(
        "domain", "dip", "sip", "dport", "sport", "l4proto", "ipversion",
        "mac", "pname", "dscp",
    )
    private val dnsFunctions = setOf("qname", "qtype", "upstream", "sub", "node", "subnode")

    fun validateRule(expression: String): Validation {
        return validateExpression(expression, routingFunctions)
    }

    fun validateDnsRule(expression: String): Validation {
        return validateExpression(expression, dnsFunctions)
    }

    private fun validateExpression(expression: String, functions: Set<String>): Validation {
        val expr = expression.trim()
        if (expr.isEmpty()) return Validation(false, "规则不能为空")
        if (expr.length > 2000) return Validation(false, "规则过长（上限 2000 字符）")
        if (expr.any { it == '\n' || it == '\r' || it == '{' || it == '}' }) {
            return Validation(false, "规则不能包含换行或花括号")
        }
        if (expr.contains("->")) return Validation(false, "只需填写匹配条件，出口在下方选择")
        if (!balanced(expr)) return Validation(false, "括号或引号不匹配")

        val calls = Regex("""([a-z_]+)\s*\(""").findAll(expr).map { it.groupValues[1] }.toList()
        if (calls.isEmpty()) return Validation(false, "至少需要一个匹配函数，例如 domain(geosite:cn)")
        val unknown = calls.filterNot { it in functions }
        if (unknown.isNotEmpty()) {
            return Validation(false, "未知匹配函数：${unknown.distinct().joinToString(", ")}")
        }
        return Validation(true)
    }

    private fun balanced(input: String): Boolean {
        var depth = 0
        var quote: Char? = null
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                quote != null -> {
                    if (c == '\\') {
                        i++
                    } else if (c == quote) {
                        quote = null
                    }
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

    /** Structural check of a rendered config before it leaves the app. */
    fun quickCheckConfig(config: String): Validation {
        val text = config.trim()
        if (text.isEmpty()) return Validation(false, "配置为空")
        listOf("global {", "dns {", "node {", "group {", "routing {").forEach { section ->
            if (!text.contains(section)) return Validation(false, "缺少 $section 段")
        }
        var depth = 0
        for (c in text) {
            if (c == '{') depth++
            if (c == '}') depth--
            if (depth < 0) return Validation(false, "花括号提前闭合")
        }
        if (depth != 0) return Validation(false, "花括号未闭合")
        return Validation(true)
    }
}
