package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Full schema-v6 is a separate apply gate; the V1 persisted envelope remains permanently ineligible. */
const val CANONICAL_MESSAGE_FULL_V2_SOLE_APPLY_ELIGIBLE: Boolean = true

/**
 * Task #168: frames of one channel kept while its enter snapshot loads. One more marks the window that
 * snapshot commits discontinuous instead (the buffer is dropped): never an unbounded queue.
 */
internal const val CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP: Int = 64

sealed interface CanonicalMessageV2CanaryOutcome {
    /** Not written: the event remains on the existing T3-shadow + legacy projection path (display only). */
    data object NotSelected : CanonicalMessageV2CanaryOutcome

    /** The V2 commit completed and the caller may project the event after a fresh grant check. */
    data object Committed : CanonicalMessageV2CanaryOutcome

    /**
     * Task #168: the channel's enter snapshot is loading; the event is buffered for it and written (or not)
     * when that snapshot commits. Nothing is written yet and the caller never waits for the network: it
     * projects the event now through the legacy display path after a fresh grant check, as for [Committed].
     */
    data object Pending : CanonicalMessageV2CanaryOutcome

    /**
     * The durable V2 write did not happen (revoked, evicted, failed). Nothing was written; the caller
     * still projects the event through the legacy display path after a fresh grant check (task #168: a
     * failed durable write must never hide a message).
     */
    data class Held(val diagnostic: String) : CanonicalMessageV2CanaryOutcome
}

/** Result of one server page fetch (enter snapshot, reply-thread snapshot). */
sealed interface CanonicalMessageV2SnapshotLoadResult {
    /**
     * [coverage] (message storage Phase 2a): the seq range the page proves — from the server `messageWindow`
     * when the page has one, else the legacy latest-page claim; `null` = the page proves no range (its facts
     * are still stored).
     */
    data class Loaded(
        val frame: CanonicalMessageV2SnapshotFrame,
        val coverage: CanonicalMessageV2Coverage? = null,
        /** Phase 2b: page rows that could not be stored; [coverage] is split around them. */
        val excludedSeqs: Set<Long> = emptySet(),
    ) : CanonicalMessageV2SnapshotLoadResult
    data class Unavailable(val diagnostic: String) : CanonicalMessageV2SnapshotLoadResult
}

/** Task #168 (diagnostics / tests): what the realtime path does with the next frame of a scope. */
enum class CanonicalMessageV2ScopeContinuity {
    /** No window committed in this process (never opened, or restored from disk only): display only. */
    NoWindow,

    /** The window is known to hold every frame since its baseline: frames are written incrementally. */
    Continuous,

    /** The window may miss frames: display only until the user next opens the channel. */
    Discontinuous,
}

/** One delivery of a realtime frame: the exact decoded frame and the connection that delivered it. */
internal data class CanonicalMessageV2Delivery(
    val foldInput: CanonicalMessageFoldInput,
    val frameTransportEpoch: Long?,
)

/** A frame that arrived while its channel's enter snapshot was loading, with the epoch it was accepted under. */
internal class CanonicalMessageV2BufferedFrame(
    val delivery: CanonicalMessageV2Delivery,
    /** The frame's own epoch, else the partition's recorded epoch when it was accepted (as #2991). */
    val transportEpoch: Long?,
)

/**
 * Task #168: the pending external baseline of one enter snapshot, registered BEFORE its fetch
 * ([CanonicalMessageV2ProductionCanary.beginEnterBaseline]). Everything that decides whether the window it
 * commits is continuous with the realtime stream is captured at request time. All mutable fields are guarded
 * by the canary's state mutex.
 */
class CanonicalMessageV2EnterBaseline internal constructor(
    internal val partition: DurablePartitionKey,
    internal val scopeId: SyncScopeId,
    /** The recorded transport epoch when the request was sent (#2991 rules); null = none recorded yet. */
    internal val epochAtRequest: Long?,
    internal val roomLeaveCountAtRequest: Long,
    /** The conversation directory said the user is not a member when the request was sent. */
    internal val nonMemberAtRequest: Boolean,
    /**
     * The server had already sent `rooms:joined` on the socket of [epochAtRequest] when the request was sent:
     * that connection is subscribed to every member room, so a page read from here on is continuous with
     * its frames. Without it the page may predate the connect-time room join.
     */
    internal val roomsJoinedAtRequest: Boolean,
    /** `enter` or `catch_up` (diagnostics only). */
    internal val source: String = "enter",
    /**
     * Task #14a: the parent channel of a reply-thread enter (`null` for a top-level channel). Its room leaves
     * count against the thread window too, and the committed cursor must name it.
     */
    internal val replyParentScopeId: SyncScopeId? = null,
    /**
     * Task #14c: the conversation directory said the user is not a member of the reply thread's PARENT channel
     * when the request was sent (always `false` for a top-level channel). Unlike [nonMemberAtRequest] (the
     * thread's own follow status), a thread join ack never lifts it.
     */
    internal val parentNonMemberAtRequest: Boolean = false,
    /**
     * Task #14c: the server's latest answer to `join:channel` for [scopeId] on the socket of [epochAtRequest]
     * when the request was sent, with no leave of that room ever noted in this process
     * ([CanonicalMessageV2ThreadJoin]).
     */
    internal val threadJoinAtRequest: CanonicalMessageV2ThreadJoin = CanonicalMessageV2ThreadJoin.Unconfirmed,
) {
    internal val buffered = ArrayList<CanonicalMessageV2BufferedFrame>()

    /** Task #14c: `channel:join_denied` for [scopeId] arrived while the fetch was in flight. */
    internal var threadJoinDeniedSinceRequest = false

    /**
     * Task #14c (diagnostics only): `channel:joined` for [scopeId] arrived while the fetch was in flight. It
     * does NOT make this baseline continuous: see [CanonicalMessageV2ProductionCanary] (ordering argument).
     */
    internal var threadJoinAckedSinceRequest = false
    internal var overflowed = false
    internal var closed = false

    /** Another writer committed into this channel while the fetch was in flight. */
    internal var superseded = false

    internal fun alreadyHolds(delivery: CanonicalMessageV2Delivery): Boolean =
        buffered.any { it.delivery == delivery }
}

/** Task #14c: what the server last answered to this socket's `join:channel` of one thread channel. */
internal enum class CanonicalMessageV2ThreadJoin {
    /** No answer on this connection (or the room was left at any time in this process): nothing is known. */
    Unconfirmed,

    /** `channel:joined` on this connection and the room was never left in this process: the socket is in the room. */
    Joined,

    /** `channel:join_denied` was the latest answer on this connection. */
    Denied,
}

/** Task #14c: the latest `join:channel` answer for one (partition, channel id). */
private class CanonicalMessageV2ChannelJoinSignal(
    val transportEpoch: Long,
    val joined: Boolean,
) {
    /** Guarded by the canary's state mutex: this ack already requested its (single) late-ack catch-up. */
    var catchUpRequested: Boolean = false
}

/**
 * Task #14c: `channel:joined` for reply thread [threadScopeId] arrived on the current connection
 * ([transportEpoch]) after that thread's enter was registered, so the enter committed its window discontinuous
 * only for want of an earlier ack ([CanonicalMessageV2ProductionCanary.threadJoinConfirmations]). The thread page,
 * if it is still open, runs its gap catch-up once: that catch-up's baseline is registered after this ack.
 */
data class CanonicalMessageV2ThreadJoinConfirmed(
    val partition: DurablePartitionKey,
    val threadScopeId: SyncScopeId,
    val replyParentScopeId: SyncScopeId,
    val transportEpoch: Long,
)

/**
 * The realtime state of one scope that has a window committed in this process (task #168: one per windowed
 * channel / DM, no single selected slot). Created only by an enter-snapshot commit; mutable fields are
 * guarded by the canary's state mutex.
 */
private class CanonicalMessageV2CanaryScopeState(
    var cursor: PersistedCursor?,
    /** The connection the baseline is known to be continuous with (the enter request's epoch). */
    val committedTransportEpoch: Long?,
    /**
     * [CanonicalMessageV2ProductionCanary.noteRoomLeft] count of this room (plus, for a reply thread, its
     * parent channel's room) when the baseline was requested.
     */
    val roomLeaveCountAtBaseline: Long,
    /** Task #14a: the parent channel of a `thread` scope (its room leaves count too); `null` for top-level. */
    val replyParentScopeId: SyncScopeId? = null,
) {
    /**
     * Task #168: the window may miss frames from here on (epoch change, room leave, external commit, failed
     * write, unapplicable update). Display only; never cleared: only the next enter commit replaces the state.
     */
    var discontinuous: Boolean = false

    /**
     * Task #14c: this reply-thread window's ENTER committed discontinuous only for want of a `channel:joined`
     * received before its request (`thread_join_ack_after_request` / `thread_delivery_unconfirmed`). Cleared once a
     * late ack of the window's own connection requested its catch-up (at most one per window).
     */
    var awaitingThreadJoinAck: Boolean = false

    /**
     * Task #168: the last delivery committed for each fact id (bounded). One realtime frame reaches the
     * canary through more than one entry (process owner and AppShell owner; on iOS the process
     * owner twice), so a delivery equal to the last committed one for its fact is that same frame again.
     */
    private val lastCommittedDeliveryByFactId = LinkedHashMap<String, CanonicalMessageV2Delivery>()

    fun isAlreadyCommitted(delivery: CanonicalMessageV2Delivery): Boolean =
        lastCommittedDeliveryByFactId[delivery.foldInput.row.id] == delivery

    fun recordCommitted(delivery: CanonicalMessageV2Delivery) {
        val factId = delivery.foldInput.row.id
        lastCommittedDeliveryByFactId.remove(factId)
        lastCommittedDeliveryByFactId[factId] = delivery
        while (lastCommittedDeliveryByFactId.size > COMMITTED_DELIVERY_MEMORY) {
            lastCommittedDeliveryByFactId.remove(lastCommittedDeliveryByFactId.keys.first())
        }
    }

    private companion object {
        const val COMMITTED_DELIVERY_MEMORY = 256
    }
}

private sealed interface CanonicalMessageV2Route {
    /** No window and no enter in flight (or flag off without a window): display only, zero V2 work. */
    data object None : CanonicalMessageV2Route

    /** Buffered for the channel's loading enter snapshot. */
    data object Buffered : CanonicalMessageV2Route

    data class Window(val scope: SyncScopeKey) : CanonicalMessageV2Route

    /** The flag went off for a scope that has a window. */
    data class Revoke(val scope: SyncScopeKey) : CanonicalMessageV2Route
}

private sealed interface CanonicalMessageV2Plan {
    data object DisplayOnly : CanonicalMessageV2Plan
    data object AlreadyCommitted : CanonicalMessageV2Plan
    data class Write(val state: CanonicalMessageV2CanaryScopeState, val cursor: PersistedCursor) : CanonicalMessageV2Plan
}

private sealed interface CanonicalMessageV2AppendResult {
    data object Committed : CanonicalMessageV2AppendResult
    data object BelowWindow : CanonicalMessageV2AppendResult
    data class NotFullV2(val detail: String) : CanonicalMessageV2AppendResult
    data object BaselineRequired : CanonicalMessageV2AppendResult
    data object PartitionRevoked : CanonicalMessageV2AppendResult
    data object ScopeEvicted : CanonicalMessageV2AppendResult
    data class Failed(val reason: String) : CanonicalMessageV2AppendResult
}

/**
 * Task #168 step 3: realtime messages are written to the durable offline cache for EVERY top-level channel /
 * DM that has a window committed in this process, behind the existing `sync_core_messages` flag.
 *
 * Message storage Phase 3a: this class is the [CanonicalMessageV2LiveCoverageTracker]. "Display only" below means
 * "no claim": production hands every frame the canary does not commit to
 * [build.raft.app.data.CanonicalMessageV2RealtimeIngest], which stores it as a loose fact (no segment) under the
 * same exact grant. Only a write made here extends the head segment.
 *
 * - Which scopes: those whose window an enter snapshot committed in this process ([stateByScope], the
 *   in-memory index). A scope without one — never opened, or only restored from disk after a cold start —
 *   is display only: no request, no write, no store lookup on the display path. A restored window may have
 *   missed frames while the process was dead, so it is treated exactly like a discontinuous one until the
 *   user opens the channel again (its enter commit creates the state).
 * - Continuous (same epoch as the baseline, never marked): `New` appends (insert-only, below-window skip,
 *   cap edge, duplicate-delivery skip, #3002); `Updated` replaces the stored fact when the frame is full,
 *   the fact is in the window and has no reply thread; otherwise it marks the scope discontinuous, or is
 *   ignored when it lies below the window.
 * - Discontinuous: display only. Nothing here ever requests the network (no first-frame activation, no
 *   background re-snapshot): the next enter snapshot is the new baseline.
 * - Hole A: the enter snapshot registers a pending baseline with its request epoch before it fetches;
 *   frames of that channel meanwhile are displayed and buffered (bounded), then replayed inside the scope
 *   writer right after the enter commit by #3007's rules.
 * - The enter (and, for the viewed channel or reply thread, the gap catch-up: [beginCatchUpBaseline]) makes a window
 *   continuous only for a member channel whose connection has already seen `rooms:joined`
 *   ([noteRoomsJoined], per epoch): before it, the page may predate the server's connect-time room join.
 * - Task #14a / #14c: a reply thread's `thread` scope is mapped, checked and baselined like a channel. A reply's
 *   `message:new` names the thread channel as its `channelId` and the parent in its `conversationContext`, so
 *   it maps to `(thread channel id, thread)`; it is written only when the frame's parent (kind, channel, root)
 *   equals the parent the committed cursor stores (any other parent makes the window discontinuous), and a
 *   reconnect or a leave of the thread's OR its parent channel's room ends continuity as for a channel.
 *   Before #14c the window never became continuous: the socket's connect-time join covers only listable
 *   channels / DMs (never a thread room, so no `rooms:joined` covers it) and `join:channel` was fire-and-forget.
 *   The server now (botiverse/slock #10457) answers every `join:channel` with `channel:joined { channelId }`
 *   once the socket is in `channel:<id>`, or `channel:join_denied { channelId }` ([noteChannelJoinAck], per
 *   (partition, channel id) with the socket's epoch). A reply-thread enter commit is CONTINUOUS only when all of:
 *   1. the committed cursor's server-confirmed parent kind is the public `channel` (never a frame's claim):
 *      only then does the server broadcast replies to `channel:<threadId>`. Under a `private` / `dm` / `joint`
 *      parent they reach only followers' `user:<id>` rooms → `reason=thread_delivery_follower_only`, ack or not;
 *   2. `channel:joined` for the thread arrived on the baseline's epoch BEFORE the baseline was registered, and
 *      the thread room was never left in this process ([CanonicalMessageV2EnterBaseline.threadJoinAtRequest]);
 *      otherwise
 *      `reason=thread_delivery_unconfirmed` (no ack: also an older server, the safe default) or
 *      `reason=thread_join_ack_after_request` (the ack arrived only while the fetch was in flight). Such an ack
 *      never makes THAT enter continuous; it requests the late-ack catch-up below (as does an ack after the
 *      commit), and a later enter on the same connection is continuous too;
 *   3. none of the channel reasons apply (room_left of the thread or parent room, rooms_not_joined, external
 *      commit, buffer overflow) nor parent_mismatch / scope_family_mismatch; a `channel:join_denied` on the
 *      baseline's epoch (the latest answer at request, or one during the fetch) ends `reason=join_denied`, and
 *      one after the commit ends the continuity of a window committed on that epoch.
 *   Membership (checked at request AND at commit; the commit line prints both moments of both flags:
 *   `nonMember@request= nonMember@commit= parentNonMember@request= parentNonMember@commit=`): a known non-member
 *   of the PARENT channel always ends `reason=parent_non_member` (an ack never lifts it; top-level channels keep
 *   `reason=non_member`). Under a public parent the thread's own flag (follow status, which the directory may get
 *   wrong) never decides — a socket in the thread room receives every reply broadcast there, follower or not — so
 *   the join-ack reason of 2 is reported instead; under a private / dm / joint parent it ends
 *   `reason=thread_non_member`.
 *   Ordering argument for 2. `channel:joined` is emitted after `socket.join(channel:<threadId>)` on the same
 *   socket, so receiving it on epoch E proves the server-side join happened before that receipt. The baseline is
 *   registered after the receipt and the page request is sent after the registration, so the server reads the
 *   page after the join. Every reply is then either committed before that read (in the page) or broadcast to
 *   the room after the join, i.e. pushed to this socket: if it arrives before the commit it is buffered and
 *   replayed by #3007's rules, after it the continuous window appends it; nothing can fall between. An ack
 *   received only AFTER the registration proves no such order: the server may read the page, commit and
 *   broadcast a reply to a room the socket is not yet in, and only then join — that reply is in neither the page
 *   nor the stream, and a later reply would advance the cursor over it (a permanent hole). Hence such an ack
 *   only serves the next enter. The epoch pins the ack to the socket that delivers the frames (a reconnect
 *   drops every room; the host rejoins and the server acks again with the new epoch).
 *   Leaves: ANY leave of the thread room in this process ([noteRoomLeft], on any epoch, before or after the
 *   ack) voids every ack of that room for the rest of the process: such a thread is never continuous again
 *   until a restart. A tighter rule is not sound with what the client observes: (a) the leave runs
 *   synchronously from the room-lease registry while acks and epochs reach the canary asynchronously, so a
 *   leave cannot be tied to the socket it happened on (it may be noted before that socket's epoch, or before
 *   any epoch); (b) the server joins after an async access check and the ack carries no request id, so an ack
 *   processed after a leave may answer a join sent BEFORE it (join → ack in flight → leave → reopen → the old
 *   ack confirms a room the socket left, while the new join is still pending). Therefore a page never leaves
 *   its reply-thread room on close ([build.raft.app.data.SharedRealtimeRoomLeaseRegistry.keepJoinedUntilClear]):
 *   the room stays joined (across reconnects: the host rejoins every joined room and the server acks again on the
 *   new epoch) until it falls out of the registry's most recently released
 *   [build.raft.app.data.MAX_RETAINED_THREAD_ROOMS] kept rooms, or the registry is cleared for the shell scope;
 *   either still leaves it and notes it here (that thread's acks are void from then on). A leave of the PARENT channel's room (still left on page close when
 *   the directory says the user is not a member of it, #2994) ends the thread's continuity as before
 *   (`room_left`), but does not void the thread room's acks.
 *   Late-ack catch-up. A page's own `join:channel` is answered while its enter fetch is in flight, so the first
 *   open of a thread always ends `thread_join_ack_after_request` (or `thread_delivery_unconfirmed` when the ack
 *   is slower than the commit). An ENTER that commits discontinuous for exactly one of these two reasons arms its
 *   window; then the first `channel:joined` for the thread whose epoch is the current connection's AND the
 *   window's own (read at commit for an ack received during the fetch, else when the ack arrives) emits ONE
 *   [CanonicalMessageV2ThreadJoinConfirmed] on [threadJoinConfirmations] and disarms the window and the ack
 *   (`thread_join_confirmed … trigger=enter_commit|ack catchUp=requested subscribers=N`). Nothing is requested for
 *   an ack of an older connection, after any leave of the thread room (`catchUp=skipped reason=room_left`),
 *   or for a catch-up's own commit (only an enter arms a window), so there is no loop and no retry: a failed
 *   catch-up leaves the window discontinuous until the next enter. The open thread page (SharedThreadStore)
 *   collects the flow and runs its gap catch-up once; with no open page the item is dropped (hot, not replayed).
 *   That catch-up registers a reply-thread baseline ([beginCatchUpBaseline] with the parent) after the ack's
 *   receipt, so the ordering argument above holds for it unchanged: its verdict is read at registration
 *   (`threadJoin=joined`), its page is requested after the registration, and its commit
 *   (`catch_up=committed … continuity=continuous`) goes through the same rule (public parent, same epoch, no
 *   leave, no denial), after which realtime replies are appended.
 * - Task #14a partial facts ("store partial, fill in when data arrives", owner decision). The server's
 *   ordinary `message:new` omits `senderDescription` and `reactions`, and no other realtime delivery of that
 *   message is fuller (a reply's `thread:updated.latestReply` is the same payload, the own-send HTTP response
 *   the same object). Rules:
 *   1. Allowed-unknown set: exactly [CANONICAL_MESSAGE_V2_ALLOWED_UNKNOWN_FRAME_AGGREGATES]. A frame missing
 *      only those is written as a PARTIAL fact; a frame missing any other required aggregate
 *      ([CANONICAL_MESSAGE_V2_REQUIRED_FRAME_AGGREGATES]) or carrying an invalid scalar stays display only
 *      and turns the window discontinuous (`reason=not_full_v2 … missing=…`), as before.
 *   2. Unknown ≠ empty: the fact's bytes hold neutral placeholders (no description, no reactions) and the
 *      store keeps an explicit marker of the unknown aggregates (`messageEntity.unknownFields`, surfaced as
 *      [RestoredCanonicalMessageV2Scope.unknownFieldsByFactId]). A durable read renders the placeholders
 *      (description absent, no reaction chips); nothing reads them as authoritative: the durable projection
 *      never derives a reaction's `selected` (always false, for full facts too), a reaction toggle sends an
 *      explicit set to the server and shows the server's answer, and no reader writes a fact back.
 *   3. Completion: every write merges with the stored fact of the same id, and a known value always beats an
 *      unknown one ([SqlDelightCanonicalMessageV2Store]). A full source (enter / adjacent / catch-up page, a
 *      full `message:updated`) completes the partial fact and clears the marker
 *      (`canonical_v2_partial_completed`); a partial frame never erases a stored known value.
 *   4. Reactions: the server announces a reaction change only as a `message:updated` carrying the message's
 *      whole roster (no delta event reaches the durable store; `reaction_viewer:updated` is receiver-private
 *      and not consumed here). A frame with `reactions` sets the roster; one without leaves it as stored —
 *      known stays known, unknown stays unknown. No delta is ever applied to an unknown roster.
 *   5. Continuity: a partial frame is a real message at a real seq, so it keeps the window continuous.
 *   6. A continuous reply-thread window (task #14c, above) shares this path: a production reply is written
 *      partial like a channel message.
 *   7. Lines: `written … source=incremental[ scopeKind=thread] partial=SenderDescription,Reactions`.
 */
/**
 * Message storage design 2026-10-27 §4 "Realtime" (Phase 3a): the live coverage tracker. It decides only whether
 * a realtime frame is provably continuous with the scope's head segment (the per-scope, per-epoch, join-ack and
 * room-leave rules of [CanonicalMessageV2ProductionCanary]); when it is, it writes the frame and extends the head
 * segment ([CanonicalMessageV2CanaryOutcome.Committed]). Any other outcome means "no claim": it never decides
 * whether the fact is stored — [build.raft.app.data.CanonicalMessageV2RealtimeIngest] then stores it loose.
 */
internal interface CanonicalMessageV2LiveCoverageTracker {
    suspend fun accept(
        partition: DurablePartitionKey,
        foldInput: CanonicalMessageFoldInput,
        frameTransportEpoch: Long? = null,
    ): CanonicalMessageV2CanaryOutcome

    /**
     * A realtime frame of [foldInput]'s channel was delivered but never reached [accept], or [accept] failed for
     * it: nothing proves the stream is still whole, so no claim may grow over it.
     */
    suspend fun noteFrameUnrecorded(partition: DurablePartitionKey, foldInput: CanonicalMessageFoldInput)

    /**
     * Message storage Phase 3b: the stream of channel id [scopeId] had a hole the client cannot name per frame (a
     * resume row that could not be stored or decoded): same effect as [noteFrameUnrecorded].
     */
    suspend fun noteScopeHole(partition: DurablePartitionKey, scopeId: SyncScopeId, reason: String)

    /**
     * Message storage Phase 3b: the hole's channels are unknown (a resume page with more to follow, a row without a
     * channel id, a horizonReset): every window of [partition] is discontinuous and every enter in flight commits
     * discontinuous.
     */
    suspend fun notePartitionHole(partition: DurablePartitionKey, reason: String)
}

private const val ROOMS_JOINED_EPOCH_MEMORY = 8

/** Task #14c: how many channels' latest `join:channel` answers are remembered (least recently answered dropped). */
private const val CHANNEL_JOIN_SIGNAL_MEMORY = 256

/** Task #14c: late-ack catch-up requests kept for a slow collector (oldest dropped; never an unbounded queue). */
private const val THREAD_JOIN_CONFIRMATION_BUFFER = 16

internal class CanonicalMessageV2ProductionCanary(
    private val isEnabledForServer: (String) -> Boolean,
    private val generationEngine: CanonicalMessageV2GenerationEngine,
    private val logger: (String) -> Unit = {},
) : CanonicalMessageV2LiveCoverageTracker {
    /** Leaf lock: never held while calling the engine's suspending writes or another lock. */
    private val stateMutex = Mutex()

    /** Guarded by [stateMutex]: the windowed index (every scope with a window committed in this process). */
    private val stateByScope = mutableMapOf<SyncScopeKey, CanonicalMessageV2CanaryScopeState>()

    /** Guarded by [stateMutex]: the enter snapshot in flight for each channel id. */
    private val enterBaselineByChannel =
        mutableMapOf<Pair<DurablePartitionKey, SyncScopeId>, CanonicalMessageV2EnterBaseline>()

    /** Guarded by [stateMutex]. */
    private val currentTransportEpochByPartition = mutableMapOf<DurablePartitionKey, Long>()

    /** Guarded by [stateMutex]: the transport epochs that have seen `rooms:joined` (most recent last, bounded). */
    private val roomsJoinedEpochsByPartition = mutableMapOf<DurablePartitionKey, LinkedHashSet<Long>>()

    /**
     * Task #14c, guarded by [stateMutex]: the latest `join:channel` answer per (partition, channel id), least
     * recently answered first (bounded by [CHANNEL_JOIN_SIGNAL_MEMORY]).
     */
    private val channelJoinSignalByChannel =
        LinkedHashMap<Pair<DurablePartitionKey, SyncScopeId>, CanonicalMessageV2ChannelJoinSignal>()

    private val roomLeaveLock = SynchronizedObject()

    /**
     * Guarded by [roomLeaveLock]: how many times the socket left each (server, room). Never bounded or evicted: a
     * missing entry reads as "never left", which would let a stale join ack confirm a thread window.
     */
    private val roomLeaveCountByRoom = mutableMapOf<Pair<String, String>, Long>()

    private val threadJoinConfirmationFlow = MutableSharedFlow<CanonicalMessageV2ThreadJoinConfirmed>(
        replay = 0,
        extraBufferCapacity = THREAD_JOIN_CONFIRMATION_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Task #14c: one item per late `channel:joined` that can make a reply-thread window continuous through a gap
     * catch-up (see the class doc). Hot and not replayed: with no collector (no open thread page) it is dropped.
     */
    val threadJoinConfirmations: SharedFlow<CanonicalMessageV2ThreadJoinConfirmed> =
        threadJoinConfirmationFlow.asSharedFlow()

    init {
        generationEngine.addPartitionRevocationObserver(::onPartitionRevoked)
        generationEngine.addScopeInvalidationObserver(::onScopesInvalidated)
    }

    override suspend fun accept(
        partition: DurablePartitionKey,
        foldInput: CanonicalMessageFoldInput,
        frameTransportEpoch: Long?,
    ): CanonicalMessageV2CanaryOutcome {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        if (!CANONICAL_MESSAGE_FULL_V2_SOLE_APPLY_ELIGIBLE) return CanonicalMessageV2CanaryOutcome.NotSelected
        val delivery = CanonicalMessageV2Delivery(foldInput, frameTransportEpoch?.takeIf { it > 0L })
        // Fast path: no writer, no store. Only a scope with a window (or an enter in flight) goes further.
        val scope = when (val route = stateMutex.withLock { routeLocked(partition, delivery) }) {
            CanonicalMessageV2Route.None -> return CanonicalMessageV2CanaryOutcome.NotSelected
            CanonicalMessageV2Route.Buffered -> return CanonicalMessageV2CanaryOutcome.Pending
            is CanonicalMessageV2Route.Window -> route.scope
            is CanonicalMessageV2Route.Revoke -> route.scope
        }
        return generationEngine.withScopeWriter(scope) writer@{
            // Re-routed inside the writer: an enter commit may have replaced the state meanwhile.
            when (val route = stateMutex.withLock { routeLocked(partition, delivery) }) {
                CanonicalMessageV2Route.None -> CanonicalMessageV2CanaryOutcome.NotSelected
                CanonicalMessageV2Route.Buffered -> CanonicalMessageV2CanaryOutcome.Pending
                is CanonicalMessageV2Route.Revoke ->
                    if (route.scope == scope) revokeInsideWriter(scope) else CanonicalMessageV2CanaryOutcome.NotSelected
                is CanonicalMessageV2Route.Window ->
                    if (route.scope == scope) acceptInsideWriter(scope, partition, delivery)
                    else CanonicalMessageV2CanaryOutcome.NotSelected
            }
        }
    }

    /** Holding [stateMutex]. Buffers for a loading enter snapshot right here (no write, no writer needed). */
    private fun routeLocked(partition: DurablePartitionKey, delivery: CanonicalMessageV2Delivery): CanonicalMessageV2Route {
        val input = delivery.foldInput
        val explicit = input.v2ScopeOrNull(partition)
        val context = input.row.conversationContext
        if (explicit == null && context != null && context.channelType != REPLY_THREAD_SCOPE_KIND) {
            // An unsupported kind (or a top-level context naming a parent).
            return CanonicalMessageV2Route.None
        }
        val scope = explicit ?: run {
            // No producer context (or a reply context without its parent): the frame names only its channel
            // id. It can never be written, but a window of that channel id must not silently miss it.
            stateByScope.keys.singleOrNull { it.partition == partition && it.scopeId == input.row.channelId }
        }
        if (!isEnabledForServer(partition.serverId)) {
            return if (scope != null && scope in stateByScope) CanonicalMessageV2Route.Revoke(scope) else CanonicalMessageV2Route.None
        }
        val baseline = enterBaselineByChannel[partition to input.row.channelId]
        if (baseline != null && !baseline.closed) {
            bufferLocked(baseline, delivery)
            return CanonicalMessageV2Route.Buffered
        }
        return if (scope != null && scope in stateByScope) CanonicalMessageV2Route.Window(scope) else CanonicalMessageV2Route.None
    }

    /** Holding [stateMutex]: display now, keep the frame for the enter commit's replay. */
    private fun bufferLocked(baseline: CanonicalMessageV2EnterBaseline, delivery: CanonicalMessageV2Delivery) {
        val scopeId = baseline.scopeId
        when {
            // The same frame through another entry: kept once (one write per frame).
            baseline.alreadyHolds(delivery) -> logger(
                "canonical_v2_canary skip=duplicate_delivery server=${baseline.partition.serverId} " +
                    "scope=$scopeId pending=enter",
            )
            baseline.overflowed -> Unit
            baseline.buffered.size >= CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP -> {
                baseline.overflowed = true
                baseline.buffered.clear()
                logger(
                    "canonical_v2_canary enter=buffer_overflow server=${baseline.partition.serverId} " +
                        "scope=$scopeId cap=$CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP",
                )
            }
            else -> baseline.buffered += CanonicalMessageV2BufferedFrame(
                delivery,
                delivery.frameTransportEpoch ?: currentTransportEpochByPartition[baseline.partition],
            )
        }
    }

    private suspend fun acceptInsideWriter(
        scope: SyncScopeKey,
        partition: DurablePartitionKey,
        delivery: CanonicalMessageV2Delivery,
    ): CanonicalMessageV2CanaryOutcome {
        val plan = stateMutex.withLock { planLocked(scope, partition, delivery) }
        val (state, cursor) = when (plan) {
            CanonicalMessageV2Plan.DisplayOnly -> return CanonicalMessageV2CanaryOutcome.NotSelected
            // The same frame through another entry: it is on disk already. Never write it again.
            CanonicalMessageV2Plan.AlreadyCommitted -> return CanonicalMessageV2CanaryOutcome.Committed
            is CanonicalMessageV2Plan.Write -> plan.state to plan.cursor
        }
        val input = delivery.foldInput
        replyParentMismatchOrNull(scope, cursor, input)?.let { mismatch ->
            logger(
                "canonical_v2_canary skip=parent_mismatch server=${scope.serverId} scope=${scope.scopeId}" +
                    "${scope.kindLabel()} $mismatch",
            )
            markDiscontinuous(scope, state, "parent_mismatch")
            return CanonicalMessageV2CanaryOutcome.NotSelected
        }
        return when (input.kind) {
            CanonicalMessageFoldKind.New -> {
                when (val result = appendNewInsideWriter(scope, state, cursor, input)) {
                    CanonicalMessageV2AppendResult.Committed -> {
                        stateMutex.withLock { state.recordCommitted(delivery) }
                        CanonicalMessageV2CanaryOutcome.Committed
                    }
                    // Display only (logged inside); continuity unchanged.
                    CanonicalMessageV2AppendResult.BelowWindow -> CanonicalMessageV2CanaryOutcome.NotSelected
                    is CanonicalMessageV2AppendResult.NotFullV2 -> {
                        markDiscontinuous(scope, state, "not_full_v2 ${input.frameLabel()} ${result.detail}")
                        CanonicalMessageV2CanaryOutcome.NotSelected
                    }
                    CanonicalMessageV2AppendResult.BaselineRequired -> {
                        markDiscontinuous(scope, state, "baseline_required")
                        CanonicalMessageV2CanaryOutcome.NotSelected
                    }
                    CanonicalMessageV2AppendResult.PartitionRevoked -> heldAfterWindowLost(scope, state, "partition_revoked")
                    CanonicalMessageV2AppendResult.ScopeEvicted -> heldAfterWindowLost(scope, state, "retention_evicted")
                    is CanonicalMessageV2AppendResult.Failed -> {
                        markDiscontinuous(scope, state, result.reason)
                        CanonicalMessageV2CanaryOutcome.Held("canonical_v2_write_failed")
                    }
                }
            }
            CanonicalMessageFoldKind.Updated -> applyUpdatedInsideWriter(scope, state, cursor, delivery)
        }
    }

    /** Holding [stateMutex]: every continuity decision for one frame, before any write. */
    private fun planLocked(
        scope: SyncScopeKey,
        partition: DurablePartitionKey,
        delivery: CanonicalMessageV2Delivery,
    ): CanonicalMessageV2Plan {
        val state = stateByScope[scope] ?: return CanonicalMessageV2Plan.DisplayOnly
        if (state.isAlreadyCommitted(delivery)) {
            logger("canonical_v2_canary skip=duplicate_delivery server=${scope.serverId} scope=${scope.scopeId}")
            return CanonicalMessageV2Plan.AlreadyCommitted
        }
        val frameEpoch = delivery.frameTransportEpoch
        val committedEpoch = state.committedTransportEpoch
        if (frameEpoch != null && committedEpoch != null && frameEpoch < committedEpoch) {
            // A late frame of an older socket: the window already continues from a newer connection, and
            // frames that socket lost may sit between. Show it, never write it, change nothing (also while
            // the scope is discontinuous). Epochs only grow within a process on every host.
            logger(
                "canonical_v2_canary skip=stale_transport server=${scope.serverId} scope=${scope.scopeId} " +
                    "frameEpoch=$frameEpoch committedEpoch=$committedEpoch",
            )
            return CanonicalMessageV2Plan.DisplayOnly
        }
        if (
            !state.discontinuous &&
            roomLeaveCount(scope.serverId, scope.scopeId, state.replyParentScopeId) != state.roomLeaveCountAtBaseline
        ) {
            markDiscontinuousLocked(scope, state, "room_left")
        }
        if (!state.discontinuous) {
            // A frame that names its connection decides by that; otherwise the recorded epoch, read here.
            val transportEpoch = frameEpoch ?: currentTransportEpochByPartition[partition]
            if (transportEpoch != committedEpoch) {
                markDiscontinuousLocked(scope, state, "epoch_changed frameEpoch=$transportEpoch committedEpoch=$committedEpoch")
            }
        }
        if (state.discontinuous) {
            logger(
                "canonical_v2_canary skip=discontinuous server=${scope.serverId} scope=${scope.scopeId}" +
                    "${scope.kindLabel()} ${delivery.foldInput.frameLabel()}",
            )
            return CanonicalMessageV2Plan.DisplayOnly
        }
        val cursor = generationEngine.currentCursor(scope) ?: state.cursor
        if (cursor == null) {
            markDiscontinuousLocked(scope, state, "no_window")
            return CanonicalMessageV2Plan.DisplayOnly
        }
        state.cursor = cursor
        return CanonicalMessageV2Plan.Write(state, cursor)
    }

    /**
     * Appends one `New` to a continuous window, inside the scope writer. Shared by the realtime path and the
     * enter replay so both keep #3002's rules (insert-only, below-window skip, committed cursor). Never
     * refreshes the scope's retention rank (task #168: ranking = last user-driven write).
     */
    private suspend fun appendNewInsideWriter(
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
        cursor: PersistedCursor,
        foldInput: CanonicalMessageFoldInput,
    ): CanonicalMessageV2AppendResult {
        val windowOldest = cursor.oldestLoadedSeq
        if (windowOldest != null && foldInput.row.seq < windowOldest) {
            // Older than the stored window: nothing says the messages between it and the window's oldest
            // edge are on disk, so writing it (and stretching the edge down to it) would claim a range never
            // loaded. Show it, never write it.
            logger(
                "canonical_v2_canary skip=below_window server=${scope.serverId} scope=${scope.scopeId} " +
                    "seq=${foldInput.row.seq} oldestLoadedSeq=$windowOldest",
            )
            return CanonicalMessageV2AppendResult.BelowWindow
        }
        val converted = when (
            val conversion = foldInput.toCanonicalMessageV2Conversion(scope.partition, replyCount = 0, allowPartial = true)
        ) {
            is CanonicalMessageV2EnvelopeConversion.Converted -> conversion
            is CanonicalMessageV2EnvelopeConversion.NotFull -> return CanonicalMessageV2AppendResult.NotFullV2(conversion.detail)
        }
        val envelope = converted.envelope
        val advanced = cursor.advancedBy(envelope.fact.seq)
        val result = commitIncremental(
            // A `New` only ever adds: a stored fact may already be newer (edited, reacted).
            CanonicalMessageV2IncrementalFrame(
                scope,
                listOf(envelope),
                advanced,
                insertOnly = true,
                preserveUpdatedAt = true,
                unknownFieldsByFactId = converted.unknownFieldsByFactId(),
            ),
        ) ?: return CanonicalMessageV2AppendResult.Failed("write_failed")
        return when (result) {
            CanonicalMessageV2CommitResult.Committed -> {
                stateMutex.withLock { state.cursor = generationEngine.currentCursor(scope) ?: advanced }
                logger(
                    "canonical_v2_canary written server=${scope.serverId} scope=${scope.scopeId} source=incremental" +
                        scope.kindLabel() + converted.partialLabel(),
                )
                CanonicalMessageV2AppendResult.Committed
            }
            is CanonicalMessageV2CommitResult.BaselineRequired -> CanonicalMessageV2AppendResult.BaselineRequired
            CanonicalMessageV2CommitResult.PartitionRevoked -> CanonicalMessageV2AppendResult.PartitionRevoked
            CanonicalMessageV2CommitResult.ScopeEvicted -> CanonicalMessageV2AppendResult.ScopeEvicted
        }
    }

    /**
     * Task #168: one `message:updated` on a continuous window, inside the scope writer. Explicit fields only:
     * - below the window's oldest edge → ignored (the window never claimed it);
     * - names a reply thread (`threadId`), so its reply count is unknown → discontinuous;
     * - partial beyond the allowed-unknown aggregates (task #14a) → discontinuous;
     * - its fact is not in the window although its seq is inside the window's range → discontinuous;
     * - otherwise the stored fact is replaced (never insert-only), cursor and retention rank unchanged; an
     *   aggregate the frame does not know keeps its stored value (task #14a).
     */
    private suspend fun applyUpdatedInsideWriter(
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
        cursor: PersistedCursor,
        delivery: CanonicalMessageV2Delivery,
    ): CanonicalMessageV2CanaryOutcome {
        val row = delivery.foldInput.row
        val windowOldest = cursor.oldestLoadedSeq
        if (windowOldest != null && row.seq < windowOldest) {
            logger(
                "canonical_v2_canary skip=outside_window server=${scope.serverId} scope=${scope.scopeId} " +
                    "seq=${row.seq} oldestLoadedSeq=$windowOldest",
            )
            return CanonicalMessageV2CanaryOutcome.NotSelected
        }
        if (row.threadId != null) {
            markDiscontinuous(scope, state, "updated_thread")
            return CanonicalMessageV2CanaryOutcome.NotSelected
        }
        val converted = when (
            val conversion = delivery.foldInput.toCanonicalMessageV2Conversion(scope.partition, replyCount = 0, allowPartial = true)
        ) {
            is CanonicalMessageV2EnvelopeConversion.Converted -> conversion
            is CanonicalMessageV2EnvelopeConversion.NotFull -> {
                markDiscontinuous(
                    scope,
                    state,
                    "updated_partial ${delivery.foldInput.frameLabel()} ${conversion.detail}",
                )
                return CanonicalMessageV2CanaryOutcome.NotSelected
            }
        }
        val stored = try {
            generationEngine.storedFactExists(scope, row.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            markDiscontinuous(scope, state, "read_failed")
            return CanonicalMessageV2CanaryOutcome.Held("canonical_v2_read_failed")
        }
        if (!stored) {
            markDiscontinuous(scope, state, "updated_missing")
            return CanonicalMessageV2CanaryOutcome.NotSelected
        }
        val result = commitIncremental(
            CanonicalMessageV2IncrementalFrame(
                scope,
                listOf(converted.envelope),
                cursor,
                insertOnly = false,
                preserveUpdatedAt = true,
                unknownFieldsByFactId = converted.unknownFieldsByFactId(),
            ),
        )
        return when (result) {
            null -> {
                markDiscontinuous(scope, state, "write_failed")
                CanonicalMessageV2CanaryOutcome.Held("canonical_v2_write_failed")
            }
            CanonicalMessageV2CommitResult.Committed -> {
                stateMutex.withLock {
                    state.cursor = generationEngine.currentCursor(scope) ?: cursor
                    state.recordCommitted(delivery)
                }
                logger(
                    "canonical_v2_canary written server=${scope.serverId} scope=${scope.scopeId} source=updated" +
                        scope.kindLabel() + converted.partialLabel(),
                )
                CanonicalMessageV2CanaryOutcome.Committed
            }
            is CanonicalMessageV2CommitResult.BaselineRequired -> {
                markDiscontinuous(scope, state, "baseline_required")
                CanonicalMessageV2CanaryOutcome.NotSelected
            }
            CanonicalMessageV2CommitResult.PartitionRevoked -> heldAfterWindowLost(scope, state, "partition_revoked")
            CanonicalMessageV2CommitResult.ScopeEvicted -> heldAfterWindowLost(scope, state, "retention_evicted")
        }
    }

    /** `null` = the write threw (nothing committed: one SQLite transaction). */
    private suspend fun commitIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2CommitResult? =
        try {
            generationEngine.applyIncremental(frame)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            logger(
                "canonical_v2_canary write_failed server=${frame.scope.serverId} scope=${frame.scope.scopeId} " +
                    "reason=${throwable::class.simpleName ?: "unknown"}",
            )
            null
        }

    /**
     * Message storage Phase 3a: a frame of this channel id was delivered but skipped (the ingest queue was full)
     * or [accept] failed for it. Every window of the channel id is discontinuous from here on, and an enter in
     * flight for it commits discontinuous (as a buffer overflow), so the head segment never grows over it.
     */
    override suspend fun noteFrameUnrecorded(
        partition: DurablePartitionKey,
        foldInput: CanonicalMessageFoldInput,
    ) = noteScopeHole(partition, foldInput.row.channelId, "frame_unrecorded ${foldInput.frameLabel()}")

    override suspend fun noteScopeHole(
        partition: DurablePartitionKey,
        scopeId: SyncScopeId,
        reason: String,
    ): Unit = withContext(NonCancellable) {
        stateMutex.withLock {
            enterBaselineByChannel[partition to scopeId]?.takeIf { !it.closed }?.let { baseline ->
                baseline.overflowed = true
                baseline.buffered.clear()
            }
            stateByScope.entries
                .filter { it.key.partition == partition && it.key.scopeId == scopeId }
                .forEach { (scope, state) -> markDiscontinuousLocked(scope, state, reason) }
        }
    }

    override suspend fun notePartitionHole(partition: DurablePartitionKey, reason: String): Unit = withContext(NonCancellable) {
        stateMutex.withLock {
            enterBaselineByChannel.entries
                .filter { (key, baseline) -> key.first == partition && !baseline.closed }
                .forEach { (_, baseline) ->
                    baseline.overflowed = true
                    baseline.buffered.clear()
                }
            stateByScope.entries
                .filter { it.key.partition == partition }
                .forEach { (scope, state) -> markDiscontinuousLocked(scope, state, "partition_hole $reason") }
        }
    }

    /**
     * Task #167: the realtime transport serving [partition] (re)connected as [transportEpoch]. Posted by
     * the native host before any frame of the new socket. Recorded per partition; every scope compares it
     * with the epoch its baseline was requested under, inside its own writer.
     */
    suspend fun noteTransportEpoch(partition: DurablePartitionKey, transportEpoch: Long) {
        if (transportEpoch <= 0L) return
        stateMutex.withLock { currentTransportEpochByPartition[partition] = transportEpoch }
    }

    /**
     * Task #168: the socket left room [roomId] on [serverId] (a `leave:channel` transition; since #2994 only
     * for rooms the user is not a member of; task #14c: a reply-thread room only on a server / shell teardown). Its later frames may be missing from the stream, so every
     * window of that channel id is discontinuous from here on, and an enter in flight cannot become
     * continuous. Non-suspending: called from the room-lease registry's lock.
     */
    fun noteRoomLeft(serverId: String, roomId: String) {
        val count = synchronized(roomLeaveLock) {
            val key = serverId to roomId
            ((roomLeaveCountByRoom[key] ?: 0L) + 1L).also { roomLeaveCountByRoom[key] = it }
        }
        logger("canonical_v2_canary room_left server=$serverId scope=$roomId count=$count")
    }

    /**
     * Task #168: the server sent `rooms:joined` on the socket of [transportEpoch] (#3013: posted by the host
     * with the socket's own epoch, witness-checked by the ingress coordinator). From now on that connection
     * receives the frames of every room the user is a member of. Explicit per epoch: never inferred from
     * another epoch's signal.
     */
    suspend fun noteRoomsJoined(partition: DurablePartitionKey, transportEpoch: Long) {
        if (transportEpoch <= 0L) return
        stateMutex.withLock {
            val epochs = roomsJoinedEpochsByPartition.getOrPut(partition) { LinkedHashSet() }
            epochs.remove(transportEpoch)
            epochs += transportEpoch
            while (epochs.size > ROOMS_JOINED_EPOCH_MEMORY) epochs.remove(epochs.first())
        }
        logger("canonical_v2_canary rooms_joined server=${partition.serverId} epoch=$transportEpoch")
    }

    /**
     * Task #14c: the server answered this socket's `join:channel` for [channelId] on the socket of
     * [transportEpoch]: [joined] = `channel:joined` (the socket is now in `channel:<channelId>` and receives
     * every later broadcast to that room), else `channel:join_denied`. Posted by the host with the socket's
     * own epoch, witness-checked by the ingress coordinator, like [noteRoomsJoined]. The latest answer per
     * channel wins. It only makes a reply-thread enter (or catch-up) registered AFTER it on the same epoch
     * continuous (see the class doc); a `channel:joined` may also request the late-ack catch-up of an armed thread
     * window ([threadJoinConfirmations]); a denial ends the continuity of any window of [channelId] committed on
     * that epoch.
     */
    suspend fun noteChannelJoinAck(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        transportEpoch: Long,
        joined: Boolean,
    ) {
        if (transportEpoch <= 0L || channelId.isBlank()) return
        stateMutex.withLock {
            val key = partition to channelId
            channelJoinSignalByChannel.remove(key)
            channelJoinSignalByChannel[key] = CanonicalMessageV2ChannelJoinSignal(
                transportEpoch = transportEpoch,
                joined = joined,
            )
            while (channelJoinSignalByChannel.size > CHANNEL_JOIN_SIGNAL_MEMORY) {
                channelJoinSignalByChannel.remove(channelJoinSignalByChannel.keys.first())
            }
            enterBaselineByChannel[key]?.takeIf { !it.closed }?.let { baseline ->
                if (joined) baseline.threadJoinAckedSinceRequest = true else baseline.threadJoinDeniedSinceRequest = true
            }
            if (!joined) {
                stateByScope.entries
                    .filter { (scope, state) ->
                        scope.partition == partition && scope.scopeId == channelId &&
                            state.committedTransportEpoch == transportEpoch
                    }
                    .forEach { (scope, state) -> markDiscontinuousLocked(scope, state, THREAD_JOIN_DENIED) }
            }
        }
        logger(
            "canonical_v2_canary channel_join_ack server=${partition.serverId} scope=$channelId " +
                "epoch=$transportEpoch joined=$joined",
        )
        if (joined) {
            stateMutex.withLock {
                stateByScope.entries
                    .filter { (scope, _) ->
                        scope.partition == partition && scope.scopeId == channelId && scope.scopeKind == REPLY_THREAD_SCOPE_KIND
                    }
                    .forEach { (scope, state) -> requestThreadJoinCatchUpLocked(scope, state, trigger = "ack") }
            }
        }
    }

    /**
     * Holding [stateMutex]. Task #14c late-ack catch-up: [state]'s enter committed discontinuous only for want of an
     * earlier ack, and the latest answer for its thread is a `channel:joined` of the CURRENT connection, which is also
     * the window's own ([CanonicalMessageV2CanaryScopeState.committedTransportEpoch]), not yet used for a catch-up.
     * Requests one gap catch-up through [threadJoinConfirmations] and disarms both the window and the ack, so one ack
     * requests at most one catch-up and a catch-up never requests another (only an enter commit arms a window).
     * Futile (never requested) once the thread room was left: any leave of it voids the thread's acks.
     */
    private fun requestThreadJoinCatchUpLocked(
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
        trigger: String,
    ) {
        if (!state.awaitingThreadJoinAck || stateByScope[scope] !== state) return
        val parent = state.replyParentScopeId ?: return
        val signal = channelJoinSignalByChannel[scope.partition to scope.scopeId] ?: return
        val epoch = currentTransportEpochByPartition[scope.partition]
        if (!signal.joined || signal.catchUpRequested || signal.transportEpoch != epoch || state.committedTransportEpoch != epoch) {
            return
        }
        state.awaitingThreadJoinAck = false
        signal.catchUpRequested = true
        val label = "canonical_v2_canary thread_join_confirmed server=${scope.serverId} scope=${scope.scopeId} " +
            "scopeKind=$REPLY_THREAD_SCOPE_KIND parent=$parent epoch=$epoch trigger=$trigger"
        // Any leave of the thread room voids its acks (class doc): the catch-up could never be continuous.
        if (roomLeaveCount(scope.serverId, scope.scopeId) != 0L) {
            logger("$label catchUp=skipped reason=room_left")
            return
        }
        val subscribers = threadJoinConfirmationFlow.subscriptionCount.value
        threadJoinConfirmationFlow.tryEmit(
            CanonicalMessageV2ThreadJoinConfirmed(
                partition = scope.partition,
                threadScopeId = scope.scopeId,
                replyParentScopeId = parent,
                transportEpoch = checkNotNull(epoch),
            ),
        )
        logger("$label catchUp=requested subscribers=$subscribers")
    }

    /**
     * Holding [stateMutex]: what [noteChannelJoinAck] says about [channelId] on the socket of [epoch].
     * [threadRoomLeaveCount] is the caller's single read of the room's leave count (the one it also records as the
     * baseline's), so a leave noted concurrently cannot fall between the verdict and the recorded count.
     */
    private fun threadJoinLocked(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        epoch: Long?,
        threadRoomLeaveCount: Long,
    ): CanonicalMessageV2ThreadJoin {
        if (epoch == null) return CanonicalMessageV2ThreadJoin.Unconfirmed
        val signal = channelJoinSignalByChannel[partition to channelId]
        return when {
            signal == null || signal.transportEpoch != epoch -> CanonicalMessageV2ThreadJoin.Unconfirmed
            !signal.joined -> CanonicalMessageV2ThreadJoin.Denied
            // Any leave of the room in this process, on any epoch, before or after the ack: never trusted (see
            // the class doc: a leave is not reliably tied to an epoch, nor an ack to the join it answers).
            threadRoomLeaveCount != 0L -> CanonicalMessageV2ThreadJoin.Unconfirmed
            else -> CanonicalMessageV2ThreadJoin.Joined
        }
    }

    /** Holding [stateMutex]. */
    private fun roomsJoinedLocked(partition: DurablePartitionKey, epoch: Long?): Boolean =
        epoch != null && roomsJoinedEpochsByPartition[partition]?.contains(epoch) == true

    private fun roomLeaveCount(serverId: String, roomId: String): Long =
        synchronized(roomLeaveLock) { roomLeaveCountByRoom[serverId to roomId] ?: 0L }

    /** Task #14a: a reply thread's window also depends on its parent channel's room (replies may come through it). */
    private fun roomLeaveCount(serverId: String, roomId: String, replyParentScopeId: SyncScopeId?): Long =
        roomLeaveCounts(serverId, roomId, replyParentScopeId).let { (room, parent) -> room + parent }

    /** The room's and its reply parent's leave counts, read together under one [roomLeaveLock] hold. */
    private fun roomLeaveCounts(serverId: String, roomId: String, replyParentScopeId: SyncScopeId?): Pair<Long, Long> =
        synchronized(roomLeaveLock) {
            (roomLeaveCountByRoom[serverId to roomId] ?: 0L) to
                (replyParentScopeId?.let { roomLeaveCountByRoom[serverId to it] } ?: 0L)
        }

    /**
     * Task #168 hole A: an enter snapshot of [scopeId] is about to be requested. MUST be called before the
     * fetch. Records the request epoch; from now until [onEnterCommitted] / [abandonEnterBaseline], frames of
     * that channel are displayed and buffered instead of written. `null` = one is already in flight (the
     * caller then commits as a plain external commit).
     *
     * Task #14a: a reply-thread enter passes the thread channel as [scopeId] and its server-named parent
     * channel as [replyParentScopeId] (`null` for a top-level channel).
     */
    suspend fun beginEnterBaseline(
        partition: DurablePartitionKey,
        scopeId: SyncScopeId,
        nonMember: Boolean,
        replyParentScopeId: SyncScopeId? = null,
        /** Task #14c: the directory says the user is not a member of [replyParentScopeId]. */
        parentNonMember: Boolean = false,
    ): CanonicalMessageV2EnterBaseline? = stateMutex.withLock {
        registerBaselineLocked(
            partition,
            scopeId,
            nonMember,
            source = ENTER_SOURCE,
            replyParentScopeId = replyParentScopeId,
            parentNonMember = parentNonMember && replyParentScopeId != null,
        )
    }

    /**
     * Task #168: a gap catch-up of [scopeId] (the channel the user is viewing) is about to fetch its latest
     * page. MUST be called before that fetch. Registers the same request-time baseline as an enter, but
     * ONLY when it can make the window continuous: the user is a member of the channel and the current
     * connection has already seen `rooms:joined` (else the page may predate the connect-time room join).
     * `null` = not registered: the catch-up commits as a plain external commit (display only afterwards).
     *
     * Task #14c: a reply thread's catch-up passes its parent channel as [replyParentScopeId] and registers the
     * same baseline as its enter (join verdict and leave counts read now), so its commit follows the same thread
     * rule: refused only for a known non-member PARENT ([parentNonMember]); the thread's own [nonMember] is decided
     * at commit like an enter's. This is the late-ack catch-up's baseline ([threadJoinConfirmations]).
     */
    suspend fun beginCatchUpBaseline(
        partition: DurablePartitionKey,
        scopeId: SyncScopeId,
        nonMember: Boolean,
        replyParentScopeId: SyncScopeId? = null,
        parentNonMember: Boolean = false,
    ): CanonicalMessageV2EnterBaseline? = stateMutex.withLock {
        val epoch = currentTransportEpochByPartition[partition]
        val replyThread = replyParentScopeId != null
        val refusal = when {
            !replyThread && nonMember -> "non_member"
            replyThread && parentNonMember -> REPLY_THREAD_PARENT_NON_MEMBER
            !roomsJoinedLocked(partition, epoch) -> "rooms_not_joined"
            else -> null
        }
        if (refusal != null) {
            logger(
                "canonical_v2_canary catch_up=unregistered server=${partition.serverId} scope=$scopeId" +
                    (if (replyThread) " scopeKind=$REPLY_THREAD_SCOPE_KIND" else "") +
                    " epoch=$epoch reason=$refusal",
            )
            return@withLock null
        }
        registerBaselineLocked(
            partition,
            scopeId,
            nonMember,
            source = "catch_up",
            replyParentScopeId = replyParentScopeId,
            parentNonMember = parentNonMember && replyThread,
        )
    }

    /** Holding [stateMutex]. */
    private fun registerBaselineLocked(
        partition: DurablePartitionKey,
        scopeId: SyncScopeId,
        nonMember: Boolean,
        source: String,
        replyParentScopeId: SyncScopeId? = null,
        parentNonMember: Boolean = false,
    ): CanonicalMessageV2EnterBaseline? {
        val key = partition to scopeId
        if (enterBaselineByChannel[key]?.closed == false) return null
        val epoch = currentTransportEpochByPartition[partition]
        // One read: the join verdict and the recorded count must see the same leaves (a leave between two reads
        // would pass the verdict yet match the recorded count at commit, leaving a continuous window outside the room).
        val (roomLeaves, parentLeaves) = roomLeaveCounts(partition.serverId, scopeId, replyParentScopeId)
        val threadJoin =
            if (replyParentScopeId != null) {
                threadJoinLocked(partition, scopeId, epoch, roomLeaves)
            } else {
                CanonicalMessageV2ThreadJoin.Unconfirmed
            }
        val baseline = CanonicalMessageV2EnterBaseline(
            partition = partition,
            scopeId = scopeId,
            epochAtRequest = epoch,
            roomLeaveCountAtRequest = roomLeaves + parentLeaves,
            nonMemberAtRequest = nonMember,
            roomsJoinedAtRequest = roomsJoinedLocked(partition, epoch),
            source = source,
            replyParentScopeId = replyParentScopeId,
            parentNonMemberAtRequest = parentNonMember,
            threadJoinAtRequest = threadJoin,
        )
        enterBaselineByChannel[key] = baseline
        val kindLabel = replyParentScopeId
            ?.let { " scopeKind=$REPLY_THREAD_SCOPE_KIND parent=$it parentNonMember=$parentNonMember threadJoin=${threadJoin.name.lowercase()}" }
            .orEmpty()
        logger(
            "canonical_v2_canary $source=registered server=${partition.serverId} scope=$scopeId$kindLabel " +
                "epochAtRequest=$epoch nonMember=$nonMember roomsJoined=${baseline.roomsJoinedAtRequest}",
        )
        return baseline
    }

    /**
     * Task #168: [baseline]'s enter snapshot [snapshot] has just committed. MUST be called inside
     * `generationEngine.withScopeWriter(snapshot.scope)`, right after `applySnapshot` returned `Committed`.
     * The scope gets a fresh state continuous under the request epoch, then the buffered frames are replayed
     * by #3007's rules. It stays discontinuous instead when the user is not a member of the channel (the
     * server joins its room asynchronously on open, so the page may miss messages never pushed), the request's
     * connection had not seen `rooms:joined` yet (same race for member rooms right after a reconnect), the room was
     * left meanwhile, another writer committed meanwhile, or the buffer overflowed. Task #14a: a reply thread's
     * window also stays discontinuous when its committed cursor does not name the baseline's parent channel, or
     * the baseline was registered for the other scope family. Task #14c: and unless the server-confirmed parent
     * (the committed cursor's `parentScopeKind`) is a public `channel` AND this connection's `channel:joined`
     * for the thread arrived before the request ([CanonicalMessageV2EnterBaseline.threadJoinAtRequest]); see the
     * class doc for the ordering argument and the reasons.
     */
    suspend fun onEnterCommitted(
        baseline: CanonicalMessageV2EnterBaseline,
        snapshot: CanonicalMessageV2SnapshotFrame,
        nonMemberAtCommit: Boolean = false,
        /** Task #14c: the directory says, at commit, the user is not a member of the thread's parent channel. */
        parentNonMemberAtCommit: Boolean = false,
    ): Unit = withContext(NonCancellable) {
        val scope = snapshot.scope
        val (state, buffered, reason) = stateMutex.withLock {
            val open = !baseline.closed && enterBaselineByChannel[baseline.partition to baseline.scopeId] === baseline
            closeLocked(baseline)
            val cursor = generationEngine.currentCursor(scope)
            if (!open || cursor == null) {
                // Dropped by a reset meanwhile, or the window is gone: no state, nothing replayed.
                markChannelLocked(baseline, "enter_dropped")
                stateByScope.remove(scope)
                return@withContext
            }
            val replyThread = scope.scopeKind == REPLY_THREAD_SCOPE_KIND
            val state = CanonicalMessageV2CanaryScopeState(
                cursor = cursor,
                committedTransportEpoch = baseline.epochAtRequest,
                roomLeaveCountAtBaseline = baseline.roomLeaveCountAtRequest,
                replyParentScopeId = baseline.replyParentScopeId,
            )
            stateByScope[scope] = state
            // Task #14c: the parent kind comes from the committed cursor (the server-confirmed parent), never
            // from a frame. Only a public channel parent broadcasts replies to `channel:<threadId>`.
            val publicParent = replyThread && cursor.parentScopeKind == PUBLIC_CHANNEL_SCOPE_KIND
            val threadJoined = replyThread && baseline.threadJoinAtRequest == CanonicalMessageV2ThreadJoin.Joined
            val reason = when {
                replyThread != (baseline.replyParentScopeId != null) -> "scope_family_mismatch"
                replyThread && cursor.parentScopeId != baseline.replyParentScopeId -> "parent_mismatch"
                replyThread && (
                    baseline.threadJoinAtRequest == CanonicalMessageV2ThreadJoin.Denied ||
                        baseline.threadJoinDeniedSinceRequest
                    ) -> THREAD_JOIN_DENIED
                // Task #14c: never lifted by a join ack (the user may not see the parent channel at all).
                replyThread && (baseline.parentNonMemberAtRequest || parentNonMemberAtCommit) -> REPLY_THREAD_PARENT_NON_MEMBER
                !replyThread && (baseline.nonMemberAtRequest || nonMemberAtCommit) -> "non_member"
                // Task #14c: under a public parent (whose membership is not known to be absent: the case above) a
                // socket in the thread room receives every reply whether or not the user follows the thread, so the
                // thread's own flag never decides there: the join-ack reasons below report what is missing. Under a
                // private / dm / joint parent replies reach only followers.
                replyThread && !publicParent && (baseline.nonMemberAtRequest || nonMemberAtCommit) -> REPLY_THREAD_NON_MEMBER
                !baseline.roomsJoinedAtRequest -> "rooms_not_joined"
                roomLeaveCount(scope.serverId, scope.scopeId, baseline.replyParentScopeId) !=
                    baseline.roomLeaveCountAtRequest -> "room_left"
                baseline.superseded -> "external_commit"
                baseline.overflowed -> "buffer_overflow"
                // Task #14c: under a private / dm / joint parent replies reach only followers' user rooms.
                replyThread && !publicParent -> REPLY_THREAD_DELIVERY_FOLLOWER_ONLY
                replyThread && !threadJoined ->
                    if (baseline.threadJoinAckedSinceRequest) REPLY_THREAD_JOIN_ACK_AFTER_REQUEST
                    else REPLY_THREAD_DELIVERY_UNCONFIRMED
                else -> null
            }
            if (reason != null) state.discontinuous = true
            val membershipLabel = if (replyThread) {
                "nonMember@request=${baseline.nonMemberAtRequest} nonMember@commit=$nonMemberAtCommit " +
                    "parentNonMember@request=${baseline.parentNonMemberAtRequest} parentNonMember@commit=$parentNonMemberAtCommit "
            } else {
                ""
            }
            logger(
                "canonical_v2_canary ${baseline.source}=committed server=${scope.serverId} scope=${scope.scopeId}${scope.kindLabel()} " +
                    "epoch=${baseline.epochAtRequest} buffered=${baseline.buffered.size} $membershipLabel" +
                    (reason?.let { "continuity=discontinuous reason=$it" } ?: "continuity=continuous"),
            )
            // Task #14c: only an ENTER arms the late-ack catch-up (a catch-up never requests another one). An ack
            // that already arrived during the fetch requests it right here; a later one from noteChannelJoinAck.
            if (baseline.source == ENTER_SOURCE && (reason == REPLY_THREAD_JOIN_ACK_AFTER_REQUEST || reason == REPLY_THREAD_DELIVERY_UNCONFIRMED)) {
                state.awaitingThreadJoinAck = true
                requestThreadJoinCatchUpLocked(scope, state, trigger = "enter_commit")
            }
            Triple(state, baseline.buffered.toList(), reason)
        }
        if (reason == null) replayBuffered(baseline, scope, state, snapshot, buffered)
    }

    /**
     * Task #168: [baseline]'s enter snapshot will not commit (failed fetch, identity / flag change, refused
     * commit). No-op once committed. Frames displayed during the fetch are not on disk, so any window of that
     * channel is discontinuous from here on.
     */
    suspend fun abandonEnterBaseline(baseline: CanonicalMessageV2EnterBaseline): Unit = withContext(NonCancellable) {
        stateMutex.withLock {
            if (baseline.closed) return@withLock
            closeLocked(baseline)
            if (baseline.buffered.isNotEmpty() || baseline.overflowed) markChannelLocked(baseline, "enter_failed")
            logger(
                "canonical_v2_canary ${baseline.source}=abandoned server=${baseline.partition.serverId} " +
                    "scope=${baseline.scopeId} buffered=${baseline.buffered.size}",
            )
        }
    }

    /** Holding [stateMutex]. */
    private fun closeLocked(baseline: CanonicalMessageV2EnterBaseline) {
        baseline.closed = true
        val key = baseline.partition to baseline.scopeId
        if (enterBaselineByChannel[key] === baseline) enterBaselineByChannel.remove(key)
    }

    /** Holding [stateMutex]: every window of [baseline]'s channel id may now miss frames. */
    private fun markChannelLocked(baseline: CanonicalMessageV2EnterBaseline, reason: String) {
        stateByScope.entries
            .filter { it.key.partition == baseline.partition && it.key.scopeId == baseline.scopeId }
            .forEach { (scope, state) -> markDiscontinuousLocked(scope, state, reason) }
    }

    /**
     * Inside the scope writer, right after the enter snapshot committed under its request epoch. A buffered
     * frame is appended only when it is provably continuous with that snapshot: a `New` of the same epoch
     * with a seq above the snapshot's latestSeq. A frame the snapshot already holds is not written again; an
     * older connection's frame is display-only; anything else marks the scope discontinuous.
     */
    private suspend fun replayBuffered(
        baseline: CanonicalMessageV2EnterBaseline,
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
        snapshot: CanonicalMessageV2SnapshotFrame,
        buffered: List<CanonicalMessageV2BufferedFrame>,
    ) {
        suspend fun discontinuous(reason: String) = markDiscontinuous(scope, state, "replay_$reason")
        val committedEpoch = baseline.epochAtRequest
        val snapshotLatestSeq = snapshot.cursor.latestSeq
        val snapshotFactIds = snapshot.envelopes.mapTo(HashSet()) { it.fact.id }
        for (frame in buffered) {
            val delivery = frame.delivery
            val input = delivery.foldInput
            val frameScope = input.v2ScopeOrNull(scope.partition)
            if (frameScope != null && frameScope != scope) continue // another scope kind of the same id
            val epoch = frame.transportEpoch
            if (epoch != null && committedEpoch != null && epoch < committedEpoch) {
                // A late frame of an older socket (as #2991): shown when it arrived, never written.
                logger(
                    "canonical_v2_canary skip=stale_transport server=${scope.serverId} scope=${scope.scopeId} " +
                        "frameEpoch=$epoch committedEpoch=$committedEpoch replay=true",
                )
                continue
            }
            if (epoch != committedEpoch) return discontinuous("epoch_mismatch")
            if (frameScope == null) {
                return discontinuous(
                    "not_full_v2 ${input.frameLabel()} invalid=conversationContext:" +
                        (input.row.conversationContext?.channelType ?: "absent"),
                )
            }
            if (input.kind != CanonicalMessageFoldKind.New) return discontinuous("buffered_${input.kind.name.lowercase()}")
            if (stateMutex.withLock { state.isAlreadyCommitted(delivery) }) continue
            val seq = input.row.seq
            val cursor = stateMutex.withLock { state.cursor } ?: return discontinuous("no_window")
            replyParentMismatchOrNull(scope, cursor, input)?.let { mismatch ->
                logger(
                    "canonical_v2_canary skip=parent_mismatch server=${scope.serverId} scope=${scope.scopeId}" +
                        "${scope.kindLabel()} $mismatch replay=true",
                )
                return discontinuous("parent_mismatch")
            }
            if (snapshotLatestSeq != null && seq <= snapshotLatestSeq) {
                // Message storage Phase 2a: the committed page may have joined older stored ranges, so a fact the
                // page lacks can still be on disk (a redelivery of a stored message): same as on the page.
                if (input.row.id in snapshotFactIds || generationEngine.storedFactExists(scope, input.row.id)) {
                    // The snapshot already holds it: never written twice.
                    stateMutex.withLock { state.recordCommitted(delivery) }
                    continue
                }
                val windowOldest = cursor.oldestLoadedSeq
                if (windowOldest != null && seq < windowOldest) {
                    logger(
                        "canonical_v2_canary skip=below_window server=${scope.serverId} scope=${scope.scopeId} " +
                            "seq=$seq oldestLoadedSeq=$windowOldest replay=true",
                    )
                    continue
                }
                return discontinuous("missing_from_snapshot")
            }
            when (val result = appendNewInsideWriter(scope, state, cursor, input)) {
                CanonicalMessageV2AppendResult.Committed -> stateMutex.withLock { state.recordCommitted(delivery) }
                CanonicalMessageV2AppendResult.BelowWindow -> Unit
                is CanonicalMessageV2AppendResult.NotFullV2 ->
                    return discontinuous("not_full_v2 ${input.frameLabel()} ${result.detail}")
                CanonicalMessageV2AppendResult.BaselineRequired -> return discontinuous("baseline_required")
                CanonicalMessageV2AppendResult.PartitionRevoked -> {
                    heldAfterWindowLost(scope, state, "partition_revoked")
                    return
                }
                CanonicalMessageV2AppendResult.ScopeEvicted -> {
                    heldAfterWindowLost(scope, state, "retention_evicted")
                    return
                }
                is CanonicalMessageV2AppendResult.Failed -> return discontinuous(result.reason)
            }
        }
    }

    /**
     * Task #168: a writer outside the canary that moves the newest edge without a baseline (newer adjacent
     * page, gap catch-up, an enter that could not register) has just committed into [scope]. MUST be called inside
     * `generationEngine.withScopeWriter(scope)`, right after that commit returned `Committed`, so it is
     * serialized with every canary write of the scope.
     *
     * The canary did not produce that window and nothing proves its continuity with the realtime stream, so
     * the scope is marked discontinuous (#3008): display only until the next enter commit. Nothing is
     * rescheduled (task #168: no background snapshot). An enter in flight for the channel cannot become
     * continuous either. No-op for a scope without a state (never-opened channels / threads).
     *
     * Locks: only the canary's leaf state mutex. Nothing of the realtime grant / authority registry is touched.
     */
    suspend fun onExternalCommit(scope: SyncScopeKey): Unit = withContext(NonCancellable) {
        // The commit already happened: a cancellation of the caller must not skip the mark.
        stateMutex.withLock {
            enterBaselineByChannel[scope.partition to scope.scopeId]?.superseded = true
            val state = stateByScope[scope] ?: return@withLock
            state.cursor = generationEngine.currentCursor(scope)
            markDiscontinuousLocked(scope, state, "external_commit")
        }
    }

    /**
     * Task #168: an older adjacent page has just committed into [scope] (inside its scope writer). It only
     * moved the oldest edge — the newest edge, where realtime frames are appended, is the one this window
     * already had — so continuity is unchanged; only the cached cursor is refreshed.
     */
    suspend fun onOlderPageCommitted(scope: SyncScopeKey): Unit = withContext(NonCancellable) {
        stateMutex.withLock {
            val state = stateByScope[scope] ?: return@withLock
            state.cursor = generationEngine.currentCursor(scope)
            logger(
                "canonical_v2_canary older_page server=${scope.serverId} scope=${scope.scopeId} " +
                    "continuity=${if (state.discontinuous) "discontinuous" else "continuous"}",
            )
        }
    }

    /** What the realtime path does with [scope]'s next frame (diagnostics / tests; never selects or widens). */
    internal suspend fun continuityOf(scope: SyncScopeKey): CanonicalMessageV2ScopeContinuity =
        stateMutex.withLock {
            val state = stateByScope[scope] ?: return@withLock CanonicalMessageV2ScopeContinuity.NoWindow
            if (state.discontinuous) CanonicalMessageV2ScopeContinuity.Discontinuous
            else CanonicalMessageV2ScopeContinuity.Continuous
        }

    /** The recorded transport epoch of [partition] (tests / diagnostics). */
    internal suspend fun currentTransportEpochOf(partition: DurablePartitionKey): Long? =
        stateMutex.withLock { currentTransportEpochByPartition[partition] }

    /** The scopes of [partition] with a window committed in this process (the in-memory index). */
    internal suspend fun windowedScopes(partition: DurablePartitionKey): Set<SyncScopeKey> =
        stateMutex.withLock { stateByScope.keys.filterTo(mutableSetOf()) { it.partition == partition } }

    private suspend fun markDiscontinuous(
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
        reason: String,
    ) = stateMutex.withLock { markDiscontinuousLocked(scope, state, reason) }

    /** Holding [stateMutex]. Only the state still installed for [scope] is marked. */
    private fun markDiscontinuousLocked(
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
        reason: String,
    ) {
        if (stateByScope[scope] !== state || state.discontinuous) return
        state.discontinuous = true
        logger(
            "canonical_v2_canary discontinuous server=${scope.serverId} scope=${scope.scopeId}${scope.kindLabel()} " +
                "reason=$reason",
        )
    }

    /**
     * The flag went off for a scope with a window: drop its V2 window and hand the scope back to the legacy
     * path. Never loads.
     */
    private suspend fun revokeInsideWriter(scope: SyncScopeKey): CanonicalMessageV2CanaryOutcome {
        generationEngine.clearScope(scope)
        stateMutex.withLock { stateByScope.remove(scope) }
        logger("canonical_v2_canary mode=off server=${scope.serverId} scope=${scope.scopeId} reason=flag_revoked")
        return CanonicalMessageV2CanaryOutcome.NotSelected
    }

    private suspend fun onPartitionRevoked(partition: DurablePartitionKey) {
        stateMutex.withLock {
            currentTransportEpochByPartition.remove(partition)
            roomsJoinedEpochsByPartition.remove(partition)
            channelJoinSignalByChannel.keys.removeAll { it.first == partition }
            stateByScope.keys.removeAll { it.partition == partition }
            enterBaselineByChannel.entries.removeAll { (key, baseline) ->
                (key.first == partition).also { if (it) baseline.closed = true }
            }
        }
    }

    private suspend fun onScopesInvalidated(scopes: Set<SyncScopeKey>) {
        stateMutex.withLock { stateByScope.keys.removeAll { it in scopes } }
    }

    private suspend fun heldAfterWindowLost(
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
        reason: String,
    ): CanonicalMessageV2CanaryOutcome {
        stateMutex.withLock { if (stateByScope[scope] === state) stateByScope.remove(scope) }
        logger("canonical_v2_canary mode=off server=${scope.serverId} scope=${scope.scopeId} reason=$reason")
        return CanonicalMessageV2CanaryOutcome.Held("canonical_v2_$reason")
    }
}

private const val REPLY_THREAD_SCOPE_KIND: String = "thread"

/**
 * Task #14a: why every reply-thread enter commit ends discontinuous: the server gives no positive signal that
 * this client receives every reply of the thread after the enter snapshot (see [CanonicalMessageV2ProductionCanary]).
 */
private const val REPLY_THREAD_DELIVERY_UNCONFIRMED: String = "thread_delivery_unconfirmed"

/**
 * Task #14c: a reply thread under a `private` / `dm` / `joint` parent: the server sends its replies only to the
 * followers' `user:<id>` rooms, so a thread-room join ack proves nothing about them.
 */
private const val REPLY_THREAD_DELIVERY_FOLLOWER_ONLY: String = "thread_delivery_follower_only"

/**
 * Task #14c: `channel:joined` for the thread arrived only while the enter fetch was in flight: the server may
 * have read the page before it joined the room (see the class doc). Continuous from the next enter on.
 */
private const val REPLY_THREAD_JOIN_ACK_AFTER_REQUEST: String = "thread_join_ack_after_request"

/** Task #14c: the server refused this socket's `join:channel` for the thread (`channel:join_denied`). */
private const val THREAD_JOIN_DENIED: String = "join_denied"

/**
 * Task #14c: the directory says the user is not a member of a reply thread's PARENT channel (at request or at
 * commit). Never lifted by a thread join ack.
 */
private const val REPLY_THREAD_PARENT_NON_MEMBER: String = "parent_non_member"

/**
 * Task #14c: the directory says the user does not follow a reply thread under a private / dm / joint parent, where
 * replies reach only followers' user rooms. Never reported under a public parent (the thread room delivers there).
 */
private const val REPLY_THREAD_NON_MEMBER: String = "thread_non_member"

private const val ENTER_SOURCE: String = "enter"

/** Task #14c: the only parent kind whose thread replies are broadcast to the thread's own room. */
private const val PUBLIC_CHANNEL_SCOPE_KIND: String = "channel"

/** `kind=new msg=<id> seq=<seq>`: which frame a canary line is about (ids only, never content). */
internal fun CanonicalMessageFoldInput.frameLabel(): String =
    "kind=${kind.name.lowercase()} msg=${row.id} seq=${row.seq}"

/** ` scopeKind=thread` for a reply-thread scope, nothing for a top-level one (log lines keep their shape). */
private fun SyncScopeKey.kindLabel(): String =
    if (scopeKind == REPLY_THREAD_SCOPE_KIND) " scopeKind=$REPLY_THREAD_SCOPE_KIND" else ""

/**
 * Task #14a: for a `thread` scope, why [input] may not be written under [cursor] (`null` = it may): the
 * frame's parent kind / channel / root must equal the parent the committed cursor stores — the one the
 * server confirmed when the thread was entered. A realtime frame never re-parents a stored thread.
 */
private fun replyParentMismatchOrNull(
    scope: SyncScopeKey,
    cursor: PersistedCursor,
    input: CanonicalMessageFoldInput,
): String? {
    if (scope.scopeKind != REPLY_THREAD_SCOPE_KIND) return null
    val context = input.row.conversationContext
    val frame = Triple(context?.parentChannelType, context?.parentChannelId, context?.parentMessageId)
    val stored = Triple(cursor.parentScopeKind, cursor.parentScopeId, cursor.rootMessageId)
    if (stored.first != null && stored.second != null && stored.third != null && frame == stored) return null
    return "frameParent=${frame.first}/${frame.second}/${frame.third} " +
        "storedParent=${stored.first}/${stored.second}/${stored.third}"
}

internal fun CanonicalMessageFoldInput.v2ScopeOrNull(partition: DurablePartitionKey): SyncScopeKey? {
    val context = row.conversationContext ?: return null
    val kind = context.channelType
    if (kind == REPLY_THREAD_SCOPE_KIND) {
        // Task #14a: a reply lives in its thread channel (`channelId`); its parent is checked against the
        // stored window's, never taken from here.
        val parentKind = context.parentChannelType
        if (
            context.parentChannelId.isNullOrBlank() ||
            parentKind == null || parentKind !in setOf("channel", "private", "dm", "joint") ||
            context.parentMessageId.isNullOrBlank()
        ) return null
        return SyncScopeKey(partition = partition, scopeId = row.channelId, scopeKind = REPLY_THREAD_SCOPE_KIND)
    }
    if (kind == CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND) {
        // Task v2: a task conversation is written under its own channel id; the normalizer checks the
        // parent context (parent channel, no parent message).
        if (context.parentChannelId.isNullOrBlank() || context.parentMessageId != null) return null
        return SyncScopeKey(partition = partition, scopeId = row.channelId, scopeKind = CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND)
    }
    if (kind !in setOf("channel", "private", "dm", "joint")) return null
    if (
        context.parentChannelId != null ||
        context.parentChannelType != null ||
        context.parentMessageId != null
    ) return null
    return SyncScopeKey(partition = partition, scopeId = row.channelId, scopeKind = kind)
}

internal fun CanonicalMessageFoldInput.toCanonicalMessageV2Envelope(
    partition: DurablePartitionKey,
    replyCount: Int,
    producerContextOverride: CanonicalConversationContextWire? = null,
): CanonicalMessageEnvelopeV2? =
    (toCanonicalMessageV2Conversion(partition, replyCount, producerContextOverride)
        as? CanonicalMessageV2EnvelopeConversion.Converted)?.envelope

/**
 * The outcome of turning one realtime frame into a full-V2 envelope. [NotFull.detail] names exactly which
 * requirement failed (`missing=SenderDescription,Reactions`, `invalid=senderName`, …) so a device log line
 * says WHY a window turned discontinuous. Built only on the failure path.
 */
internal sealed interface CanonicalMessageV2EnvelopeConversion {
    /**
     * [unknownFields] (task #14a, only with `allowPartial`): the allowed aggregates the frame did not carry;
     * the envelope holds neutral placeholders for them. Empty = a full fact.
     */
    data class Converted(
        val envelope: CanonicalMessageEnvelopeV2,
        val unknownFields: Set<MessageAggregateField> = emptySet(),
    ) : CanonicalMessageV2EnvelopeConversion

    data class NotFull(val detail: String) : CanonicalMessageV2EnvelopeConversion
}

internal fun CanonicalMessageV2EnvelopeConversion.Converted.unknownFieldsByFactId(): Map<String, Set<MessageAggregateField>> =
    if (unknownFields.isEmpty()) emptyMap() else mapOf(envelope.fact.id to unknownFields)

/** ` partial=SenderDescription,Reactions` for a partial fact, nothing for a full one. */
internal fun CanonicalMessageV2EnvelopeConversion.Converted.partialLabel(): String =
    if (unknownFields.isEmpty()) ""
    else " partial=" + CANONICAL_MESSAGE_V2_ALLOWED_UNKNOWN_FRAME_AGGREGATES.filter { it in unknownFields }.joinToString(",") { it.name }

/**
 * The optional aggregates a realtime frame must carry (explicit wire keys) before it can become a full-V2
 * fact. An absent key means "the producer did not say", never "empty": e.g. the ordinary send pipeline's
 * `message:new` omits `senderDescription` and `reactions` (botiverse/slock `broadcastAndDeliver` builds its
 * payload from the inserted row + senderName / senderMembershipStatus / attachments / mentions only), and
 * an agent's description cannot be invented here. Such a frame is display only, unless every missing key is
 * in [CANONICAL_MESSAGE_V2_ALLOWED_UNKNOWN_FRAME_AGGREGATES] (task #14a: then it is a partial fact).
 */
internal val CANONICAL_MESSAGE_V2_REQUIRED_FRAME_AGGREGATES: List<MessageAggregateField> = listOf(
    MessageAggregateField.SenderName,
    MessageAggregateField.SenderDescription,
    MessageAggregateField.SenderMembershipStatus,
    MessageAggregateField.Mentions,
    MessageAggregateField.Attachments,
    MessageAggregateField.Reactions,
    MessageAggregateField.ActionMetadata,
)

/**
 * Task #14a: the only aggregates a realtime frame may omit and still be written, as a partial fact (the
 * server's ordinary `message:new` carries neither key). One named set: widening it is a product decision.
 */
internal val CANONICAL_MESSAGE_V2_ALLOWED_UNKNOWN_FRAME_AGGREGATES: List<MessageAggregateField> = listOf(
    MessageAggregateField.SenderDescription,
    MessageAggregateField.Reactions,
)

/**
 * [allowPartial] (task #14a, realtime only): a frame whose only missing aggregates are in
 * [CANONICAL_MESSAGE_V2_ALLOWED_UNKNOWN_FRAME_AGGREGATES] converts with those as
 * [CanonicalMessageV2EnvelopeConversion.Converted.unknownFields] (placeholders: no description, no reactions).
 * Pages never pass it: a REST node must be full.
 */
internal fun CanonicalMessageFoldInput.toCanonicalMessageV2Conversion(
    partition: DurablePartitionKey,
    replyCount: Int,
    producerContextOverride: CanonicalConversationContextWire? = null,
    allowPartial: Boolean = false,
): CanonicalMessageV2EnvelopeConversion {
    fun notFull(detail: String) = CanonicalMessageV2EnvelopeConversion.NotFull(detail)
    if (replyCount < 0) return notFull("invalid=replyCount")
    val required = if (producerContextOverride == null) {
        CANONICAL_MESSAGE_V2_REQUIRED_FRAME_AGGREGATES + MessageAggregateField.ConversationContext
    } else {
        CANONICAL_MESSAGE_V2_REQUIRED_FRAME_AGGREGATES
    }
    val missing = required.filterNot(presentAggregates::contains)
    val unknown = if (allowPartial && CANONICAL_MESSAGE_V2_ALLOWED_UNKNOWN_FRAME_AGGREGATES.containsAll(missing)) {
        missing.toSet()
    } else {
        emptySet()
    }
    if (missing.size > unknown.size) {
        val invalid = row.invalidCanonicalV2Scalars()
        return notFull(
            "missing=${missing.joinToString(",") { it.name }}" +
                if (invalid.isEmpty()) "" else " invalid=${invalid.joinToString(",")}",
        )
    }
    row.invalidCanonicalV2Scalars().takeIf { it.isNotEmpty() }?.let { return notFull("invalid=${it.joinToString(",")}") }
    val context = producerContextOverride ?: row.conversationContext ?: return notFull("invalid=conversationContext")
    val normalizedReactions = if (MessageAggregateField.Reactions in unknown) {
        emptyList()
    } else {
        val normalized = normalizeLegacyReactionRoster(
            context = ReactionNormalizationContext(
                serverId = partition.serverId,
                messageId = row.id,
                parentScopeKey = ReactionParentScopeKey(partition.serverId, row.channelId, context.channelType),
                authority = ReactionIngressAuthority.SharedRoom,
            ),
            reactions = row.reactions.map { it.toLegacyReactionRosterDto() },
        ) as? ReactionNormalizationOutcome.Normalized ?: return notFull("invalid=reactions")
        normalized.value.sharedFact
    }
    val actionMetadata = row.actionMetadata.toCanonicalActionMetadataV2OrNull()
        ?: if (row.actionMetadata != null) return notFull("invalid=actionMetadata") else null
    val fact = CanonicalMessageNodeV2(
        id = row.id,
        seq = row.seq,
        senderType = row.senderType,
        senderId = row.senderId,
        content = row.content,
        messageType = row.messageType,
        createdAt = row.createdAt,
        randomId = row.randomId,
        senderName = row.senderName,
        senderDescription = if (MessageAggregateField.SenderDescription in unknown) null else row.senderDescription,
        senderMembershipStatus = row.senderMembershipStatus,
        mentions = row.mentions.map { CanonicalMentionV2(it.id, it.name, it.type) },
        attachments = row.attachments.map {
            CanonicalAttachmentV2(
                id = it.id,
                filename = it.filename,
                mimeType = it.mimeType,
                sizeBytes = it.sizeBytes,
                width = it.width,
                height = it.height,
                thumbnailUrl = it.thumbnailUrl,
            )
        },
        reactions = normalizedReactions,
        actionMetadata = actionMetadata,
        replyCount = replyCount,
        deletedAt = normalizeCanonicalRetraction(row.deletedAt),
    )
    return when (
        val normalized = normalizeCanonicalMessageV2(
            CanonicalMessageV2NormalizationInput(
                partition = partition,
                scopeId = row.channelId,
                fact = fact,
                producerContext = context,
                childReplyScopeId = row.threadId,
            ),
        )
    ) {
        is CanonicalMessageV2NormalizationResult.Normalized ->
            CanonicalMessageV2EnvelopeConversion.Converted(normalized.envelope, unknown)
        is CanonicalMessageV2NormalizationResult.Rejected -> notFull("invalid=normalize:${normalized.diagnostic}")
    }
}

/** Only for a seq at or above the window's oldest edge (below-window frames are never written). */
private fun PersistedCursor.advancedBy(seq: Long): PersistedCursor =
    copy(
        latestSeq = maxOf(latestSeq ?: seq, seq),
        oldestLoadedSeq = oldestLoadedSeq ?: seq,
        newestLoadedSeq = maxOf(newestLoadedSeq ?: seq, seq),
        hasLoadedNewest = true,
    )

private val canonicalV2ProducerJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class CanonicalActionMetadataProducer(
    val kind: String? = null,
    val version: Int? = null,
    val action: CanonicalActionCardActionV2? = null,
    val state: String = "prepared",
    val executedAt: String? = null,
    val executedByUserName: String? = null,
    val executedByUserId: String? = null,
    /** Read key by key ([toCanonicalActionCardResultV2OrNull]): a key its kind does not take is dropped. */
    val result: JsonElement? = null,
    val sourceServerId: String? = null,
    val targetServerName: String? = null,
    val confirmationVersion: Int? = null,
    val presentation: JsonElement? = null,
    val forwardedItems: List<CanonicalForwardedBundleProducerItem> = emptyList(),
)

@Serializable
private data class CanonicalForwardedBundleProducerItem(
    val index: Int? = null,
    val sourceAuthorSnapshot: CanonicalForwardedAuthorProducer? = null,
    val sourceCreatedAt: String? = null,
    val sourceTargetSnapshot: CanonicalForwardedTargetProducer? = null,
    val contentSnapshot: String = "",
    val attachmentSnapshots: List<CanonicalForwardedAttachmentSnapshotV2> = emptyList(),
)

@Serializable
private data class CanonicalForwardedAuthorProducer(
    val type: String? = null,
    val name: String? = null,
    val uniqueName: String? = null,
)

@Serializable
private data class CanonicalForwardedTargetProducer(
    val type: String? = null,
    val label: String = "",
    val labelVisibility: String? = null,
)

private fun JsonElement?.toCanonicalActionMetadataV2OrNull(): CanonicalActionMetadataV2? {
    val element = this ?: return null
    if (!element.hasSupportedCanonicalActionProducerShape()) return null
    val producer = runCatching {
        canonicalV2ProducerJson.decodeFromJsonElement(
            CanonicalActionMetadataProducer.serializer(),
            element.withKnownActionCardKeysOnly(),
        )
    }.getOrNull() ?: return null
    return when (producer.kind) {
        "action-card" -> {
            val action = producer.action ?: return null
            if (action.type.isBlank()) return null
            CanonicalActionMetadataV2.ActionCard(
                version = producer.version,
                action = action,
                state = producer.state,
                executedAt = producer.executedAt,
                executedByUserName = producer.executedByUserName,
                result = producer.result.toCanonicalActionCardResultV2OrNull(),
                sourceServerId = producer.sourceServerId?.takeIf { it.isCanonicalV2Value() },
                targetServerName = producer.targetServerName?.trim()?.takeIf { it.isNotEmpty() },
                confirmationVersion = producer.confirmationVersion?.takeIf { it >= 0 },
                presentation = producer.presentation as? JsonObject,
                executedByUserId = producer.executedByUserId?.takeIf { it.isCanonicalV2Value() },
            )
        }
        "forwarded-bundle" -> CanonicalActionMetadataV2.ForwardedBundle(
            version = producer.version,
            items = producer.forwardedItems.mapIndexed { fallbackIndex, item ->
                CanonicalForwardedBundleItemV2(
                    index = item.index ?: fallbackIndex,
                    author = item.sourceAuthorSnapshot?.let {
                        CanonicalForwardedAuthorSnapshotV2(it.type, it.name, it.uniqueName)
                    },
                    createdAt = item.sourceCreatedAt,
                    target = item.sourceTargetSnapshot?.let {
                        CanonicalForwardedTargetSnapshotV2(it.type, it.label, it.labelVisibility)
                    },
                    content = item.contentSnapshot,
                    attachments = item.attachmentSnapshots,
                )
            },
        )
        else -> null
    }
}

/** The canonical-V2 scalar requirements a row breaks, by name (empty = all hold). */
private fun CanonicalMessageRow.invalidCanonicalV2Scalars(): List<String> = buildList {
    if (seq < 0L) add("seq")
    listOf(
        "id" to id, "channelId" to channelId, "senderType" to senderType, "senderId" to senderId,
        "messageType" to messageType, "createdAt" to createdAt, "senderName" to senderName,
    ).forEach { (name, value) -> if (!value.isCanonicalV2Value()) add(name) }
    if (randomId?.isCanonicalV2Value() == false) add("randomId")
    if (threadId?.isCanonicalV2Value() == false) add("threadId")
    if (!mentions.all { it.id.isCanonicalV2Value() && it.name.isCanonicalV2Value() && it.type.isCanonicalV2Value() }) {
        add("mentions")
    }
    if (
        !attachments.all {
            it.id.isCanonicalV2Value() && it.filename.isCanonicalV2Value() && it.mimeType.isCanonicalV2Value() &&
                it.sizeBytes >= 0L && it.width?.let { w -> w >= 0 } != false && it.height?.let { h -> h >= 0 } != false
        }
    ) add("attachments")
}

private fun String.isCanonicalV2Value(): Boolean = isNotBlank() && this == trim()

private fun JsonElement.hasSupportedCanonicalActionProducerShape(): Boolean {
    val root = this as? JsonObject ?: return false
    val kind = (root["kind"] as? JsonPrimitive)?.content
    return when (kind) {
        // Lenient by key (task #43): a key the fact does not carry is dropped
        // ([withKnownActionCardKeysOnly], [toCanonicalActionCardResultV2OrNull]), never the card.
        "action-card" -> root["action"] is JsonObject
        "forwarded-bundle" -> {
            if (!root.keys.all(FORWARDED_ROOT_KEYS::contains)) return false
            val items = root["forwardedItems"] as? JsonArray ?: return false
            items.all { itemElement ->
                val item = itemElement as? JsonObject ?: return@all false
                if (!item.keys.all(FORWARDED_ITEM_KEYS::contains)) return@all false
                val author = item["sourceAuthorSnapshot"]
                if (author != null && author !is JsonNull &&
                    (author as? JsonObject)?.keys?.all(FORWARDED_AUTHOR_KEYS::contains) != true
                ) return@all false
                val target = item["sourceTargetSnapshot"]
                if (target != null && target !is JsonNull &&
                    (target as? JsonObject)?.keys?.all(FORWARDED_TARGET_KEYS::contains) != true
                ) return@all false
                val attachments = item["attachmentSnapshots"] as? JsonArray ?: return@all false
                attachments.all { attachment ->
                    (attachment as? JsonObject)?.keys?.all(FORWARDED_ATTACHMENT_KEYS::contains) == true
                }
            }
        }
        else -> false
    }
}

/**
 * The producer card's root and action with only the keys the fact knows. Before task #43 any other key
 * refused the whole card, so a done feedback card (action `feedbackType` / `message`, result `ticketId` /
 * `reference` / `source`) never reached the V2 store, and neither did the page or the realtime window it
 * was in. Dropped keys: the conversion bookkeeping (`conversionJobId`, `conversionEpoch`, `reconfirmedAt`;
 * the card's `state` already says frozen / reconfirm_required) and whatever a newer server adds.
 */
private fun JsonElement.withKnownActionCardKeysOnly(): JsonElement {
    val root = this as? JsonObject ?: return this
    if ((root["kind"] as? JsonPrimitive)?.content != "action-card") return this
    val action = root["action"] as? JsonObject
    return JsonObject(
        root.filterKeys(ACTION_CARD_ROOT_KEYS::contains).let { kept ->
            if (action == null) kept else kept + ("action" to JsonObject(action.filterKeys(ACTION_CARD_ACTION_KEYS::contains)))
        },
    )
}

/**
 * The producer `result` keyed by its kind ([CANONICAL_ACTION_CARD_RESULT_KEYS_BY_KIND]). A key the kind
 * does not take, or one whose value has the wrong type, is dropped; a result without a usable `kind` is
 * dropped (the card stays, without a result). Wire `null` on an optional key is absent in the fact.
 */
private fun JsonElement?.toCanonicalActionCardResultV2OrNull(): CanonicalActionCardResultV2? {
    val result = this as? JsonObject ?: return null
    val kind = (result["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isCanonicalV2Value() } ?: return null
    val allowed = CANONICAL_ACTION_CARD_RESULT_KEYS_BY_KIND[kind] ?: setOf("kind")
    fun string(key: String): String? =
        if (key !in allowed) null else (result[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun strings(key: String): List<String>? {
        if (key !in allowed) return null
        val array = result[key] as? JsonArray ?: return null
        return array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
    }
    fun boolean(key: String): Boolean? =
        if (key !in allowed) null else (result[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    return CanonicalActionCardResultV2(
        kind = kind,
        id = string("id"),
        name = string("name"),
        channelId = string("channelId"),
        channelName = string("channelName"),
        addedHumanIds = strings("addedHumanIds").orEmpty(),
        addedAgentIds = strings("addedAgentIds").orEmpty(),
        mode = string("mode"),
        requestId = string("requestId"),
        grantId = string("grantId"),
        mcpServerId = string("mcpServerId"),
        serverId = string("serverId"),
        agentId = string("agentId"),
        agentName = string("agentName"),
        clientId = string("clientId"),
        clientKey = string("clientKey"),
        clientName = string("clientName"),
        ownerAgentId = string("ownerAgentId"),
        ownerAgentName = string("ownerAgentName"),
        returnUrl = string("returnUrl"),
        homepageUrl = string("homepageUrl"),
        agentManifestUrl = string("agentManifestUrl"),
        setupUrl = string("setupUrl"),
        setupDescription = string("setupDescription"),
        scopes = strings("scopes"),
        unsafeDemoUrlOverride = boolean("unsafeDemoUrlOverride"),
        ticketId = string("ticketId"),
        reference = string("reference"),
        source = string("source"),
    )
}

private val ACTION_CARD_ROOT_KEYS = setOf(
    "kind", "version", "action", "state", "executedAt", "executedByUserName", "result",
    // Carried into the fact as optional keys (manifest v8; executedByUserId v9).
    "sourceServerId", "targetServerName", "confirmationVersion", "presentation", "executedByUserId",
)
private val ACTION_CARD_ACTION_KEYS = setOf(
    "type", "name", "description", "visibility", "channel", "suggestedComputer", "requiredComputer",
    "humans", "agents", "initialHumans", "initialAgents", "draftHint", "requestId", "agentId",
    "agentName", "clientId", "clientKey", "clientName", "scopes",
)
private val FORWARDED_ROOT_KEYS = setOf("kind", "version", "forwardedItems")
private val FORWARDED_ITEM_KEYS = setOf(
    "index", "sourceMessageId", "sourceAuthorSnapshot", "sourceCreatedAt", "sourceTargetSnapshot",
    "contentSnapshot", "attachmentSnapshots", "provenanceState",
)
private val FORWARDED_AUTHOR_KEYS = setOf("type", "id", "name", "uniqueName")
private val FORWARDED_TARGET_KEYS = setOf("id", "type", "label", "labelVisibility")
private val FORWARDED_ATTACHMENT_KEYS = setOf("filename", "mimeType")
