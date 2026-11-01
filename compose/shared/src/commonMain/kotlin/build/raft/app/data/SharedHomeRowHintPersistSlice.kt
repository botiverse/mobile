package build.raft.app.data

import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable

/**
 * Last-known per-row unread count and last-message preview, shown on cold start while Home is
 * still the durable identity cache (task #166).
 *
 * The identity slice deliberately persists rows with `unreadCount = 0` / `unreadCountKnown =
 * false` / no preview: those values gate the app badge sum, realtime increments and sidebar
 * mark-read. Hints therefore never enter the rows. They live beside them in
 * [SharedHomeContentState.Content.restoredRowHints], are read only by the row projection while
 * [SharedHomeContentState.Content.isDurableIdentityCache] is true and the row's own count is
 * still unknown, and disappear when the first network Home replaces the content.
 *
 * Previews are message content, so this lives under its own key family, keyed by user and server:
 * sign-out keeps them for the same user, and losing access to a server drops that server's copies
 * ([AccountLocalData]).
 *
 * Persistence audit item 6 (read state offline while `sync_core_read_state_v1` is off): the record
 * also carries the row's @ mark, the cross-server switcher badges and the followed-thread marks.
 * All three are last-known candidates: they only fill gaps on a cold start and the first network
 * Home (or unread summary) replaces them.
 */
@Serializable
data class HomeRowRestoredHint(
    val unreadCount: Int = 0,
    val lastMessagePreview: String? = null,
    val lastMessageAt: String? = null,
    val lastMessageSenderName: String? = null,
    val mentionCount: Int = 0
)

@Serializable
internal data class HomeRowHintsSnapshot(
    val serverId: String,
    val memberId: String,
    val hints: Map<String, HomeRowRestoredHint> = emptyMap(),
    /** [SharedHomeState.serverUnreadCounts] (server switcher badges), by server id. */
    val serverUnreadCounts: Map<String, Int> = emptyMap(),
    /** [HomeSnapshot.followedThreadUnread], by thread channel id. */
    val followedThreadUnread: Map<String, HomeFollowedThreadUnread> = emptyMap()
)

/** Bounds what a single row can add to the durable preference store. */
internal const val HomeRowHintPreviewMaxChars = 280

internal fun createSharedHomeRowHintPersistSlice(
    storage: HomeRowHintPersistStorage,
    diagnosticsLogger: (String) -> Unit = {},
    failureLogger: (String) -> Unit = diagnosticsLogger
): MviPersistSlice<SharedHomeState, SharedHomeIntent> =
    MviPersistMiddleware(
        storage = storage,
        spec = SharedHomeRowHintPersistSpec,
        diagnosticsLogger = diagnosticsLogger,
        failureLogger = failureLogger
    )

/**
 * The row hints' storage: the last-known copy family (task #164 limits: 7 days, 5 servers per user,
 * the whole family removed by [RaftLastCountsPersistStorage.clearAll] when the session ends).
 */
internal class HomeRowHintPersistStorage(
    store: SharedPreferenceStore,
    nowMillis: () -> Long = ::defaultNowMillis,
) : RaftLastCountsPersistStorage(store, indexKey = "mvi:persist:home:hints:index", nowMillis = nowMillis)

private object SharedHomeRowHintPersistSpec :
    MviPersistSpec<SharedHomeState, SharedHomeIntent, HomeRowHintsSnapshot> {
    override val name: String = "shared_home_row_hints"
    override val version: Int = 1
    override val snapshotSerializer = HomeRowHintsSnapshot.serializer()

    override fun key(previous: SharedHomeState, intent: SharedHomeIntent, reduced: SharedHomeState): String? {
        val (userId, serverId) = homePersistScope(previous, reduced) ?: return null
        return "mvi:persist:home:hints:v$version:user:$userId:server:${serverId.value}"
    }

    override fun shouldHydrate(
        previous: SharedHomeState,
        intent: SharedHomeIntent,
        reduced: SharedHomeState
    ): Boolean =
        // Runs after the identity slice in the same reduction, so a restored identity cache is
        // already in [reduced] when the hints are attached.
        (reduced.content as? SharedHomeContentState.Content)?.isDurableIdentityCache == true &&
            (intent is SharedHomeIntent.Start || intent is SharedHomeIntent.SelectServer)

    override fun mergeHydrated(
        previous: SharedHomeState,
        intent: SharedHomeIntent,
        reduced: SharedHomeState,
        snapshot: HomeRowHintsSnapshot,
        metadata: MviPersistMetadata
    ): SharedHomeState {
        val content = reduced.content as? SharedHomeContentState.Content ?: return reduced
        if (!content.isDurableIdentityCache) return reduced
        val base = content.baseSnapshot
        if (snapshot.serverId != base.server.id.value) return reduced
        if (snapshot.memberId != content.session.user.id || snapshot.memberId != base.currentMember.id.value) {
            return reduced
        }
        val rowIds = base.homeRowIds()
        val hints = snapshot.hints.filterKeys { it in rowIds }
        // Only servers this session still belongs to; a badge the state already holds is newer.
        val serverIds = content.session.servers.mapTo(HashSet()) { it.id }
        val serverUnread =
            snapshot.serverUnreadCounts.filterKeys { it in serverIds && it !in reduced.serverUnreadCounts }
        // Only threads under a channel this Home still lists, and only while Home has no marks of its own.
        val threads =
            if (base.followedThreadUnread.isNotEmpty()) {
                emptyMap()
            } else {
                snapshot.followedThreadUnread.filterValues { it.parentChannelId.value in rowIds && it.unreadCount > 0 }
            }
        if (hints.isEmpty() && serverUnread.isEmpty() && threads.isEmpty()) return reduced
        val restoredContent =
            if (threads.isEmpty()) {
                content.copy(restoredRowHints = hints)
            } else {
                content.copy(
                    snapshot = content.snapshot.copy(followedThreadUnread = threads),
                    baseSnapshot = base.copy(followedThreadUnread = threads),
                    restoredRowHints = hints
                )
            }
        return reduced.copy(
            content = restoredContent,
            serverUnreadCounts = reduced.serverUnreadCounts + serverUnread
        )
    }

    override fun shouldPersist(previous: SharedHomeState, intent: SharedHomeIntent, next: SharedHomeState): Boolean =
        next.content is SharedHomeContentState.Content &&
            (
                intent is SharedHomeIntent.Start ||
                    intent is SharedHomeIntent.Refresh ||
                    intent is SharedHomeIntent.ForegroundRefresh ||
                    intent is SharedHomeIntent.SelectServer ||
                    intent is SharedHomeIntent.CloseDirectMessage ||
                    intent is SharedHomeIntent.ArchiveChannel ||
                    intent is SharedHomeIntent.LeaveChannel ||
                    // Reading a conversation must not resurrect its old count on the next cold start.
                    intent is SharedHomeIntent.ApplyReadReceiptPrediction ||
                    // A read fact: its version and cursor are written to [PerReceiverStateVersionStore]
                    // in this reduction, so the marks it lowered (followed threads) are written with
                    // them (audit addendum 2 item 4). Row counts follow its reconcile refresh.
                    (intent is SharedHomeIntent.ApplyRealtimeEvent && intent.event is PerReceiverReadStateChanged)
                )

    override fun shouldRemove(previous: SharedHomeState, intent: SharedHomeIntent, next: SharedHomeState): Boolean =
        next.content is SharedHomeContentState.Error &&
            next.content.code == "unauthenticated"

    override fun partialize(state: SharedHomeState): HomeRowHintsSnapshot? {
        val content = state.content as? SharedHomeContentState.Content ?: return null
        return content.toRowHintsSnapshot(serverUnreadCounts = state.serverUnreadCounts)
    }
}

internal fun SharedHomeContentState.Content.toRowHintsSnapshot(
    serverUnreadCounts: Map<String, Int> = emptyMap()
): HomeRowHintsSnapshot {
    val base = baseSnapshot
    val hints =
        (base.channels + base.directMessages).mapNotNull { row ->
            val id = row.threadId.value
            val prior = restoredRowHints[id]
            val unread = if (row.unreadCountKnown) row.unreadCount.coerceAtLeast(0) else prior?.unreadCount ?: 0
            val mentions =
                when {
                    unread <= 0 -> 0
                    row.unreadCountKnown || row.mentionCountKnown -> row.mentionCount.coerceAtLeast(0)
                    else -> prior?.mentionCount ?: 0
                }
            val rowHasPreview = !row.lastMessagePreview.isNullOrBlank()
            val preview = (if (rowHasPreview) row.lastMessagePreview else prior?.lastMessagePreview)
                ?.take(HomeRowHintPreviewMaxChars)
            if (unread <= 0 && preview == null) return@mapNotNull null
            id to
                HomeRowRestoredHint(
                    unreadCount = unread,
                    lastMessagePreview = preview,
                    lastMessageAt = if (rowHasPreview) row.lastMessageAt else prior?.lastMessageAt ?: row.lastMessageAt,
                    lastMessageSenderName =
                        if (rowHasPreview) row.lastMessageSenderName else prior?.lastMessageSenderName,
                    mentionCount = mentions
                )
        }.toMap()
    return HomeRowHintsSnapshot(
        serverId = base.server.id.value,
        memberId = base.currentMember.id.value,
        hints = hints,
        serverUnreadCounts = serverUnreadCounts.filterValues { it > 0 },
        followedThreadUnread = base.followedThreadUnread.filterValues { it.unreadCount > 0 }
    )
}

/**
 * Hints the sidebar may show: only while this content is still the durable cache. The row
 * projection additionally ignores a hint once the row's own count is known (a read prediction
 * or realtime event), because that is newer than anything restored.
 */
internal fun SharedHomeContentState.Content.displayRowHints(): Map<String, HomeRowRestoredHint> =
    if (isDurableIdentityCache || rowHintsRetained) restoredRowHints else emptyMap()

/**
 * Task #164: true when this content's row counts came from a network Home (live), false while it
 * shows only the durable cache's last-known hints (cold start, server switch, sign-in).
 */
internal fun SharedHomeContentState.Content.showsLiveUnread(): Boolean =
    !isDurableIdentityCache && !rowHintsRetained

/**
 * Task #164: a network Home whose unread was frozen (stale or unavailable) over content that still
 * showed the restored last-known counts keeps showing them, instead of rows with no count, until
 * a load with fresh unread replaces the content.
 */
internal fun SharedHomeContentState.Content.keepingLastKnownRowHintsFrom(
    previous: SharedHomeContentState.Content?,
    unreadFrozen: Boolean
): SharedHomeContentState.Content {
    if (!unreadFrozen || previous == null) return this
    if (previous.baseSnapshot.server.id != baseSnapshot.server.id || previous.session.user.id != session.user.id) return this
    val hints = previous.displayRowHints()
    if (hints.isEmpty()) return this
    return copy(restoredRowHints = hints, rowHintsRetained = true)
}

private fun HomeSnapshot.homeRowIds(): Set<String> =
    (channels.map { it.threadId.value } + directMessages.map { it.threadId.value }).toSet()

internal fun homePersistScope(previous: SharedHomeState, reduced: SharedHomeState): Pair<String, ServerId>? {
    val session = reduced.content.homeSessionOrNull() ?: previous.content.homeSessionOrNull() ?: return null
    val serverId =
        reduced.content.homeServerIdOrNull()
            ?: previous.content.homeServerIdOrNull()
            ?: session.currentServerId?.let(::ServerId)
            ?: return null
    val userId = session.user.id.takeIf { it.isNotBlank() } ?: return null
    return userId to serverId
}
