package build.raft.app.shared

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class AndroidQuiverUpdateCheckResult(
        val status: String,
        val statusLabel: String,
        val detail: String,
        val updateAvailable: Boolean = false,
        val latestVersion: String = "",
        val latestVersionCode: Long? = null,
        val downloadUrl: String? = null,
        val errorCode: String? = null
)

internal object AndroidQuiverUpdateCheckClient {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
    }

    suspend fun check(
            baseUrl: String,
            appSlug: String,
            channel: String,
            currentVersionCode: Long,
            arch: String?
    ): AndroidQuiverUpdateCheckResult =
            withContext(Dispatchers.IO) {
                val cleanBaseUrl = baseUrl.trim().ifBlank { "https://quiver-worker.artin.workers.dev" }
                val cleanSlug = appSlug.trim().ifBlank { "slock-android" }
                val cleanChannel = channel.trim().ifBlank { "main" }
                val url =
                        cleanBaseUrl
                                .trimEnd('/')
                                .toHttpUrl()
                                .newBuilder()
                                .addPathSegments("public/v2/apps")
                                .addPathSegment(cleanSlug)
                                .addPathSegments("updates/check")
                                .addQueryParameter("channel", cleanChannel)
                                .addQueryParameter("product_type", "android-apk")
                                .addQueryParameter("current_version_code", currentVersionCode.toString())
                                .addQueryParameter("platform", "android")
                                .addQueryParameter("filetype", "apk")
                                .apply {
                                    arch?.trim()?.takeIf { it.isNotBlank() }?.let { addQueryParameter("arch", it) }
                                }
                                .build()
                val request =
                        Request.Builder()
                                .url(url)
                                .header("accept", "application/json")
                                .build()
                runCatching {
                            httpClient.newCall(request).execute().use { response ->
                                val body = response.body?.string().orEmpty()
                                when (response.code) {
                                    200 -> parseSuccess(body, currentVersionCode, cleanChannel)
                                    404 ->
                                            AndroidQuiverUpdateCheckResult(
                                                    status = "error",
                                                    statusLabel = "Not found",
                                                    detail = "No Quiver release or compatible APK was found for $cleanSlug on $cleanChannel.",
                                                    errorCode = "not_found"
                                            )
                                    400 ->
                                            AndroidQuiverUpdateCheckResult(
                                                    status = "error",
                                                    statusLabel = "Bad request",
                                                    detail = "Quiver rejected the update check request.",
                                                    errorCode = "bad_request"
                                            )
                                    else ->
                                            AndroidQuiverUpdateCheckResult(
                                                    status = "error",
                                                    statusLabel = "HTTP ${response.code}",
                                                    detail = body.take(160).ifBlank { "Quiver returned HTTP ${response.code}." },
                                                    errorCode = "http_${response.code}"
                                            )
                                }
                            }
                        }
                        .getOrElse { error ->
                            AndroidQuiverUpdateCheckResult(
                                    status = "error",
                                    statusLabel = "Check failed",
                                    detail =
                                            when (error) {
                                                is IllegalArgumentException -> "Invalid Quiver update URL: $cleanBaseUrl."
                                                is IOException -> "Network error while checking Quiver updates."
                                                else -> error.message ?: "Quiver update check failed."
                                            },
                                    errorCode = error.javaClass.simpleName
                            )
                        }
            }

    private fun parseSuccess(
            body: String,
            currentVersionCode: Long,
            channel: String
    ): AndroidQuiverUpdateCheckResult {
        val json = JSONObject(body)
        val updateAvailable = json.optBoolean("update_available", false)
        val latest = json.optJSONObject("latest")
        val asset = json.optJSONObject("asset")
        val latestVersionCode =
                latest?.optLong("version_code", 0L)
                        ?.takeIf { it > 0L }
                        ?: json.optLong("latest_version_code", 0L).takeIf { it > 0L }
        val latestVersion = latest?.optString("version").orEmpty()
        val downloadUrl = asset?.optString("download_url").orEmpty()
        return if (updateAvailable && latestVersionCode != null && latestVersionCode > currentVersionCode) {
            AndroidQuiverUpdateCheckResult(
                    status = "ok",
                    statusLabel = "Update available",
                    detail =
                            buildString {
                                append("Quiver has ")
                                append(latestVersion.ifBlank { "version code $latestVersionCode" })
                                append(" on ")
                                append(channel)
                                if (downloadUrl.isNotBlank()) append(". Download URL is ready.")
                            },
                    updateAvailable = true,
                    latestVersion = latestVersion,
                    latestVersionCode = latestVersionCode,
                    downloadUrl = downloadUrl
            )
        } else {
            AndroidQuiverUpdateCheckResult(
                    status = "ok",
                    statusLabel = "Current",
                    detail = "No Quiver update is available on $channel for version code $currentVersionCode.",
                    latestVersion = latestVersion,
                    latestVersionCode = latestVersionCode,
                    downloadUrl = downloadUrl
            )
        }
    }
}
