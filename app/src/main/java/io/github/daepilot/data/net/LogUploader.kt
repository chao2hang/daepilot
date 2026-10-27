package io.github.daepilot.data.net

import io.github.daepilot.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class LogUploadRequest(
    val app: String,
    val versionCode: Int,
    val device: String,
    val manufacturer: String,
    val android: String,
    val abi: String,
    val mode: String,
    val daeVersion: String,
    val moduleVersion: String,
    val status: String,
    val note: String,
    val events: String,
    val config: String,
    val diagnostics: String,
    val log: String,
)

@Serializable
private data class LogUploadResponse(
    val ok: Boolean = false,
    val id: String? = null,
    val error: String? = null,
)

class LogUploader(
    private val endpoint: String = BuildConfig.LOG_UPLOAD_URL,
    private val client: OkHttpClient = defaultClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun upload(request: LogUploadRequest): Result<String> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (attempt in 0 until 4) {
            try {
                return@withContext Result.success(uploadOnce(request))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastError = error
                if (attempt < 3) delay(1_000L * (attempt + 1))
            }
        }
        Result.failure(lastError ?: IllegalStateException("上传失败"))
    }

    private fun uploadOnce(request: LogUploadRequest): String {
        val body = json.encodeToString(request)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val separator = if (endpoint.contains("?")) "&" else "?"
        val httpRequest = Request.Builder()
            .url(endpoint + separator + "t=" + System.currentTimeMillis())
            .post(body)
            .header("User-Agent", "DaePilot/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(httpRequest).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("服务器返回 HTTP ${response.code}")
            }
            val parsed = runCatching { json.decodeFromString<LogUploadResponse>(text) }
                .getOrElse { error("服务器返回格式异常") }
            if (!parsed.ok) error(parsed.error?.takeIf { it.isNotBlank() } ?: "上传失败")
            return parsed.id?.takeIf { it.isNotBlank() } ?: error("服务器未返回编号")
        }
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Trim noise and mask URL credentials before anything leaves the device. */
        fun sanitizeLog(raw: String, maxChars: Int = 300_000): String {
            val noAnsi = raw.replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")
            val masked = noAnsi.replace(
                Regex(
                    """(?i)([?&](?:token|key|password|passwd|pwd|auth|uuid|secret)=)[^&\s]+""",
                ),
                "${'$'}1<redacted>",
            )
            val credentialsMasked = masked.replace(
                Regex("""(?i)([a-z][a-z0-9+.-]*://)[^/@\s]+@"""),
                "${'$'}1<redacted>@",
            )
            // vmess:// and ssr:// hide their credential in an opaque base64 body
            // with no '@', so the userinfo rule above never fires.
            val opaqueMasked = credentialsMasked.replace(OPAQUE_PAYLOAD) {
                it.value.substringBefore("://") + "://<redacted>"
            }
            return if (opaqueMasked.length <= maxChars) opaqueMasked
            else opaqueMasked.take(maxChars) + "\n…（已截断）"
        }

        private val OPAQUE_PAYLOAD = Regex("""(?i)\b(?:vmess|ssr)://[A-Za-z0-9_\-.=]+""")
    }
}
