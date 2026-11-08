package io.github.daepilot.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class DomainTarget(
    val label: String,
    val host: String,
    val url: String,
    val expect204: Boolean = false,
    // Some sites answer a bare probe with 401/403 while still being reachable
    // through the proxy; those codes still prove the connection works.
    val allowAuth: Boolean = false,
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

object DomainTargets {
    val google = DomainTarget(
        "Google", "google.com", "https://www.google.com/generate_204", expect204 = true,
    )
    val youtube = DomainTarget("YouTube", "youtube.com", "https://www.youtube.com/")
    val github = DomainTarget("GitHub", "github.com", "https://github.com/")
    val telegram = DomainTarget("Telegram", "telegram.org", "https://telegram.org/")
    val chatgpt = DomainTarget(
        "ChatGPT", "chatgpt.com", "https://chatgpt.com/", allowAuth = true,
    )
    val netflix = DomainTarget("Netflix", "netflix.com", "https://www.netflix.com/")
    val x = DomainTarget("X", "x.com", "https://x.com/")
    val baidu = DomainTarget("Baidu", "baidu.com", "https://www.baidu.com/")

    /** The sites a proxy is usually judged by, shown on the home screen. */
    val home = listOf(google, youtube, github, telegram, chatgpt, netflix, x, baidu)
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
    private val exitProbeUrl: String = "https://www.cloudflare.com/cdn-cgi/trace",
) {
    suspend fun probeOne(target: DomainTarget): DomainTestResult =
        withContext(Dispatchers.IO) { probe(target) }

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
                val ok = when {
                    target.expect204 -> it.code == 204
                    target.allowAuth -> it.code in 200..399 || it.code == 401 || it.code == 403
                    else -> it.code in 200..399
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

    suspend fun fetchExit(): ExitInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(exitProbeUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            parseExit(body)
        }
    }

    private fun request(target: DomainTarget, head: Boolean): Request =
        Request.Builder()
            .url(target.url)
            .apply { if (head) head() }
            .header("Cache-Control", "no-cache")
            .build()

    companion object {
        /**
         * Probes resolve to IPv4 only when possible: while the tunnel is down
         * a foreign name can still be reached over a direct IPv6 route, and the
         * app would then read the carrier's own address from the trace and call
         * the switch successful.
         */
        private val ipv4FirstDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val all = Dns.SYSTEM.lookup(hostname)
                return all.filter { it is Inet4Address }.ifEmpty { all }
            }
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(ipv4FirstDns)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
