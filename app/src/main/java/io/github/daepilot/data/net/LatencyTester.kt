package io.github.daepilot.data.net

import io.github.daepilot.core.model.Node
import io.github.daepilot.core.model.ShareEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.system.measureNanoTime

data class LatencyResult(
    val nodeId: String,
    val latencyMs: Int?,
    val error: String? = null,
    val method: String = "tcp",
)

/**
 * Handshake round-trip to the node's own service port: TCP connect for stream
 * protocols, QUIC Version Negotiation for QUIC-only nodes (hysteria2, TUIC).
 * ICMP remains only as a fallback, because it measures the host rather than the
 * listener and many nodes drop it.
 */
class LatencyTester(
    private val timeoutMs: Int = 2500,
    private val parallelism: Int = 8,
    private val quicSamples: Int = 3,
    private val quicTimeoutMs: Int = 1200,
) {
    suspend fun test(nodes: List<Node>): List<LatencyResult> = coroutineScope {
        val semaphore = Semaphore(parallelism.coerceAtLeast(1))
        nodes.map { node ->
            async(Dispatchers.IO) {
                semaphore.withPermit { probe(node) }
            }
        }.awaitAll()
    }

    suspend fun probe(node: Node): LatencyResult = withContext(Dispatchers.IO) {
        val endpoint: ShareEndpoint = ShareEndpoint.parse(node.link)
            ?: return@withContext LatencyResult(node.id, null, "无法解析服务器地址")
        val port = endpoint.port
            ?: return@withContext LatencyResult(node.id, null, "缺少端口")
        if (node.protocol.isUdpOnly) {
            probeQuic(node.id, endpoint.host, port)
        } else {
            probeTcp(node.id, endpoint.host, port)
        }
    }

    private fun probeTcp(nodeId: String, host: String, port: Int): LatencyResult {
        var failure: String? = null
        val elapsedMs = try {
            measureNanoTime {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                }
            } / 1_000_000L
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
            -1L
        }
        return if (failure != null) {
            LatencyResult(nodeId, null, failure)
        } else {
            LatencyResult(nodeId, elapsedMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    private fun probeQuic(nodeId: String, host: String, port: Int): LatencyResult {
        // UDP loses packets routinely, so one reply is enough but several are
        // sampled: the fastest is the honest estimate of the path.
        var best: Int? = null
        var failure: String? = null
        repeat(quicSamples.coerceAtLeast(1)) {
            val sample = runCatching { sampleQuicRtt(host, port) }
            val millis = sample.getOrNull()
            if (millis != null) {
                best = minOf(best ?: millis, millis)
            } else {
                failure = sample.exceptionOrNull()?.message
            }
        }
        val rtt = best
        if (rtt != null) return LatencyResult(nodeId, rtt, method = "quic")
        val icmp = probeIcmp(nodeId, host)
        if (icmp.latencyMs != null) return icmp
        return icmp.copy(error = "QUIC 端口探测失败（${failure ?: "无握手应答"}），ICMP 备用也无响应")
    }

    /**
     * A long-header packet naming a version no implementation supports must be
     * answered with a Version Negotiation packet (RFC 9000 6.4.2, 17.3), and a
     * server may only do so when the destination connection id is at least 8
     * bytes, which keeps the probe from being used to amplify. The answer comes
     * from the same listener and UDP path the tunnel uses, so its round trip is
     * the node's real latency even when the host filters ICMP.
     */
    private fun sampleQuicRtt(host: String, port: Int): Int {
        val request = QUIC_PROBE_PACKET
        return DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(host, port))
            socket.soTimeout = quicTimeoutMs
            val reply = DatagramPacket(ByteArray(request.size), request.size)
            val elapsed = measureNanoTime {
                socket.send(DatagramPacket(request, request.size))
                socket.receive(reply)
            } / 1_000_000L
            if (!isVersionNegotiation(reply.data, reply.length)) {
                error("服务器未按 QUIC 协议应答")
            }
            elapsed.toInt().coerceAtLeast(0)
        }
    }

    private fun probeIcmp(nodeId: String, host: String): LatencyResult {
        if (host.isBlank() || host.startsWith("-")) {
            return LatencyResult(nodeId, null, "节点地址无效")
        }
        val commands = buildList {
            if (host.contains(':')) add(listOf("ping6", "-c", "1", "-W", "2", host))
            add(listOf("ping", "-c", "1", "-W", "2", host))
        }
        var failure = "ICMP 无响应（服务器可能屏蔽 ping）"
        for (command in commands) {
            try {
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                val finished = process.waitFor(timeoutMs.toLong() + 1_500, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    failure = "ICMP 超时"
                    continue
                }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val millis = parsePingMillis(output)
                if (process.exitValue() == 0 && millis != null) {
                    return LatencyResult(nodeId, millis, method = "icmp")
                }
                failure = pingError(output)
            } catch (e: IOException) {
                failure = e.message ?: e.javaClass.simpleName
            }
        }
        return LatencyResult(nodeId, null, failure, method = "icmp")
    }

    private fun pingError(output: String): String {
        val line = output.lineSequence().firstOrNull {
            it.contains("unreachable", ignoreCase = true) ||
                it.contains("100% packet loss", ignoreCase = true) ||
                it.contains("cannot resolve", ignoreCase = true) ||
                it.contains("unknown host", ignoreCase = true) ||
                it.contains("not known", ignoreCase = true)
        }
        return line?.trim()?.take(100) ?: "ICMP 无响应（服务器可能屏蔽 ping）"
    }

    companion object {
        private const val PROBE_PACKET_SIZE = 1232
        private val PROBE_VERSION = byteArrayOf(0x0a, 0x0a, 0x0a, 0x0a)
        private val PROBE_DESTINATION_CONNECTION_ID = ByteArray(20) { 0xAB.toByte() }
        private val PROBE_SOURCE_CONNECTION_ID = ByteArray(20) { 0xCD.toByte() }
        internal val QUIC_PROBE_PACKET: ByteArray = run {
            val ids = PROBE_DESTINATION_CONNECTION_ID.size.toByte()
            val header = byteArrayOf(0xC0.toByte()) + PROBE_VERSION +
                ids + PROBE_DESTINATION_CONNECTION_ID + ids + PROBE_SOURCE_CONNECTION_ID
            header + ByteArray(PROBE_PACKET_SIZE - header.size)
        }

        private val pingTime = Regex("""time[=<]\s*([0-9.]+)\s*ms""", RegexOption.IGNORE_CASE)

        fun parsePingMillis(output: String): Int? =
            pingTime.find(output)?.groupValues?.get(1)
                ?.toDoubleOrNull()
                ?.roundToInt()
                ?.coerceAtLeast(0)

        /** Long header (top bit set) plus the reserved zero version field. */
        fun isVersionNegotiation(data: ByteArray, length: Int): Boolean =
            length >= 7 &&
                data[0].toInt() and 0x80 != 0 &&
                data[1] == 0.toByte() && data[2] == 0.toByte() &&
                data[3] == 0.toByte() && data[4] == 0.toByte()
    }
}
