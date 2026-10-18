package build.raft.app.network.sync.activity

import build.raft.app.network.sync.SyncDensity
import build.raft.app.network.sync.SyncDomainConfig
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.activity.contract.v1.ActivityFilter
import build.raft.app.network.sync.activity.contract.v1.ActivityIngress
import build.raft.app.network.sync.activity.contract.v1.ActivityRow
import build.raft.app.network.sync.activity.contract.v1.ActivityRowTombstone
import build.raft.app.network.sync.activity.contract.v1.ActivityScope
import build.raft.app.network.sync.activity.contract.v1.ActivityScopeState
import build.raft.app.network.sync.activity.contract.v1.ActivitySyncContractJson
import build.raft.app.network.sync.activity.contract.v1.ActivityWindow
import build.raft.app.network.sync.activity.contract.v1.AffectedReadState
import build.raft.app.network.sync.activity.contract.v1.ChannelActivityRow
import build.raft.app.network.sync.activity.contract.v1.DmActivityRow
import build.raft.app.network.sync.activity.contract.v1.DifferenceIngress
import build.raft.app.network.sync.activity.contract.v1.FrameIngress
import build.raft.app.network.sync.activity.contract.v1.ReadStateUpdatedIngress
import build.raft.app.network.sync.activity.contract.v1.SnapshotIngress
import build.raft.app.network.sync.activity.contract.v1.ThreadActivityRow
import build.raft.app.network.sync.activity.contract.v1.UInt64String
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

const val ACTIVITY_DOMAIN: String = "activity"

/** Exact magnitude comparison for canonical decimal-string uint64 values. */
fun compareUInt64String(left: UInt64String, right: UInt64String): Int {
    if (left.value.length != right.value.length) {
        return if (left.value.length < right.value.length) -1 else 1
    }
    return left.value.compareTo(right.value).coerceIn(-1, 1)
}

/** Pure events accepted by the Activity domain fold. */
sealed interface ActivityDomainEvent {
    data class Frame(
        val rows: List<ActivityRow>,
        val tombstones: List<ActivityRowTombstone> = emptyList(),
        val activityVersion: UInt64String? = null,
        val window: ActivityFrameWindowFact = ActivityFrameWindowFact(),
    ) : ActivityDomainEvent

    data class ReadStateUpdated(
        val updates: List<AffectedReadState>,
        val activityVersion: UInt64String? = null,
    ) : ActivityDomainEvent
}

/**
 * Window metadata a frame may carry (a difference is folded as a frame that
 * carries all of it). A null field was not carried and leaves state untouched;
 * `nextCursorPresent` distinguishes a carried null cursor from an absent one.
 * Mirrors sync-core's acceptedActivityFrameWindowFact.
 */
data class ActivityFrameWindowFact(
    val nextCursorPresent: Boolean = false,
    val nextCursor: String? = null,
    val hasMore: Boolean? = null,
    val complete: Boolean? = null,
    val totalCount: UInt? = null,
    val totalUnreadCount: UInt? = null,
)

/** One Activity scope's canonical folded state. */
data class ActivityDomainState(
    val rows: List<ActivityRow>,
    val tombstones: Map<String, UInt64String>,
    val activityVersion: UInt64String?,
    val nextCursor: String?,
    val hasMore: Boolean,
    val complete: Boolean,
    val totalCount: UInt = 0u,
    val totalUnreadCount: UInt = 0u,
)

fun initialActivityState(): ActivityDomainState =
    ActivityDomainState(
        rows = emptyList(),
        tombstones = emptyMap(),
        activityVersion = null,
        nextCursor = null,
        hasMore = false,
        complete = false,
    )

fun ActivityIngress.toActivityDomainEventOrNull(): ActivityDomainEvent? = when (this) {
    is FrameIngress -> ActivityDomainEvent.Frame(rows, tombstones, activityVersion)
    is ReadStateUpdatedIngress -> ActivityDomainEvent.ReadStateUpdated(updates, activityVersion)
    else -> null
}

private val rowComparator = Comparator<ActivityRow> { left, right ->
    val time = right.lastActivityAt().compareTo(left.lastActivityAt())
    if (time != 0) time else left.rowId().compareTo(right.rowId())
}

private fun canonicalTombstones(values: Map<String, UInt64String>): Map<String, UInt64String> =
    values.entries
        .sortedBy(Map.Entry<String, UInt64String>::key)
        .associateTo(linkedMapOf()) { it.key to it.value }

private fun applyRowsAndTombstones(
    state: ActivityDomainState,
    incomingRows: List<ActivityRow>,
    incomingTombstones: List<ActivityRowTombstone>,
): ActivityDomainState {
    val byId = state.rows.associateByTo(linkedMapOf(), ActivityRow::rowId)
    val tombstones = state.tombstones.toMutableMap()

    incomingRows.forEach { candidate ->
        val rowId = candidate.rowId()
        val buried = tombstones[rowId]
        if (buried != null && compareUInt64String(candidate.rowVersion(), buried) <= 0) {
            return@forEach
        }
        val existing = byId[rowId]
        if (existing != null && compareUInt64String(candidate.rowVersion(), existing.rowVersion()) <= 0) {
            return@forEach
        }
        tombstones.remove(rowId)
        byId[rowId] = candidate
    }

    incomingTombstones.forEach { candidate ->
        val existing = byId[candidate.rowId]
        if (existing != null && compareUInt64String(candidate.rowVersion, existing.rowVersion()) < 0) {
            return@forEach
        }
        val buried = tombstones[candidate.rowId]
        if (buried != null && compareUInt64String(candidate.rowVersion, buried) <= 0) {
            return@forEach
        }
        byId.remove(candidate.rowId)
        tombstones[candidate.rowId] = candidate.rowVersion
    }

    return state.copy(
        rows = byId.values.sortedWith(rowComparator),
        tombstones = canonicalTombstones(tombstones),
    )
}

private fun advanceActivityVersion(
    current: UInt64String?,
    incoming: UInt64String?,
): UInt64String? = when {
    incoming == null -> current
    current == null -> incoming
    compareUInt64String(incoming, current) > 0 -> incoming
    else -> current
}

private fun foldReadStateUpdates(
    state: ActivityDomainState,
    updates: List<AffectedReadState>,
): ActivityDomainState {
    var changed = false
    val rows = state.rows.map { row ->
        val update = updates.firstOrNull { it.scopeId == row.rowId() } ?: return@map row
        if (compareUInt64String(update.readStateVersion, row.readStateVersion()) <= 0) return@map row
        changed = true
        row.withReadState(update.maxReadSeq, update.readStateVersion)
    }
    return if (changed) state.copy(rows = rows) else state
}

/** Pure and total: an unknown or malformed host event is a reference-stable no-op. */
fun foldActivityEvent(state: ActivityDomainState, event: Any?): ActivityDomainState {
    val domainEvent = when (event) {
        is ActivityDomainEvent -> event
        is ActivityIngress -> event.toActivityDomainEventOrNull()
        else -> null
    } ?: return state

    return when (domainEvent) {
        is ActivityDomainEvent.Frame -> {
            val withRows = applyRowsAndTombstones(state, domainEvent.rows, domainEvent.tombstones)
            val window = domainEvent.window
            withRows.copy(
                activityVersion = advanceActivityVersion(
                    withRows.activityVersion,
                    domainEvent.activityVersion,
                ),
                nextCursor = if (window.nextCursorPresent) window.nextCursor else withRows.nextCursor,
                hasMore = window.hasMore ?: withRows.hasMore,
                complete = window.complete ?: withRows.complete,
                totalCount = window.totalCount ?: withRows.totalCount,
                totalUnreadCount = window.totalUnreadCount ?: withRows.totalUnreadCount,
            )
        }
        is ActivityDomainEvent.ReadStateUpdated -> {
            val withReads = foldReadStateUpdates(state, domainEvent.updates)
            withReads.copy(
                activityVersion = advanceActivityVersion(
                    withReads.activityVersion,
                    domainEvent.activityVersion,
                ),
            )
        }
    }
}

fun activityStateFromSnapshot(raw: Any?): ActivityDomainState {
    val window: ActivityWindow
    val activityVersion: UInt64String?
    when (raw) {
        is SnapshotIngress -> {
            window = raw.window
            activityVersion = raw.activityVersion
        }
        is ActivityScopeState -> {
            window = raw.window
            activityVersion = raw.activityVersion
        }
        is ActivityWindow -> {
            window = raw
            activityVersion = null
        }
        else -> return initialActivityState()
    }

    val applied = applyRowsAndTombstones(
        initialActivityState(),
        window.rows,
        window.tombstones,
    )
    return applied.copy(
        // A snapshot replaces the baseline, so only tombstones carried by this
        // snapshot remain. They must survive to block stale resurrection.
        tombstones = applied.tombstones,
        activityVersion = activityVersion,
        nextCursor = window.nextCursor,
        hasMore = window.hasMore,
        complete = window.complete,
        totalCount = window.totalCount,
        totalUnreadCount = window.totalUnreadCount,
    )
}

/** A difference folds as one frame carrying its rows and its full window metadata. */
fun DifferenceIngress.toActivityFrame(): ActivityDomainEvent.Frame =
    ActivityDomainEvent.Frame(
        rows = rows,
        tombstones = tombstones,
        activityVersion = activityVersion,
        window = ActivityFrameWindowFact(
            nextCursorPresent = true,
            nextCursor = nextCursor,
            hasMore = hasMore,
            complete = complete,
            totalCount = totalCount,
            totalUnreadCount = totalUnreadCount,
        ),
    )

fun createActivityDomain(): SyncDomainConfig =
    SyncDomainConfig(
        name = ACTIVITY_DOMAIN,
        density = SyncDensity.Contiguous,
        initialState = ::initialActivityState,
        eventFingerprint = ::fingerprintActivityEvent,
        fold = { state, event, _ ->
            foldActivityEvent(state as? ActivityDomainState ?: initialActivityState(), event)
        },
        fromSnapshot = { snapshot -> activityStateFromSnapshot(snapshot.state) },
    )

fun fingerprintActivityEvent(event: Any?): String = when (event) {
    is ActivityIngress -> ActivitySyncContractJson.strict.encodeToString(ActivityIngress.serializer(), event)
    is ActivityDomainEvent -> event.toString()
    else -> "null"
}

fun encodeActivityScopeId(
    serverId: String,
    principalId: String,
    filter: String,
    windowId: String,
): SyncScopeId =
    ActivitySyncContractJson.strict.encodeToString(
        ListSerializer(String.serializer()),
        listOf(serverId, principalId, filter, windowId),
    )

fun encodeActivityScopeId(scope: ActivityScope): SyncScopeId =
    encodeActivityScopeId(
        serverId = scope.serverId,
        principalId = scope.principalId,
        filter = scope.filter.wireValue(),
        windowId = scope.windowId,
    )

private fun ActivityFilter.wireValue(): String = when (this) {
    ActivityFilter.All -> "all"
    ActivityFilter.Unread -> "unread"
    ActivityFilter.Mentions -> "mentions"
}

private fun ActivityRow.rowId(): String = when (this) {
    is ChannelActivityRow -> rowId
    is DmActivityRow -> rowId
    is ThreadActivityRow -> rowId
}

private fun ActivityRow.rowVersion(): UInt64String = when (this) {
    is ChannelActivityRow -> rowVersion
    is DmActivityRow -> rowVersion
    is ThreadActivityRow -> rowVersion
}

private fun ActivityRow.lastActivityAt(): String = when (this) {
    is ChannelActivityRow -> lastActivityAt
    is DmActivityRow -> lastActivityAt
    is ThreadActivityRow -> lastActivityAt
}

private fun ActivityRow.readStateVersion(): UInt64String = when (this) {
    is ChannelActivityRow -> readStateVersion
    is DmActivityRow -> readStateVersion
    is ThreadActivityRow -> readStateVersion
}

private fun ActivityRow.withReadState(
    maxReadSeq: UInt64String,
    readStateVersion: UInt64String,
): ActivityRow = when (this) {
    is ChannelActivityRow -> copy(maxReadSeq = maxReadSeq, readStateVersion = readStateVersion)
    is DmActivityRow -> copy(maxReadSeq = maxReadSeq, readStateVersion = readStateVersion)
    is ThreadActivityRow -> copy(maxReadSeq = maxReadSeq, readStateVersion = readStateVersion)
}
