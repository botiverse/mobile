package build.raft.app.transfer

/**
 * A meter state as a test needs it: the values a host's meter would have sent across the bridge
 * (decoded with the same invariants, so a fixture cannot show 100% before Done either).
 */
internal fun testTransfer(
    downloadedBytes: Long = 0L,
    totalBytes: Long? = null,
    percent: Int? = null,
    bytesPerSecond: Long? = null,
    phase: RaftTransferPhase = if (downloadedBytes > 0L) RaftTransferPhase.Running else RaftTransferPhase.Queued
): RaftTransferProgress =
    RaftTransferProgress.decoded(
        bytesDone = downloadedBytes,
        bytesTotal = totalBytes,
        phase = phase,
        percent = percent,
        bytesPerSecond = bytesPerSecond
    )
