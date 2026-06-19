package ai.slock.compose

import ai.slock.compose.data.InboxItem
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.SlockTimeContext
import ai.slock.compose.model.slockEpochDayForDate
import ai.slock.compose.model.threadIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedInboxPanelTest {
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
    fun homeOpenRequestUsesLatestActivityMessageAsAnchor() {
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
        assertEquals("activity-message", request.anchorMessageId)
    }

    @Test
    fun homeOpenRequestFallsBackToThreadParentAsAnchor() {
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
        assertEquals("message-1", request.anchorMessageId)
    }

    private fun activityItem(
        latestActivityAt: String? = null,
        lastMessageAt: String? = null
    ): InboxItem =
        InboxItem(
            key = "activity-1",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("channel-1"),
            channelName = "general",
            lastMessageAt = lastMessageAt,
            latestActivityAt = latestActivityAt
        )
}
