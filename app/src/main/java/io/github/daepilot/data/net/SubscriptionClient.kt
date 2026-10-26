package io.github.daepilot.data.net

import io.github.daepilot.core.model.TrafficInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class SubscriptionFetch(
    val body: ByteArray?,
    val etag: String?,
    val traffic: TrafficInfo?,
    val providerIntervalHours: Int?,
    val notModified: Boolean = false,
    val contentType: String? = null,
)

class SubscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Downloads subscription bodies. Kept free of Android APIs so it can be tested
 * against MockWebServer.
 */
class SubscriptionClient(
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun fetch(url: String, etag: String? = null): SubscriptionFetch = withContext(Dispatchers.IO) {
        val parsed = runCatching { java.net.URI(url) }.getOrNull()
        if (parsed == null || parsed.scheme !in setOf("http", "https") || parsed.host.isNullOrBlank()) {
            throw SubscriptionException("订阅地址必须是 http(s) URL")
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 304) {
                    return@use SubscriptionFetch(
                        body = null,
                        etag = etag,
                        traffic = parseUserInfo(response.header("subscription-userinfo")),
                        providerIntervalHours = parseInterval(response.header("profile-update-interval")),
                        notModified = true,
                    )
                }
                if (!response.isSuccessful) {
                    throw SubscriptionException(
                        "订阅服务器返回 HTTP ${response.code}（${redact(url)}）",
                    )
                }
                val length = response.body?.contentLength() ?: -1
                if (length > MAX_BODY_BYTES) {
                    throw SubscriptionException("订阅内容过大（${length / 1024 / 1024} MB）")
                }
                val bytes = response.body?.bytes() ?: throw SubscriptionException("订阅响应为空")
                if (bytes.size > MAX_BODY_BYTES) {
                    throw SubscriptionException("订阅内容超过 20 MB 限制")
                }
                SubscriptionFetch(
                    body = bytes,
                    etag = response.header("ETag"),
                    traffic = parseUserInfo(response.header("subscription-userinfo")),
                    providerIntervalHours = parseInterval(response.header("profile-update-interval")),
                    contentType = response.header("Content-Type"),
                )
            }
        } catch (e: SubscriptionException) {
            throw e
        } catch (e: Exception) {
            throw SubscriptionException("无法连接订阅服务器：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    companion object {
        const val USER_AGENT = "DaePilot/0.1.0 (Android; dae 2.1.1)"
        private const val MAX_BODY_BYTES = 20L * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

        /** `upload=1; download=2; total=3; expire=4` */
        fun parseUserInfo(value: String?): TrafficInfo? {
            if (value.isNullOrBlank()) return null
            val fields = value.split(';').mapNotNull { part ->
                val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                k.trim().lowercase() to v.trim().toLongOrNull()
            }.toMap()
            val upload = fields["upload"] ?: 0L
            val download = fields["download"] ?: 0L
            val total = fields["total"] ?: 0L
            val expire = fields["expire"] ?: 0L
            if (upload == 0L && download == 0L && total == 0L && expire == 0L) return null
            return TrafficInfo(upload, download, total, expire)
        }

        fun parseInterval(value: String?): Int? {
            val hours = value?.trim()?.toIntOrNull() ?: return null
            return hours.takeIf { it in 1..720 }
        }

        /** Never log a subscription token; keep scheme + host + port. */
        fun redact(url: String): String {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd <= 0) return "<redacted-url>"
            val after = url.substring(schemeEnd + 3)
            val authorityEnd = after.indexOfFirst { it == '/' || it == '?' || it == '#' }
                .let { if (it < 0) after.length else it }
            val authority = after.substring(0, authorityEnd)
            val host = authority.substringAfterLast('@')
            if (host.isBlank()) return "<redacted-url>"
            return "${url.substring(0, schemeEnd)}://$host/<redacted>"
        }
    }
}
