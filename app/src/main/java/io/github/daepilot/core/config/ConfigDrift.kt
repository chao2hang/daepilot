package io.github.daepilot.core.config

/**
 * Compares the configuration the app renders with the one deployed on the
 * device.
 *
 * The module pins `wan_interface` to the detected link in the deployed file,
 * so that line is normalized away: without it every check would report drift
 * and reload the daemon for nothing.
 */
object ConfigDrift {
    private val wanLine = Regex("^([ \\t]*wan_interface:[ \\t]*)\\S+.*$", RegexOption.MULTILINE)

    fun normalize(config: String): String =
        wanLine.replace(config.trim()) { "${it.groupValues[1]}auto" }

    fun differs(rendered: String, deployed: String): Boolean =
        normalize(rendered) != normalize(deployed)
}
