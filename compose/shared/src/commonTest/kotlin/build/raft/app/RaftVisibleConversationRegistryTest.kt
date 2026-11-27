package build.raft.app

import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.thread.RaftVisibleConversationRegistry
import build.raft.app.thread.RaftVisibleConversationSnapshot
import build.raft.app.thread.raftSystemNotificationPushEventOrNull
import build.raft.app.thread.targetsForegroundVisibleConversation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftVisibleConversationRegistryTest {
    @AfterTest
    fun tearDown() {
        RaftVisibleConversationRegistry.unregister("test-owner")
        RaftVisibleConversationRegistry.unregister("other-owner")
    }

    private fun visible(
        conversationKey: String?,
        serverId: String = "server-1",
        directory: SharedConversationDirectoryState = SharedConversationDirectoryState()
    ) =
        RaftVisibleConversationSnapshot(
            currentServerId = serverId,
            visibleConversationKey = conversationKey,
            visibleRouteIdentity = null,
            conversationDirectory = directory
        )

    private fun channelPush(channelId: String, serverId: String = "server-1") =
        assertNotNull(
            raftSystemNotificationPushEventOrNull(
                serverId = serverId,
                kind = "channel",
                channelId = channelId,
                threadId = null,
                parentChannelId = null,
                parentMessageId = null,
                messageId = "message-1",
                category = "channel_unread"
            )
        )

    @Test
    fun pushForOpenConversationStaysSilent() {
        assertTrue(channelPush("channel-1").targetsForegroundVisibleConversation(visible("channel-1")))
    }

    @Test
    fun pushForAnotherConversationStillPresents() {
        assertFalse(channelPush("channel-2").targetsForegroundVisibleConversation(visible("channel-1")))
    }

    @Test
    fun pushFromAnotherServerStillPresentsEvenWithSameChannelId() {
        assertFalse(
            channelPush("channel-1", serverId = "server-2")
                .targetsForegroundVisibleConversation(visible("channel-1"))
        )
    }

    @Test
    fun incompletePayloadIsNotMatched() {
        assertNull(
            raftSystemNotificationPushEventOrNull(
                serverId = "server-1",
                kind = "channel",
                channelId = "channel-1",
                threadId = null,
                parentChannelId = null,
                parentMessageId = null,
                messageId = " ",
                category = null
            )
        )
        assertNull(
            raftSystemNotificationPushEventOrNull(
                serverId = "server-1",
                kind = "unknown",
                channelId = "channel-1",
                threadId = null,
                parentChannelId = null,
                parentMessageId = null,
                messageId = "message-1",
                category = null
            )
        )
    }
}
