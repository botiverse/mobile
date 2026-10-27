package build.raft.app.data

import build.raft.app.model.InboxItemKind
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

private const val ActivityUpdateTrackingWindow = 20
private const val ActivityRetiredUpdateTrackingWindow = ActivityUpdateTrackingWindow * 5

internal data class ActivityUpdateMarker(
    val filter: InboxFilter,
    val itemKey: String,
    val latestActivityMessageId: String?
)

internal data class ActivityPendingUpdateLedger(
    val pendingMarkers: Set<ActivityUpdateMarker> = emptySet(),
    val retiredMarkers: Set<ActivityUpdateMarker> = emptySet()
) {
    val count: Int
        get() = pendingMarkers.size

    fun record(markers: Set<ActivityUpdateMarker>): ActivityPendingUpdateLedger {
        val genuinelyPending = markers - retiredMarkers
        if (genuinelyPending.isEmpty()) return this
        return copy(pendingMarkers = pendingMarkers + genuinelyPending)
    }

    fun retire(markers: Set<ActivityUpdateMarker>): ActivityPendingUpdateLedger {
        val retiring = pendingMarkers intersect markers
        if (retiring.isEmpty()) return this
        val allRetired = (retiredMarkers + retiring).toList()
        val retainedFrom = (allRetired.size - ActivityRetiredUpdateTrackingWindow).coerceAtLeast(0)
        val retired = allRetired.subList(retainedFrom, allRetired.size).toSet()
        return copy(pendingMarkers = pendingMarkers - retiring, retiredMarkers = retired)
    }

    fun consumePending(): ActivityPendingUpdateLedger = retire(pendingMarkers)
}

internal data class ActivityUpdateTrackingState(
    val filter: InboxFilter,
    val previousItems: List<InboxItem> = emptyList(),
    val pendingUpdates: ActivityPendingUpdateLedger = ActivityPendingUpdateLedger()
) {
    val pendingCount: Int
        get() = pendingUpdates.count

    fun recordSnapshot(nextFilter: InboxFilter, currentItems: List<InboxItem>): ActivityUpdateTrackingState {
        val currentWindow = currentItems.take(ActivityUpdateTrackingWindow)
        if (filter != nextFilter) {
            return ActivityUpdateTrackingState(filter = nextFilter, previousItems = currentWindow)
        }
        val update = activityUpdatePresentation(nextFilter, previousItems, currentItems)
        return copy(
            previousItems = currentWindow,
            pendingUpdates = pendingUpdates.record(update.pendingMarkers)
        )
    }

    fun consumePending(): ActivityUpdateTrackingState =
        copy(pendingUpdates = pendingUpdates.consumePending())

    fun reconcileCanonicalSnapshot(nextFilter: InboxFilter, currentItems: List<InboxItem>): ActivityUpdateTrackingState {
        if (filter != nextFilter || pendingUpdates.pendingMarkers.isEmpty()) return this
        val currentMarkers =
            currentItems.mapTo(linkedSetOf()) { item ->
                ActivityUpdateMarker(nextFilter, item.key, item.activityUpdateRevisionMessageId())
            }
        val reconciled = pendingUpdates.retire(pendingUpdates.pendingMarkers - currentMarkers)
        return if (reconciled == pendingUpdates) this else copy(pendingUpdates = reconciled)
    }
}

internal data class ActivityUpdatePresentation(val pendingMarkers: Set<ActivityUpdateMarker>) {
    val pendingIncrement: Int
        get() = pendingMarkers.size
}

internal fun activityUpdatePresentation(
    filter: InboxFilter = InboxFilter.All,
    previousItems: List<InboxItem>,
    currentItems: List<InboxItem>
): ActivityUpdatePresentation =
    ActivityUpdatePresentation(
        activityPendingUpdateMarkers(filter, previousItems, currentItems, nearTop = false)
    )

internal fun activityPendingUpdateIncrement(
    previousItems: List<InboxItem>,
    currentItems: List<InboxItem>,
    nearTop: Boolean,
    filter: InboxFilter = InboxFilter.All
): Int = activityPendingUpdateMarkers(filter, previousItems, currentItems, nearTop).size

internal fun activityPendingUpdateMarkers(
    filter: InboxFilter,
    previousItems: List<InboxItem>,
    currentItems: List<InboxItem>,
    nearTop: Boolean
): Set<ActivityUpdateMarker> {
    if (nearTop || previousItems.isEmpty()) return emptySet()
    val previousByKey = previousItems.associateBy { it.key }
    val currentWindow = currentItems.take(ActivityUpdateTrackingWindow)
    if (currentWindow.isEmpty()) return emptySet()
    val freshTopRows = currentWindow.takeWhile { it.key !in previousByKey }
    val advancedKnownRows =
        currentWindow.filter { current ->
            val previous = previousByKey[current.key] ?: return@filter false
            val currentLatest = current.activityUpdateRevisionMessageId()
            currentLatest != null && currentLatest != previous.activityUpdateRevisionMessageId()
        }
    return (freshTopRows + advancedKnownRows)
        .mapTo(linkedSetOf()) { item ->
            ActivityUpdateMarker(filter, item.key, item.activityUpdateRevisionMessageId())
        }
}

internal fun InboxItem.activityUpdateRevisionMessageId(): String? =
    when (kind) {
        InboxItemKind.Thread -> latestActivityMessageId?.value
        InboxItemKind.Channel,
        InboxItemKind.DirectMessage -> lastMessageId?.value ?: latestActivityMessageId?.value
        InboxItemKind.MentionAction,
        InboxItemKind.System -> latestActivityMessageId?.value ?: lastMessageId?.value
        // An object row's newest event (its Done frontier) moves with every new event.
        InboxItemKind.Object -> activityObject?.doneFrontierSeq?.let { "object-event:$it" }
    }

internal data class SharedActivityPresentationCheckpoint(
    val presentedItems: List<InboxItem>,
    val updateTracking: ActivityUpdateTrackingState,
    val presentationGeneration: Int = 0,
    val pullRefreshInFlight: Boolean = false,
    // task #45 follow-up: the selected row highlight is user-visible state; it must
    // survive the panel being unmounted while an inline destination covers it.
    val selectedItemKey: String? = null
)

/**
 * Route-retained Activity presentation state. The canonical Activity store owns content;
 * this auxiliary store owns only the user's currently presented key order and pending reveal
 * ledger so a Thread route cannot destroy viewport ownership by disposing the composable.
 */
internal class SharedActivityPresentationStore {
    private val lock = SynchronizedObject()
    private val checkpoints = mutableMapOf<PresentationOwner, SharedActivityPresentationCheckpoint>()

    private data class PresentationOwner(
        val accountId: String,
        val serverId: String,
        val filter: InboxFilter
    )

    fun restoreOrCreate(
        accountId: String,
        serverId: String,
        filter: InboxFilter,
        canonicalItems: List<InboxItem>
    ): SharedActivityPresentationCheckpoint = synchronized(lock) {
        if (accountId.isBlank()) {
            checkpoints.clear()
            return@synchronized SharedActivityPresentationCheckpoint(
                presentedItems = canonicalItems,
                updateTracking = ActivityUpdateTrackingState(filter).recordSnapshot(filter, canonicalItems)
            )
        }
        val owner = PresentationOwner(accountId, serverId, filter)
        checkpoints[owner]
            ?: SharedActivityPresentationCheckpoint(
                    presentedItems = canonicalItems,
                    updateTracking = ActivityUpdateTrackingState(filter).recordSnapshot(filter, canonicalItems)
                )
                .also { checkpoints[owner] = it }
    }

    fun save(
        accountId: String,
        serverId: String,
        filter: InboxFilter,
        checkpoint: SharedActivityPresentationCheckpoint
    ) {
        synchronized(lock) {
            if (accountId.isBlank()) {
                checkpoints.clear()
                return@synchronized
            }
            checkpoints[PresentationOwner(accountId, serverId, filter)] = checkpoint
        }
    }

    fun clear() {
        synchronized(lock) {
            checkpoints.clear()
        }
    }

    fun clearOwner(accountId: String, serverId: String) {
        synchronized(lock) {
            if (accountId.isBlank()) {
                checkpoints.clear()
            } else {
                checkpoints.keys.removeAll { owner ->
                    owner.accountId == accountId && owner.serverId == serverId
                }
            }
        }
    }
}
