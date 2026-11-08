package io.github.daepilot.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

data class SpeedTestResult(
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val downloadBytes: Long = 0,
    val uploadBytes: Long = 0,
    val error: String? = null,
)

/**
 * Measures throughput through whatever path the app's traffic currently takes.
 * In the app that path is the temporary dae group created for the node test.
 */
class SpeedTester(
    private val baseUrl: String = "https://speed.cloudflare.com",
    private val downloadBytes: Int = 5_000_000,
    private val uploadBytes: Int = 2_000_000,
) {
    suspend fun run(): SpeedTestResult =
        withContext(Dispatchers.IO) {
            val client = buildClient()
            // Establish DNS/TCP/TLS first: timing the handshake inside the
            // measured transfer under-reports fast nodes badly on small samples.
            warmUp(client)
            val download = download(client)
            val upload = upload(client)
            val downloadMbps = download.mbps()
            val uploadMbps = upload.mbps()
            SpeedTestResult(
                downloadMbps = downloadMbps,
                uploadMbps = uploadMbps,
                downloadBytes = download.bytes,
                uploadBytes = upload.bytes,
                error = if (downloadMbps == null && uploadMbps == null) {
                    download.failure ?: upload.failure ?: "测速失败"
                } else {
                    null
                },
            )
        }

    private fun buildClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

    private fun warmUp(client: OkHttpClient) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/__down?bytes=1024")
                .header("Cache-Control", "no-store")
                .build()
            client.newCall(request).execute().use { response ->
                response.body?.bytes()
            }
        }
    }

    private fun download(client: OkHttpClient): Sample {
        val start = System.nanoTime()
        var bytes = 0L
        var failure: String? = null
        try {
            val request = Request.Builder()
                .url("$baseUrl/__down?bytes=$downloadBytes")
                .header("Cache-Control", "no-store")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    failure = "下载失败 HTTP ${response.code}"
                } else {
                    val body = response.body
                    if (body == null) {
                        failure = "下载响应为空"
                    } else {
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                bytes += read
                                if (bytes >= downloadBytes) break
                                if (elapsedMs(start) >= MAX_PHASE_MS) break
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            failure = describe(e)
        }
        return Sample(bytes, elapsedMs(start), failure)
    }

    private fun upload(client: OkHttpClient): Sample {
        val payload = ByteArray(uploadBytes)
        val sent = AtomicLong()
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = payload.size.toLong()

            override fun writeTo(sink: BufferedSink) {
                var offset = 0
                while (offset < payload.size) {
                    val count = minOf(64 * 1024, payload.size - offset)
                    sink.write(payload, offset, count)
                    sent.addAndGet(count.toLong())
                    offset += count
                }
            }
        }
        val start = System.nanoTime()
        var bytes = 0L
        var failure: String? = null
        try {
            val request = Request.Builder()
                .url("$baseUrl/__up")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    bytes = sent.get()
                } else {
                    failure = "上传失败 HTTP ${response.code}"
                }
                response.body?.close()
            }
        } catch (e: IOException) {
            failure = describe(e)
            // A timeout can still carry useful partial-transfer throughput.
            bytes = sent.get()
        }
        return Sample(bytes, elapsedMs(start), failure)
    }

    private fun elapsedMs(startNanos: Long): Long =
        (System.nanoTime() - startNanos) / 1_000_000

    private fun describe(error: IOException): String = when (error) {
        is SocketTimeoutException -> "超时"
        is UnknownHostException -> "域名解析失败"
        is SSLException -> "TLS 失败"
        is ConnectException -> "连接失败"
        else -> error.message ?: "网络错误"
    }

    private data class Sample(val bytes: Long, val elapsedMs: Long, val failure: String?) {
        fun mbps(): Double? {
            if (bytes <= 0) return null
            return bytes * 8.0 / (elapsedMs.coerceAtLeast(1) / 1000.0) / 1_000_000.0
        }
    }

    companion object {
        const val MAX_PHASE_MS = 25_000L
    }
}
