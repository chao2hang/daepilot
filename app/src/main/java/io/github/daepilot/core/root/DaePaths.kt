package io.github.daepilot.core.root

/** Fixed on-device locations shared by the app, Magisk module and daemon script. */
object DaePaths {
    const val BASE = "/data/adb/daepilot"
    const val MODULE = "/data/adb/modules/daepilot"
    const val MODULE_PROP = "$MODULE/module.prop"
    const val CTL = "$BASE/bin/daepilotctl"
    const val DAE = "$BASE/bin/dae"
    const val ROOTFS = "$BASE/rootfs"
    const val CONFIG_DIR = "$BASE/config"
    const val CONFIG = "$CONFIG_DIR/config.dae"
    const val CONFIG_BACKUP = "$CONFIG_DIR/config.dae.bak"
    const val STAGING = "$BASE/staging/config.dae"
    const val LOG_DIR = "$BASE/logs"
    const val LOG = "$LOG_DIR/dae.log"
    const val WATCHDOG_LOG = "$LOG_DIR/watchdog.log"
    const val RUN = "$BASE/run"
    const val PID = "$RUN/dae.pid"
    const val DNS_SERVERS = "$RUN/dns.servers"
    const val WATCHDOG_PID = "$RUN/watchdog.pid"
    const val ENABLED = "$BASE/enabled"
    const val VERSION = "$BASE/bin/VERSION"

    const val MODULE_ASSET = "daepilot-module.zip"
}
