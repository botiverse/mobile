package build.raft.app

import build.raft.app.data.ConversationReadOnSight
import build.raft.app.data.DataResult
import build.raft.app.data.MarkUnreadOutcome
import build.raft.app.data.RaftReadToggleNotice
import build.raft.app.data.leftMarkedUnread
import build.raft.app.data.markUnreadOutcome
import build.raft.app.model.PerReceiverReadStateChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val ThreadVisibleReadCoalesceWindowMillis = 2_000L

/**
 * Visible read (the coalesce window, the in-flight follow-up and the deferred retry) and Mark as
 * Unread with its hold, for [SharedThreadStore].
 *
 * The store dispatches into it on its own scope, on the same thread as before; it starts no scope
 * of its own and takes no lock. It owns the read markers and the unread hold; the store resets the
 * markers from `clear()` ([resetReadMarkers]) and opens the visible-read gate from `dispatch`
 * ([showVisibleRead]). The store keeps `deferredReadJob` (clear() cancels it with its other jobs):
 * the deferred retry still launches on the store's scope and is read and written here through
 * [getDeferredReadJob] / [setDeferredReadJob]. [readOnSight] is keyed by the store
 * ([readOnSightOwner]), as before. Store state is only read ([currentState]).
 */
internal class ThreadVisibleRead(
    private val dataSource: SharedThreadDataSource,
    private val scope: CoroutineScope,
    private val diagnosticsLogger: ((String) -> Unit)?,
    private val readCoalesceTimeSource: TimeSource,
    private val readOnSight: ConversationReadOnSight,
    private val readOnSightOwner: Any,
    readToggleNotices: MutableSharedFlow<RaftReadToggleNotice>,
    private val currentState: () -> SharedThreadState,
    private val currentVisiblePayloadOwnerScopeId: () -> String?,
    private val getDeferredReadJob: () -> Job?,
    private val setDeferredReadJob: (Job?) -> Unit,
) {
    private val _readToggleNotices = readToggleNotices

    private var readInFlightMarker: ThreadVisibleReadMarker? = null
    private var pendingReadMarker: ThreadVisibleReadMarker? = null
    private var lastCompletedReadMarker: ThreadVisibleReadMarker? = null
    private var lastCompletedReadAt: TimeMark? = null
    // fb-4440a18c (artin, 1.22.0): whether the page still shows this conversation. The page's
    // visible-read gate only covers its own dispatch; the deferred retry and the queued in-flight
    // follow-up run later, after the page may have been left, covered or backgrounded, and must not
    // mark anything read then. MarkVisibleRead opens it, [SharedThreadIntent.VisibleReadHidden] shuts it.
    private var visibleReadShown: Boolean = true
    // botiverse/slock #11687: the thread this page marked unread. Its visible reads are held while
    // the page shows it (web threadUnreadHold / autoReadHold), so the open page does not read it
    // again at once; leaving the page (a new store) or showing another thread ends the hold, and so
    // does sending in a channel or DM marked here (#16250, [endConversationUnreadHoldOnSend]).
    private var markedUnreadHoldChannelId: String? = null
    // #proj-mobile:#70: the thread whose Mark as Unread was answered 202 (admitted, result unknown). The
    // `read_state:updated` for it decides; it says so (the nothing toast) only while the hold is still on.
    private var pendingMarkUnreadChannelId: String? = null
    /** The store's visible payload owner scope, read at each use. */
    private val visiblePayloadOwnerScopeId: String?
        get() = currentVisiblePayloadOwnerScopeId()

    /** The store's field, read and written at each use. */
    private var deferredReadJob: Job?
        get() = getDeferredReadJob()
        set(value) {
            setDeferredReadJob(value)
        }

    /** `dispatch(MarkVisibleRead)`: opened there, not in the launched read. */
    fun showVisibleRead() {
        visibleReadShown = true
    }

    /** `clear()`: the read markers of the cleared route. */
    fun resetReadMarkers() {
        readInFlightMarker = null
        pendingReadMarker = null
        lastCompletedReadMarker = null
        lastCompletedReadAt = null
    }

    /** fb-4440a18c: the page stopped showing this conversation; nothing it queued may be marked now. */
    fun hideVisibleRead() {
        visibleReadShown = false
        readOnSight.hide(readOnSightOwner)
        val hadDeferred = deferredReadJob?.isActive == true
        deferredReadJob?.cancel()
        deferredReadJob = null
        val dropped = pendingReadMarker
        pendingReadMarker = null
        if (hadDeferred || dropped != null) {
            diagnosticsLogger?.invoke(
                "thread_read_skip channelId=${dropped?.channelId.orEmpty()} reason=not_shown " +
                    "deferred=$hadDeferred pending=${dropped?.latestMessageId.orEmpty()}"
            )
        }
    }

    suspend fun markVisibleRead(visibleTailIndex: Int? = null) {
        // Shown again below only once this read is known to go out for the live tail.
        readOnSight.hide(readOnSightOwner)
        if (!visibleReadShown) {
            diagnosticsLogger?.invoke("thread_read_skip reason=not_shown")
            return
        }
        val authorityState = currentState()
        if (
            authorityState.accessResolution != ThreadAccessResolution.Resolved ||
            !authorityState.writeActionsEnabled
        ) {
            diagnosticsLogger?.invoke("thread_read_skip reason=write_authority_closed")
            return
        }
        val expectedProvenance = dataSource.currentReplyCacheProvenance() ?: return
        if (visiblePayloadOwnerScopeId != expectedProvenance.threadPayloadOwnerScopeId) return
        val expectedRoute = authorityState.payload.resolveThreadRouteIdentity()
        fun authorityStillCurrent(): Boolean {
            val currentState = currentState()
            return currentState.accessResolution == ThreadAccessResolution.Resolved &&
                currentState.writeActionsEnabled &&
                currentState.payload.resolveThreadRouteIdentity() == expectedRoute &&
                dataSource.isCurrentReplyCacheProvenance(expectedProvenance)
        }
        if (!authorityStillCurrent()) return
        val marker = authorityState.payload.visibleReadMarker(visibleTailIndex) ?: return
        if (marker.channelId == markedUnreadHoldChannelId) {
            diagnosticsLogger?.invoke(
                "thread_read_skip channelId=${marker.channelId} reason=marked_unread_hold latest=${marker.latestMessageId}"
            )
            return
        }
        // Reading another thread here means the person left the one they marked: its hold ends.
        markedUnreadHoldChannelId = null
        pendingMarkUnreadChannelId = null
        // Read on sight only at the live tail: scrolled up, a live append is not read (task #232),
        // so Activity must count it as the server does.
        if (visibleTailIndex == null || authorityState.payload.visibleReadMarker(null) == marker) {
            readOnSight.show(readOnSightOwner, marker.channelId)
        }
        if (marker.wasRecentlyMarkedRead()) {
            // Task #19 (Hands 2aa74345): the window only coalesces. A marker NEWER than the completed one
            // (a message that arrived, or a stand-in replaced by the network page, right after the last
            // read) must still be marked once the window ends: nothing else re-runs the visible read while
            // the user just stays on the page, and the conversation kept one unread forever.
            val deferMs = if (marker != lastCompletedReadMarker) readCoalesceRemainingMillis() else null
            diagnosticsLogger?.invoke(
                "thread_read_skip channelId=${marker.channelId} reason=recently_marked latest=${marker.latestMessageId}" +
                    (deferMs?.let { " deferredMs=$it" } ?: "")
            )
            if (deferMs != null) {
                deferredReadJob?.cancel()
                deferredReadJob =
                    scope.launch {
                        delay(deferMs)
                        // Only the marker the user was shown: anything that changed since (a newer message
                        // while the page went to the background, older rows shifting the index) is owned by
                        // the page's own next visible-read dispatch, never marked from here unseen.
                        if (currentState().payload.visibleReadMarker(visibleTailIndex) == marker) {
                            markVisibleRead(visibleTailIndex)
                        } else {
                            diagnosticsLogger?.invoke(
                                "thread_read_skip channelId=${marker.channelId} reason=deferred_marker_changed " +
                                    "latest=${marker.latestMessageId}"
                            )
                        }
                    }
            }
            return
        }
        if (readInFlightMarker != null) {
            pendingReadMarker = marker
            diagnosticsLogger?.invoke(
                "thread_read_skip channelId=${marker.channelId} reason=in_flight latest=${marker.latestMessageId}"
            )
            return
        }

        var nextMarker: ThreadVisibleReadMarker? = marker
        while (nextMarker != null) {
            // A follow-up queued while the previous read was in flight goes out only if the page still
            // shows the conversation (fb-4440a18c: it was sent after the person had left).
            if (!authorityStillCurrent() || !visibleReadShown) {
                pendingReadMarker = null
                return
            }
            val current = nextMarker
            pendingReadMarker = null
            readInFlightMarker = current
            val startedAt = TimeSource.Monotonic.markNow()
            diagnosticsLogger?.invoke(
                "thread_read_start channelId=${current.channelId} latest=${current.latestMessageId}"
            )
            try {
                val result =
                    dataSource.markVisibleChannelRead(
                        current.channelId,
                        current.latestSeq,
                        expectedProvenance = expectedProvenance
                    )
                if (
                    authorityStillCurrent() &&
                    (result is DataResult.Content || result is DataResult.Empty)
                ) {
                    lastCompletedReadMarker = current
                    lastCompletedReadAt = readCoalesceTimeSource.markNow()
                }
                diagnosticsLogger?.invoke(
                    "thread_read_result channelId=${current.channelId} result=${result.threadDiagnosticsResult()} " +
                        "mode=${if (current.latestSeq != null && current.latestSeq > 0L) "seq" else "all"} " +
                        "seq=${current.latestSeq ?: -1} latest=${current.latestMessageId} " +
                        "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
            } finally {
                readInFlightMarker = null
            }
            val pending = pendingReadMarker
            nextMarker =
                if (pending != null && pending != current && pending != lastCompletedReadMarker) {
                    pending
                } else {
                    pendingReadMarker = null
                    null
                }
        }
    }

    /**
     * botiverse/slock #11687 (web threadUnreadHold.markOpenThreadUnread): the hold starts before the
     * write, so no visible read of this page goes out after it; a read still queued is dropped. The
     * answer is read as the Activity row reads it ([markUnreadOutcome]): Counted / Marked keep the
     * hold; Nothing to mark says so (the Activity toast) and, like a failed write, ends the hold, as
     * nothing was marked. A 202 (Pending, #proj-mobile:#70) keeps the hold with no toast until the
     * `read_state:updated` for the thread answers ([resolvePendingMarkUnread]).
     */
    suspend fun markThreadUnread(threadChannelId: String) {
        val channelId = threadChannelId.trim().takeIf { it.isNotBlank() } ?: return
        // botiverse/slock #14222: a followed thread marked from a channel's list is not the one this
        // page shows: the same write with no hold (web markThreadUnreadFromThread), so the page's
        // own reads (and a hold on its conversation) go on as before.
        val holds = channelId == currentState().payload.cacheThreadId()
        if (holds) {
            markedUnreadHoldChannelId = channelId
            readOnSight.hide(readOnSightOwner)
            pendingMarkUnreadChannelId = null
            deferredReadJob?.cancel()
            deferredReadJob = null
            if (pendingReadMarker?.channelId == channelId) pendingReadMarker = null
            lastCompletedReadMarker = null
            lastCompletedReadAt = null
        }
        val result = dataSource.markThreadChannelUnread(channelId)
        val outcome = (result as? DataResult.Content)?.data?.markUnreadOutcome()
        diagnosticsLogger?.invoke(
            "thread_mark_unread channelId=$channelId result=${result.threadDiagnosticsResult()} " +
                "outcome=${outcome?.name ?: "none"}"
        )
        if (outcome == MarkUnreadOutcome.Counted || outcome == MarkUnreadOutcome.Marked) return
        if (outcome == MarkUnreadOutcome.Pending) {
            if (markedUnreadHoldChannelId == channelId) pendingMarkUnreadChannelId = channelId
            return
        }
        if (markedUnreadHoldChannelId == channelId) markedUnreadHoldChannelId = null
        if (outcome == MarkUnreadOutcome.NothingToMark) {
            _readToggleNotices.tryEmit(RaftReadToggleNotice.MarkUnreadNothing)
        }
    }

    /**
     * botiverse/slock #14222: the conversation this page shows (its timeline channel), marked unread
     * and held as [markThreadUnread] holds an open thread (web messageStore.markUnread keeps it
     * unread while the person stays in it).
     */
    suspend fun markConversationUnread() {
        val payload = currentState().payload
        if (payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread) return
        val channelId = payload.cacheThreadId() ?: return
        markThreadUnread(channelId)
    }

    /**
     * botiverse/slock #16250 (web messageStore.sendMessage forgetManualUnread): a message the person
     * sends from this page into the channel or DM they marked unread here ends that hold. It ends as
     * the send starts, whatever its answer, as web forgets the mark before its request: their own
     * message reads the conversation and later ones are read on sight again. A reply thread's hold
     * stays, as web's thread hold (threadUnreadHold) is not ended by a reply.
     */
    fun endConversationUnreadHoldOnSend(payload: RaftThreadScreenPayload) {
        if (payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread) return
        val channelId = markedUnreadHoldChannelId?.takeIf { it == payload.cacheThreadId() } ?: return
        markedUnreadHoldChannelId = null
        pendingMarkUnreadChannelId = null
        diagnosticsLogger?.invoke("thread_mark_unread_hold_end channelId=$channelId reason=sent")
    }

    /**
     * #proj-mobile:#70 (Tenny): the `read_state:updated` that answers a 202 Mark as Unread. Marked (a
     * count or the mark) keeps the hold; not marked reads as a 200 nothing to mark (the toast, the
     * hold ends), but only while the person is still on the thread (the hold is on). Once they left,
     * the event only updates the state, with no toast.
     */
    fun resolvePendingMarkUnread(event: PerReceiverReadStateChanged, sessionServerId: String?) {
        val channelId = pendingMarkUnreadChannelId ?: return
        if (event.serverId != null && !sessionServerId.isNullOrBlank() && event.serverId?.value != sessionServerId) return
        val scope = event.scopes.lastOrNull { it.scopeId.value == channelId } ?: return
        pendingMarkUnreadChannelId = null
        val marked = scope.leftMarkedUnread()
        val stillThere = markedUnreadHoldChannelId == channelId
        diagnosticsLogger?.invoke(
            "thread_mark_unread_pending_resolved channelId=$channelId marked=$marked stillThere=$stillThere"
        )
        if (marked || !stillThere) return
        markedUnreadHoldChannelId = null
        _readToggleNotices.tryEmit(RaftReadToggleNotice.MarkUnreadNothing)
    }

    /** Time left in the coalesce window of the last completed read (at least 1 ms). */
    private fun readCoalesceRemainingMillis(): Long {
        val ageMs = lastCompletedReadAt?.elapsedNow()?.inWholeMilliseconds ?: 0L
        return (ThreadVisibleReadCoalesceWindowMillis - ageMs).coerceAtLeast(1L)
    }

    private fun ThreadVisibleReadMarker.wasRecentlyMarkedRead(): Boolean {
        val completed = lastCompletedReadMarker ?: return false
        if (completed.channelId != channelId) return false
        if (completed == this) return true
        val completedAt = lastCompletedReadAt ?: return false
        val ageMs = completedAt.elapsedNow().inWholeMilliseconds
        return ageMs in 0L until ThreadVisibleReadCoalesceWindowMillis
    }
}
