package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.model.ConversationKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockVisibleConversationRegistryTest {
    @AfterTest
    fun tearDown() {
        SlockVisibleConversationRegistry.unregister("test-owner")
        SlockVisibleConversationRegistry.unregister("other-owner")
    }

    private fun visible(
        conversationKey: String?,
        serverId: String = "server-1",
        directory: SharedConversationDirectoryState = SharedConversationDirectoryState()
    ) =
        SlockVisibleConversationSnapshot(
            currentServerId = serverId,
            visibleConversationKey = conversationKey,
            visibleRouteIdentity = null,
            conversationDirectory = directory
        )

    private val threadDirectory =
        slockConversationDirectoryStateFromProjections(
            listOf(
                SharedConversationDirectoryProjection(
                    serverId = "server-1",
                    canonicalChannelId = "channel-1",
                    aliases = setOf("channel-1"),
                    conversationKind = ConversationKind.Channel,
                    surfaceKind = KuiklyThreadSurfaceKind.Channel,
                    channelKind = "channel",
                    title = "team-ops"
                ),
                SharedConversationDirectoryProjection(
                    serverId = "server-1",
                    canonicalChannelId = "thread-stream",
                    aliases = setOf("thread-stream", "parent-message"),
                    conversationKind = ConversationKind.Thread,
                    surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                    channelKind = "thread",
                    title = "deploy checklist",
                    parentChannelId = "channel-1",
                    parentMessageId = "parent-message"
                )
            )
        )

    private fun channelPush(channelId: String, serverId: String = "server-1") =
        assertNotNull(
            slockSystemNotificationPushEventOrNull(
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
    fun pushWhenNoConversationIsOpenStillPresents() {
        assertFalse(channelPush("channel-1").targetsForegroundVisibleConversation(visible(null)))
    }

    @Test
    fun pushFromAnotherServerStillPresentsEvenWithSameChannelId() {
        assertFalse(
            channelPush("channel-1", serverId = "server-2")
                .targetsForegroundVisibleConversation(visible("channel-1"))
        )
    }

    @Test
    fun threadPushForOpenThreadStaysSilent() {
        val event =
            assertNotNull(
                slockSystemNotificationPushEventOrNull(
                    serverId = "server-1",
                    kind = "thread",
                    channelId = "thread-stream",
                    threadId = "thread-stream",
                    parentChannelId = "channel-1",
                    parentMessageId = "parent-message",
                    messageId = "reply-1",
                    category = "mention"
                )
            )
        assertEquals(ConversationKind.Thread, event.kind)
        assertEquals("thread-stream", event.threadId?.value)
        assertTrue(
            event.targetsForegroundVisibleConversation(visible("thread-stream", directory = threadDirectory))
        )
        assertFalse(
            event.targetsForegroundVisibleConversation(visible("channel-2", directory = threadDirectory))
        )
    }

    @Test
    fun payloadWithoutKindInfersThreadAndDirectMessage() {
        val thread =
            slockSystemNotificationPushEventOrNull(
                serverId = "server-1",
                kind = null,
                channelId = "channel-1",
                threadId = "thread-stream",
                parentChannelId = "channel-1",
                parentMessageId = "parent-message",
                messageId = "reply-1",
                category = "general_unread"
            )
        assertEquals(ConversationKind.Thread, thread?.kind)

        val dm =
            slockSystemNotificationPushEventOrNull(
                serverId = "server-1",
                kind = null,
                channelId = "dm-1",
                threadId = "dm-1",
                parentChannelId = null,
                parentMessageId = null,
                messageId = "message-1",
                category = "direct_message"
            )
        assertEquals(ConversationKind.DirectMessage, dm?.kind)
    }

    @Test
    fun incompletePayloadIsNotMatched() {
        assertNull(
            slockSystemNotificationPushEventOrNull(
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
            slockSystemNotificationPushEventOrNull(
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

    @Test
    fun registryFollowsTheLatestOwnerAndIgnoresStaleUnregister() {
        assertNull(SlockVisibleConversationRegistry.snapshot())
        SlockVisibleConversationRegistry.register("test-owner") { visible("channel-1") }
        SlockVisibleConversationRegistry.register("other-owner") { visible("channel-2") }
        SlockVisibleConversationRegistry.unregister("test-owner")
        assertEquals("channel-2", SlockVisibleConversationRegistry.snapshot()?.visibleConversationKey)
        SlockVisibleConversationRegistry.unregister("other-owner")
        assertNull(SlockVisibleConversationRegistry.snapshot())
    }

    @Test
    fun providerFailureMeansNothingIsVisible() {
        SlockVisibleConversationRegistry.register("test-owner") { error("route host gone") }
        assertNull(SlockVisibleConversationRegistry.snapshot())
    }
}
