package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.ThreadId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemNotificationsTest {
    @Test
    fun notificationPreferenceDefaultsToDisabledWhenNoPreferenceExists() = runBlocking {
        val repository = PersistentNotificationPreferenceRepository(
            KeyValueNotificationPreferenceStore(InMemorySlockKeyValueStore())
        )

        val snapshot = repository.getNotificationPreference()

        assertFalse(snapshot.enabled)
    }

    @Test
    fun notificationPreferencePersistsSelectedState() = runBlocking {
        val repository = PersistentNotificationPreferenceRepository(
            KeyValueNotificationPreferenceStore(InMemorySlockKeyValueStore())
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
        assertEquals("#general", request.title)
        assertEquals("Ada: Hello team", request.body)
        assertEquals("unread:server-1:channel-1:message-1", request.notificationKey)
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

    private fun message(body: String = "Hello"): Message =
        Message(
            id = MessageId("message-1"),
            threadId = ThreadId("thread-1"),
            channelId = ChannelId("channel-1"),
            author =
                MemberProfile(
                    id = MemberId("member-2"),
                    displayName = "Ada",
                    handle = "ada",
                    kind = MemberKind.Human
                ),
            timestampLabel = "now",
            body = body
        )
}
