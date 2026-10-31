package io.github.daepilot.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class DomainTarget(
    val label: String,
    val host: String,
    val url: String,
    val expect204: Boolean = false,
)

data class DomainTestResult(
    val target: DomainTarget,
    val reachable: Boolean,
    val latencyMs: Long,
    val detail: String,
)

data class ExitInfo(
    val ip: String,
    val country: String,
    val colo: String,
)

data class DomainTestReport(
    val results: List<DomainTestResult>,
    val exit: ExitInfo?,
    val node: String?,
    val proxyRunning: Boolean,
)

object DomainTargets {
    val defaults = listOf(
        DomainTarget("Google", "google.com", "https://www.google.com/generate_204", expect204 = true),
        DomainTarget("YouTube", "youtube.com", "https://www.youtube.com/"),
        DomainTarget("GitHub", "github.com", "https://github.com/"),
        DomainTarget("Telegram", "telegram.org", "https://telegram.org/"),
        DomainTarget("Baidu", "baidu.com", "https://www.baidu.com/"),
    )
}

/**
 * Probes reachability of a few well-known sites over the current system network.
 *
 * OkHttp has no knowledge of the dae data plane: when the proxy is running the
 * requests are captured by the kernel hooks exactly like any other app traffic,
 * so a successful YouTube request proves the selected node can actually reach it
 * (not merely that the node handshake is alive).
 */
class DomainTester(
    private val client: OkHttpClient = defaultClient(),
    private val targets: List<DomainTarget> = DomainTargets.defaults,
    private val exitProbeUrl: String = "https://www.cloudflare.com/cdn-cgi/trace",
) {
    suspend fun run(proxyRunning: Boolean, node: String?): DomainTestReport =
        withContext(Dispatchers.IO) {
            // Sequential on purpose: a low-bandwidth or rate-limited node turns
            // five concurrent TLS handshakes into five timeouts and makes a
            // healthy proxy look broken. One request at a time measures what a
            // browser would actually see.
            val exit = runCatching { fetchExit() }.getOrNull()
            val results = targets.map { target -> probe(target) }
            DomainTestReport(results, exit, node, proxyRunning)
        }

    internal fun probe(target: DomainTarget): DomainTestResult {
        val started = System.nanoTime()
        return try {
            var response = client.newCall(request(target, head = true)).execute()
            // A few servers reject HEAD; retry those with a body-less GET.
            if (response.code == 405 || response.code == 501) {
                response.close()
                response = client.newCall(request(target, head = false)).execute()
            }
            response.use {
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val ok = if (target.expect204) {
                    it.code == 204
                } else {
                    it.code in 200..399
                }
                DomainTestResult(target, ok, elapsed, "HTTP ${it.code}")
            }
        } catch (e: Exception) {
            val elapsed = (System.nanoTime() - started) / 1_000_000
            DomainTestResult(target, false, elapsed, classify(e))
        }
    }

    internal fun classify(error: Throwable): String = when (error) {
        is UnknownHostException -> "DNS 解析失败"
        is SocketTimeoutException -> "连接超时"
        is InterruptedIOException -> "连接超时"
        is SSLException -> "TLS 握手失败"
        is ConnectException -> "连接被拒绝"
        is IOException -> "网络不可达"
        else -> error.message?.take(60) ?: error.javaClass.simpleName
    }

    internal fun parseExit(text: String): ExitInfo? {
        val values = text.lineSequence().mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
        }.toMap()
        val ip = values["ip"]?.takeIf { it.isNotBlank() } ?: return null
        return ExitInfo(ip, values["loc"].orEmpty(), values["colo"].orEmpty())
    }

    private fun fetchExit(): ExitInfo? {
        val request = Request.Builder().url(exitProbeUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return parseExit(body)
        }
    }

    private fun request(target: DomainTarget, head: Boolean): Request =
        Request.Builder()
            .url(target.url)
            .apply { if (head) head() }
            .header("Cache-Control", "no-cache")
            .build()

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
