package build.raft.app.data

import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ServerId
import kotlinx.coroutines.CoroutineScope

internal fun createSharedHomeIdentityPersistSlice(
    storage: MviPersistStorage,
    diagnosticsLogger: (String) -> Unit = {},
    failureLogger: (String) -> Unit = diagnosticsLogger,
    scrollDeferScope: CoroutineScope? = null,
): MviPersistSlice<SharedHomeState, SharedHomeIntent> {
    val middleware =
        MviPersistMiddleware(
            storage = storage,
            spec = SharedHomeIdentityPersistSpec,
            diagnosticsLogger = diagnosticsLogger,
            failureLogger = failureLogger
        )
    return if (scrollDeferScope != null) {
        ScrollDeferredMviPersistSlice(delegate = middleware, persistScope = scrollDeferScope)
    } else {
        middleware
    }
}

private object SharedHomeIdentityPersistSpec :
    MviPersistSpec<SharedHomeState, SharedHomeIntent, HomeSnapshot> {
    override val name: String = "shared_home_identity"
    override val version: Int = 1
    override val snapshotSerializer = HomeSnapshot.serializer()

    override fun key(previous: SharedHomeState, intent: SharedHomeIntent, reduced: SharedHomeState): String? {
        val session = reduced.content.homeSessionOrNull() ?: previous.content.homeSessionOrNull() ?: return null
        val serverId =
            reduced.content.homeServerIdOrNull()
                ?: previous.content.homeServerIdOrNull()
                ?: session.currentServerId?.let(::ServerId)
                ?: return null
        val userId = session.user.id.takeIf { it.isNotBlank() } ?: return null
        return "mvi:persist:home:identity:v$version:user:$userId:server:${serverId.value}"
    }

    override fun shouldHydrate(
        previous: SharedHomeState,
        intent: SharedHomeIntent,
        reduced: SharedHomeState
    ): Boolean =
        reduced.content !is SharedHomeContentState.Content &&
            (intent is SharedHomeIntent.Start || intent is SharedHomeIntent.SelectServer)

    override fun mergeHydrated(
        previous: SharedHomeState,
        intent: SharedHomeIntent,
        reduced: SharedHomeState,
        snapshot: HomeSnapshot,
        metadata: MviPersistMetadata
    ): SharedHomeState {
        val session = reduced.content.homeSessionOrNull() ?: previous.content.homeSessionOrNull() ?: return reduced
        if (reduced.content is SharedHomeContentState.Content) return reduced
        val serverId =
            reduced.content.homeServerIdOrNull()
                ?: previous.content.homeServerIdOrNull()
                ?: session.currentServerId?.let(::ServerId)
                ?: return reduced
        if (!snapshot.matchesPersistedHomeIdentity(session = session, expectedServerId = serverId)) {
            return reduced
        }
        val pruned = snapshot.pruneForHomeIdentityPersist()
        return reduced.copy(
            content =
                SharedHomeContentState.Content(
                    snapshot = pruned,
                    session = session,
                    contentRefreshing = true,
                    baseSnapshot = pruned,
                    isDurableIdentityCache = true
                )
        )
    }

    override fun shouldPersist(previous: SharedHomeState, intent: SharedHomeIntent, next: SharedHomeState): Boolean =
        next.content is SharedHomeContentState.Content &&
            (
                intent is SharedHomeIntent.Start ||
                    intent is SharedHomeIntent.Refresh ||
                    intent is SharedHomeIntent.ForegroundRefresh ||
                    intent is SharedHomeIntent.SelectServer ||
                    intent is SharedHomeIntent.ChannelCreated ||
                    intent is SharedHomeIntent.ChannelJoined ||
                    intent is SharedHomeIntent.ChannelMembershipEnded ||
                    intent is SharedHomeIntent.ChannelProfileUpdated ||
                    intent is SharedHomeIntent.TogglePinned ||
                    intent is SharedHomeIntent.SetPinnedSortMode ||
                    intent is SharedHomeIntent.SetChannelSortMode ||
                    intent is SharedHomeIntent.SetJointChannelSortMode ||
                    intent is SharedHomeIntent.ReorderSidebar ||
                    intent is SharedHomeIntent.ReorderServers ||
                    intent is SharedHomeIntent.CloseDirectMessage ||
                    intent is SharedHomeIntent.ReopenClosedConversation ||
                    intent is SharedHomeIntent.ArchiveChannel ||
                    intent is SharedHomeIntent.LeaveChannel
                )

    override fun shouldRemove(previous: SharedHomeState, intent: SharedHomeIntent, next: SharedHomeState): Boolean =
        next.content is SharedHomeContentState.Error &&
            next.content.code == "unauthenticated"

    override fun partialize(state: SharedHomeState): HomeSnapshot? =
        (state.content as? SharedHomeContentState.Content)
            ?.baseSnapshot
            ?.pruneForHomeIdentityPersist()
}

internal fun HomeSnapshot.matchesPersistedHomeIdentity(
    session: StoredAuthSession,
    expectedServerId: ServerId
): Boolean =
    server.id == expectedServerId &&
        currentMember.id.value == session.user.id &&
        session.servers.any { it.id == expectedServerId.value }

internal fun HomeSnapshot.pruneForHomeIdentityPersist(): HomeSnapshot =
    copy(
        channels = channels.map { it.pruneForHomeIdentityPersist() },
        directMessages = directMessages.map { it.pruneForHomeIdentityPersist() },
        sidebarOrder =
            sidebarOrder?.let { order ->
                val normalizedSections = normalizeSidebarCustomSections(order.customSections)
                order.copy(
                    customSections = normalizedSections,
                    sectionOrder = normalizeSidebarSectionOrder(order.sectionOrder, normalizedSections),
                    sectionPlacements = normalizeSidebarSectionPlacements(order.sectionPlacements)
                )
            },
        unreadTotal = 0,
        unreadSource = AppBadgeSource.UNKNOWN,
        serverUnreadCounts = emptyMap(),
        // Unread-bearing like the row counts: the identity cache never restores a thread mark or a
        // switcher badge. Their last-known copies live in the row hints ([HomeRowHintsSnapshot]).
        followedThreadUnread = emptyMap(),
        markedUnreadIds = emptySet()
    )

private fun HomeChannelRow.pruneForHomeIdentityPersist(): HomeChannelRow =
    copy(
        unreadCount = 0,
        unreadCountKnown = false,
        mentionCount = 0,
        mentionCountKnown = false,
        lastMessageId = null,
        firstUnreadMessageId = null,
        // The accepted read cursor lives with its version in PerReceiverStateVersionStore; a copy here
        // is not rewritten by realtime read facts and would sit older than that version on disk.
        readStateMaxReadSeq = null,
        lastMessagePreview = null,
        lastMessageAt = null,
        lastMessageSenderName = null,
        presenceMemberId = null,
        hasDraft = false,
        hasDraftKnown = false,
        activityNotificationPreference = ChannelActivityNotificationPreference.Unsupported
    )

/**
 * [HomeConversationRow.presenceMemberId] is the DM peer's canonical identity, not a volatile presence value.
 * Typed human/agent pins use it to resolve the persisted DM row before the network refresh completes.
 */
private fun HomeDirectMessageRow.pruneForHomeIdentityPersist(): HomeDirectMessageRow =
    copy(
        unreadCount = 0,
        unreadCountKnown = false,
        mentionCount = 0,
        mentionCountKnown = false,
        lastMessageId = null,
        firstUnreadMessageId = null,
        // The accepted read cursor lives with its version in PerReceiverStateVersionStore; a copy here
        // is not rewritten by realtime read facts and would sit older than that version on disk.
        readStateMaxReadSeq = null,
        lastMessagePreview = null,
        lastMessageAt = null,
        lastMessageSenderName = null,
        hasDraft = false,
        hasDraftKnown = false,
        activityNotificationPreference = ChannelActivityNotificationPreference.Unsupported
    )

internal fun SharedHomeContentState.homeSessionOrNull(): StoredAuthSession? =
    when (this) {
        is SharedHomeContentState.Content -> session
        is SharedHomeContentState.Empty -> session
        is SharedHomeContentState.Loading -> session
        is SharedHomeContentState.Error -> session
    }

internal fun SharedHomeContentState.homeServerIdOrNull(): ServerId? =
    when (this) {
        is SharedHomeContentState.Content -> snapshot.server.id
        is SharedHomeContentState.Empty -> session.currentServerId?.let(::ServerId)
        is SharedHomeContentState.Loading -> session?.currentServerId?.let(::ServerId)
        is SharedHomeContentState.Error -> session?.currentServerId?.let(::ServerId)
    }
