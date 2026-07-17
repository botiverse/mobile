package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.PendingMentionAction
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.mergedSharedConversationDirectoryState
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.SharedActivitySenderProfile
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
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SharedInboxPanelTest {
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

        assertEquals("activity-done:channel:first", first.activityDoneClickIdentity())
        assertNotEquals(first.activityDoneClickIdentity(), second.activityDoneClickIdentity())
        assertEquals(
            first.activityDoneClickIdentity(),
            first.copy(lastMessagePreview = "display-only update").activityDoneClickIdentity()
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
                clickedIndex = 1,
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
            activityReplyThreadPrewarmNextAfterClick(items, clickedIndex = -1, serverId = "server-1")
        )
        assertEquals(
            emptyList(),
            activityReplyThreadPrewarmNextAfterClick(items, clickedIndex = items.size, serverId = "server-1")
        )
    }

    @Test
    fun activityPageDidAppearRefreshesOnlyAfterInitialAppearance() {
        assertEquals(false, activityPageDidAppearShouldRefresh(0))
        assertEquals(true, activityPageDidAppearShouldRefresh(1))
    }

    @Test
    fun activityPullRefreshKeepsRowsStationaryAndMovesProgressToHeader() {
        assertEquals(
            ActivityRefreshPresentation(
                headerRefreshing = true,
                pullRefreshing = true
            ),
            activityRefreshPresentation(refreshing = true, pullRefreshing = true)
        )
        assertEquals(
            ActivityRefreshPresentation(
                headerRefreshing = true,
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
            ActivityUpdatePresentation(pendingIncrement = 0, shouldAutoAnchorTop = false),
            activityUpdatePresentation(
                previousItems = previous,
                currentItems = current,
                nearTop = true,
                isScrollInProgress = false
            )
        )
    }

    @Test
    fun activityUpdatePresentationAutoAnchorsGenuineTopUpdateWhenParked() {
        val previous = listOf(activityItem(key = "channel:channel-old-top"))
        val current =
            listOf(
                activityItem(key = "channel:channel-new"),
                activityItem(key = "channel:channel-old-top")
            )

        assertEquals(
            ActivityUpdatePresentation(pendingIncrement = 0, shouldAutoAnchorTop = true),
            activityUpdatePresentation(
                previousItems = previous,
                currentItems = current,
                nearTop = true,
                isScrollInProgress = false
            )
        )
    }

    @Test
    fun activityUpdatePresentationDefersTopUpdateDuringUserScroll() {
        val previous = listOf(activityItem(key = "channel:channel-old-top"))
        val current =
            listOf(
                activityItem(key = "channel:channel-new"),
                activityItem(key = "channel:channel-old-top")
            )

        assertEquals(
            ActivityUpdatePresentation(pendingIncrement = 1, shouldAutoAnchorTop = false),
            activityUpdatePresentation(
                previousItems = previous,
                currentItems = current,
                nearTop = true,
                isScrollInProgress = true
            )
        )
    }

    @Test
    fun activityUpdatePresentationDoesNotAutoAnchorReadStateMutation() {
        val previous = listOf(activityItem(key = "channel:channel-top", unreadCount = 1))
        val current = listOf(activityItem(key = "channel:channel-top", unreadCount = 0))

        assertEquals(
            ActivityUpdatePresentation(pendingIncrement = 0, shouldAutoAnchorTop = false),
            activityUpdatePresentation(
                previousItems = previous,
                currentItems = current,
                nearTop = true,
                isScrollInProgress = false
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
        assertEquals(FontWeight.Normal, senderSpan.item.fontWeight)
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
                SharedActivityFooterSlot.Replies,
                SharedActivityFooterSlot.Task,
                SharedActivityFooterSlot.Mention,
                SharedActivityFooterSlot.New
            ),
            item.activityFooterSlots()
        )
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
            listOf(SharedActivityFooterSlot.Replies, SharedActivityFooterSlot.Task),
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

        val unresolved =
            item.toHomeMessageOpenRequest(serverId = "", conversationDirectory = SharedConversationDirectoryState())
        assertTrue(unresolved is ConversationOpenResolution.Pending)
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

    private fun activityItem(
        key: String = "activity-1",
        latestActivityAt: String? = null,
        lastMessageAt: String? = null,
        unreadCount: Int = 0,
        firstUnreadMessageId: String? = null,
        latestActivityMessageId: String? = null
    ): InboxItem =
        InboxItem(
            key = key,
            kind = InboxItemKind.Channel,
            channelId = ChannelId("channel-1"),
            channelName = "general",
            unreadCount = unreadCount,
            firstUnreadMessageId = firstUnreadMessageId?.let(::MessageId),
            latestActivityMessageId = latestActivityMessageId?.let(::MessageId),
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
