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
import build.raft.app.data.PendingMentionAction
import build.raft.app.data.SharedActivityState
import build.raft.app.data.ServerFeatureFlagSnapshot
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.mergedSharedConversationDirectoryState
import build.raft.app.data.realtime.SlockRealtimeNotifyContract
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.SharedActivitySenderProfile
import build.raft.app.data.SharedActivityPresentationCheckpoint
import build.raft.app.data.SharedActivityPresentationStore
import build.raft.app.components.SlockBrutalTone
import build.raft.app.data.activityPendingUpdateIncrement
import build.raft.app.data.activityPendingUpdateMarkers
import build.raft.app.data.activityUpdatePresentation
import build.raft.app.data.activityUpdateRevisionMessageId
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.TaskStatus
import build.raft.app.model.ThreadId
import build.raft.app.model.slockEpochDayForDate
import build.raft.app.model.threadIdentity
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    fun activitySuppressedInternalJointRowReappearsWhenSourceBecomesLocal() {
        val unsafe =
            replyActivityItem("restored").copy(
                parentChannelId = ChannelId("joint-storage-parent"),
                parentChannelKind = ConversationKind.Joint,
                unreadCount = 1
            )
        val server = ServerSummary(ServerId("server-1"), "Raft")
        val hidden =
            projectActivityStateWithoutUnopenableInternalJointRows(
                SharedActivityState(snapshot = InboxSnapshot(server = server, items = listOf(unsafe)))
            )
        val corrected =
            unsafe.copy(
                parentChannelId = ChannelId("joint-local-parent"),
                parentChannelName = "member-visible-joint"
            )
        val restored =
            projectActivityStateWithoutUnopenableInternalJointRows(
                SharedActivityState(snapshot = InboxSnapshot(server = server, items = listOf(corrected)))
            )

        assertTrue(hidden.snapshot?.items.orEmpty().isEmpty())
        assertEquals(listOf("thread:restored"), restored.snapshot?.items?.map(InboxItem::key))
        assertEquals(1, restored.totalCount)
        assertEquals(1, restored.totalUnreadCount)
    }

    @Test
    fun openingActivityRowsLeavesReadAcknowledgementToTheDestinationStore() {
        val opened = activityItem(key = "thread:opened").copy(unreadCount = 2)
        val promotedWhileNavigating = activityItem(key = "thread:promoted").copy(unreadCount = 1)

        assertEquals(null, opened.activityOpenReadIntent())
        assertEquals(null, promotedWhileNavigating.activityOpenReadIntent())
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
    fun activityTailSentinelDoesNotDuplicateAnInFlightRequest() {
        assertFalse(
            activityShouldRequestLoadMore(
                hasMore = true,
                refreshing = false,
                loadingMore = true,
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
    fun activityTailSentinelStopsAtTerminalPage() {
        assertFalse(
            activityShouldRequestLoadMore(
                hasMore = false,
                refreshing = false,
                loadingMore = false,
                loadMoreErrorCode = null
            )
        )
    }

    @Test
    fun activityTailSentinelGatesRepeatedRefreshesAndRearmsAtTheTerminalState() {
        assertFalse(
            activityShouldRequestLoadMore(
                hasMore = true,
                refreshing = true,
                loadingMore = false,
                loadMoreErrorCode = null
            )
        )
        assertFalse(
            activityShouldRequestLoadMore(
                hasMore = true,
                refreshing = true,
                loadingMore = false,
                loadMoreErrorCode = null
            )
        )
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
    fun activityTailSentinelOnlyShowsLoadingForARealInFlightRequest() {
        assertEquals(
            ActivityLoadMoreFooterMode.Sentinel,
            activityLoadMoreFooterMode(loadingMore = false, loadMoreErrorCode = null)
        )
        assertEquals(
            ActivityLoadMoreFooterMode.Loading,
            activityLoadMoreFooterMode(loadingMore = true, loadMoreErrorCode = null)
        )
        assertEquals(
            ActivityLoadMoreFooterMode.Retry,
            activityLoadMoreFooterMode(loadingMore = false, loadMoreErrorCode = "Network")
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
    fun activityDoneClickIdentityTracksLazyRowIdentity() {
        val first = activityItem(key = "channel:first")
        val second = activityItem(key = "channel:second")
        val owner = activityPresentationOwnerIdentity("account-a", "server-a", InboxFilter.All)

        assertEquals(
            "activity-done:channel:first|owner:$owner|presentation:0",
            first.activityDoneClickIdentity(presentationOwnerIdentity = owner),
        )
        assertNotEquals(
            first.activityDoneClickIdentity(presentationOwnerIdentity = owner),
            second.activityDoneClickIdentity(presentationOwnerIdentity = owner),
        )
        assertEquals(
            first.activityDoneClickIdentity(presentationOwnerIdentity = owner),
            first.copy(lastMessagePreview = "display-only update")
                .activityDoneClickIdentity(presentationOwnerIdentity = owner),
        )
        assertNotEquals(
            first.activityDoneClickIdentity(0, owner),
            first.activityDoneClickIdentity(1, owner),
        )
        assertNotEquals(
            first.activityDoneClickIdentity(0, owner),
            first.activityDoneClickIdentity(
                0,
                activityPresentationOwnerIdentity("account-b", "server-a", InboxFilter.All),
            ),
        )
    }

    @Test
    fun replyPrewarmMarkerRequiresVisiblePlannerEligibleUnreadThreadIdentity() {
        val unreadReply =
            InboxItem(
                key = "thread:parent-message",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("parent-channel"),
                channelName = "general",
                unreadCount = 2,
                firstUnreadMessageId = MessageId("reply-1"),
                latestActivityMessageId = MessageId("reply-2"),
                threadChannelId = ChannelId("reply-thread"),
                parentChannelId = ChannelId("parent-channel"),
                parentMessageId = MessageId("parent-message")
            )

        val marker = unreadReply.toReplyThreadPrewarmMarker("server-1")

        assertEquals("server-1", marker?.key?.serverId)
        assertEquals("parent-channel", marker?.key?.parentChannelId)
        assertEquals("parent-message", marker?.key?.parentMessageId)
        assertEquals("reply-thread", marker?.key?.threadChannelId)
        assertEquals(null, marker?.contentGeneration)
        assertEquals(null, unreadReply.copy(unreadCount = 0).toReplyThreadPrewarmMarker("server-1"))
        assertEquals(null, unreadReply.copy(kind = InboxItemKind.Channel).toReplyThreadPrewarmMarker("server-1"))
        assertEquals(null, unreadReply.copy(threadChannelId = null).toReplyThreadPrewarmMarker("server-1"))
        assertEquals(null, unreadReply.toReplyThreadPrewarmMarker(""))
    }

    @Test
    fun activityReplyPrewarmStartsOnlyTheNextEligibleRowAfterClick() {
        val previous = replyActivityItem("previous")
        val clicked = activityItem(key = "clicked")
        val next = replyActivityItem("next")
        val ineligibleRead = replyActivityItem("read").copy(unreadCount = 0)
        val later = replyActivityItem("later")

        val selectedPrewarm =
            activityReplyThreadPrewarmNextAfterClick(
                items = listOf(previous, clicked, next, ineligibleRead, later),
                clickedKey = clicked.key,
                serverId = "server-1"
            )

        assertEquals(
            listOf("reply-next"),
            selectedPrewarm.map { marker -> marker.key.threadChannelId }
        )
    }

    @Test
    fun activityReplyPrewarmTailDoesNotWarmBeforeAValidClick() {
        val items = listOf(replyActivityItem("first"), replyActivityItem("second"))

        assertEquals(
            emptyList(),
            activityReplyThreadPrewarmNextAfterClick(items, clickedKey = "missing", serverId = "server-1")
        )
        assertEquals(
            emptyList(),
            activityReplyThreadPrewarmNextAfterClick(emptyList(), clickedKey = "first", serverId = "server-1")
        )
    }

    @Test
    fun activityPageDidAppearRefreshesOnlyAfterInitialAppearance() {
        assertEquals(false, activityPageDidAppearShouldRefresh(0))
        assertEquals(true, activityPageDidAppearShouldRefresh(1))
    }

    @Test
    fun activityAppLifecycleResumeRefreshesOnlyOnVisibleWireEvents() {
        // Android publishes "resume", iOS publishes "show" (task #383).
        assertEquals(
            true,
            activityAppLifecycleResumeShouldRefresh(
                action = SlockRealtimeNotifyContract.ActionAppLifecycle,
                lifecycleEvent = SlockRealtimeNotifyContract.LifecycleVisibleResume
            )
        )
        assertEquals(
            true,
            activityAppLifecycleResumeShouldRefresh(
                action = SlockRealtimeNotifyContract.ActionAppLifecycle,
                lifecycleEvent = SlockRealtimeNotifyContract.LifecycleVisibleShow
            )
        )
        assertEquals(
            false,
            activityAppLifecycleResumeShouldRefresh(
                action = SlockRealtimeNotifyContract.ActionAppLifecycle,
                lifecycleEvent = SlockRealtimeNotifyContract.LifecycleHidden
            )
        )
        assertEquals(
            false,
            activityAppLifecycleResumeShouldRefresh(
                action = "realtime",
                lifecycleEvent = SlockRealtimeNotifyContract.LifecycleVisibleResume
            )
        )
        assertEquals(
            false,
            activityAppLifecycleResumeShouldRefresh(action = "", lifecycleEvent = "")
        )
    }

    @Test
    fun activityOnlyExplicitPullRefreshOwnsVisibleHeaderProgress() {
        assertEquals(
            ActivityRefreshPresentation(
                headerRefreshing = true,
                pullRefreshing = true
            ),
            activityRefreshPresentation(refreshing = true, pullRefreshing = true)
        )
        assertEquals(
            ActivityRefreshPresentation(
                headerRefreshing = false,
                pullRefreshing = false
            ),
            activityRefreshPresentation(refreshing = true, pullRefreshing = false)
        )
        assertEquals(
            ActivityRefreshPresentation(
                headerRefreshing = false,
                pullRefreshing = false
            ),
            activityRefreshPresentation(refreshing = false, pullRefreshing = false)
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
    fun activityListDoesNotRecoverAtTopOrForAnEmptyFilterResult() {
        assertEquals(
            false,
            activityListShouldRecoverVisibleContent(
                firstVisibleItemIndex = 0,
                visibleItemKeys = emptyList(),
                currentItemKeys = listOf("channel:remaining")
            )
        )
        assertEquals(
            false,
            activityListShouldRecoverVisibleContent(
                firstVisibleItemIndex = 4,
                visibleItemKeys = listOf("channel:removed"),
                currentItemKeys = emptyList()
            )
        )
    }

    @Test
    fun activityListDoesNotRecoverWhenOnlyTheLoadMoreFooterIsVisible() {
        assertEquals(
            false,
            activityListShouldRecoverVisibleContent(
                firstVisibleItemIndex = 12,
                visibleItemKeys = listOf(ACTIVITY_LOAD_MORE_ROW_KEY),
                currentItemKeys = listOf("channel:remaining-1", "channel:remaining-2")
            )
        )
    }

    @Test
    fun activityListDoesNotRecoverWhileLayoutReportsNoVisibleRows() {
        assertEquals(
            false,
            activityListShouldRecoverVisibleContent(
                firstVisibleItemIndex = 7,
                visibleItemKeys = emptyList(),
                currentItemKeys = listOf("channel:remaining-1", "channel:remaining-2")
            )
        )
    }

    @Test
    fun activityPendingUpdateIncrementStaysHiddenNearTop() {
        val items = listOf(activityItem(key = "channel:channel-new"), activityItem(key = "channel:channel-old-top"))

        assertEquals(
            0,
            activityPendingUpdateIncrement(
                previousItems = listOf(activityItem(key = "channel:channel-old-top")),
                currentItems = items,
                nearTop = true
            )
        )
    }

    @Test
    fun activityPendingUpdateIncrementCountsReplacementRowWhenAnchorDisappears() {
        val items = listOf(activityItem(key = "channel:channel-new"))

        assertEquals(
            1,
            activityPendingUpdateIncrement(
                previousItems = listOf(activityItem(key = "channel-old-top")),
                currentItems = items,
                nearTop = false
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
    fun activityPendingUpdateIncrementCountsAdvancedLatestActivityMessage() {
        val oldItems =
            listOf(
                activityItem(key = "channel:channel-top", latestActivityMessageId = "msg-1"),
                activityItem(key = "channel:channel-next")
            )
        val newItems =
            listOf(
                activityItem(key = "channel:channel-top", latestActivityMessageId = "msg-2"),
                activityItem(key = "channel:channel-next")
            )

        assertEquals(
            1,
            activityPendingUpdateIncrement(
                previousItems = oldItems,
                currentItems = newItems,
                nearTop = false
            )
        )
    }

    @Test
    fun activityPendingUpdateIncrementIgnoresStableTopWindow() {
        val items =
            listOf(
                activityItem(key = "channel:channel-top", unreadCount = 1, firstUnreadMessageId = "first-unread"),
                activityItem(key = "channel:channel-next", unreadCount = 0)
            )

        assertEquals(
            0,
            activityPendingUpdateIncrement(
                previousItems = items,
                currentItems = items,
                nearTop = false
            )
        )
    }

    @Test
    fun activityPendingUpdateIncrementIgnoresLocalRemovalWhenOldTopDisappears() {
        val oldItems =
            listOf(
                activityItem(key = "channel:channel-top"),
                activityItem(key = "channel:channel-next")
            )
        val newItems = listOf(activityItem(key = "channel:channel-next"))

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
    fun activityPendingUpdateIncrementIgnoresRowsRevealedBelowKnownRows() {
        // Newest-first feed: a genuinely new update can never sort below an
        // untouched known row, so unseen keys under a known one are reveals
        // (tail-fill after removals / window shifts) — not new activity.
        val oldItems =
            listOf(
                activityItem(key = "channel:channel-top"),
                activityItem(key = "thread:checked-thread"),
                activityItem(key = "channel:channel-next")
            )
        val newItems =
            listOf(
                activityItem(key = "channel:channel-top"),
                activityItem(key = "channel:channel-next"),
                activityItem(key = "channel:tail-fill")
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
    fun activityUpdatePresentationDefersGenuineTopUpdateEvenWhenParked() {
        val previous = listOf(activityItem(key = "channel:channel-old-top"))
        val current =
            listOf(
                activityItem(key = "channel:channel-new"),
                activityItem(key = "channel:channel-old-top")
            )

        assertEquals(
            ActivityUpdatePresentation(
                pendingMarkers =
                    setOf(
                        ActivityUpdateMarker(
                            filter = InboxFilter.All,
                            itemKey = "channel:channel-new",
                            latestActivityMessageId = null
                        )
                    )
            ),
            activityUpdatePresentation(
                previousItems = previous,
                currentItems = current
            )
        )
    }

    @Test
    fun activityUpdatePresentationDoesNotAutoAnchorReadStateMutation() {
        val previous = listOf(activityItem(key = "channel:channel-top", unreadCount = 1))
        val current = listOf(activityItem(key = "channel:channel-top", unreadCount = 0))

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
    fun activityPendingUpdateLedgerPreservesArrivalAfterRevealStarts() {
        val first = ActivityUpdateMarker(InboxFilter.All, "channel:first", "message:first")
        val second = ActivityUpdateMarker(InboxFilter.All, "channel:second", "message:second")

        val revealStarted =
            ActivityPendingUpdateLedger()
                .record(setOf(first))
                .consumePending()
        val laterArrival = revealStarted.record(setOf(second))

        assertEquals(1, laterArrival.count)
        assertEquals(setOf(second), laterArrival.pendingMarkers)
    }

    @Test
    fun activityPendingUpdateLedgerAllowsNewMessageForRetiredConversation() {
        val first = ActivityUpdateMarker(InboxFilter.All, "channel:same", "message:first")
        val second = ActivityUpdateMarker(InboxFilter.All, "channel:same", "message:second")

        val laterArrival =
            ActivityPendingUpdateLedger()
                .record(setOf(first))
                .consumePending()
                .record(setOf(second))

        assertEquals(setOf(second), laterArrival.pendingMarkers)
    }

    @Test
    fun activityPendingUpdateLedgerSecondBannerRemainsActionable() {
        val first = ActivityUpdateMarker(InboxFilter.All, "channel:first", "message:first")
        val second = ActivityUpdateMarker(InboxFilter.All, "channel:second", "message:second")
        val laterArrival =
            ActivityPendingUpdateLedger()
                .record(setOf(first))
                .consumePending()
                .record(setOf(second))

        val secondRevealStarted = laterArrival.consumePending()

        assertEquals(0, secondRevealStarted.count)
        assertEquals(setOf(first, second), secondRevealStarted.retiredMarkers)
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
    fun activityUpdateTrackingConsumesEachFilterWithoutCarryingRetiredMarkersAcrossTabs() {
        val allMarker = activityItem(key = "channel:same", latestActivityMessageId = "message:same")
        val allConsumed =
            ActivityUpdateTrackingState(InboxFilter.All)
                .recordSnapshot(InboxFilter.All, listOf(activityItem(key = "channel:old")))
                .recordSnapshot(InboxFilter.All, listOf(allMarker, activityItem(key = "channel:old")))
                .consumePending()

        val unreadBaseline = allConsumed.recordSnapshot(InboxFilter.Unread, listOf(activityItem(key = "channel:unread-old")))
        val unreadPending =
            unreadBaseline.recordSnapshot(
                InboxFilter.Unread,
                listOf(allMarker, activityItem(key = "channel:unread-old"))
            )

        assertEquals(1, unreadPending.pendingCount)
        assertEquals(
            setOf(ActivityUpdateMarker(InboxFilter.Unread, "channel:same", "message:same")),
            unreadPending.pendingUpdates.pendingMarkers
        )
    }

    @Test
    fun activityUpdateTrackingClickContractHoldsForAllUnreadAndMentions() {
        InboxFilter.entries.forEach { filter ->
            val old = activityItem(key = "channel:same", latestActivityMessageId = "message:old", unreadCount = 1)
            val first = activityItem(key = "channel:same", latestActivityMessageId = "message:first", unreadCount = 1)
            val later = activityItem(key = "channel:same", latestActivityMessageId = "message:later", unreadCount = 1)

            val baseline = ActivityUpdateTrackingState(filter).recordSnapshot(filter, listOf(old))
            val firstPending = baseline.recordSnapshot(filter, listOf(first))
            assertEquals(1, firstPending.pendingCount, "$filter first banner")

            val consumed = firstPending.consumePending()
            assertEquals(0, consumed.pendingCount, "$filter click consumes its snapshot")
            val stable = consumed.recordSnapshot(filter, listOf(first))
            assertEquals(0, stable.pendingCount, "$filter consumed snapshot never recurs")

            val laterPending = stable.recordSnapshot(filter, listOf(later))
            assertEquals(1, laterPending.pendingCount, "$filter later message remains actionable")
            assertEquals(
                setOf(ActivityUpdateMarker(filter, "channel:same", "message:later")),
                laterPending.pendingUpdates.pendingMarkers
            )
        }
    }

    @Test
    fun activityUpdateTrackingKeepsOrderChangesPendingUntilExplicitReveal() {
        InboxFilter.entries.forEach { filter ->
            val old = activityItem(key = "channel:old", latestActivityMessageId = "message:old", unreadCount = 1)
            val visibleArrivals =
                (1..8).map { index ->
                    activityItem(
                        key = "channel:new-$index",
                        latestActivityMessageId = "message:new-$index",
                        unreadCount = 1
                    )
                }
            val current = visibleArrivals + old

            val pending =
                ActivityUpdateTrackingState(filter)
                    .recordSnapshot(filter, listOf(old))
                    .recordSnapshot(filter, current)
            assertEquals(8, pending.pendingCount, "$filter records the arrivals before layout catches up")

            val reconciled =
                pending.reconcileCanonicalSnapshot(
                    nextFilter = filter,
                    currentItems = current
                )
            assertEquals(8, reconciled.pendingCount, "$filter keeps order changes pending until the user reveals them")
            assertEquals(0, reconciled.consumePending().pendingCount, "$filter explicit reveal consumes the batch")

            val refreshedWithoutArrivals =
                pending.reconcileCanonicalSnapshot(
                    nextFilter = filter,
                    currentItems = listOf(old)
                )
            assertEquals(0, refreshedWithoutArrivals.pendingCount, "$filter refresh must drop markers absent from its baseline")
        }
    }

    @Test
    fun activityPresentedOrderKeepsLowerReplyInItsOriginalSlotUntilReveal() {
        val first = activityItem(key = "channel:first", latestActivityMessageId = "message:first")
        val lower =
            activityItem(
                key = "thread:lower",
                latestActivityMessageId = "message:old",
                kind = InboxItemKind.Thread
            )
        val third = activityItem(key = "channel:third", latestActivityMessageId = "message:third")
        val updatedLower = lower.copy(latestActivityMessageId = MessageId("message:new"), unreadCount = 1)
        val canonical = listOf(updatedLower, first, third)

        val held =
            activityItemsPreservingPresentedOrder(
                presentedItems = listOf(first, lower, third),
                canonicalItems = canonical
            )

        assertEquals(listOf(first.key, lower.key, third.key), held.map(InboxItem::key))
        assertEquals(MessageId("message:new"), held[1].latestActivityMessageId)
        assertEquals(1, held.count { it.key == lower.key })
        assertEquals(listOf(lower.key, first.key, third.key), canonical.map(InboxItem::key))
    }

    @Test
    fun activityForegroundRefreshKeepsLowerSelfSendInItsOriginalSlotUntilReveal() {
        val filter = InboxFilter.All
        val first =
            activityItem(
                key = "thread:first",
                latestActivityMessageId = "message:first",
                kind = InboxItemKind.Thread
            )
        val lower =
            activityItem(
                key = "thread:lower",
                latestActivityMessageId = "message:old",
                kind = InboxItemKind.Thread
            )
        val third =
            activityItem(
                key = "thread:third",
                latestActivityMessageId = "message:third",
                kind = InboxItemKind.Thread
            )
        val selfSend =
            lower.copy(
                latestActivityMessageId = MessageId("message:self-send"),
                latestActivityPreview = "sent by me"
            )
        val canonicalAfterThreadResume = listOf(selfSend, first, third)
        val tracking =
            ActivityUpdateTrackingState(filter)
                .recordSnapshot(filter, listOf(first, lower, third))
                .recordSnapshot(filter, canonicalAfterThreadResume)

        assertEquals(1, tracking.pendingCount)
        assertFalse(
            activityShouldAdoptCanonicalOrder(
                pendingCount = tracking.pendingCount,
                pullRefreshCompleted = false
            )
        )

        val held =
            activityItemsPreservingPresentedOrder(
                presentedItems = listOf(first, lower, third),
                canonicalItems = canonicalAfterThreadResume
            )

        assertEquals(listOf(first.key, lower.key, third.key), held.map(InboxItem::key))
        assertEquals(MessageId("message:self-send"), held[1].latestActivityMessageId)
        assertEquals(1, held.count { it.key == lower.key })
        assertTrue(
            activityShouldAdoptCanonicalOrder(
                pendingCount = tracking.pendingCount,
                pullRefreshCompleted = true
            )
        )
        assertTrue(
            activityShouldAdoptCanonicalOrder(
                pendingCount = 0,
                pullRefreshCompleted = false
            )
        )
    }

    @Test
    fun activityRouteRecreationRestoresPresentedOrderBeforeRecordingSelfSend() {
        val filter = InboxFilter.All
        val serverId = "server:route-retained"
        val first = activityItem(key = "thread:first", latestActivityMessageId = "message:first", kind = InboxItemKind.Thread)
        val lower = activityItem(key = "thread:lower", latestActivityMessageId = "message:old", kind = InboxItemKind.Thread)
        val third = activityItem(key = "thread:third", latestActivityMessageId = "message:third", kind = InboxItemKind.Thread)
        val baseline = listOf(first, lower, third)
        val selfSend = lower.copy(latestActivityMessageId = MessageId("message:self-send"))
        val canonicalAfterRouteReturn = listOf(third, first, selfSend)
        val presentationStore = SharedActivityPresentationStore()

        val beforeRoute = presentationStore.restoreOrCreate("account:one", serverId, filter, baseline)
        presentationStore.save(
            "account:one",
            serverId,
            filter,
            SharedActivityPresentationCheckpoint(
                presentedItems = beforeRoute.presentedItems,
                updateTracking = beforeRoute.updateTracking
            )
        )

        val restored = presentationStore.restoreOrCreate("account:one", serverId, filter, canonicalAfterRouteReturn)
        val trackingAfterReturn =
            restored.updateTracking
                .recordSnapshot(filter, canonicalAfterRouteReturn)
                .reconcileCanonicalSnapshot(filter, canonicalAfterRouteReturn)
        val held = activityItemsPreservingPresentedOrder(restored.presentedItems, canonicalAfterRouteReturn)

        assertEquals(listOf(first.key, lower.key, third.key), restored.presentedItems.map(InboxItem::key))
        assertEquals(1, trackingAfterReturn.pendingCount)
        assertEquals(listOf(first.key, lower.key, third.key), held.map(InboxItem::key))
        assertEquals(MessageId("message:self-send"), held[1].latestActivityMessageId)
        assertFalse(activityShouldAdoptCanonicalOrder(trackingAfterReturn.pendingCount, pullRefreshCompleted = false))
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
    fun activityStableOrderFlagOffClearsAllOwnerFilterCheckpoints() {
        val store = SharedActivityPresentationStore()
        val filters = listOf(InboxFilter.All, InboxFilter.Unread, InboxFilter.Mentions)
        filters.forEachIndexed { index, filter ->
            val retained = listOf(activityItem(key = "thread:retained:$filter"))
            store.save(
                accountId = "account:one",
                serverId = "server:one",
                filter = filter,
                checkpoint =
                    SharedActivityPresentationCheckpoint(
                        presentedItems = retained,
                        updateTracking =
                            ActivityUpdateTrackingState(
                                filter = filter,
                                previousItems = retained,
                                pendingUpdates =
                                    ActivityPendingUpdateLedger(
                                        pendingMarkers = setOf(ActivityUpdateMarker(filter, retained.single().key, "message:$index"))
                                    )
                            ),
                        presentationGeneration = index + 1,
                        pullRefreshInFlight = true
                    )
            )
        }

        store.clearOwner("account:one", "server:one")

        filters.forEach { filter ->
            val canonical = listOf(activityItem(key = "thread:canonical:$filter"))
            val restored = store.restoreOrCreate("account:one", "server:one", filter, canonical)
            assertEquals(canonical.map(InboxItem::key), restored.presentedItems.map(InboxItem::key))
            assertEquals(0, restored.updateTracking.pendingCount)
            assertEquals(0, restored.presentationGeneration)
            assertFalse(restored.pullRefreshInFlight)
        }
    }

    @Test
    fun activityStableOrderDecisionChangeAdoptsCanonicalAndClearsPendingPresentationState() {
        val first = activityItem(key = "thread:first")
        val second = activityItem(key = "thread:second")
        val promoted = activityItem(key = "thread:promoted")

        val checkpoint =
            activityCanonicalCheckpointAfterStableOrderDecisionChange(
                selectedFilter = InboxFilter.All,
                canonicalItems = listOf(promoted, first, second),
                previousPresentedItems = listOf(first, second),
                previousPresentationGeneration = 4,
            )

        assertEquals(listOf(promoted.key, first.key, second.key), checkpoint.presentedItems.map(InboxItem::key))
        assertEquals(0, checkpoint.updateTracking.pendingCount)
        assertEquals(5, checkpoint.presentationGeneration)
        assertFalse(checkpoint.pullRefreshInFlight)
        assertNotEquals(
            first.activityOpenGestureKey(4),
            first.activityOpenGestureKey(checkpoint.presentationGeneration),
        )
    }

    @Test
    fun activityStableOrderEnableStartsAtCanonicalThenHoldsTheNextUpdate() {
        val first = activityItem(key = "thread:first")
        val second = activityItem(key = "thread:second")
        val enabledCheckpoint =
            activityCanonicalCheckpointAfterStableOrderDecisionChange(
                selectedFilter = InboxFilter.All,
                canonicalItems = listOf(first, second),
                previousPresentedItems = listOf(second, first),
                previousPresentationGeneration = 2,
            )
        val newHead = activityItem(key = "thread:new")
        val nextCanonical = listOf(newHead, first, second)
        val nextTracking =
            enabledCheckpoint.updateTracking.recordSnapshot(InboxFilter.All, nextCanonical)

        assertEquals(1, nextTracking.pendingCount)
        assertEquals(
            listOf(first.key, second.key),
            activityItemsAfterCanonicalSnapshot(
                stableOrderEnabled = true,
                pendingCount = nextTracking.pendingCount,
                pullRefreshCompleted = false,
                presentedItems = enabledCheckpoint.presentedItems,
                canonicalItems = nextCanonical,
            ).map(InboxItem::key),
        )
    }

    @Test
    fun activityChannelAndDmLastMessageRevisionRemainHeldUntilReveal() {
        listOf(InboxItemKind.Channel, InboxItemKind.DirectMessage).forEach { kind ->
            val filter = InboxFilter.All
            val first = activityItem(key = "$kind:first", kind = kind, lastMessageId = "message:first")
            val lower = activityItem(key = "$kind:lower", kind = kind, lastMessageId = "message:old")
            val third = activityItem(key = "$kind:third", kind = kind, lastMessageId = "message:third")
            val updatedLower = lower.copy(lastMessageId = MessageId("message:self-send"))
            val canonical = listOf(updatedLower, first, third)
            val tracking =
                ActivityUpdateTrackingState(filter)
                    .recordSnapshot(filter, listOf(first, lower, third))
                    .recordSnapshot(filter, canonical)

            assertEquals(1, tracking.pendingCount, "$kind lastMessageId advance is a pending activity revision")
            val held =
                activityItemsPreservingPresentedOrder(
                    presentedItems = listOf(first, lower, third),
                    canonicalItems = canonical
                )
            assertEquals(listOf(first.key, lower.key, third.key), held.map(InboxItem::key), "$kind stays in slot")
            assertEquals(MessageId("message:self-send"), held[1].lastMessageId)
            assertEquals(1, held.count { it.key == lower.key })
            assertFalse(activityShouldAdoptCanonicalOrder(tracking.pendingCount, pullRefreshCompleted = false))
            assertTrue(activityShouldAdoptCanonicalOrder(tracking.consumePending().pendingCount, pullRefreshCompleted = false))
        }
    }

    @Test
    fun activityPresentedOrderWithholdsBrandNewHeadUntilReveal() {
        val first = activityItem(key = "channel:first")
        val second = activityItem(key = "channel:second")
        val newHead = activityItem(key = "channel:new", latestActivityMessageId = "message:new")

        val held =
            activityItemsPreservingPresentedOrder(
                presentedItems = listOf(first, second),
                canonicalItems = listOf(newHead, first, second)
            )

        assertEquals(listOf(first.key, second.key), held.map(InboxItem::key))
        assertEquals(listOf(newHead.key, first.key, second.key), listOf(newHead, first, second).map(InboxItem::key))
    }

    @Test
    fun activityPresentationGenerationChangesOnlyWhenVisibleKeyOrderCommits() {
        val first = activityItem(key = "channel:first")
        val second = activityItem(key = "channel:second")
        val updatedSecond = second.copy(latestActivityMessageId = MessageId("message:new"))

        assertEquals(
            7,
            activityNextPresentationGeneration(
                currentGeneration = 7,
                presentedItems = listOf(first, second),
                nextPresentedItems = listOf(first, updatedSecond)
            )
        )
        assertEquals(
            8,
            activityNextPresentationGeneration(
                currentGeneration = 7,
                presentedItems = listOf(first, second),
                nextPresentedItems = listOf(updatedSecond, first)
            )
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
            SlockTimeContext(
                localOffsetMinutes = 480,
                todayEpochDay = slockEpochDayForDate(2026, 6, 18)
            )

        assertEquals("17:12", item.activityTimeLabel(context))
    }

    @Test
    fun activityTimeLabelFormatsWhenHostOmitsTodayEpochDay() {
        val item =
            activityItem(
                latestActivityAt = "2026-06-18T09:12:00Z",
                lastMessageAt = "2026-06-18T08:05:00Z"
            )
        val context =
            SlockTimeContext(
                localOffsetMinutes = 480,
                nowEpochMillis = 1781760000000L
            )

        assertEquals("17:12", item.activityTimeLabel(context))
    }

    @Test
    fun activityTimeLabelFormatsLastMessageFallbackBeforeRendering() {
        val item =
            activityItem(
                latestActivityAt = null,
                lastMessageAt = "2026-06-17T23:05:00Z"
            )
        val context =
            SlockTimeContext(
                localOffsetMinutes = 480,
                todayEpochDay = slockEpochDayForDate(2026, 6, 18)
            )

        assertEquals("07:05", item.activityTimeLabel(context))
    }

    @Test
    fun activityTimeLabelKeepsRawValueWhenContextIsMissing() {
        val raw = "2026-06-18T09:12:00Z"

        assertEquals(raw, activityItem(latestActivityAt = raw).activityTimeLabel(null))
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
    fun activityPreviewSenderSpanUsesSlockBoldFontWithoutSyntheticWeight() {
        val item =
            InboxItem(
                key = "activity-1",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("channel-1"),
                channelName = "general",
                lastMessagePreview = "Latest update",
                lastMessageSenderName = "Artin"
            )

        val annotated = item.primaryActivityPreviewText().toAnnotatedString()
        val senderSpan = annotated.spanStyles.single()

        assertEquals("Artin: Latest update", annotated.text)
        assertEquals(0, senderSpan.start)
        assertEquals("Artin: ".length, senderSpan.end)
        assertEquals(SlockType.ButtonBold.fontFamily, senderSpan.item.fontFamily)
        assertEquals(SlockType.ButtonBold.fontWeight, senderSpan.item.fontWeight)
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
    fun activityDmParentThreadFallsBackToRawParentNameWithoutPeerMap() {
        // Before the DM list hydrate lands (or when the DM is absent from the
        // Home snapshot) the row still shows the @-identity shape; it only
        // loses the peer-resolution quality, never the DM-vs-channel marker.
        val item =
            InboxItem(
                key = "thread:dm-thread-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("dm-thread-1"),
                channelName = "thread-stream",
                parentChannelId = ChannelId("dm-1"),
                parentChannelName = "raw-dm-name",
                parentChannelKind = ConversationKind.DirectMessage,
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "DM root preview"
            )

        assertEquals("@raw-dm-name", item.channelLabel())
        assertEquals("@raw-dm-name: DM root preview", item.primaryActivityPreviewText().fullText)
        assertEquals("raw-dm-name", item.toConversationSummary().name)
    }

    @Test
    fun activityChannelParentThreadKeepsHashTagAndBareRootPreview() {
        val item =
            InboxItem(
                key = "thread:activity-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-1"),
                channelName = "thread-stream",
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview",
                latestActivityPreview = "Latest reply",
                latestActivitySenderName = "Artin",
                replyCount = 2
            )
        val dmPeerNames = mapOf("channel-1" to "wrong-peer")

        assertEquals("#general", item.channelLabel(dmPeerNames))
        assertEquals("Parent message preview", item.primaryActivityPreviewText(dmPeerNames = dmPeerNames).fullText)
        assertEquals(null, item.primaryActivityPreviewText(dmPeerNames = dmPeerNames).senderName)
        assertEquals("Artin: Latest reply", item.secondaryActivityPreviewText()?.fullText)
    }

    @Test
    fun activityFooterSlotsMatchReactOrderForThreadRows() {
        val item =
            InboxItem(
                key = "thread:activity-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-1"),
                channelName = "thread-stream",
                replyCount = 12,
                taskNumber = 177,
                taskStatus = TaskStatus.InProgress,
                hasMention = true,
                unreadCount = 3
            )

        assertEquals(
            listOf(
                SharedActivityFooterSlot.Task,
                SharedActivityFooterSlot.Replies,
                SharedActivityFooterSlot.Mention,
                SharedActivityFooterSlot.New
            ),
            item.activityFooterSlots()
        )
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
        assertEquals(listOf(SharedActivityFooterSlot.Replies, SharedActivityFooterSlot.Unfollowed), item.activityFooterSlots())
        assertEquals(
            SharedActivityFooterBadgeVisual(SlockBrutalTone.Stone, SlockLucideIconName.BellOff),
            SharedActivityFooterSlot.Unfollowed.badgeVisual()
        )
        assertFalse(item.activityShowsDoneAction())
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
        assertEquals(listOf("follow-thread"), activityContextMenuActionIds(unfollowed))
    }

    @Test
    fun activityFooterSlotsProjectEveryMetadataKindToSquareBadgeVisuals() {
        assertEquals(
            SharedActivityFooterBadgeVisual(
                tone = SlockBrutalTone.Cyan,
                leadingIcon = SlockLucideIconName.Play,
                foregroundColor = TaskStatus.InProgress.sharedTaskStatusChipMeta().foregroundColor
            ),
            SharedActivityFooterSlot.Task.badgeVisual(TaskStatus.InProgress)
        )
        assertEquals(
            SharedActivityFooterBadgeVisual(SlockBrutalTone.White),
            SharedActivityFooterSlot.Replies.badgeVisual()
        )
        assertEquals(
            SharedActivityFooterBadgeVisual(SlockBrutalTone.Yellow, SlockLucideIconName.AtSign),
            SharedActivityFooterSlot.Mention.badgeVisual()
        )
        assertEquals(
            SharedActivityFooterBadgeVisual(SlockBrutalTone.Pink),
            SharedActivityFooterSlot.New.badgeVisual()
        )
        assertEquals(
            SharedActivityFooterBadgeVisual(SlockBrutalTone.White, SlockLucideIconName.Pencil),
            SharedActivityFooterSlot.Draft.badgeVisual()
        )
    }

    @Test
    fun activityTaskBadgeVisualKeepsSharedStatusColors() {
        assertEquals(SlockBrutalTone.Orange, SharedActivityFooterSlot.Task.badgeVisual(TaskStatus.Todo).tone)
        assertEquals(SlockBrutalTone.Cyan, SharedActivityFooterSlot.Task.badgeVisual(TaskStatus.InProgress).tone)
        assertEquals(SlockBrutalTone.Lavender, SharedActivityFooterSlot.Task.badgeVisual(TaskStatus.InReview).tone)
        assertEquals(SlockBrutalTone.Lime, SharedActivityFooterSlot.Task.badgeVisual(TaskStatus.Done).tone)
        assertEquals(SlockBrutalTone.Stone, SharedActivityFooterSlot.Task.badgeVisual(TaskStatus.Closed).tone)
    }

    @Test
    fun activityTaskBadgeForegroundUsesConversationTaskChipToken() {
        TaskStatus.entries.forEach { status ->
            assertEquals(
                status.sharedTaskStatusChipMeta().foregroundColor,
                SharedActivityFooterSlot.Task.badgeVisual(status).foregroundColor,
                "foreground drift for $status"
            )
        }
    }

    @Test
    fun activityFooterSlotsPutDraftPencilAtRowBottomEnd() {
        val item =
            InboxItem(
                key = "thread:activity-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-1"),
                channelName = "thread-stream",
                replyCount = 12,
                hasMention = true,
                unreadCount = 3,
                hasDraft = true
            )

        assertEquals(
            listOf(
                SharedActivityFooterSlot.Replies,
                SharedActivityFooterSlot.Mention,
                SharedActivityFooterSlot.New,
                SharedActivityFooterSlot.Draft
            ),
            item.activityFooterSlots()
        )
    }

    @Test
    fun activityFooterSlotsHideTaskWhenTaskStatusBadgesDisabled() {
        val item =
            InboxItem(
                key = "thread:activity-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-1"),
                channelName = "thread-stream",
                replyCount = 12,
                taskNumber = 177,
                taskStatus = TaskStatus.InProgress,
                hasMention = true,
                unreadCount = 3
            )

        assertEquals(
            listOf(
                SharedActivityFooterSlot.Replies,
                SharedActivityFooterSlot.Mention,
                SharedActivityFooterSlot.New
            ),
            item.activityFooterSlots(activityTaskStatusBadgesEnabled = false)
        )
    }

    @Test
    fun activityThreadTaskBadgeUsesSingleLineReactLabel() {
        val item =
            InboxItem(
                key = "thread:activity-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-1"),
                channelName = "thread-stream",
                replyCount = 12,
                taskNumber = 177,
                taskStatus = TaskStatus.InReview,
                taskClaimedByName = "Claude Assistant"
            )

        assertEquals("#177 @Claude Assistant", item.activityTaskBadgeText())
        assertEquals(
            listOf(SharedActivityFooterSlot.Task, SharedActivityFooterSlot.Replies),
            item.activityFooterSlots()
        )
    }

    @Test
    fun activityMentionActionRowUsesSenderSideActionShape() {
        val item =
            InboxItem(
                key = "mention_action:resolution-1",
                kind = InboxItemKind.MentionAction,
                channelId = ChannelId("channel-1"),
                channelName = "android-dev",
                channelKind = ConversationKind.Channel,
                lastMessageId = MessageId("message-1"),
                lastMessagePreview = "Can someone ping @missing-human?",
                lastMessageAt = "2026-06-26T02:40:00Z",
                unreadCount = 0,
                hasMention = false,
                mentionActionId = "resolution-1",
                pendingMentionActions =
                    listOf(
                        PendingMentionAction(
                            resolutionId = "resolution-1",
                            messageId = MessageId("message-1"),
                            channelId = ChannelId("channel-1"),
                            channelName = "android-dev",
                            targetType = "user",
                            targetHandle = "missing-human",
                            availableActions = listOf("notify", "invite")
                        )
                    )
            )

        assertEquals("Can someone ping @missing-human?", item.primaryPreviewText())
        assertEquals(null, item.primaryActivityPreviewText().senderName)
        assertEquals(emptyList(), item.activityFooterSlots())

        val request = item.toHomeMessageOpenRequestForTest()
        assertEquals("message-1", request.anchorMessageId)
        assertEquals(ConversationKind.Channel, request.conversationIdentity.kind)
        val rowSummary = item.toConversationSummary()
        assertEquals("android-dev", rowSummary.name)
        assertEquals(0, rowSummary.unreadCount)
        assertEquals(0, rowSummary.mentionCount)
    }

    @Test
    fun activityThreadItemKeepsThreadKindWhenParentChannelKindIsChannel() {
        val conversation =
            InboxItem(
                key = "thread:thread-channel-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-1"),
                channelName = "thread-stream",
                threadChannelId = ChannelId("thread-channel-1"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview",
                latestActivityPreview = "Latest reply"
            ).toConversationSummary()

        assertEquals(ConversationKind.Thread, conversation.kind)
        assertEquals(threadIdentity(ChannelId("channel-1"), MessageId("message-1")), conversation.threadId)
        assertEquals(ChannelId("channel-1"), conversation.channelId)
        assertEquals(ChannelId("thread-channel-1"), conversation.thread?.streamChannelId)
        assertEquals(ChannelId("channel-1"), conversation.thread?.parentChannelId)
        assertEquals(MessageId("message-1"), conversation.thread?.parentMessageId)
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
    fun homeOpenRequestMatchesReactChannelAnchorContract() {
        val item =
            InboxItem(
                key = "channel:general",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("channel-1"),
                channelName = "general",
                unreadCount = 2,
                firstUnreadMessageId = MessageId("first-unread"),
                latestActivityMessageId = MessageId("activity-message"),
                lastMessageId = MessageId("last-message")
            )
        val unreadRequest = item.toHomeMessageOpenRequestForTest()
        val readRequest =
            item.copy(
                unreadCount = 0,
                firstUnreadMessageId = MessageId("stale-first-unread")
            ).toHomeMessageOpenRequestForTest()

        assertEquals("first-unread", unreadRequest.anchorMessageId)
        assertEquals("last-message", readRequest.anchorMessageId)
    }

    @Test
    fun homeOpenRequestLaunchIdentityIgnoresRowActivitySummaryChanges() {
        val base =
            InboxItem(
                key = "channel:general",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("channel-1"),
                channelName = "general",
                lastMessageId = MessageId("last-message-1"),
                lastMessagePreview = "First preview",
                lastMessageAt = "2026-06-27T10:00:00Z",
                lastMessageSenderName = "Alice",
                unreadCount = 1,
                firstUnreadMessageId = MessageId("first-unread-1")
            )
        val updated =
            base.copy(
                lastMessageId = MessageId("last-message-2"),
                lastMessagePreview = "Second preview",
                lastMessageAt = "2026-06-27T10:05:00Z",
                lastMessageSenderName = "Bob",
                unreadCount = 0,
                firstUnreadMessageId = MessageId("first-unread-2")
            )

        assertNotEquals(base.toConversationSummary(), updated.toConversationSummary())
        assertEquals(base.toHomeMessageOpenRequestForTest().conversationIdentity, updated.toHomeMessageOpenRequestForTest().conversationIdentity)
        assertEquals("first-unread-1", base.toHomeMessageOpenRequestForTest().anchorMessageId)
        assertEquals("last-message-2", updated.toHomeMessageOpenRequestForTest().anchorMessageId)
    }

    @Test
    fun inboxItemOpenGestureKeyChangesWhenStableThreadRowTargetsNewReply() {
        val base =
            InboxItem(
                key = "thread:reply-stream",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("reply-stream"),
                channelName = "visual-testing",
                threadChannelId = ChannelId("reply-stream"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "visual-testing",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("parent-message"),
                latestActivityMessageId = MessageId("reply-1"),
                latestActivityPreview = "old reply",
                latestActivityAt = "2026-07-04T14:00:00Z"
            )
        val updated =
            base.copy(
                latestActivityMessageId = MessageId("reply-2"),
                latestActivityPreview = "new reply",
                latestActivityAt = "2026-07-04T14:05:00Z"
            )

        assertEquals(base.key, updated.key)
        assertEquals(base.toHomeMessageOpenRequestForTest().conversationIdentity, updated.toHomeMessageOpenRequestForTest().conversationIdentity)
        assertEquals("reply-1", base.toHomeMessageOpenRequestForTest().anchorMessageId)
        assertEquals("reply-2", updated.toHomeMessageOpenRequestForTest().anchorMessageId)
        assertNotEquals(base.inboxItemOpenGestureKey(), updated.inboxItemOpenGestureKey())
    }

    @Test
    fun inboxItemOpenGestureKeyIgnoresDisplayOnlyPreviewChanges() {
        val base =
            InboxItem(
                key = "thread:reply-stream",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("reply-stream"),
                channelName = "visual-testing",
                threadChannelId = ChannelId("reply-stream"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "visual-testing",
                parentMessageId = MessageId("parent-message"),
                latestActivityMessageId = MessageId("reply-1"),
                latestActivityPreview = "old text",
                latestActivitySenderName = "Alice"
            )
        val updated =
            base.copy(
                latestActivityPreview = "new text",
                latestActivitySenderName = "Bob"
            )

        assertEquals(base.inboxItemOpenGestureKey(), updated.inboxItemOpenGestureKey())
    }

    @Test
    fun activityOpenGestureKeyChangesWhenPresentedOrderGenerationChanges() {
        val item = activityItem(key = "channel:stable", latestActivityMessageId = "message:old", unreadCount = 0)
        val owner = activityPresentationOwnerIdentity("account-a", "server-a", InboxFilter.All)
        val updatedSameSlot =
            item.copy(
                latestActivityMessageId = MessageId("message:new"),
                firstUnreadMessageId = MessageId("message:new"),
                unreadCount = 3
            )

        assertEquals(
            item.activityOpenGestureKey(3, owner),
            updatedSameSlot.activityOpenGestureKey(3, owner),
        )
        assertNotEquals(
            item.activityOpenGestureKey(3, owner),
            item.activityOpenGestureKey(4, owner),
        )
        assertNotEquals(
            item.activityOpenGestureKey(3, owner),
            item.activityOpenGestureKey(
                3,
                activityPresentationOwnerIdentity("account-a", "server-a", InboxFilter.Unread),
            ),
        )
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
    fun activityGestureCancelsWhenRenderedKeyIsRemovedBeforePointerUp() {
        val rendered = activityItem(key = "thread:removed")
        val replacement = activityItem(key = "thread:replacement")

        assertEquals(
            null,
            activityItemForCommittedGesture(
                renderedItemKey = rendered.key,
                renderedPresentationOwnerIdentity = "owner-a",
                renderedPresentationGeneration = 7,
                currentItems = listOf(replacement),
                currentPresentationOwnerIdentity = "owner-a",
                currentPresentationGeneration = 7,
            ),
        )
    }

    @Test
    fun activityGestureCancelsAcrossAccountOrServerOwnerSwitchWithTheSameKeyAndGeneration() {
        val sharedKey = activityItem(key = "thread:shared")
        val accountA = activityPresentationOwnerIdentity("account-a", "server-a", InboxFilter.All)
        val accountB = activityPresentationOwnerIdentity("account-b", "server-a", InboxFilter.All)
        val serverB = activityPresentationOwnerIdentity("account-a", "server-b", InboxFilter.All)

        listOf(accountB, serverB).forEach { currentOwner ->
            assertEquals(
                null,
                activityItemForCommittedGesture(
                    renderedItemKey = sharedKey.key,
                    renderedPresentationOwnerIdentity = accountA,
                    renderedPresentationGeneration = 0,
                    currentItems = listOf(sharedKey),
                    currentPresentationOwnerIdentity = currentOwner,
                    currentPresentationGeneration = 0,
                ),
            )
            assertEquals(
                sharedKey,
                activityItemForCommittedGesture(
                    renderedItemKey = sharedKey.key,
                    renderedPresentationOwnerIdentity = currentOwner,
                    renderedPresentationGeneration = 0,
                    currentItems = listOf(sharedKey),
                    currentPresentationOwnerIdentity = currentOwner,
                    currentPresentationGeneration = 0,
                ),
            )
        }
    }

    @Test
    fun activityGestureCancelsAcrossFilterSwitchWithTheSameKeyAndGeneration() {
        val sharedKey = activityItem(key = "thread:shared")
        val all = activityPresentationOwnerIdentity("account-a", "server-a", InboxFilter.All)
        val unread = activityPresentationOwnerIdentity("account-a", "server-a", InboxFilter.Unread)

        assertEquals(
            null,
            activityItemForCommittedGesture(
                renderedItemKey = sharedKey.key,
                renderedPresentationOwnerIdentity = all,
                renderedPresentationGeneration = 0,
                currentItems = listOf(sharedKey),
                currentPresentationOwnerIdentity = unread,
                currentPresentationGeneration = 0,
            ),
        )
        assertEquals(
            sharedKey,
            activityItemForCommittedGesture(
                renderedItemKey = sharedKey.key,
                renderedPresentationOwnerIdentity = unread,
                renderedPresentationGeneration = 0,
                currentItems = listOf(sharedKey),
                currentPresentationOwnerIdentity = unread,
                currentPresentationGeneration = 0,
            ),
        )
    }

    @Test
    fun threadLaunchIdentityIgnoresDisplayAccessDirectoryPresentationAndStatusFields() {
        val base =
            ConversationSummary(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel,
                name = "general",
                subtitle = "Team channel",
                joined = true,
                archived = false,
                jointChannelId = "joint-1",
                jointRole = "owner",
                permissions = ConversationPermissions(canRead = true, canSend = true, canCreateTask = true),
                presenceMemberId = MemberId("member-1"),
                memberCount = 12,
                jointPeerServerId = ServerId("server-1"),
                jointPeerServerName = "Server One",
                jointPeerServerSlug = "server-one",
                jointPeerStatus = "online"
            )
        val updated =
            base.copy(
                name = "renamed",
                subtitle = "Renamed channel",
                joined = false,
                archived = true,
                jointChannelId = "joint-2",
                jointRole = "guest",
                permissions = ConversationPermissions(canRead = true, canSend = false, canCreateTask = false),
                presenceMemberId = MemberId("member-2"),
                memberCount = 42,
                jointPeerServerId = ServerId("server-2"),
                jointPeerServerName = "Server Two",
                jointPeerServerSlug = "server-two",
                jointPeerStatus = "offline"
            )

        assertNotEquals(base, updated)
        assertEquals(base.toThreadLaunchConversationIdentity(), updated.toThreadLaunchConversationIdentity())
    }

    @Test
    fun threadItemWithoutExplicitParentDoesNotInferParentFromChannelId() {
        val conversation =
            InboxItem(
                key = "thread:thread-channel-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-1"),
                channelName = "thread-stream",
                threadChannelId = ChannelId("thread-channel-1"),
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview"
            ).toConversationSummary()

        assertEquals(ConversationKind.Thread, conversation.kind)
        assertEquals(ThreadId("thread-channel-1"), conversation.threadId)
        assertEquals(null, conversation.channelId)
        assertEquals(null, conversation.thread)
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
                        surfaceKind = KuiklyThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        title = "general"
                    )
                ).toSharedConversationDirectoryState()
            ).mergedSharedConversationDirectoryState()
        val request =
            item.toHomeMessageOpenRequestForTest(serverId = "server-1", conversationDirectory = directory)

        assertEquals(ConversationKind.Thread, request.conversationIdentity.kind)
        assertEquals(threadIdentity(ChannelId("channel-1"), MessageId("message-1")), request.conversationIdentity.threadId)
        assertEquals(KuiklyThreadSurfaceKind.Channel, request.conversationIdentity.thread?.parentSurfaceKind)
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

    @Test
    fun inboxPanelThreadItemResolveStreamChannelIdReplacesChannelFallback() {
        // 反例 1 regression: Thread item `toConversationSummary()` must use the explicit
        // `threadChannelId` as `ThreadConversation.streamChannelId`, not fall back to
        // `threadChannelId ?: channelId` (the prior silent fallback). For non-thread items,
        // the helper resolves to null and `ThreadConversation` is null.
        val threadItem =
            InboxItem(
                key = "thread:thread-channel-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-1"),
                channelName = "thread-stream",
                threadChannelId = ChannelId("thread-channel-explicit"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentMessageId = MessageId("message-1")
            )
        assertEquals(ChannelId("thread-channel-explicit"), threadItem.resolveInboxStreamChannelId())

        val channelItem =
            InboxItem(
                key = "channel:general",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("channel-1"),
                channelName = "general"
            )
        assertEquals(null, channelItem.resolveInboxStreamChannelId())
    }

    @Test
    fun inboxPanelThreadItemResolveAnchorMessageIdMatchesReactContract() {
        val threadItem =
            InboxItem(
                key = "thread:thread-channel-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-1"),
                channelName = "thread-stream",
                threadChannelId = ChannelId("thread-channel-1"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentMessageId = MessageId("parent-msg"),
                unreadCount = 1,
                firstUnreadMessageId = MessageId("unread-reply"),
                latestActivityMessageId = MessageId("latest-reply"),
                lastMessageId = MessageId("last-reply")
            )
        assertEquals(MessageId("unread-reply"), threadItem.resolveInboxAnchorMessageId())

        val onlyLatest =
            threadItem.copy(
                key = "thread-only-latest",
                firstUnreadMessageId = null
            )
        assertEquals(MessageId("latest-reply"), onlyLatest.resolveInboxAnchorMessageId())

        val onlyLast =
            threadItem.copy(
                key = "thread-only-last",
                firstUnreadMessageId = null,
                latestActivityMessageId = null
            )
        assertEquals(null, onlyLast.resolveInboxAnchorMessageId())

        val readWithStaleFirstUnread =
            threadItem.copy(
                key = "thread-read-stale-first",
                unreadCount = 0
            )
        assertEquals(MessageId("latest-reply"), readWithStaleFirstUnread.resolveInboxAnchorMessageId())

        val noneButParent =
            threadItem.copy(
                key = "thread-none-but-parent",
                firstUnreadMessageId = null,
                latestActivityMessageId = null,
                lastMessageId = null
            )
        // Critical: parentMessageId must NOT appear in the anchor chain.
        assertEquals(null, noneButParent.resolveInboxAnchorMessageId())
    }

    @Test
    fun projectActivityStateForFilterPageReturnsActiveStateWhenFilterMatches() {
        val snapshot = InboxSnapshot(ServerSummary(ServerId("srv-1"), "Raft"), items = listOf(replyActivityItem("1")))
        val state = SharedActivityState(selectedFilter = InboxFilter.All, snapshot = snapshot, loaded = true)
        val result = projectActivityStateForFilterPage(state, InboxFilter.All)
        assertEquals(state, result)
        assertEquals(snapshot, result.snapshot)
        assertEquals(true, result.loaded)
    }

    @Test
    fun projectActivityStateForFilterPageProjectsCachedSnapshotWhenDifferentFilter() {
        val allSnapshot = InboxSnapshot(ServerSummary(ServerId("srv-1"), "Raft"), items = listOf(replyActivityItem("1")))
        val unreadSnapshot = InboxSnapshot(ServerSummary(ServerId("srv-1"), "Raft"), items = listOf(replyActivityItem("2")))
        val state = SharedActivityState(
            selectedFilter = InboxFilter.All,
            snapshot = allSnapshot,
            filterSnapshots = mapOf(
                InboxFilter.All to allSnapshot,
                InboxFilter.Unread to unreadSnapshot
            )
        )
        val result = projectActivityStateForFilterPage(state, InboxFilter.Unread)
        assertEquals(InboxFilter.Unread, result.selectedFilter)
        assertEquals(unreadSnapshot, result.snapshot)
        assertEquals(true, result.loaded)
        assertEquals(false, result.loading)
    }

    @Test
    fun projectActivityStateForFilterPageReturnsLoadingStateWhenNoCachedSnapshot() {
        val allSnapshot = InboxSnapshot(ServerSummary(ServerId("srv-1"), "Raft"), items = listOf(replyActivityItem("1")))
        val state = SharedActivityState(
            selectedFilter = InboxFilter.All,
            snapshot = allSnapshot,
            filterSnapshots = mapOf(InboxFilter.All to allSnapshot)
        )
        val result = projectActivityStateForFilterPage(state, InboxFilter.Mentions)
        assertEquals(InboxFilter.Mentions, result.selectedFilter)
        assertEquals(null, result.snapshot)
        assertEquals(false, result.loaded)
        assertEquals(true, result.loading)
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
