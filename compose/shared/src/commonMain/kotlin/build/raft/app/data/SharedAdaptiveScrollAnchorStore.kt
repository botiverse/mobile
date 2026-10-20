package build.raft.app.data

import build.raft.app.RaftAdaptiveDetailIdentity
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Ephemeral viewport-transfer record for an A4 standalone-Thread → detail-pane adoption.
 *
 * Primary locator is the canonical message id plus the viewport pixel offset relative to that
 * message's row top; the list index is only a fallback hint because destination item ordering may
 * differ (new messages arrived, different pane height → different layout chunk). `atLatest` means
 * the source viewport was already at the canonical bottom so the destination can use its existing
 * latest-anchor behavior instead of locating a message.
 *
 * The record is keyed by `(shellInstanceId, identity.key)` — never a page-instance key, since the
 * whole point is surviving the source pager's remount as a different pager. `requestId` fences the
 * record to exactly one adoption transaction so a late-arriving older request can't resurrect an
 * already-restored or superseded anchor.
 */
internal data class SharedAdaptiveScrollAnchor(
    val requestId: String,
    val messageId: String?,
    val messageOffsetPx: Int,
    val fallbackIndex: Int,
    val atLatest: Boolean,
    val capturedAtMillis: Long,
) {
    init {
        require(requestId.isNotBlank()) { "Adaptive scroll anchor requires a request id" }
        require(capturedAtMillis > 0L) { "Adaptive scroll anchor requires a capture timestamp" }
    }
}

/**
 * Shell-scoped viewport transfer store. Staged by the standalone Thread before it posts the open
 * request; peeked by the destination pane child while restoring; consumed only after the
 * destination reports the message located and the viewport re-applied (commit), or cleared when
 * the adoption token is cancelled/rejected/times out.
 */
internal class SharedAdaptiveScrollAnchorStore {
    private val lock = SynchronizedObject()
    private val anchors = mutableMapOf<String, SharedAdaptiveScrollAnchor>()

    private fun key(shellInstanceId: String, identity: RaftAdaptiveDetailIdentity): String =
        "${shellInstanceId.trim()}\u001f${identity.key}"

    /** Stage or replace the anchor for one pending adoption under the same request. */
    fun stage(
        shellInstanceId: String,
        identity: RaftAdaptiveDetailIdentity,
        anchor: SharedAdaptiveScrollAnchor,
    ) = synchronized(lock) {
        anchors[key(shellInstanceId, identity)] = anchor
    }

    /**
     * Read without consuming. The destination keeps the record until its restore commit; callers
     * must not treat a peek hit as proof the transfer already happened.
     */
    fun peek(
        shellInstanceId: String,
        identity: RaftAdaptiveDetailIdentity,
    ): SharedAdaptiveScrollAnchor? =
        synchronized(lock) { anchors[key(shellInstanceId, identity)] }

    /** Consume exactly the record staged under [requestId]; stale/other requests leave it alone. */
    fun consume(
        shellInstanceId: String,
        identity: RaftAdaptiveDetailIdentity,
        requestId: String,
    ): SharedAdaptiveScrollAnchor? =
        synchronized(lock) {
            val key = key(shellInstanceId, identity)
            val current = anchors[key] ?: return null
            if (current.requestId != requestId) return null
            anchors.remove(key)
            current
        }

    /** Drop the staged anchor for a cancelled/rejected adoption under the same request. */
    fun discard(
        shellInstanceId: String,
        identity: RaftAdaptiveDetailIdentity,
        requestId: String,
    ) {
        synchronized(lock) {
            val key = key(shellInstanceId, identity)
            if (anchors[key]?.requestId == requestId) anchors.remove(key)
        }
    }

    fun clear() {
        synchronized(lock) { anchors.clear() }
    }
}
