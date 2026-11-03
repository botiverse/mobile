package build.raft.app.downloads

/**
 * What the window's download bar (Compose 版) shows, from the download centre's rows: the
 * downloads running now, or else the last one this session finished. Rows from an earlier
 * session ([RaftDownloadEntry.updatedAtMillis] before [sinceMillis]) and finished rows the user
 * closed are left out.
 */
internal sealed interface RaftDownloadBarState {
    /** One or more downloads running. [entryId] / [fileName] only when it is one; [fraction] null while unknown. */
    data class Downloading(val count: Int, val entryId: String?, val fileName: String?, val fraction: Float?) : RaftDownloadBarState

    data class Saved(val entryId: String, val fileName: String, val localUri: String?) : RaftDownloadBarState

    data class Failed(val entryId: String, val fileName: String, val message: String?) : RaftDownloadBarState
}

/**
 * The bar for [entries] (the centre's rows, newest first). [dismissed] maps a closed row's id to
 * its [RaftDownloadEntry.updatedAtMillis] at the time: the row shows again once it changes (a
 * retry, a new download of the same file).
 */
internal fun raftDownloadBarState(
    entries: List<RaftDownloadEntry>,
    sinceMillis: Long,
    dismissed: Map<String, Long> = emptyMap(),
): RaftDownloadBarState? {
    val session = entries.filter { it.updatedAtMillis >= sinceMillis && dismissed[it.id] != it.updatedAtMillis }
    val active = session.filter { it.status.isActive }
    if (active.isNotEmpty()) {
        val single = active.singleOrNull()
        return RaftDownloadBarState.Downloading(
            count = active.size,
            entryId = single?.id,
            fileName = single?.fileName,
            fraction = raftDownloadBarFraction(active),
        )
    }
    val last = session.maxByOrNull { it.updatedAtMillis } ?: return null
    return when (last.status) {
        RaftDownloadStatus.Completed -> RaftDownloadBarState.Saved(last.id, last.fileName, last.localUri)
        RaftDownloadStatus.Failed -> RaftDownloadBarState.Failed(last.id, last.fileName, last.errorMessage)
        else -> null
    }
}

/** Bytes done over bytes to do, across [active]; null while any size is unknown. */
private fun raftDownloadBarFraction(active: List<RaftDownloadEntry>): Float? {
    var done = 0L
    var total = 0L
    active.forEach { entry ->
        val entryTotal = entry.transfer?.bytesTotal ?: entry.totalBytes ?: return null
        if (entryTotal <= 0L) return null
        total += entryTotal
        done += (entry.transfer?.bytesDone ?: entry.downloadedBytes).coerceIn(0L, entryTotal)
    }
    return if (total > 0L) done.toFloat() / total else null
}
