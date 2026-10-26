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
 * TCP handshake for stream protocols, ICMP echo for QUIC-only nodes (hysteria2,
 * TUIC). A TCP connect cannot reach a QUIC server and previously made those
 * nodes untestable. ICMP measures reachability/RTT, not the proxy handshake, so
 * the UI labels those results explicitly.
 */
class LatencyTester(
    private val timeoutMs: Int = 2500,
    private val parallelism: Int = 8,
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
        if (node.protocol.isUdpOnly) {
            return@withContext probeIcmp(node.id, endpoint.host)
        }
        val port = endpoint.port
            ?: return@withContext LatencyResult(node.id, null, "缺少端口")
        var failure: String? = null
        val elapsedMs = try {
            measureNanoTime {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(endpoint.host, port), timeoutMs)
                }
            } / 1_000_000L
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
            -1L
        }
        if (failure != null) {
            LatencyResult(node.id, null, failure)
        } else {
            LatencyResult(node.id, elapsedMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    private fun probeIcmp(nodeId: String, host: String): LatencyResult {
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
        private val pingTime = Regex("""time[=<]\s*([0-9.]+)\s*ms""", RegexOption.IGNORE_CASE)

        fun parsePingMillis(output: String): Int? =
            pingTime.find(output)?.groupValues?.get(1)
                ?.toDoubleOrNull()
                ?.roundToInt()
                ?.coerceAtLeast(0)
    }
}
