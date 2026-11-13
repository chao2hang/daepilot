package io.github.daepilot.core.log

/**
 * Aggregates the recurring runtime warnings dae emits into counts. dae exposes
 * no metrics API, so the log tail is the only source; counts cover the current
 * control plane generation inside the fetched window, not the daemon's lifetime.
 */
data class LogAlerts(
    val dnsUpstreamFailures: Int = 0,
    val udpQueueDrops: Int = 0,
    val datapathAnomalies: Int = 0,
    val failingUpstreams: List<String> = emptyList(),
) {
    val total: Int get() = dnsUpstreamFailures + udpQueueDrops + datapathAnomalies
}

object LogAlertParser {
    private val upstreamRegex = Regex("""upstream=(\S+)""")

    fun parse(lines: List<String>): LogAlerts {
        // The tail window usually spans several daemon restarts; warnings from
        // retired control planes would otherwise be reported as live alerts.
        val from = lines.indexOfLast { it.contains("Total startup time") } + 1
        var dns = 0
        var udp = 0
        var anomaly = 0
        val upstreams = linkedSetOf<String>()
        for (line in lines.subList(from, lines.size)) {
            when {
                line.contains("DNS forward to upstream failed") -> {
                    dns++
                    upstreamRegex.find(line)?.groupValues?.get(1)?.let { upstreams.add(it) }
                }
                line.contains("dropping UDP task from saturated per-flow queue") -> udp++
                line.contains("datapath anomaly:") -> anomaly++
            }
        }
        return LogAlerts(dns, udp, anomaly, upstreams.toList())
    }
}