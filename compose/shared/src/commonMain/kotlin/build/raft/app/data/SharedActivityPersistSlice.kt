package build.raft.app.data

import build.raft.app.model.PerReceiverReadStateChanged
import kotlinx.serialization.Serializable

internal fun createSharedActivityPersistSlice(
    storage: MviPersistStorage,
    diagnosticsLogger: (String) -> Unit = {},
    failureLogger: (String) -> Unit = diagnosticsLogger
): MviPersistSlice<SharedActivityState, SharedActivityIntent> =
    MviPersistMiddleware(
        storage = storage,
        spec = SharedActivityPersistSpec,
        diagnosticsLogger = diagnosticsLogger,
        failureLogger = failureLogger
    )

private object SharedActivityPersistSpec :
    MviPersistSpec<SharedActivityState, SharedActivityIntent, SharedActivityPersistSnapshot> {
    override val name: String = "shared_activity"
    override val version: Int = 1
    override val snapshotSerializer = SharedActivityPersistSnapshot.serializer()

    override fun key(
        previous: SharedActivityState,
        intent: SharedActivityIntent,
        reduced: SharedActivityState
    ): String? {
        val serverId = reduced.ownerServerId?.trim().takeUnless { it.isNullOrBlank() }
            ?: previous.ownerServerId?.trim().takeUnless { it.isNullOrBlank() }
            ?: return null
        val userId = reduced.ownerUserId?.trim().takeUnless { it.isNullOrBlank() }
            ?: previous.ownerUserId?.trim().takeUnless { it.isNullOrBlank() }
            ?: return null
        val filter = reduced.selectedFilter
        return "mvi:persist:activity:v$version:user:$userId:server:$serverId:filter:${filter.name}"
    }

    override fun shouldHydrate(
        previous: SharedActivityState,
        intent: SharedActivityIntent,
        reduced: SharedActivityState
    ): Boolean =
        reduced.snapshot == null &&
            (
                intent is SharedActivityIntent.ServerSelected ||
                    intent is SharedActivityIntent.LoadInitial ||
                    intent is SharedActivityIntent.Refresh ||
                    intent is SharedActivityIntent.PullRefresh ||
                    intent is SharedActivityIntent.SetFilter
                )

    override fun mergeHydrated(
        previous: SharedActivityState,
        intent: SharedActivityIntent,
        reduced: SharedActivityState,
        snapshot: SharedActivityPersistSnapshot,
        metadata: MviPersistMetadata
    ): SharedActivityState {
        if (reduced.snapshot != null) return reduced
        if (snapshot.selectedFilterName != reduced.selectedFilter.name) return reduced
        // Only rows of the server the state now belongs to; the key already names it.
        if (reduced.ownerServerId != null && snapshot.snapshot.server.id.value != reduced.ownerServerId) return reduced
        val persistedFilter =
            InboxFilter.entries.firstOrNull { it.name == snapshot.selectedFilterName }
                ?: InboxFilter.All
        return reduced.copy(
            snapshot = snapshot.snapshot.dedupeActivitySnapshot().projectActivityFilter(persistedFilter),
            senderProfiles = snapshot.senderProfiles,
            loaded = true,
            loading = false,
            refreshing = true,
            pullRefreshing = false,
            loadingMore = false,
            errorCode = null,
            loadMoreErrorCode = null
        )
    }

    override fun shouldPersist(
        previous: SharedActivityState,
        intent: SharedActivityIntent,
        next: SharedActivityState
    ): Boolean =
        next.loaded &&
            next.snapshot != null &&
            (
                intent is SharedActivityIntent.LoadInitial ||
                    intent is SharedActivityIntent.Refresh ||
                    intent is SharedActivityIntent.PullRefresh ||
                    intent is SharedActivityIntent.LoadMore ||
                    intent is SharedActivityIntent.SetFilter ||
                    intent is SharedActivityIntent.MarkAllRead ||
                    intent is SharedActivityIntent.MarkDone ||
                    intent is SharedActivityIntent.UnfollowThread ||
                    intent is SharedActivityIntent.FollowThread ||
                    intent is SharedActivityIntent.MarkRead ||
                    intent is SharedActivityIntent.MarkUnread ||
                    intent is SharedActivityIntent.ApplyReadReceiptPrediction ||
                    intent is SharedActivityIntent.ApplyRealtimeSummaryEvent ||
                    // A read fact accepted here also advanced the shared read-state version on disk
                    // (PerReceiverStateVersionStore); persist the rows it cleared with it, or a cold
                    // start shows the old unread under the newer version (audit addendum 2 item 4).
                    (intent is SharedActivityIntent.ApplyRealtimeEvent && intent.event is PerReceiverReadStateChanged)
                )

    override fun shouldRemove(
        previous: SharedActivityState,
        intent: SharedActivityIntent,
        next: SharedActivityState
    ): Boolean =
        next.errorCode == "unauthenticated"

    override fun partialize(state: SharedActivityState): SharedActivityPersistSnapshot? {
        // The rows retained for this visit are a view of the visit only; a cold start does not bring them back.
        val snapshot = state.withRetainedReadRowsReleased().snapshot ?: return null
        if (!state.loaded) return null
        return SharedActivityPersistSnapshot(
            selectedFilterName = state.selectedFilter.name,
            snapshot = snapshot,
            senderProfiles = state.senderProfiles
        )
    }
}

@Serializable
private data class SharedActivityPersistSnapshot(
    val selectedFilterName: String,
    val snapshot: InboxSnapshot,
    val senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap()
)

private fun InboxSnapshot.dedupeActivitySnapshot(): InboxSnapshot =
    copy(items = items.map { item -> item.withActivityFollowStateProjection() }.dedupeActivityItems())
