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
import kotlin.test.assertNull
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

        assertEquals(build.raft.app.data.SharedActivityIntent.MarkRead(opened, fromOpen = true), opened.activityOpenReadIntent())
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
    fun activityContextMenuMutesJoinedChannelsAndEligibleDirectMessages() {
        val channel =
            InboxItem(
                key = "channel:general",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("general"),
                channelName = "general",
            )
        val dm = channel.copy(key = "dm:peer", kind = InboxItemKind.DirectMessage, channelId = ChannelId("dm-peer"))
        val appBotDm = channel.copy(key = "dm:app-bot", kind = InboxItemKind.DirectMessage, channelId = ChannelId("dm-app-bot"))

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
        // Ordinary human/agent DMs are absent from `activityMuteByChannelId`: no mute item.
        assertEquals(listOf("toggle-read", "done"), activityContextMenuActionIds(dm, mapOf("general" to false)))
        // App-bot DMs (present in `activityMuteByChannelId` via server `activityMuteSupported`) mirror channels.
        assertEquals(
            listOf("toggle-read", "done", "mute-activity"),
            activityContextMenuActionIds(appBotDm, mapOf("dm-app-bot" to false)),
        )
        assertEquals(
            listOf("toggle-read", "done", "unmute-activity"),
            activityContextMenuActionIds(appBotDm, mapOf("dm-app-bot" to true)),
        )

        // Menu order v4 (botiverse/slock #16225): Mute / Unmute Activity sits in its own group.
        assertEquals(
            listOf("toggle-read" to false, "done" to false, "mute-activity" to true),
            activityContextMenuItems(channel, mapOf("general" to false)) { it }.map { it.id to it.dividerBefore },
        )
        assertEquals(
            listOf("toggle-read" to false, "done" to false, "unmute-activity" to true),
            activityContextMenuItems(channel, mapOf("general" to true)) { it }.map { it.id to it.dividerBefore },
        )
        assertEquals(
            listOf("toggle-read" to false, "done" to false, "mute-activity" to true),
            activityContextMenuItems(appBotDm, mapOf("dm-app-bot" to false)) { it }.map { it.id to it.dividerBefore },
        )
        assertEquals(
            listOf("toggle-read" to false, "done" to false),
            activityContextMenuItems(channel, emptyMap()) { it }.map { it.id to it.dividerBefore },
        )
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
                latestActivityMessageId = MessageId("agent-unfollow-line"),
                lastMessageId = MessageId("last-reply")
            )
        val unreadRequest = item.toHomeMessageOpenRequestForTest()
        val readItem =
            item.copy(
                unreadCount = 0,
                firstUnreadMessageId = MessageId("stale-first-unread")
            )
        val readRequest = readItem.toHomeMessageOpenRequestForTest()
        val unreadWithoutFirst =
            item.copy(
                unreadCount = 2,
                firstUnreadMessageId = null
            )
        val unreadWithoutFirstRequest = unreadWithoutFirst.toHomeMessageOpenRequestForTest()

        assertEquals(MessageId("older-unread-reply"), item.activityThreadUnreadFocusId())
        assertNull(readItem.activityThreadUnreadFocusId())
        assertNull(unreadWithoutFirst.activityThreadUnreadFocusId())
        assertEquals("older-unread-reply", unreadRequest.anchorMessageId)
        // slock #16908: read thread rows (and unread rows without firstUnreadMessageId) open at
        // latest replies with no focus, never focusing latestActivityMessageId.
        assertNull(readRequest.anchorMessageId)
        assertNull(unreadWithoutFirstRequest.anchorMessageId)
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
