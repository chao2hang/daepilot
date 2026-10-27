package io.github.daepilot.core.root

import io.github.daepilot.core.model.CompatibilityReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads kernel and Android prerequisites with a single root round-trip.
 * The script prints `KEY=value` lines; unknown keys are ignored so the parser
 * keeps working when a shell utility is missing.
 */
class CompatibilityChecker(private val root: RootShell) {
    suspend fun check(): CompatibilityReport = withContext(Dispatchers.IO) {
        val rootAvailable = root.isRootAvailable()
        if (!rootAvailable) {
            return@withContext CompatibilityReport(
                rootAvailable = false,
                note = "未获得 root 权限",
            )
        }
        val script = """
            echo "KERNEL=${'$'}(uname -r)"
            if [ -e /sys/kernel/btf/vmlinux ]; then echo "BTF=1"; else echo "BTF=0"; fi
            if grep -q cgroup2 /proc/mounts; then echo "CGROUP2=1"; else echo "CGROUP2=0"; fi
            if [ -e /proc/sys/net/core/bpf_jit_enable ]; then
              echo "BPFJIT=${'$'}(cat /proc/sys/net/core/bpf_jit_enable 2>/dev/null)"
            else
              echo "BPFJIT=1"
            fi
            echo "MEMAVAIL=${'$'}(awk '/MemAvailable/ {print int(${'$'}2/1024)}' /proc/meminfo)"
            echo "SELINUX=${'$'}(getenforce 2>/dev/null || echo Unknown)"
            if [ -f ${DaePaths.MODULE_PROP} ]; then echo "MODULE=1"; else echo "MODULE=0"; fi
            if [ -x ${DaePaths.DAE} ]; then echo "DAEBIN=1"; else echo "DAEBIN=0"; fi
            echo "DAEVER=${'$'}(${DaePaths.DAE} --version 2>/dev/null | head -n 1)"
            # Whether pname routing actually attached, per the daemon's own log: a
            # mounted cgroup v2 does not imply the hook could be installed. dae
            # emits one "Bind to WAN" line per control plane right after the
            # warning, so the warning belongs to the live generation iff it sits
            # after the second-to-last of them.
            awk '/Bind to WAN/ { prev = last; last = NR }
                 /pname routing cannot be used/ { warn = NR }
                 END { printf "PNAME_WARN=%d\nPNAME_PREV=%d\n", warn, prev }' \
              ${DaePaths.LOG} 2>/dev/null || echo "PNAME_WARN=0"
            echo "PROCS=${'$'}(ps -A -o NAME= 2>/dev/null | tr '\n' ' ')"
            if [ -d /data/adb/box ]; then echo "BOXFILES=1"; else echo "BOXFILES=0"; fi
            echo "IPT=${'$'}(iptables -t mangle -S 2>/dev/null | grep -ciE 'box|clash|sing-box|mihomo' || true)"
            echo "END=1"
        """.trimIndent()

        val result = root.runLenient(script, timeoutMs = 25_000)
        val values = result.out.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()

        val kernel = values["KERNEL"].orEmpty()
        val (major, minor) = parseKernel(kernel)
        val kernelOk = major > 5 || (major == 5 && minor >= 15)
        val memMb = values["MEMAVAIL"]?.toLongOrNull() ?: 0L
        val jitRaw = values["BPFJIT"]
        val jit = jitRaw == null || jitRaw == "1" || jitRaw.toIntOrNull()?.let { it > 0 } == true

        val conflicts = buildList {
            val procs = values["PROCS"].orEmpty().lowercase()
            listOf("sing-box", "clash", "mihomo", "xray", "v2ray", "hysteria").forEach { name ->
                if (procs.split(' ').any { it == name || it.startsWith("$name-") }) {
                    add("检测到 $name 进程")
                }
            }
            if (values["BOXFILES"] == "1") add("检测到 /data/adb/box（Box for Magisk）")
            if ((values["IPT"]?.toIntOrNull() ?: 0) > 0) add("iptables mangle 表存在代理规则")
        }

        CompatibilityReport(
            kernelRelease = kernel,
            kernelMajor = major,
            kernelMinor = minor,
            kernelOk = kernelOk,
            btfPresent = values["BTF"] == "1",
            cgroup2Mounted = values["CGROUP2"] == "1",
            bpfJitEnabled = jit,
            memAvailableMb = memMb,
            memoryOk = memMb >= MIN_MEMORY_MB,
            rootAvailable = true,
            moduleInstalled = values["MODULE"] == "1",
            daeBinaryPresent = values["DAEBIN"] == "1",
            daeVersion = values["DAEVER"]?.takeIf { it.isNotBlank() && it != "null" },
            conflicts = conflicts,
            selinuxMode = values["SELINUX"].orEmpty(),
            pnameRouting = pnameRouting(values["PNAME_WARN"]?.toLongOrNull(), values["PNAME_PREV"]?.toLongOrNull()),
        )
    }

    /**
     * The module is optional for a first look at compatibility: the app can
     * still show why the data plane is unavailable without it.
     */
    suspend fun checkWithoutRoot(): CompatibilityReport = CompatibilityReport(
        rootAvailable = false,
        note = "未获得 root 权限，无法读取内核信息",
    )

    companion object {
        const val MIN_MEMORY_MB = 150L

        fun parseKernel(release: String): Pair<Int, Int> {
            val match = Regex("""(\d+)\.(\d+)""").find(release) ?: return 0 to 0
            return match.groupValues[1].toInt() to match.groupValues[2].toInt()
        }

        /**
         * Three-state on purpose: before the daemon has created a control plane
         * there is nothing to report, and "supported" must not be inferred from a
         * mounted cgroup v2 — only the attach result says whether `pname` works.
         */
        fun pnameRouting(warnLine: Long?, previousGenerationLine: Long?): Boolean? {
            val warn = warnLine ?: 0L
            if (warn == 0L) return null
            return warn <= (previousGenerationLine ?: 0L)
        }
    }
}
