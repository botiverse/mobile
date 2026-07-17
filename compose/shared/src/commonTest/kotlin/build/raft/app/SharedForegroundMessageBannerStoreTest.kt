package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.ServerId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedForegroundMessageBannerStoreTest {
    @Test
    fun samePushSurvivesHostReplacementWithoutRestartingItsDeadline() {
        var now = 1_000L
        val store = SharedForegroundMessageBannerStore(nowMillis = { now }, autoDismissMs = 5_000L)
        val banner = notificationBanner(messageId = "message-1")

        assertTrue(store.publish(banner))
        assertEquals(6_000L, store.state.value.expiresAtMillis)

        now = 3_500L
        assertFalse(store.publish(banner))
        assertEquals(banner, store.state.value.banner)
        assertEquals(6_000L, store.state.value.expiresAtMillis)

        now = 5_999L
        assertFalse(store.expireIfDue(banner.id, 6_000L))
        assertEquals(banner, store.state.value.banner)

        now = 6_000L
        assertTrue(store.expireIfDue(banner.id, 6_000L))
        assertNull(store.state.value.banner)
    }

    @Test
    fun replacementRejectsStaleDismissAndStaleExpiry() {
        var now = 10L
        val store = SharedForegroundMessageBannerStore(nowMillis = { now }, autoDismissMs = 100L)
        val first = notificationBanner(messageId = "message-1")
        val second = notificationBanner(messageId = "message-2")

        assertTrue(store.publish(first))
        val firstDeadline = assertNotNull(store.state.value.expiresAtMillis)
        now = 20L
        assertTrue(store.publish(second))

        assertFalse(store.dismiss(first.id))
        now = firstDeadline
        assertFalse(store.expireIfDue(first.id, firstDeadline))
        assertEquals(second, store.state.value.banner)
        assertTrue(store.dismiss(second.id))
        assertNull(store.state.value.banner)
    }

    @Test
    fun threadHostHydratesPushContextFromProcessConversationDirectory() {
        val parent =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "parent-channel",
                aliases = setOf("parent-channel"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "home-fixes"
            )
        val state = slockConversationDirectoryStateFromProjections(listOf(parent))
        val provider = FixedConversationIdentityProvider(state)
        val event =
            NotificationPushReceived(
                serverId = ServerId("server-1"),
                kind = ConversationKind.Thread,
                threadId = ChannelId("reply-stream"),
                parentChannelId = ChannelId("parent-channel"),
                parentMessageId = MessageId("parent-message"),
                messageId = MessageId("reply-1"),
                title = "Cindy",
                body = "hello",
                tag = "message:reply-1"
            )

        val directory = notificationPushConversationDirectory(event, provider)
        val banner =
            slockNotificationPushBannerOrNull(
                event = event,
                currentServerId = "server-1",
                visibleConversationKey = null,
                conversationDirectory = directory
            )

        val resolved = assertNotNull(banner)
        assertEquals(SlockForegroundMessageBannerIconKind.Channel, resolved.iconKind)
        assertEquals("#home-fixes - thread", resolved.context)
        assertEquals("parent-channel", resolved.conversation.thread?.parentChannelId?.value)
    }

    private fun notificationBanner(messageId: String): SlockForegroundMessageBanner =
        assertNotNull(
            slockNotificationPushBannerOrNull(
                event =
                    NotificationPushReceived(
                        serverId = ServerId("server-1"),
                        kind = ConversationKind.Channel,
                        channelId = ChannelId("channel-1"),
                        messageId = MessageId(messageId),
                        title = "Cindy",
                        body = "hello",
                        tag = "message:$messageId"
                    ),
                currentServerId = "server-1",
                visibleConversationKey = null
            )
        )
}

private class FixedConversationIdentityProvider(
    private val state: SharedConversationDirectoryState
) : SharedConversationIdentityProvider {
    override fun observeServer(serverId: ServerId): Flow<SharedConversationDirectoryState> = flowOf(state)

    override fun projectionFlow(
        serverId: ServerId,
        ids: Collection<String>
    ): Flow<Map<String, SharedConversationDirectoryProjection>> =
        flowOf(ids.mapNotNull { id -> state.resolve(id)?.let { id to it } }.toMap())

    override fun resolveProjection(
        serverId: ServerId,
        id: String
    ): SharedConversationDirectoryProjection? = state.resolve(id)
}
