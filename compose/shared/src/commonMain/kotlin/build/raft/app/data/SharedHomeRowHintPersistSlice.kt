package build.raft.app.data

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
 */
@Serializable
data class HomeRowRestoredHint(
    val unreadCount: Int = 0,
    val lastMessagePreview: String? = null,
    val lastMessageAt: String? = null,
    val lastMessageSenderName: String? = null
)

@Serializable
internal data class HomeRowHintsSnapshot(
    val serverId: String,
    val memberId: String,
    val hints: Map<String, HomeRowRestoredHint> = emptyMap()
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
        if (hints.isEmpty()) return reduced
        return reduced.copy(content = content.copy(restoredRowHints = hints))
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
                    intent is SharedHomeIntent.ApplyReadReceiptPrediction
                )

    override fun shouldRemove(previous: SharedHomeState, intent: SharedHomeIntent, next: SharedHomeState): Boolean =
        next.content is SharedHomeContentState.Error &&
            next.content.code == "unauthenticated"

    override fun partialize(state: SharedHomeState): HomeRowHintsSnapshot? {
        val content = state.content as? SharedHomeContentState.Content ?: return null
        return content.toRowHintsSnapshot()
    }
}

internal fun SharedHomeContentState.Content.toRowHintsSnapshot(): HomeRowHintsSnapshot {
    val base = baseSnapshot
    val hints =
        (base.channels + base.directMessages).mapNotNull { row ->
            val id = row.threadId.value
            val prior = restoredRowHints[id]
            val unread = if (row.unreadCountKnown) row.unreadCount.coerceAtLeast(0) else prior?.unreadCount ?: 0
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
                        if (rowHasPreview) row.lastMessageSenderName else prior?.lastMessageSenderName
                )
        }.toMap()
    return HomeRowHintsSnapshot(
        serverId = base.server.id.value,
        memberId = base.currentMember.id.value,
        hints = hints
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
