package io.github.daepilot.core.config

import io.github.daepilot.core.model.GroupPolicy
import io.github.daepilot.core.model.Node
import io.github.daepilot.core.model.NodeProtocol
import io.github.daepilot.core.model.OutboundGroup
import io.github.daepilot.core.model.RenderSpec
import io.github.daepilot.core.model.RoutingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestPlanTest {
    private val node = Node(
        id = "n1",
        name = "SG",
        protocol = NodeProtocol.HYSTERIA2,
        link = "hysteria2://user@1.2.3.4:443",
    )
    private val other = Node(
        id = "n2",
        name = "HK",
        protocol = NodeProtocol.HYSTERIA2,
        link = "hysteria2://user@5.6.7.8:443",
    )

    @Test
    fun `pins the proxy to the target and prepends the test rule`() {
        val spec = RenderSpec(
            nodes = listOf(node, other),
            groups = listOf(
                OutboundGroup(
                    id = "g1",
                    name = "HK_GROUP",
                    policy = GroupPolicy.MIN,
                    nodeIds = listOf(other.id),
                ),
            ),
            selectedNodeId = other.id,
            customRules = listOf(
                RoutingRule(id = "r1", expression = "domain(example.com)", outbound = "proxy"),
            ),
            bypassPackages = listOf("io.github.daepilot.debug", "com.example"),
        )
        val plan = requireNotNull(
            SpeedTestPlan.build(spec, node.id, "io.github.daepilot.debug"),
        )
        assertEquals(node.id, plan.selectedNodeId)
        assertEquals(spec.groups, plan.groups)
        assertEquals(2, plan.customRules.size)
        assertEquals("domain(speed.cloudflare.com)", plan.customRules[0].expression)
        assertEquals("proxy", plan.customRules[0].outbound)
        assertEquals("domain(example.com)", plan.customRules[1].expression)
        assertFalse(plan.bypassPackages.contains("io.github.daepilot.debug"))
        assertTrue(plan.bypassPackages.contains("com.example"))
    }

    @Test
    fun `returns null when the node is not in the rendered spec`() {
        val spec = RenderSpec(nodes = listOf(other))
        assertNull(SpeedTestPlan.build(spec, node.id, "self"))
    }

    @Test
    fun `keeps a target that is already selected`() {
        val spec = RenderSpec(nodes = listOf(node), selectedNodeId = node.id)
        val plan = requireNotNull(SpeedTestPlan.build(spec, node.id, ""))
        assertEquals(node.id, plan.selectedNodeId)
        assertEquals(1, plan.customRules.size)
        assertTrue(plan.bypassPackages.isEmpty())
    }
}
