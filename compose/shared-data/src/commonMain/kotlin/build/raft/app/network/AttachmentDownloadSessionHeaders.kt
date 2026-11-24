package build.raft.app.network

import build.raft.app.RaftCoreInternalApi

import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The app's session for one download of a Raft API address (an Artifact's file or zip, which the
 * server answers only signed in), held in shared memory for the one transfer that is about to
 * start: registered by shared code right before it asks the host to save that address
 * ([build.raft.app.base.BridgeModule.saveAttachment]), taken once by the download transport for
 * the same download id and the same URL. The credential never crosses the native bridge (no
 * argument, no log) and is used for nothing else: an entry is dropped when taken, when a newer one
 * replaces it, or after [ttlMillis].
 */
@RaftCoreInternalApi object AttachmentDownloadSessionHeaders {
    private class Entry(val url: String, val headers: Map<String, String>, val at: TimeMark)

    private val lock = SynchronizedObject()
    private val entries = HashMap<String, Entry>()
    @RaftCoreInternalApi var timeSource: TimeSource = TimeSource.Monotonic
    @RaftCoreInternalApi const val ttlMillis: Long = 60_000L

    fun register(downloadId: String, url: String, headers: Map<String, String>) {
        if (downloadId.isBlank() || url.isBlank() || headers.isEmpty()) return
        synchronized(lock) {
            pruneLocked()
            entries[downloadId] = Entry(url, headers, timeSource.markNow())
        }
    }

    /** The headers for [downloadId] when they were registered for exactly [url]; removed either way. */
    fun take(downloadId: String, url: String): Map<String, String>? =
        synchronized(lock) {
            pruneLocked()
            val entry = entries.remove(downloadId) ?: return@synchronized null
            entry.headers.takeIf { entry.url == url }
        }

    fun forget(downloadId: String) {
        synchronized(lock) { entries.remove(downloadId) }
    }

    @RaftCoreInternalApi fun clearForTest() {
        synchronized(lock) { entries.clear() }
    }

    private fun pruneLocked() {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.at.elapsedNow().inWholeMilliseconds > ttlMillis) iterator.remove()
        }
    }
}
