package io.github.daepilot.core.root

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import io.github.daepilot.core.config.DaeConfigRenderer
import io.github.daepilot.core.model.CompatibilityReport
import io.github.daepilot.core.model.DaemonStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class ActionResult(val ok: Boolean, val message: String)

/**
 * Thin, defensive wrapper around the on-device `daepilotctl` script.
 *
 * The data plane is owned by the Magisk module, never by the app process:
 * every call here shells out to the script, which detaches dae with setsid and
 * keeps a watchdog alive. That is what makes the proxy survive task removal.
 */
class DaemonController(
    private val context: Context,
    private val root: RootShell,
) {
    private val checker = CompatibilityChecker(root)

    // The service poller, the notification actions and the UI all share one cached
    // root shell. Without this, a toggle from the notification and a toggle from
    // the screen can both run `daepilotctl start`, and an apply can interleave
    // with a stop.
    private val controlMutex = Mutex()

    private suspend fun <T> controlled(block: suspend () -> T): T = controlMutex.withLock { block() }

    suspend fun isModuleInstalled(): Boolean = root.exists(DaePaths.MODULE_PROP)

    suspend fun status(): DaemonStatus = withContext(Dispatchers.IO) {
        if (!root.isRootAvailable()) {
            return@withContext DaemonStatus(running = false, mode = "no-root", lastError = "未获得 root 权限")
        }
        if (!isModuleInstalled()) {
            return@withContext DaemonStatus(running = false, mode = "module-missing", lastError = "尚未安装 DaePilot Magisk 模块")
        }
        val result = root.runLenient("${DaePaths.CTL} status", timeoutMs = 20_000)
        val values = parseKeyValues(result.out)
        val running = values["running"] == "1"
        val uptime = values["uptime"]?.toLongOrNull() ?: 0L
        DaemonStatus(
            running = running,
            pid = values["pid"]?.toIntOrNull(),
            startedAt = if (running && uptime > 0) System.currentTimeMillis() - uptime * 1000 else 0L,
            version = values["version"]?.takeIf { it.isNotBlank() },
            configValid = values["config_valid"]?.let { it == "1" },
            lastError = values["last_error"]?.takeIf { it.isNotBlank() },
            mode = values["mode"] ?: "chroot",
            rssKb = values["rss_kb"]?.toLongOrNull() ?: 0L,
            cpuPercent = values["cpu_pct"]?.toDoubleOrNull(),
            bootEnabled = values["boot_enabled"] == "1",
            watchdog = values["watchdog"] == "1",
            desiredRunning = values["enabled"] == "1",
            wan = values["wan"]?.takeIf { it.isNotBlank() },
            wanIp = values["wan_ip"]?.takeIf { it.isNotBlank() },
            node = values["node"]?.takeIf { it.isNotBlank() },
            dns = values["dns"]?.takeIf { it.isNotBlank() },
            dnsMode = values["dns_mode"]?.takeIf { it.isNotBlank() },
            ipv6Mode = values["ipv6_mode"]?.takeIf { it.isNotBlank() },
            pnameRouting = when (values["pname"]) {
                "1" -> true
                "0" -> false
                else -> null
            },
            bypassUidCount = values["bypass_uid"]?.toIntOrNull(),
            lan = values["lan"]?.takeIf { it.isNotBlank() },
            lanClients = values["lan_clients"]?.toIntOrNull(),
            lanHook = values["lan_hook"]?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun start(): ActionResult = runCtl("start", timeoutMs = 120_000)
    suspend fun stop(): ActionResult = runCtl("stop", timeoutMs = 60_000)
    suspend fun reload(): ActionResult = runCtl("reload", timeoutMs = 120_000)
    suspend fun enableBootStart(): ActionResult = runCtl("enable")
    suspend fun disableBootStart(): ActionResult = runCtl("disable")

    /** The configuration currently deployed on the device, or null when unknown. */
    suspend fun readDeployedConfig(): String? = withContext(Dispatchers.IO) {
        if (!root.isRootAvailable()) return@withContext null
        val result = root.runLenient("cat ${DaePaths.CONFIG}", timeoutMs = 20_000)
        result.out.takeIf { it.isNotBlank() }
    }

    suspend fun applyConfig(config: String, reload: Boolean = true): ActionResult =
        withContext(Dispatchers.IO) {
            controlled {
                if (!root.isRootAvailable()) {
                    return@controlled ActionResult(false, "未获得 root 权限")
                }
                if (!isModuleInstalled()) {
                    return@controlled ActionResult(false, "尚未安装 DaePilot Magisk 模块")
                }
                if (!root.writeFile(DaePaths.STAGING, config, "600")) {
                    return@controlled ActionResult(false, "无法写入暂存配置（$DaePaths.STAGING）")
                }
                syncDnsServers()
                val flag = if (reload) " --reload" else ""
                val result = root.run(
                    "${DaePaths.CTL} apply ${RootShell.quote(DaePaths.STAGING)}$flag 2>&1",
                    timeoutMs = 150_000,
                )
                val message = result.out.ifBlank { result.err }.trim().ifBlank {
                    if (result.ok) "配置已应用" else "配置应用失败"
                }
                ActionResult(result.ok, message)
            }
        }

    /**
     * Publishes the UIDs of the bypass packages so the module can mark their
     * packets with [DaeConfigRenderer.BYPASS_DSCP]. This is the fallback for
     * ROMs where dae's pname cgroup hook cannot attach.
     */
    suspend fun syncBypassUids(packages: List<String>): ActionResult =
        withContext(Dispatchers.IO) {
            controlled {
                if (!root.isRootAvailable()) {
                    return@controlled ActionResult(false, "未获得 root 权限")
                }
                if (!isModuleInstalled()) {
                    return@controlled ActionResult(false, "尚未安装 DaePilot Magisk 模块")
                }
                val resolved = LinkedHashMap<String, Int>()
                val pm = context.packageManager
                for (pkg in packages) {
                    runCatching { pm.getApplicationInfo(pkg, 0).uid }
                        .getOrNull()
                        ?.let { resolved[pkg] = it }
                }
                val missing = packages.filter { it !in resolved }
                if (missing.isNotEmpty()) {
                    val table = parsePackageUids(
                        root.runLenient("pm list packages -U 2>/dev/null", 20_000).out,
                    )
                    missing.forEach { pkg -> table[pkg]?.let { resolved[pkg] = it } }
                }
                val stillMissing = packages.filter { it !in resolved }
                for (pkg in stillMissing) {
                    val out = root.runLenient(
                        "dumpsys package ${RootShell.quote(pkg)} 2>/dev/null | " +
                            "sed -n 's/.*userId=\\([0-9]*\\).*/\\1/p' | head -n 1",
                        15_000,
                    ).out.trim()
                    out.lineSequence().firstOrNull()?.toIntOrNull()?.let { resolved[pkg] = it }
                }
                val unresolved = packages.filter { it !in resolved }
                val uids = resolved.values.distinct().sorted()
                val content = if (uids.isEmpty()) "" else uids.joinToString("\n", postfix = "\n")
                if (!root.writeFile(DaePaths.BYPASS_UIDS, content, "600")) {
                    return@controlled ActionResult(false, "无法写入分应用 UID 列表")
                }
                val applied = root.run("${DaePaths.CTL} bypass 2>&1", timeoutMs = 20_000)
                val summary = buildString {
                    if (uids.isEmpty()) {
                        append("已清除分应用直连标记")
                    } else {
                        append("已为 ${uids.size} 个 UID 设置直连标记")
                    }
                    if (unresolved.isNotEmpty()) {
                        append("；未找到：")
                        append(unresolved.joinToString(", "))
                    }
                }
                ActionResult(
                    applied.ok,
                    if (applied.ok) {
                        summary
                    } else {
                        applied.out.ifBlank { applied.err }.trim().ifBlank { "应用分应用标记失败" }
                    },
                )
            }
        }

    suspend fun readLog(lines: Int = 300): String = withContext(Dispatchers.IO) {
        val result = root.runLenient(
            "${DaePaths.CTL} logs ${lines.coerceIn(20, 5000)}",
            timeoutMs = 20_000,
        )
        result.out.ifBlank { result.err.ifBlank { "暂无日志" } }
    }

    /** Size+mtime of the daemon log; lets pollers skip the tail when it is idle. */
    suspend fun logFingerprint(): String = withContext(Dispatchers.IO) {
        root.runLenient("stat -c '%s %Y' ${DaePaths.LOG}", timeoutMs = 5_000).out.trim()
    }

    suspend fun clearLog(): ActionResult = runCtl("clearlog")

    suspend fun diagnose(): CompatibilityReport = withContext(Dispatchers.IO) {
        val report = checker.check()
        val status = status()
        report.copy(
            daeVersion = report.daeVersion ?: status.version,
            note = report.note ?: status.lastError,
        )
    }

    suspend fun installBundledModule(): ActionResult = withContext(Dispatchers.IO) {
        controlled {
            if (!root.isRootAvailable()) return@controlled ActionResult(false, "未获得 root 权限")
            val zip = File(context.cacheDir, "daepilot-module.zip")
            val copied = runCatching {
                context.assets.open(DaePaths.MODULE_ASSET).use { input ->
                    zip.outputStream().use { output -> input.copyTo(output) }
                }
            }.isSuccess
            if (!copied || zip.length() < 1024) {
                return@controlled ActionResult(
                    false,
                    "安装包内置模块缺失，请在 Releases 下载 daepilot-module.zip 后通过 Magisk 刷入",
                )
            }
            val quoted = RootShell.quote(zip.absolutePath)
            val script = """
                if [ -x /data/adb/ksu/bin/ksud ]; then
                  /data/adb/ksu/bin/ksud module install $quoted && echo INSTALLED=ksu
                elif command -v magisk >/dev/null 2>&1; then
                  magisk --install-module $quoted && echo INSTALLED=magisk
                elif command -v apd >/dev/null 2>&1; then
                  apd module install $quoted && echo INSTALLED=apatch
                else
                  echo "NO_INSTALLER=1"
                fi
            """.trimIndent()
            val result = root.run(script, timeoutMs = 180_000)
            when {
                result.out.contains("INSTALLED=") -> ActionResult(
                    true,
                    "模块已安装，重启设备后生效。重启前请先确保已授予 root 权限。",
                )
                result.out.contains("NO_INSTALLER=1") -> ActionResult(
                    false,
                    "未找到 Magisk / KernelSU / APatch 安装器，请手动在管理器中刷入模块 zip",
                )
                else -> ActionResult(false, result.out.ifBlank { result.err }.trim().ifBlank { "模块安装失败" })
            }
        }
    }

    private suspend fun runCtl(command: String, timeoutMs: Long = 30_000): ActionResult =
        controlled {
            if (!root.isRootAvailable()) return@controlled ActionResult(false, "未获得 root 权限")
            if (!isModuleInstalled()) return@controlled ActionResult(false, "尚未安装 DaePilot Magisk 模块")
            if (!root.exists(DaePaths.CTL)) {
                return@controlled ActionResult(false, "模块控制脚本缺失，请重新刷入模块")
            }
            if (command == "start" || command == "reload") syncDnsServers()
            // Merge stderr into stdout: daepilotctl's fail() writes diagnostics to
            // stderr, and libsu captures the streams separately — without this the
            // app would show a generic "执行失败" instead of the real reason
            // (e.g. "配置校验失败：<dae output>").
            val result = root.run("${DaePaths.CTL} $command 2>&1", timeoutMs = timeoutMs)
            val text = result.out.ifBlank { result.err }.trim()
            ActionResult(result.ok, text.ifBlank { if (result.ok) "完成" else "执行失败" })
        }

    /**
     * Publishes the active network's resolvers for dae's bootstrap resolver.
     *
     * Android keeps `net.dns1..4` empty on many ROMs, and carriers routinely
     * drop queries to public resolvers, so the script cannot discover a usable
     * resolver on its own. `dumpsys` is not reachable from a root shell on
     * every build, but the app can always read [android.net.LinkProperties].
     */
    private suspend fun syncDnsServers() {
        val servers = runCatching { activeDnsServers() }.getOrDefault(emptyList())
        if (servers.isEmpty()) return
        root.writeFile(DaePaths.DNS_SERVERS, servers.joinToString("\n", postfix = "\n"), "600")
    }

    private fun activeDnsServers(): List<String> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val networks = LinkedHashSet<Network>()
        manager.activeNetwork?.let(networks::add)
        networks.addAll(manager.allNetworks)
        val servers = LinkedHashSet<String>()
        for (network in networks) {
            val properties = manager.getLinkProperties(network) ?: continue
            for (address in properties.dnsServers) {
                val host = address.hostAddress?.substringBefore('%') ?: continue
                if (host.isEmpty() || host == "::1" || host == "0.0.0.0") continue
                if (host.startsWith("127.") || host.startsWith("169.254.")) continue
                if (host.startsWith("fe80:", ignoreCase = true)) continue
                servers.add(host)
            }
        }
        return servers.toList()
    }

    private fun parsePackageUids(output: String): Map<String, Int> {
        val map = LinkedHashMap<String, Int>()
        val packagePattern = Regex("""package:(\S+)""")
        val uidPattern = Regex("""\buid:(\d+)\b""")
        for (line in output.lineSequence()) {
            val pkg = packagePattern.find(line)?.groupValues?.get(1) ?: continue
            val uid = uidPattern.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            map[pkg] = uid
        }
        return map
    }

    companion object {
        fun parseKeyValues(output: String): Map<String, String> =
            output.lineSequence().mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }.toMap()
    }
}
