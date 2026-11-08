package io.github.daepilot.core.config

import io.github.daepilot.core.model.RenderSpec
import io.github.daepilot.core.model.RoutingRule

/**
 * Builds the config used for a node speed test. The proxy group is pinned to
 * [nodeId] for the duration of the test so both the name lookup and the test
 * traffic use that node; the caller re-applies the user's config afterwards.
 */
object SpeedTestPlan {
    const val TEST_HOST = "speed.cloudflare.com"

    fun build(spec: RenderSpec, nodeId: String, selfPackage: String): RenderSpec? {
        if (spec.nodes.none { it.id == nodeId }) return null
        val rule = RoutingRule(
            id = "speedtest-domain",
            expression = "domain($TEST_HOST)",
            outbound = "proxy",
        )
        return spec.copy(
            selectedNodeId = nodeId,
            customRules = listOf(rule) + spec.customRules,
            bypassPackages = spec.bypassPackages.filterNot { isSelfPackage(it, selfPackage) },
        )
    }

    private fun isSelfPackage(entry: String, selfPackage: String): Boolean {
        val value = entry.trim()
        if (value.isEmpty() || selfPackage.isEmpty()) return false
        return value.equals(selfPackage, ignoreCase = true) ||
            value.takeLast(15).equals(selfPackage.takeLast(15), ignoreCase = true)
    }
}
