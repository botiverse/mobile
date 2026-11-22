package build.raft.app

import build.raft.app.data.ActivityUnreadSummaryProjection
import build.raft.app.data.ActivityPendingUpdateLedger
import build.raft.app.data.ActivityUpdateMarker
import build.raft.app.data.ActivityUpdatePresentation
import build.raft.app.data.ActivityUpdateTrackingState
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.withActivityFollowStateProjection
import build.raft.app.data.MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY
import build.raft.app.data.SharedActivityState
import build.raft.app.data.ServerFeatureFlagSnapshot
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.mergedSharedConversationDirectoryState
import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.SharedActivitySenderProfile
import build.raft.app.data.SharedActivityPresentationCheckpoint
import build.raft.app.data.SharedActivityPresentationStore
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.app.data.activityPendingUpdateIncrement
import build.raft.app.data.activityPendingUpdateMarkers
import build.raft.app.data.activityUpdatePresentation
import build.raft.app.data.activityUpdateRevisionMessageId
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.model.threadIdentity
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedInboxPanelTest {
    @Test
    fun activitySuppressesOnlyUnopenableTypedInternalJointRowsAndKeepsCountsCoherent() {
        val unsafe =
            replyActivityItem("unsafe").copy(
                parentChannelId = ChannelId("joint-storage-parent"),
                parentChannelName = "joint-storage-parent",
                parentChannelKind = ConversationKind.Joint,
                unreadCount = 3
            )
        val safeJoint =
            replyActivityItem("safe").copy(
                parentChannelId = ChannelId("joint-local-parent"),
                parentChannelName = "member-visible-joint",
                parentChannelKind = ConversationKind.Joint,
                unreadCount = 2
            )
        val storageLookingNameWithLocalSource =
            replyActivityItem("display-only").copy(
                parentChannelId = ChannelId("joint-local-parent-2"),
                parentChannelName = "joint-storage-display-only",
                parentChannelKind = ConversationKind.Joint,
                unreadCount = 1
            )
        val ordinaryChannel =
            InboxItem(
                key = "channel:ordinary",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("ordinary"),
                channelName = "ordinary",
                channelKind = ConversationKind.Channel,
                unreadCount = 4
            )
        val ordinaryDm =
            InboxItem(
                key = "dm:ordinary",
                kind = InboxItemKind.DirectMessage,
                channelId = ChannelId("dm-ordinary"),
                channelName = "Cindy",
                channelKind = ConversationKind.DirectMessage
            )
        val state =
            SharedActivityState(
                snapshot =
                    InboxSnapshot(
                        server = ServerSummary(ServerId("server-1"), "Raft"),
                        items =
                            listOf(
                                safeJoint,
                                unsafe,
                                ordinaryChannel,
                                ordinaryDm,
                                storageLookingNameWithLocalSource
                            ),
                        totalCount = 9,
                        totalUnreadCount = 14
                    )
            )

        val projected = projectActivityStateWithoutUnopenableInternalJointRows(state)

        assertEquals(
            listOf("thread:safe", "channel:ordinary", "dm:ordinary", "thread:display-only"),
            projected.snapshot?.items?.map(InboxItem::key)
        )
        assertEquals(8, projected.totalCount)
        assertEquals(11, projected.totalUnreadCount)
        assertEquals(5, state.snapshot?.items?.size)
        assertEquals(9, state.totalCount)
        assertEquals(14, state.totalUnreadCount)

        val processFactAlreadyExcludesUnsafeRow =
            ActivityUnreadSummaryProjection(
                serverId = "server-1",
                totalCount = 8,
                totalUnreadCount = 11,
                hasValue = true,
                source = "activity:load_initial"
            )
        val combined =
            projectActivityStateWithoutUnopenableInternalJointRows(
                projectActivityStateWithProcessSummary(state, processFactAlreadyExcludesUnsafeRow)
            )
        assertEquals(8, combined.totalCount)
        assertEquals(11, combined.totalUnreadCount)
    }

    @Test
    fun openingAnUnreadActivityRowMarksThatRowReadLikeWeb() {
        val opened = activityItem(key = "thread:opened").copy(unreadCount = 2)

        assertEquals(build.raft.app.data.SharedActivityIntent.MarkRead(opened), opened.activityOpenReadIntent())
        assertEquals(null, activityItem(key = "thread:read").activityOpenReadIntent())
        assertEquals(null, opened.copy(kind = InboxItemKind.Thread, isFollowing = false).activityOpenReadIntent())
    }

    @Test
    fun activityTailSentinelRequestsNextPageWhenMoreContentExists() {
        assertTrue(
            activityShouldRequestLoadMore(
                hasMore = true,
                refreshing = false,
                loadingMore = false,
                loadMoreErrorCode = null
            )
        )
    }

    @Test
    fun activityTailSentinelLeavesFailureOnExplicitRetry() {
        assertFalse(
            activityShouldRequestLoadMore(
                hasMore = true,
                refreshing = false,
                loadingMore = false,
                loadMoreErrorCode = "Network"
            )
        )
    }

    @Test
    fun activityReadPrimaryContentMutedCoversConversationKinds() {
        val readThread = activityItem(key = "thread:read").copy(kind = InboxItemKind.Thread)
        val unreadThread = readThread.copy(key = "thread:unread", unreadCount = 1)
        val readChannel = activityItem(key = "channel:read")
        val readDirectMessage = readChannel.copy(key = "dm:read", kind = InboxItemKind.DirectMessage)
        val readMention = readChannel.copy(key = "mention:read", kind = InboxItemKind.MentionAction)
        val readSystem = readChannel.copy(key = "system:read", kind = InboxItemKind.System)

        assertTrue(readThread.activityPrimaryContentMuted())
        assertEquals(false, unreadThread.activityPrimaryContentMuted())
        assertTrue(readChannel.activityPrimaryContentMuted())
        assertTrue(readDirectMessage.activityPrimaryContentMuted())
        assertTrue(readMention.activityPrimaryContentMuted())
        assertEquals(false, readSystem.activityPrimaryContentMuted())
    }

    @Test
    fun activityPageDidAppearRefreshesOnlyAfterInitialAppearance() {
        assertEquals(false, activityPageDidAppearShouldRefresh(0))
        assertEquals(true, activityPageDidAppearShouldRefresh(1))
        // Task #60: a kept-alive but hidden Activity tab skips the shell's resume appearance; it
        // refreshes when the tab becomes active instead.
        assertEquals(false, activityPageDidAppearShouldRefresh(1, active = false))
    }

    @Test
    fun activityAppLifecycleResumeRefreshesOnlyOnVisibleWireEvents() {
        // Android publishes "resume", iOS publishes "show" (task #383).
        assertEquals(
            true,
            activityAppLifecycleResumeShouldRefresh(
                action = RaftRealtimeNotifyContract.ActionAppLifecycle,
                lifecycleEvent = RaftRealtimeNotifyContract.LifecycleVisibleResume
            )
        )
        assertEquals(
            true,
            activityAppLifecycleResumeShouldRefresh(
                action = RaftRealtimeNotifyContract.ActionAppLifecycle,
                lifecycleEvent = RaftRealtimeNotifyContract.LifecycleVisibleShow
            )
        )
        assertEquals(
            false,
            activityAppLifecycleResumeShouldRefresh(
                action = RaftRealtimeNotifyContract.ActionAppLifecycle,
                lifecycleEvent = RaftRealtimeNotifyContract.LifecycleHidden
            )
        )
        assertEquals(
            false,
            activityAppLifecycleResumeShouldRefresh(
                action = "realtime",
                lifecycleEvent = RaftRealtimeNotifyContract.LifecycleVisibleResume
            )
        )
        assertEquals(
            false,
            activityAppLifecycleResumeShouldRefresh(action = "", lifecycleEvent = "")
        )
    }

    @Test
    fun activityAllFilterConsumesProcessUnreadSummary() {
        val pageState =
            SharedActivityState(
                selectedFilter = InboxFilter.All,
                snapshot =
                    InboxSnapshot(
                        server = ServerSummary(ServerId("server-1"), "Raft"),
                        items = emptyList(),
                        totalCount = 4,
                        totalUnreadCount = 1
                    ),
                loaded = true
            )

        val projected =
            projectActivityStateWithProcessSummary(
                state = pageState,
                activitySummary =
                    ActivityUnreadSummaryProjection(
                        serverId = "server-1",
                        totalCount = 12,
                        totalUnreadCount = 9,
                        hasValue = true
                    )
            )

        assertEquals(12, projected.totalCount)
        assertEquals(9, projected.totalUnreadCount)
    }

    @Test
    fun activityNonAllFilterKeepsPageScopedCounts() {
        val pageState =
            SharedActivityState(
                selectedFilter = InboxFilter.Mentions,
                snapshot =
                    InboxSnapshot(
                        server = ServerSummary(ServerId("server-1"), "Raft"),
                        items = emptyList(),
                        totalCount = 3,
                        totalUnreadCount = 2
                    ),
                loaded = true
            )

        val projected =
            projectActivityStateWithProcessSummary(
                state = pageState,
                activitySummary =
                    ActivityUnreadSummaryProjection(
                        serverId = "server-1",
                        totalCount = 12,
                        totalUnreadCount = 9,
                        hasValue = true
                    )
            )

        assertEquals(3, projected.totalCount)
        assertEquals(2, projected.totalUnreadCount)
        assertEquals(InboxFilter.Mentions, projected.selectedFilter)
    }

    @Test
    fun activityPendingUpdateIncrementCountsPrependedRowsWhenAwayFromTop() {
        val previous =
            listOf(
                activityItem(key = "channel:channel-old-top"),
                activityItem(key = "channel:channel-old-next")
            )
        val items =
            listOf(
                activityItem(key = "channel:channel-new-1"),
                activityItem(key = "channel:channel-new-2"),
                activityItem(key = "channel:channel-old-top"),
                activityItem(key = "channel:channel-old-next")
            )

        assertEquals(
            2,
            activityPendingUpdateIncrement(
                previousItems = previous,
                currentItems = items,
                nearTop = false
            )
        )
    }

    @Test
    fun activityListRecoversWhenRetainedViewportOnlyReferencesRemovedUnreadRows() {
        assertEquals(
            true,
            activityListShouldRecoverVisibleContent(
                firstVisibleItemIndex = 4,
                visibleItemKeys = listOf("channel:opened", "activity_load_more"),
                currentItemKeys = listOf("channel:remaining-1", "channel:remaining-2")
            )
        )
    }

    @Test
    fun activityListPreservesViewportWhenAnyVisibleAnchorStillExists() {
        assertEquals(
            false,
            activityListShouldRecoverVisibleContent(
                firstVisibleItemIndex = 4,
                visibleItemKeys = listOf("channel:remaining-2", "activity_load_more"),
                currentItemKeys = listOf("channel:remaining-1", "channel:remaining-2")
            )
        )
    }

    @Test
    fun activityPendingUpdateIncrementIgnoresReadStateMutations() {
        // task #554 follow-up regression: marking an item read/unread (or a task
        // status change) mutates counts but is the USER's action — it must never
        // grow the "x new" badge. Only latestActivityMessageId advancement counts.
        val oldItems =
            listOf(
                activityItem(key = "channel:channel-top", unreadCount = 3, firstUnreadMessageId = "first-unread"),
                activityItem(key = "channel:channel-next", unreadCount = 0)
            )
        val newItems =
            listOf(
                activityItem(key = "channel:channel-top", unreadCount = 0),
                activityItem(key = "channel:channel-next", unreadCount = 0)
            )

        assertEquals(
            0,
            activityPendingUpdateIncrement(
                previousItems = oldItems,
                currentItems = newItems,
                nearTop = false
            )
        )
    }

    @Test
    fun activityUpdatePresentationDoesNotAutoAnchorTailPagination() {
        val previous =
            listOf(
                activityItem(key = "channel:channel-top"),
                activityItem(key = "channel:channel-next")
            )
        val current = previous + activityItem(key = "channel:older-page")

        assertEquals(
            ActivityUpdatePresentation(pendingMarkers = emptySet()),
            activityUpdatePresentation(
                previousItems = previous,
                currentItems = current
            )
        )
    }

    @Test
    fun activityPendingUpdateLedgerConsumesClickedSnapshotWithoutRecurrence() {
        val first = ActivityUpdateMarker(InboxFilter.All, "channel:first", "message:first")
        val pending = ActivityPendingUpdateLedger().record(setOf(first))

        val consumed = pending.consumePending()

        assertEquals(0, consumed.count)
        assertEquals(consumed, consumed.record(setOf(first)))
    }

    @Test
    fun activityUpdateTrackingTreatsEachFilterAsAnIndependentBaseline() {
        val allInitial = listOf(activityItem(key = "channel:all-old"))
        val allNew =
            listOf(
                activityItem(key = "channel:all-new", latestActivityMessageId = "all-message"),
                activityItem(key = "channel:all-old")
            )
        val unreadInitial = listOf(activityItem(key = "channel:unread-old", unreadCount = 1))
        val unreadNew =
            listOf(
                activityItem(key = "channel:unread-new", latestActivityMessageId = "unread-message", unreadCount = 1),
                activityItem(key = "channel:unread-old", unreadCount = 1)
            )
        val mentionsInitial = listOf(activityItem(key = "thread:mention-old", unreadCount = 1))
        val mentionsNew =
            listOf(
                activityItem(key = "thread:mention-new", latestActivityMessageId = "mention-message", unreadCount = 1),
                activityItem(key = "thread:mention-old", unreadCount = 1)
            )

        val allBaseline = ActivityUpdateTrackingState(InboxFilter.All).recordSnapshot(InboxFilter.All, allInitial)
        val allPending = allBaseline.recordSnapshot(InboxFilter.All, allNew)
        assertEquals(1, allPending.pendingCount)
        assertEquals(setOf(InboxFilter.All), allPending.pendingUpdates.pendingMarkers.map { it.filter }.toSet())

        val unreadBaseline = allPending.recordSnapshot(InboxFilter.Unread, unreadInitial)
        assertEquals(0, unreadBaseline.pendingCount)
        assertEquals(InboxFilter.Unread, unreadBaseline.filter)
        val unreadPending = unreadBaseline.recordSnapshot(InboxFilter.Unread, unreadNew)
        assertEquals(1, unreadPending.pendingCount)
        assertEquals(setOf(InboxFilter.Unread), unreadPending.pendingUpdates.pendingMarkers.map { it.filter }.toSet())

        val mentionsBaseline = unreadPending.recordSnapshot(InboxFilter.Mentions, mentionsInitial)
        assertEquals(0, mentionsBaseline.pendingCount)
        assertEquals(InboxFilter.Mentions, mentionsBaseline.filter)
        val mentionsPending = mentionsBaseline.recordSnapshot(InboxFilter.Mentions, mentionsNew)
        assertEquals(1, mentionsPending.pendingCount)
        assertEquals(setOf(InboxFilter.Mentions), mentionsPending.pendingUpdates.pendingMarkers.map { it.filter }.toSet())
    }

    @Test
    fun activityRoutePresentationIsIsolatedByAccountServerAndFilter() {
        val store = SharedActivityPresentationStore()
        val accountOne = listOf(activityItem(key = "thread:account-one"))
        val accountTwo = listOf(activityItem(key = "thread:account-two"))

        store.save(
            accountId = "account:one",
            serverId = "server:shared",
            filter = InboxFilter.All,
            checkpoint =
                SharedActivityPresentationCheckpoint(
                    presentedItems = accountOne,
                    updateTracking =
                        ActivityUpdateTrackingState(
                            filter = InboxFilter.All,
                            previousItems = accountOne,
                            pendingUpdates =
                                ActivityPendingUpdateLedger(
                                    pendingMarkers =
                                        setOf(
                                            ActivityUpdateMarker(
                                                filter = InboxFilter.All,
                                                itemKey = accountOne.single().key,
                                                latestActivityMessageId = "message:account-one"
                                            )
                                        )
                                )
                        ),
                    presentationGeneration = 9,
                    pullRefreshInFlight = true
                )
        )

        val accountTwoRestored = store.restoreOrCreate("account:two", "server:shared", InboxFilter.All, accountTwo)
        assertEquals(accountTwo.map(InboxItem::key), accountTwoRestored.presentedItems.map(InboxItem::key))
        assertEquals(0, accountTwoRestored.updateTracking.pendingCount)
        assertEquals(0, accountTwoRestored.presentationGeneration)
        assertFalse(accountTwoRestored.pullRefreshInFlight)
        assertEquals(
            accountTwo.map(InboxItem::key),
            store.restoreOrCreate("account:one", "server:other", InboxFilter.All, accountTwo).presentedItems.map(InboxItem::key)
        )
        assertEquals(
            accountTwo.map(InboxItem::key),
            store.restoreOrCreate("account:one", "server:shared", InboxFilter.Unread, accountTwo).presentedItems.map(InboxItem::key)
        )

        val missingOwner = store.restoreOrCreate("", "server:shared", InboxFilter.All, accountTwo)
        assertEquals(accountTwo.map(InboxItem::key), missingOwner.presentedItems.map(InboxItem::key))
        assertEquals(accountTwo.map(InboxItem::key), store.restoreOrCreate("account:one", "server:shared", InboxFilter.All, accountTwo).presentedItems.map(InboxItem::key))
    }

    @Test
    fun activityStableOrderFlagDefaultsOffAndOffRestoresImmediateCanonicalOrder() {
        val first = activityItem(key = "thread:first")
        val lower = activityItem(key = "thread:lower")
        val canonical = listOf(lower, first)

        assertFalse(ServerFeatureFlagSnapshot.Unresolved.isEnabled(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY))
        assertEquals(
            canonical.map(InboxItem::key),
            activityItemsAfterCanonicalSnapshot(
                stableOrderEnabled = false,
                pendingCount = 1,
                pullRefreshCompleted = false,
                presentedItems = listOf(first, lower),
                canonicalItems = canonical
            ).map(InboxItem::key)
        )
        assertEquals(
            listOf(first.key, lower.key),
            activityItemsAfterCanonicalSnapshot(
                stableOrderEnabled = true,
                pendingCount = 1,
                pullRefreshCompleted = false,
                presentedItems = listOf(first, lower),
                canonicalItems = canonical
            ).map(InboxItem::key)
        )
    }

    @Test
    fun activityTimeLabelFormatsLatestActivityBeforeRendering() {
        val item =
            activityItem(
                latestActivityAt = "2026-06-18T09:12:00Z",
                lastMessageAt = "2026-06-18T08:05:00Z"
            )
        val context =
            RaftTimeContext(
                localOffsetMinutes = 480,
                todayEpochDay = raftEpochDayForDate(2026, 6, 18)
            )

        assertEquals("17:12", item.activityTimeLabel(context))
    }

    @Test
    fun activityPreviewSenderNameUsesWireNameAndProfileOverride() {
        val item =
            InboxItem(
                key = "activity-1",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("channel-1"),
                channelName = "general",
                lastMessagePreview = "Latest update",
                lastMessageSenderId = "agent-1",
                lastMessageSenderName = "@agent-one",
                latestActivitySenderId = "agent-1",
                latestActivitySenderName = "@agent-one"
            )

        assertEquals("@agent-one: Latest update", item.primaryPreviewText())
        assertEquals("@agent-one", item.primaryActivityPreviewText().senderName)
        assertEquals(
            "Agent One: Latest update",
            item.primaryPreviewText(
                mapOf(
                    "agent-1" to
                        SharedActivitySenderProfile(
                            displayName = "Agent One"
                        )
                )
            )
        )
    }

    @Test
    fun activityThreadSecondaryPreviewUsesLatestActivitySenderName() {
        val item =
            InboxItem(
                key = "thread:activity-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-1"),
                channelName = "thread-stream",
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview",
                latestActivityPreview = "Latest reply",
                latestActivitySenderName = "Artin",
                replyCount = 2
            )

        assertEquals("Parent message preview", item.primaryPreviewText())
        assertEquals("Artin: Latest reply", item.secondaryPreviewText())
        assertEquals("Artin", item.secondaryActivityPreviewText()?.senderName)
    }

    @Test
    fun activityDmParentThreadRendersPeerIdentityLines() {
        // task #399 飞书 shape: a thread hosted in a DM shows the peer's
        // @-identity in the channel tag and prefixes the root preview with
        // "@peer:", while the second line stays "sender: latest reply".
        val item =
            InboxItem(
                key = "thread:dm-thread-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("dm-thread-1"),
                channelName = "thread-stream",
                parentChannelId = ChannelId("dm-1"),
                // channels.name is not a reliable peer projection for DMs;
                // the hydrated Home DM list name wins.
                parentChannelName = "raw-dm-name",
                parentChannelKind = ConversationKind.DirectMessage,
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "DM root preview",
                latestActivityPreview = "Latest reply",
                latestActivitySenderName = "Artin",
                replyCount = 2
            )
        val dmPeerNames = mapOf("dm-1" to "Jiacheng")

        assertEquals("@Jiacheng", item.channelLabel(dmPeerNames))
        assertEquals("@Jiacheng: DM root preview", item.primaryActivityPreviewText(dmPeerNames = dmPeerNames).fullText)
        assertEquals("@Jiacheng", item.primaryActivityPreviewText(dmPeerNames = dmPeerNames).senderName)
        assertEquals("Artin: Latest reply", item.secondaryActivityPreviewText()?.fullText)
        assertEquals("Jiacheng", item.toConversationSummary(dmPeerNames).name)
    }

    @Test
    fun unfollowedThreadGetsExactlyOneStatusSlotAndNoOrdinaryAttentionSlots() {
        val item =
            InboxItem(
                key = "thread:unfollowed",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-unfollowed"),
                channelName = "thread-stream",
                isFollowing = false,
                replyCount = 4,
                unreadCount = 9,
                hasMention = true,
                firstUnreadMessageId = MessageId("should-clear")
            ).withActivityFollowStateProjection()

        assertEquals(0, item.unreadCount)
        assertFalse(item.hasMention)
        assertEquals(null, item.firstUnreadMessageId)
        assertEquals(
            SharedActivityFooterBadgeVisual(RaftBadgeVariant.Muted, RaftLucideIconName.BellOff),
            SharedActivityFooterSlot.Unfollowed.badgeVisual()
        )
        assertTrue(item.activityShowsDoneAction(), "web keeps Done on unfollowed rows")
        assertTrue(item.copy(isFollowing = true).activityShowsDoneAction())
    }

    @Test
    fun activityContextMenuUsesOnlyFollowForUnfollowedAndOnlyUnfollowForFollowed() {
        val followed =
            InboxItem(
                key = "thread:followed",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-followed"),
                channelName = "thread",
                isFollowing = true
            )
        val unfollowed = followed.copy(key = "thread:unfollowed", isFollowing = false)

        assertEquals(listOf("toggle-read", "done", "unfollow-thread"), activityContextMenuActionIds(followed))
        assertEquals(listOf("done", "follow-thread"), activityContextMenuActionIds(unfollowed))
    }

    @Test
    fun activityContextMenuMutesOnlyJoinedChannelRows() {
        val channel =
            InboxItem(
                key = "channel:general",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("general"),
                channelName = "general",
            )
        val dm = channel.copy(key = "dm:peer", kind = InboxItemKind.DirectMessage, channelId = ChannelId("dm-peer"))

        assertEquals(
            listOf("toggle-read", "done", "mute-activity"),
            activityContextMenuActionIds(channel, mapOf("general" to false)),
        )
        assertEquals(
            listOf("toggle-read", "done", "unmute-activity"),
            activityContextMenuActionIds(channel, mapOf("general" to true)),
        )
        // Not a joined channel of this server (no Home row): no mute, like web `canToggleActivityMute`.
        assertEquals(listOf("toggle-read", "done"), activityContextMenuActionIds(channel, emptyMap()))
        // DMs mute from their own settings, not from Activity.
        assertEquals(listOf("toggle-read", "done"), activityContextMenuActionIds(dm, mapOf("dm-peer" to false)))
    }

    @Test
    fun homeOpenRequestUsesFirstUnreadOnlyWhenRowIsUnread() {
        val item =
            InboxItem(
                key = "channel:general",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("channel-1"),
                channelName = "general",
                unreadCount = 1,
                lastMessageId = MessageId("last-message"),
                latestActivityMessageId = MessageId("activity-message"),
                firstUnreadMessageId = MessageId("first-unread")
            )
        val unreadRequest = item.toHomeMessageOpenRequestForTest()
        val readRequest = item.copy(unreadCount = 0).toHomeMessageOpenRequestForTest()

        assertEquals(MessageId("activity-message"), item.toConversationSummary().lastMessageId)
        assertEquals("first-unread", unreadRequest.anchorMessageId)
        assertEquals("last-message", readRequest.anchorMessageId)
    }

    @Test
    fun homeOpenRequestMatchesReactThreadAnchorContract() {
        val item =
            InboxItem(
                key = "thread:thread-channel-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-1"),
                channelName = "thread-stream",
                threadChannelId = ChannelId("thread-channel-1"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("parent-msg"),
                unreadCount = 1,
                firstUnreadMessageId = MessageId("older-unread-reply"),
                latestActivityMessageId = MessageId("latest-visible-reply"),
                lastMessageId = MessageId("last-reply")
            )
        val unreadRequest = item.toHomeMessageOpenRequestForTest()
        val readRequest =
            item.copy(
                unreadCount = 0,
                firstUnreadMessageId = MessageId("stale-first-unread")
            ).toHomeMessageOpenRequestForTest()

        assertEquals("older-unread-reply", unreadRequest.anchorMessageId)
        assertEquals("latest-visible-reply", readRequest.anchorMessageId)
        assertEquals(threadIdentity(ChannelId("channel-1"), MessageId("parent-msg")), unreadRequest.conversationIdentity.threadId)
    }

    @Test
    fun activityGestureCommitsNewestContentForTheSameRenderedKeyAndGeneration() {
        val rendered = activityItem(key = "thread:stable", latestActivityMessageId = "message:old", unreadCount = 0)
        val updated =
            rendered.copy(
                latestActivityMessageId = MessageId("message:new"),
                firstUnreadMessageId = MessageId("message:new"),
                unreadCount = 2,
            )

        assertEquals(
            updated,
            activityItemForCommittedGesture(
                renderedItemKey = rendered.key,
                renderedPresentationOwnerIdentity = "owner-a",
                renderedPresentationGeneration = 7,
                currentItems = listOf(updated),
                currentPresentationOwnerIdentity = "owner-a",
                currentPresentationGeneration = 7,
            ),
        )
    }

    @Test
    fun activityGestureCancelsWhenCanonicalOrderChangesBeforePointerUp() {
        val first = activityItem(key = "thread:first")
        val second = activityItem(key = "thread:second")

        assertEquals(
            null,
            activityItemForCommittedGesture(
                renderedItemKey = first.key,
                renderedPresentationOwnerIdentity = "owner-a",
                renderedPresentationGeneration = 7,
                currentItems = listOf(second, first),
                currentPresentationOwnerIdentity = "owner-a",
                currentPresentationGeneration = 8,
            ),
        )
    }

    @Test
    fun homeOpenRequestDoesNotFallBackToParentForThreadWithoutReplyId() {
        // Original intent kept: a thread row without reply ids must NOT anchor on
        // parentMessageId (parent origin, not a reply id). Post t5/#419 A3 the row's unknown
        // parentChannelKind additionally means it only resolves via the directory (thread
        // projection + parent channel projection); without one it stays Pending — never a
        // guessed channel-shaped request.
        val item =
            InboxItem(
                key = "thread:thread-channel-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-1"),
                channelName = "thread-stream",
                threadChannelId = ChannelId("thread-channel-1"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview"
            )
        val directory =
            listOf(
                InboxSnapshot(
                    server = ServerSummary(ServerId("server-1"), "Raft"),
                    items = listOf(item)
                ).toSharedConversationDirectoryState(),
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-1",
                        canonicalChannelId = "channel-1",
                        aliases = setOf("channel-1"),
                        conversationKind = ConversationKind.Channel,
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        title = "general"
                    )
                ).toSharedConversationDirectoryState()
            ).mergedSharedConversationDirectoryState()
        val request =
            item.toHomeMessageOpenRequestForTest(serverId = "server-1", conversationDirectory = directory)

        assertEquals(ConversationKind.Thread, request.conversationIdentity.kind)
        assertEquals(threadIdentity(ChannelId("channel-1"), MessageId("message-1")), request.conversationIdentity.threadId)
        assertEquals(RaftThreadSurfaceKind.Channel, request.conversationIdentity.thread?.parentSurfaceKind)
        assertEquals(null, request.anchorMessageId)

        // task #304/#307 (cae1eb2e4): with no directory entry the parent kind is
        // unknown, but the source tuple still opens the reply thread rather than
        // parking it as Pending; the unknown kind stays null on the identity.
        val unresolved =
            item.toHomeMessageOpenRequest(serverId = "", conversationDirectory = SharedConversationDirectoryState())
        val opened = assertIs<ConversationOpenResolution.Resolved>(unresolved, unresolved.toString())
        assertEquals(ConversationKind.Thread, opened.request.conversationIdentity.kind)
        assertEquals(null, opened.request.conversationIdentity.thread?.parentSurfaceKind)
    }

    internal fun activityItem(
        key: String = "activity-1",
        latestActivityAt: String? = null,
        lastMessageAt: String? = null,
        unreadCount: Int = 0,
        firstUnreadMessageId: String? = null,
        latestActivityMessageId: String? = null,
        kind: InboxItemKind = InboxItemKind.Channel,
        lastMessageId: String? = null
    ): InboxItem =
        InboxItem(
            key = key,
            kind = kind,
            channelId = ChannelId("channel-1"),
            channelName = "general",
            unreadCount = unreadCount,
            firstUnreadMessageId = firstUnreadMessageId?.let(::MessageId),
            latestActivityMessageId = latestActivityMessageId?.let(::MessageId),
            lastMessageId = lastMessageId?.let(::MessageId),
            lastMessageAt = lastMessageAt,
            latestActivityAt = latestActivityAt
        )

    private fun replyActivityItem(key: String): InboxItem =
        InboxItem(
            key = "thread:$key",
            kind = InboxItemKind.Thread,
            channelId = ChannelId("parent-$key"),
            channelName = "channel-$key",
            unreadCount = 1,
            threadChannelId = ChannelId("reply-$key"),
            parentChannelId = ChannelId("parent-$key"),
            parentMessageId = MessageId("message-$key")
        )
}
