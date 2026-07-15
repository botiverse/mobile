package build.raft.app.shared

internal data class AttachmentDownloadProgressSample(
    val percent: Int?,
    val bytesPerSecond: Long?
)

/**
 * Keeps attachment progress honest and renderable:
 *
 * - the response/header wait is not reported as a fake 0% transfer;
 * - the first positive byte is emitted immediately;
 * - later updates are capped to a UI-friendly cadence;
 * - speed is measured from transferred-byte deltas, excluding URL resolution,
 *   response wait, and long pause/stall gaps.
 */
internal class AttachmentDownloadProgressSampler(
    private val minEmitIntervalMs: Long = 250L,
    private val speedWindowMs: Long = 500L,
    private val stallResetMs: Long = 2_000L
) {
    private var lastEmitAtMs: Long? = null
    private var lastEmittedPercent: Int? = null
    private var emittedPositiveBytes = false
    private var lastChunkAtMs: Long? = null
    private var speedWindowAtMs: Long? = null
    private var speedWindowBytes = 0L
    private var lastSpeedBytesPerSecond: Long? = null

    fun onResponseStart(nowMs: Long) {
        lastEmitAtMs = nowMs
        lastEmittedPercent = null
        emittedPositiveBytes = false
        lastChunkAtMs = null
        speedWindowAtMs = null
        speedWindowBytes = 0L
        lastSpeedBytesPerSecond = null
    }

    fun sample(
        downloadedBytes: Long,
        totalBytes: Long?,
        nowMs: Long,
        complete: Boolean = false
    ): AttachmentDownloadProgressSample? {
        val safeDownloadedBytes = downloadedBytes.coerceAtLeast(0L)
        val percent = attachmentDownloadPercent(safeDownloadedBytes, totalBytes, complete)
        updateSpeed(safeDownloadedBytes, nowMs)

        val firstPositiveBytes = safeDownloadedBytes > 0L && !emittedPositiveBytes
        val emitIntervalElapsed = nowMs - (lastEmitAtMs ?: nowMs) >= minEmitIntervalMs
        val determinateProgressAdvanced = percent != null && percent != lastEmittedPercent
        val indeterminateProgressAdvanced = totalBytes == null && emitIntervalElapsed
        val shouldEmit =
            complete ||
                firstPositiveBytes ||
                (determinateProgressAdvanced && emitIntervalElapsed) ||
                indeterminateProgressAdvanced
        if (!shouldEmit) return null

        lastEmitAtMs = nowMs
        lastEmittedPercent = percent
        emittedPositiveBytes = emittedPositiveBytes || safeDownloadedBytes > 0L
        return AttachmentDownloadProgressSample(
            percent = percent,
            bytesPerSecond = lastSpeedBytesPerSecond
        )
    }

    private fun updateSpeed(downloadedBytes: Long, nowMs: Long) {
        val previousChunkAt = lastChunkAtMs
        val resetWindow =
            speedWindowAtMs == null ||
                (previousChunkAt != null && nowMs - previousChunkAt >= stallResetMs) ||
                downloadedBytes < speedWindowBytes
        if (resetWindow) {
            speedWindowAtMs = nowMs
            speedWindowBytes = downloadedBytes
            lastSpeedBytesPerSecond = null
            lastChunkAtMs = nowMs
            return
        }

        val windowStartedAt = speedWindowAtMs ?: nowMs
        val elapsedMs = nowMs - windowStartedAt
        val byteDelta = downloadedBytes - speedWindowBytes
        if (elapsedMs >= speedWindowMs && byteDelta > 0L) {
            lastSpeedBytesPerSecond = ((byteDelta * 1_000L) / elapsedMs).takeIf { it > 0L }
            speedWindowAtMs = nowMs
            speedWindowBytes = downloadedBytes
        }
        lastChunkAtMs = nowMs
    }
}

internal fun attachmentDownloadPercent(
    downloadedBytes: Long,
    totalBytes: Long?,
    complete: Boolean = false
): Int? {
    val total = totalBytes?.takeIf { it > 0L } ?: return null
    if (complete || downloadedBytes >= total) return 100
    if (downloadedBytes <= 0L) return null
    return ((downloadedBytes.toDouble() * 100.0) / total.toDouble())
        .toInt()
        .coerceIn(1, 99)
}
