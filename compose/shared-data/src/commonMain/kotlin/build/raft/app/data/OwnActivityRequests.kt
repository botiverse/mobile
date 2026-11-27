package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Mobile parity with `botiverse/slock` `#17040` / `#17064`
 * (`packages/shared/src/activityObjectsContract.ts` + `packages/web/src/store/ownActivityRequests.ts`):
 * tags this client's Activity object writes (`POST /api/objects/activity/{done,undone,read}`) and
 * `POST /api/channels/inbox/read-all` with `X-Raft-Client-Request-Id` so the echoed
 * `activity:changed` frame does not trigger a redundant Activity list re-read.
 */
@RaftCoreInternalApi const val ActivityClientRequestIdHeader = "X-Raft-Client-Request-Id"
@RaftCoreInternalApi const val ActivityClientRequestIdMaxChars = 64
@RaftCoreInternalApi const val OwnActivityRequestKeptMillis = 60_000L

@OptIn(ExperimentalUuidApi::class)
@RaftCoreInternalApi fun randomActivityClientRequestId(): String = Uuid.random().toString()

@RaftCoreInternalApi fun isValidActivityClientRequestId(value: String): Boolean {
    if (value.isEmpty() || value.length > ActivityClientRequestIdMaxChars) return false
    for (ch in value) {
        val allowed =
            (ch in 'A'..'Z') ||
                (ch in 'a'..'z') ||
                (ch in '0'..'9') ||
                ch == '_' ||
                ch == '-'
        if (!allowed) return false
    }
    return true
}

/**
 * Remembers `X-Raft-Client-Request-Id` values sent by this [SharedActivityStore] instance so the
 * matching `activity:changed` echo skips its redundant Activity list refresh. Exact match,
 * single-use, 60-second TTL (mirroring `ownActivityRequests.ts`).
 */
@RaftCoreInternalApi class OwnActivityRequestTracker(
    private val nowMillis: () -> Long,
    private val keptMillis: Long = OwnActivityRequestKeptMillis,
    private val mintId: () -> String = ::randomActivityClientRequestId,
) {
    private val lock = SynchronizedObject()
    private val sent = LinkedHashMap<String, Long>()

    fun mint(): String {
        val now = nowMillis()
        val id = mintId()
        synchronized(lock) {
            sweepExpiredLocked(now)
            sent[id] = now
        }
        return id
    }

    fun take(requestId: String?): Boolean {
        if (requestId.isNullOrBlank()) return false
        val now = nowMillis()
        return synchronized(lock) {
            val at = sent.remove(requestId) ?: return@synchronized false
            now <= at + keptMillis
        }
    }

    fun forget(requestId: String?) {
        if (requestId.isNullOrBlank()) return
        synchronized(lock) {
            sent.remove(requestId)
        }
    }

    fun clear() {
        synchronized(lock) {
            sent.clear()
        }
    }

    private fun sweepExpiredLocked(now: Long) {
        if (sent.isEmpty()) return
        val iterator = sent.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now > entry.value + keptMillis) {
                iterator.remove()
            }
        }
    }
}
