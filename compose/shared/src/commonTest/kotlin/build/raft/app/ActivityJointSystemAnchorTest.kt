package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.parseSharedInboxSnapshot
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityJointSystemAnchorTest {
    @Test
    fun jointSystemActivityKeepsExactEventAnchor() {
        val item =
            requireNotNull(
                parseSharedInboxSnapshot(
                    """{"items":[{"kind":"channel","channelId":"joint-1","channelName":"raft-mobile-reconcile","channelType":"joint","unreadCount":1,"firstUnreadMessageId":"system-event","lastMessageId":"system-event","lastMessageSenderType":"system"}]}"""
                )
            ).items.single()
        val directory =
            listOf(
                SharedConversationDirectoryProjection(
                    serverId = "server-1",
                    canonicalChannelId = "joint-1",
                    aliases = setOf("joint-1"),
                    conversationKind = ConversationKind.Joint,
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    channelKind = "joint",
                    title = "raft-mobile-reconcile"
                )
            ).toSharedConversationDirectoryState()

        val jointSystemRequest =
            item.toHomeMessageOpenRequestForTest(
                serverId = "server-1",
                conversationDirectory = directory
            )

        assertEquals(ConversationKind.Joint, jointSystemRequest.conversationIdentity.kind)
        assertEquals("system-event", jointSystemRequest.anchorMessageId)
        assertEquals(
            "system-event",
            item.resolveInboxAnchorMessageId()?.value
        )
        assertEquals(
            "system-event",
            item.copy(kind = InboxItemKind.MentionAction)
                .toHomeMessageOpenRequestForTest("server-1", directory)
                .anchorMessageId
        )
        assertEquals(
            "thread-reply",
            item.copy(
                kind = InboxItemKind.Thread,
                threadChannelId = ChannelId("thread-1"),
                parentChannelId = ChannelId("joint-1"),
                parentChannelKind = ConversationKind.Joint,
                parentMessageId = MessageId("parent-1"),
                firstUnreadMessageId = MessageId("thread-reply"),
                latestActivityMessageId = MessageId("thread-reply")
            ).resolveInboxAnchorMessageId()?.value
        )
    }
}
