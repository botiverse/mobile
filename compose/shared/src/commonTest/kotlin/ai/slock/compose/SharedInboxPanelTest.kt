package ai.slock.compose

import ai.slock.compose.data.InboxItem
import ai.slock.compose.data.PendingMentionAction
import ai.slock.compose.data.SharedActivitySenderProfile
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.SlockTimeContext
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.ThreadId
import ai.slock.compose.model.slockEpochDayForDate
import ai.slock.compose.model.threadIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedInboxPanelTest {
    @Test
    fun activityPendingUpdateIncrementCountsPrependedRowsWhenAwayFromTop() {
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
                previousTopItemKey = "channel:channel-old-top",
                previousUpdateSignature =
                    activityUpdateSignature(
                        listOf(
                            activityItem(key = "channel:channel-old-top"),
                            activityItem(key = "channel:channel-old-next")
                        )
                    ),
                currentItems = items,
                nearTop = false
            )
        )
    }

    @Test
    fun activityPendingUpdateIncrementStaysHiddenNearTop() {
        val items = listOf(activityItem(key = "channel:channel-new"), activityItem(key = "channel:channel-old-top"))

        assertEquals(
            0,
            activityPendingUpdateIncrement(
                previousTopItemKey = "channel:channel-old-top",
                previousUpdateSignature =
                    activityUpdateSignature(listOf(activityItem(key = "channel:channel-old-top"))),
                currentItems = items,
                nearTop = true
            )
        )
    }

    @Test
    fun activityPendingUpdateIncrementUsesSingleFallbackWhenAnchorDisappears() {
        val items = listOf(activityItem(key = "channel:channel-new"))

        assertEquals(
            1,
            activityPendingUpdateIncrement(
                previousTopItemKey = "channel-old-top",
                previousUpdateSignature =
                    activityUpdateSignature(listOf(activityItem(key = "channel-old-top"))),
                currentItems = items,
                nearTop = false
            )
        )
    }

    @Test
    fun activityPendingUpdateIncrementDetectsUnreadChangeWhenTopKeyIsStable() {
        val oldItems =
            listOf(
                activityItem(key = "channel:channel-top", unreadCount = 0),
                activityItem(key = "channel:channel-next", unreadCount = 0)
            )
        val newItems =
            listOf(
                activityItem(key = "channel:channel-top", unreadCount = 1, firstUnreadMessageId = "first-unread"),
                activityItem(key = "channel:channel-next", unreadCount = 0)
            )

        assertEquals(
            1,
            activityPendingUpdateIncrement(
                previousTopItemKey = "channel:channel-top",
                previousUpdateSignature = activityUpdateSignature(oldItems),
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
                previousTopItemKey = "channel:channel-top",
                previousUpdateSignature = activityUpdateSignature(items),
                currentItems = items,
                nearTop = false
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
                            kind = MemberKind.Agent,
                            displayName = "Agent One",
                            avatarWireValue = null
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

        val request = item.toHomeMessageOpenRequest()
        assertEquals("message-1", request.anchorMessageId)
        assertEquals(ConversationKind.Channel, request.conversation.kind)
        assertEquals("android-dev", request.conversation.name)
        assertEquals(0, request.conversation.unreadCount)
        assertEquals(0, request.conversation.mentionCount)
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
    fun homeOpenRequestUsesFirstUnreadAsAnchorWhenPresent() {
        // Phase 3 #3 #1 (A): anchor chain walks firstUnreadMessageId -> latestActivityMessageId
        // -> lastMessageId regardless of kind or unreadCount. When firstUnreadMessageId is
        // present (regardless of unreadCount bucket), it wins. This replaces the old
        // unread-aware chain that picked latestActivityMessageId for unreadCount == 0
        // (反例 11 / #280 audit). Channel / DirectMessage / System share the same 3-level chain.
        val request =
            InboxItem(
                key = "channel:general",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("channel-1"),
                channelName = "general",
                lastMessageId = MessageId("last-message"),
                latestActivityMessageId = MessageId("activity-message"),
                firstUnreadMessageId = MessageId("first-unread")
        ).toHomeMessageOpenRequest()

        assertEquals(MessageId("activity-message"), request.conversation.lastMessageId)
        assertEquals("first-unread", request.anchorMessageId)
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
        // Thread item without firstUnreadMessageId / latestActivityMessageId / lastMessageId
        // must NOT silently fall back to parentMessageId. That field is the parent channel
        // origin (not a reply id) and mixing it into the anchor chain degrades the reply-stream
        // anchor (see #275-style contract escapes and the v0.2 thread contract).
        val request =
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
            ).toHomeMessageOpenRequest()

        assertEquals(ConversationKind.Thread, request.conversation.kind)
        assertEquals(threadIdentity(ChannelId("channel-1"), MessageId("message-1")), request.conversation.threadId)
        assertEquals(null, request.anchorMessageId)
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
    fun inboxPanelThreadItemResolveAnchorMessageId3LevelChain() {
        // 反例 11 regression: Thread item anchor must walk firstUnreadMessageId ->
        // latestActivityMessageId -> lastMessageId and never fall back to parentMessageId.
        // The latter is the parent channel origin, not a thread reply id.
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
        assertEquals(MessageId("last-reply"), onlyLast.resolveInboxAnchorMessageId())

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
        firstUnreadMessageId: String? = null
    ): InboxItem =
        InboxItem(
            key = key,
            kind = InboxItemKind.Channel,
            channelId = ChannelId("channel-1"),
            channelName = "general",
            unreadCount = unreadCount,
            firstUnreadMessageId = firstUnreadMessageId?.let(::MessageId),
            lastMessageAt = lastMessageAt,
            latestActivityAt = latestActivityAt
        )
}
