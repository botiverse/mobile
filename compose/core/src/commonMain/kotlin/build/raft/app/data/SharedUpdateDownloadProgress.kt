package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.optLong
import build.raft.app.optString
import kotlinx.serialization.json.JsonObject


/**
 * One poll of the host-owned update download. Bytes belong to that attempt, never to the offered
 * APK size. The percent is not here: the app-update page feeds these samples to its transfer meter.
 */
data class SharedUpdateDownloadProgress(
    val token: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val phase: String
) {
    val active: Boolean get() = phase == "downloading" || phase == "paused"
}

@RaftCoreInternalApi
fun JsonObject.toSharedUpdateDownloadProgress(): SharedUpdateDownloadProgress? {
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
