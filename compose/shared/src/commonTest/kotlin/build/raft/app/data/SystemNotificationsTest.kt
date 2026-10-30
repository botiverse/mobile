package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemNotificationsTest {
    @Test
    fun notificationPreferenceDefaultsToEnabledWhenNoPreferenceExists() = runBlocking {
        val repository = PersistentNotificationPreferenceRepository(
            KeyValueNotificationPreferenceStore(InMemoryRaftKeyValueStore())
        )

        val snapshot = repository.getNotificationPreference()

        assertTrue(snapshot.enabled)
        assertFalse(snapshot.foregroundKeepAliveEnabled)
        assertFalse(snapshot.offlineComputerAlertsEnabled)
    }

    @Test
    fun notificationPreferencePersistsSelectedState() = runBlocking {
        val repository = PersistentNotificationPreferenceRepository(
            KeyValueNotificationPreferenceStore(InMemoryRaftKeyValueStore())
        )

        val enabled = repository.setNotificationEnabled(true)
        val restoredEnabled = repository.getNotificationPreference()
        val disabled = repository.setNotificationEnabled(false)
        val restoredDisabled = repository.getNotificationPreference()

        assertTrue(enabled.enabled)
        assertTrue(restoredEnabled.enabled)
        assertFalse(disabled.enabled)
        assertFalse(restoredDisabled.enabled)
    }

    @Test
    fun foregroundKeepAlivePreferenceDefaultsOffAndPersistsSelectedState() = runBlocking {
        val repository = PersistentNotificationPreferenceRepository(
            KeyValueNotificationPreferenceStore(InMemoryRaftKeyValueStore())
        )

        val initial = repository.getNotificationPreference()
        val enabled = repository.setForegroundKeepAliveEnabled(true)
        val restoredEnabled = repository.getNotificationPreference()
        val disabled = repository.setForegroundKeepAliveEnabled(false)
        val restoredDisabled = repository.getNotificationPreference()

        assertFalse(initial.foregroundKeepAliveEnabled)
        assertTrue(enabled.foregroundKeepAliveEnabled)
        assertTrue(restoredEnabled.foregroundKeepAliveEnabled)
        assertFalse(disabled.foregroundKeepAliveEnabled)
        assertFalse(restoredDisabled.foregroundKeepAliveEnabled)
        assertTrue(restoredDisabled.enabled)
    }

    @Test
    fun offlineComputerAlertsDefaultOffForExistingInstallsAndPersistSelectedState() = runBlocking {
        val repository = PersistentNotificationPreferenceRepository(
            KeyValueNotificationPreferenceStore(InMemoryRaftKeyValueStore())
        )

        val initial = repository.getNotificationPreference()
        val enabled = repository.setOfflineComputerAlertsEnabled(true)
        val restoredEnabled = repository.getNotificationPreference()
        val disabled = repository.setOfflineComputerAlertsEnabled(false)
        val restoredDisabled = repository.getNotificationPreference()

        assertFalse(initial.offlineComputerAlertsEnabled)
        assertTrue(enabled.offlineComputerAlertsEnabled)
        assertTrue(restoredEnabled.offlineComputerAlertsEnabled)
        assertFalse(disabled.offlineComputerAlertsEnabled)
        assertFalse(restoredDisabled.offlineComputerAlertsEnabled)
        assertTrue(restoredDisabled.enabled)
        assertFalse(restoredDisabled.foregroundKeepAliveEnabled)
    }

    @Test
    fun inboxNotificationPolicySuppressesForegroundAndOwnVisibleMessages() {
        assertEquals(
            false,
            shouldShowSystemInboxNotification(
                messageAuthorId = "member-1",
                currentMemberId = "member-1",
                isVisibleThread = false
            )
        )
        assertEquals(
            false,
            shouldShowSystemInboxNotification(
                messageAuthorId = "member-2",
                currentMemberId = "member-1",
                isVisibleThread = true
            )
        )
        assertEquals(
            false,
            shouldShowSystemInboxNotification(
                messageAuthorId = "member-2",
                currentMemberId = "member-1",
                isVisibleThread = false,
                isAppInForeground = true
            )
        )
    }

    @Test
    fun inboxNotificationPolicyAllowsOtherUnreadBackgroundMessages() {
        assertTrue(
            shouldShowSystemInboxNotification(
                messageAuthorId = "member-2",
                currentMemberId = "member-1",
                isVisibleThread = false,
                isAppInForeground = false
            )
        )
    }

    @Test
    fun unreadNotificationServerGateAllowsEveryServerButNeedsOne() {
        // The unified contract (§4.5): another workspace's notification is shown like any other.
        assertEquals(
            SystemUnreadNotificationServerGateResult(shouldPost = true, code = "allowed"),
            systemUnreadNotificationServerGate(notificationServerId = "server-2")
        )
        assertEquals(
            SystemUnreadNotificationServerGateResult(shouldPost = false, code = "server_missing"),
            systemUnreadNotificationServerGate(notificationServerId = "")
        )
        assertEquals(
            SystemUnreadNotificationServerGateResult(shouldPost = false, code = "server_missing"),
            systemUnreadNotificationServerGate(notificationServerId = null)
        )
    }

    @Test
    fun unreadNotificationRequestBuildsChannelPayload() {
        val request =
            buildSystemUnreadNotificationRequest(
                serverId = "server-1",
                message = message(body = "  Hello\nteam  "),
                currentMemberId = "member-1",
                isVisibleThread = false,
                conversationName = "general",
                conversationKind = ConversationKind.Channel
            )

        requireNotNull(request)
        assertEquals(SystemUnreadNotificationCategory.ChannelUnread, request.category)
        assertEquals(ConversationKind.Channel, request.conversationKind)
        assertEquals("channel", request.conversationKindWireValue())
        assertEquals("#general", request.title)
        assertEquals("Ada: Hello team", request.body)
        assertEquals("unread:message:message-1", request.notificationKey)
    }

    @Test
    fun unreadNotificationRequestSuppressesVisibleThread() {
        val request =
            buildSystemUnreadNotificationRequest(
                serverId = "server-1",
                message = message(),
                currentMemberId = "member-1",
                isVisibleThread = true,
                conversationName = "general",
                conversationKind = ConversationKind.Channel
            )

        assertEquals(null, request)
    }

    @Test
    fun unreadNotificationRequestPromotesMentions() {
        val request =
            buildSystemUnreadNotificationRequest(
                serverId = "server-1",
                message = message(body = "@me please check this"),
                currentMemberId = "member-1",
                isVisibleThread = false,
                conversationName = "ops",
                conversationKind = ConversationKind.Channel,
                currentMemberHandle = "me"
            )

        requireNotNull(request)
        assertEquals(SystemUnreadNotificationCategory.Mention, request.category)
        assertEquals("#ops", request.title)
    }

    @Test
    fun unreadNotificationRequestUsesThreadChannelAsLaunchStream() {
        val request =
            buildSystemUnreadNotificationRequest(
                serverId = "server-1",
                message =
                    message(
                        threadId = "channel-1",
                        channelId = "channel-1",
                        threadChannelId = "thread-message-1",
                        parentMessageId = "message-1"
                    ),
                currentMemberId = "member-1",
                isVisibleThread = false,
                conversationName = "general",
                conversationKind = ConversationKind.Thread
            )

        requireNotNull(request)
        assertEquals(ConversationKind.Thread, request.conversationKind)
        assertEquals("thread", request.conversationKindWireValue())
        assertEquals("thread-message-1", request.threadId)
        assertEquals("thread-message-1", request.channelId)
        assertEquals("channel-1", request.parentChannelId)
        assertEquals("message-1", request.parentMessageId)
        assertEquals("unread:message:message-1", request.notificationKey)
    }

    @Test
    fun jointPushesShareOneCanonicalTrayFactWhileKeepingEachLocalRoute() {
        val host =
            buildSystemUnreadNotificationRequestFromPush(
                push = notificationPush("server-host", "host-channel"),
                isVisibleThread = false,
                isAppInForeground = false
            )
        val peer =
            buildSystemUnreadNotificationRequestFromPush(
                push = notificationPush("server-peer", "peer-channel"),
                isVisibleThread = false,
                isAppInForeground = false
            )

        requireNotNull(host)
        requireNotNull(peer)
        assertEquals("unread:message:canonical-message", host.notificationKey)
        assertEquals(host.notificationKey, peer.notificationKey)
        assertEquals("server-host", host.serverId)
        assertEquals("host-channel", host.channelId)
        assertEquals("server-peer", peer.serverId)
        assertEquals("peer-channel", peer.channelId)
    }

    @Test
    fun pushRequestCarriesTheContractEnvelope() {
        val request =
            requireNotNull(
                buildSystemUnreadNotificationRequestFromPush(
                    push =
                        notificationPush("server-2", "dm-1").copy(
                            kind = ConversationKind.DirectMessage,
                            category = "dm",
                            seq = 41L,
                            conversationId = "dm-1",
                            silent = true,
                            routeJson = """{"type":"dm","serverId":"server-2","channelId":"dm-1","messageId":"canonical-message"}"""
                        ),
                    isVisibleThread = false,
                    isAppInForeground = false
                )
            )
        assertEquals("dm", request.contractCategory)
        assertEquals(41L, request.seq)
        // groupKey rebuilt from serverId + conversationId when the payload carries none.
        assertEquals("server-2:dm-1", request.groupKey)
        assertTrue(request.silent)
        assertEquals(RaftAndroidNotificationChannelKind.DirectMessage, raftAndroidNotificationChannelKind(request.contractCategory))
    }

    private fun notificationPush(serverId: String, channelId: String): NotificationPushReceived =
        NotificationPushReceived(
            serverId = ServerId(serverId),
            kind = ConversationKind.Joint,
            channelId = ChannelId(channelId),
            messageId = MessageId("canonical-message"),
            title = "Joint task",
            body = "Task updated",
            tag = "task"
        )

    private fun message(
        body: String = "Hello",
        threadId: String = "thread-1",
        channelId: String = "channel-1",
        threadChannelId: String? = null,
        parentMessageId: String? = null
    ): Message =
        Message(
            id = MessageId("message-1"),
            threadId = ThreadId(threadId),
            channelId = ChannelId(channelId),
            threadChannelId = threadChannelId?.let(::ChannelId),
            author =
                MemberProfile(
                    id = MemberId("member-2"),
                    displayName = "Ada",
                    handle = "ada",
                    kind = MemberKind.Human
                ),
            timestampLabel = "now",
            body = body,
            parentMessageId = parentMessageId?.let(::MessageId)
        )
}
