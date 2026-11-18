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
    /**
     * Anomalies whose destination is IPv6. dae rejects AAAA answers, so apps
     * retry cached IPv6 addresses for a while and every SYN hits the same
     * datapath case. They are expected noise, not a datapath fault.
     */
    val ipv6Retries: Int = 0,
    /** DNS failures caused by an untrusted node certificate chain. */
    val certificateFailures: Int = 0,
    val failingUpstreams: List<String> = emptyList(),
) {
    val total: Int get() = dnsUpstreamFailures + udpQueueDrops + datapathAnomalies
    /** Anything worth showing on the card, including informational rows. */
    val hasEntries: Boolean get() = total > 0 || ipv6Retries > 0 || certificateFailures > 0
}

object LogAlertParser {
    private val upstreamRegex = Regex("""upstream=(\S+)""")
    private val ipv4Destination = Regex("""\d{1,3}(?:\.\d{1,3}){3}:\d+""")

    fun parse(lines: List<String>): LogAlerts {
        // The tail window usually spans several daemon restarts; warnings from
        // retired control planes would otherwise be reported as live alerts.
        val from = lines.indexOfLast { it.contains("Total startup time") } + 1
        var dns = 0
        var udp = 0
        var anomaly = 0
        var ipv6 = 0
        var certificates = 0
        val upstreams = linkedSetOf<String>()
        for (line in lines.subList(from, lines.size)) {
            when {
                line.contains("DNS forward to upstream failed") -> {
                    dns++
                    if (line.contains("certificate signed by unknown authority") ||
                        line.contains("failed to verify certificate") ||
                        line.contains("x509:")
                    ) {
                        certificates++
                    }
                    upstreamRegex.find(line)?.groupValues?.get(1)?.let { upstreams.add(it) }
                }
                line.contains("dropping UDP task from saturated per-flow queue") -> udp++
                line.contains("datapath anomaly:") -> {
                    val destination = line.substringAfter(" > ", "").trimEnd(')', ']', ',', ';')
                    if (destination.contains(':') && !ipv4Destination.matches(destination)) {
                        ipv6++
                    } else {
                        anomaly++
                    }
                }
            }
        }
        return LogAlerts(dns, udp, anomaly, ipv6, certificates, upstreams.toList())
    }
}