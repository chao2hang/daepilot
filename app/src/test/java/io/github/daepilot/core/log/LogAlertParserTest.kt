package io.github.daepilot.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogAlertParserTest {
    private val dnsFailure =
        " WARN DNS forward to upstream failed dialer=节点 error=Get \"https://1.1.1.1:443/dns-query\": " +
            "context deadline exceeded network=tcp+4 outbound=proxy upstream=https://1.1.1.1:443/dns-query"
    private val udpDrop =
        " WARN dropping UDP task from saturated per-flow queue queue_limit=256"
    private val anomaly =
        " WARN datapath anomaly: pure SYN refused rewrite of a live flow's routing metadata " +
            "(type=5 pid=0 outbound=0 l4proto=6 10.0.0.1:50656 > 2607:f8b0::7:443)"

    @Test
    fun emptyInputHasNoAlerts() {
        val alerts = LogAlertParser.parse(emptyList())
        assertEquals(0, alerts.total)
        assertTrue(alerts.failingUpstreams.isEmpty())
    }

    @Test
    fun unrelatedLinesAreIgnored() {
        val alerts = LogAlertParser.parse(
            listOf(
                " INFO Network online.",
                "DEBUG cache hit _qname=example.com. dest=223.5.5.5:53",
                " INFO Reload: Finished",
            ),
        )
        assertEquals(0, alerts.total)
    }

    @Test
    fun countsEachWarningCategory() {
        val alerts = LogAlertParser.parse(
            listOf(dnsFailure, dnsFailure, udpDrop, anomaly, anomaly, anomaly),
        )
        assertEquals(2, alerts.dnsUpstreamFailures)
        assertEquals(1, alerts.udpQueueDrops)
        assertEquals(3, alerts.datapathAnomalies)
        assertEquals(6, alerts.total)
    }

    @Test
    fun collectsDistinctFailingUpstreams() {
        val other = dnsFailure.replace("1.1.1.1", "223.5.5.5")
        val alerts = LogAlertParser.parse(listOf(dnsFailure, dnsFailure, other))
        assertEquals(3, alerts.dnsUpstreamFailures)
        assertEquals(
            listOf("https://1.1.1.1:443/dns-query", "https://223.5.5.5:443/dns-query"),
            alerts.failingUpstreams,
        )
    }

    @Test
    fun dnsFailureWithoutUpstreamTokenStillCounts() {
        val alerts = LogAlertParser.parse(listOf(" WARN DNS forward to upstream failed error=boom"))
        assertEquals(1, alerts.dnsUpstreamFailures)
        assertTrue(alerts.failingUpstreams.isEmpty())
    }

    @Test
    fun warningsBeforeTheLastStartupAreIgnored() {
        val alerts = LogAlertParser.parse(
            listOf(
                anomaly,
                anomaly,
                dnsFailure,
                " INFO Total startup time: 529.885417ms",
                " INFO Bind to WAN: rmnet_data3",
                dnsFailure,
            ),
        )
        assertEquals(1, alerts.dnsUpstreamFailures)
        assertEquals(0, alerts.datapathAnomalies)
        assertEquals(1, alerts.total)
    }

    @Test
    fun windowWithoutStartupMarkerCountsEverything() {
        val alerts = LogAlertParser.parse(listOf(anomaly, dnsFailure))
        assertEquals(1, alerts.dnsUpstreamFailures)
        assertEquals(1, alerts.datapathAnomalies)
    }
}