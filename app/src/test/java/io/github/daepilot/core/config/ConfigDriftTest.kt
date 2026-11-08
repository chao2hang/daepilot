package io.github.daepilot.core.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDriftTest {
    private val rendered = """
        global {
          wan_interface: auto
          log_level: info
        }
    """.trimIndent()

    @Test
    fun identicalConfigsDoNotDrift() {
        assertFalse(ConfigDrift.differs(rendered, rendered))
    }

    @Test
    fun thePinnedWanInterfaceIsNotDrift() {
        val deployed = rendered.replace("wan_interface: auto", "wan_interface: rmnet_data0")
        assertFalse(ConfigDrift.differs(rendered, deployed))
    }

    @Test
    fun aChangedRuleIsDrift() {
        val deployed = rendered.replace("log_level: info", "log_level: debug")
        assertTrue(ConfigDrift.differs(rendered, deployed))
    }

    @Test
    fun trailingWhitespaceIsIgnored() {
        assertFalse(ConfigDrift.differs(rendered + "\n", rendered))
    }
}
