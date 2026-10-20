package build.raft.app

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Page-owned lifecycle fence for cross-process composer-image event sessions.
 *
 * A partial N/N accumulator can outlive the event callback which first created it.  The tracker
 * keeps the session identity independent from the mutable Compose state, and remembers whether a
 * complete batch has already entered the process-owned upload handoff.  Disposal may immediately
 * abort partial sessions; an in-flight handoff is allowed to run its `finally` cleanup, with a
 * second sweep after the consumer job joins as a fallback.
 */
internal data class ThreadComposerImageEventSessionCloseResult(
    val abortSessionIds: Set<String>,
    val inFlightSessionIds: Set<String>
)

internal class ThreadComposerImageEventSessionTracker {
    private val lock = SynchronizedObject()
    private val sessions = linkedMapOf<String, Boolean>()
    /** Retain the handoff bit after close until the uploader's finally path releases it. */
    private val closedInFlightSessionIds = linkedSetOf<String>()
    private var closed = false

    fun track(sessionId: String): Boolean {
        val normalized = sessionId.trim()
        if (normalized.isBlank()) return false
        return synchronized(lock) {
            if (closed) {
                false
            } else {
                if (normalized !in sessions) {
                    sessions[normalized] = false
                }
                true
            }
        }
    }

    /** Mark a complete N/N batch as handed to the process-owned uploader. */
    fun markHandoffStarted(sessionId: String): Boolean {
        val normalized = sessionId.trim()
        if (normalized.isBlank()) return false
        return synchronized(lock) {
            val state = sessions[normalized] ?: return@synchronized false
            if (closed || state) return@synchronized false
            sessions[normalized] = true
            true
        }
    }

    fun release(sessionId: String) {
        synchronized(lock) {
            val normalized = sessionId.trim()
            sessions.remove(normalized)
            closedInFlightSessionIds.remove(normalized)
        }
    }

    fun owns(sessionId: String): Boolean = synchronized(lock) {
        !closed && sessionId.trim() in sessions
    }

    /** True while the complete N/N batch is owned by the process uploader. */
    fun isHandoffStarted(sessionId: String): Boolean = synchronized(lock) {
        val normalized = sessionId.trim()
        sessions[normalized] == true || normalized in closedInFlightSessionIds
    }

    /** Close admission for this page and return every session captured before disposal. */
    fun close(): ThreadComposerImageEventSessionCloseResult = synchronized(lock) {
        closed = true
        val abort = sessions.filterValues { started -> !started }.keys.toSet()
        val inFlight = sessions.filterValues { started -> started }.keys.toSet()
        closedInFlightSessionIds += inFlight
        sessions.clear()
        ThreadComposerImageEventSessionCloseResult(abort, inFlight)
    }

    fun isClosed(): Boolean = synchronized(lock) { closed }
}

/** Register a preview owner or synchronously retire the already-admitted process session. */
internal fun registerThreadComposerImagePreviewSession(
    sessionId: String,
    tracker: ThreadComposerImageEventSessionTracker,
    abort: (String) -> Unit,
): Boolean {
    val accepted = tracker.track(sessionId)
    if (!accepted) abort(sessionId.trim())
    return accepted
}

/**
 * Abort one Thread page's partial composer-image sessions and remove their accumulators.
 *
 * The caller holds its page mutex while invoking this helper.  Native retirement is delegated so
 * tests can observe the exact refs and production can use the page's bridge module.  A session
 * which has already completed is harmless: the registry/ledger transition is idempotent and
 * returns no refs.
 */
internal fun abortThreadComposerImageEventSessions(
    sessionIds: Collection<String>,
    threadKey: String,
    batches: MutableMap<ThreadComposerImageEventBatchKey, ThreadComposerImageEventBatchAccumulator>,
    registry: RaftFilePreviewEphemeralActionRegistry = raftFilePreviewEphemeralActionRegistry(),
    cleanupLedger: RaftFilePreviewEphemeralCleanupLedger = raftFilePreviewEphemeralCleanupLedger(),
    retire: (sessionId: String, fileRefs: Set<String>) -> Unit = { id, refs ->
        ThreadComposerTemporaryFileRetirement.retire(id, refs)
    }
): Set<String> {
    val normalizedThreadKey = threadKey.trim()
    val normalizedIds = sessionIds.map(String::trim).filter(String::isNotBlank).toSet()
    if (normalizedThreadKey.isBlank() || normalizedIds.isEmpty()) return emptySet()
    val cleanupRefs = linkedSetOf<String>()
    normalizedIds.forEach { sessionId ->
        batches.keys
            .filter { key -> key.sessionId == sessionId && key.threadKey == normalizedThreadKey }
            .forEach { key -> batches.remove(key)?.abort() }
        val refs = raftFilePreviewAbortComposerImageEventSession(
            sessionId = sessionId,
            registry = registry,
            cleanupLedger = cleanupLedger
        )
        if (refs.isNotEmpty()) {
            cleanupRefs += refs
            retire(sessionId, refs)
        }
    }
    return cleanupRefs
}
