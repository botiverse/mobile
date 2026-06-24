package ai.slock.compose

import ai.slock.compose.data.InboxItem
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.MessageId
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedHomeSavedActionsTest {
    @Test
    fun savedPermalinkUsesChannelRouteForChannelMessage() {
        val item =
            InboxItem(
                key = "saved-channel",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("channel-1"),
                channelName = "general",
                lastMessageId = MessageId("message-1")
            )

        assertEquals(
            "https://app.raft.build/s/raft/channel/channel-1?msg=message-1",
            sharedHomeSavedPermalink(item, "raft")
        )
    }

    @Test
    fun savedPermalinkUsesDmRouteForDmMessage() {
        val item =
            InboxItem(
                key = "saved-dm",
                kind = InboxItemKind.DirectMessage,
                channelId = ChannelId("dm-1"),
                channelName = "artin",
                lastMessageId = MessageId("message-2")
            )

        assertEquals(
            "https://app.raft.build/s/raft/dm/dm-1?msg=message-2",
            sharedHomeSavedPermalink(item, "raft")
        )
    }

    @Test
    fun savedPermalinkKeepsThreadOnParentChannelRoute() {
        val item =
            InboxItem(
                key = "saved-thread",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-1"),
                channelName = "thread",
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("parent-1"),
                latestActivityMessageId = MessageId("reply-1")
            )

        assertEquals(
            "https://app.raft.build/s/raft/channel/channel-1?msg=reply-1&thread=channel-1%3Aparent-1",
            sharedHomeSavedPermalink(item, "raft")
        )
    }
}
