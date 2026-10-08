package build.raft.app.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** Bytes belong to one host-owned download attempt, never to the offered APK size. */
data class SharedUpdateDownloadProgress(
    val token: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val phase: String
) {
    val percent: Int?
        get() = totalBytes?.takeIf { it > 0 }?.let {
            (downloadedBytes.toDouble() / it.toDouble() * 100.0).toInt().coerceIn(0, 100)
        }
    val active: Boolean get() = phase == "downloading" || phase == "paused"
}

internal fun JSONObject.toSharedUpdateDownloadProgress(): SharedUpdateDownloadProgress? {
    val token = optString("token")
    val phase = optString("phase")
    if (optString("status") != "ok" || token.isBlank() ||
        phase !in setOf("downloading", "paused", "ready", "failed")) return null
    return SharedUpdateDownloadProgress(
        token = token,
        downloadedBytes = optLong("downloadedBytes", 0L).coerceAtLeast(0L),
        totalBytes = optLong("totalBytes", -1L).takeIf { it > 0L },
        phase = phase
    )
}
