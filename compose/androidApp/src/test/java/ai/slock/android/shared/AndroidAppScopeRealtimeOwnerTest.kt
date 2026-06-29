package ai.slock.android.shared

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.ThreadId
import ai.slock.compose.model.ConversationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAppScopeRealtimeOwnerTest {
    @Test
    fun backgroundUnreadNotificationPolicyRequiresBackgroundEnabledPreferenceAndNoConsumers() {
        assertTrue(
            AndroidAppScopeRealtimeOwner.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = false,
                preferenceEnabled = true,
                isAppInForeground = false
            )
        )
        assertFalse(
            AndroidAppScopeRealtimeOwner.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = true,
                preferenceEnabled = true,
                isAppInForeground = false
            )
        )
        assertFalse(
            AndroidAppScopeRealtimeOwner.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = false,
                preferenceEnabled = false,
                isAppInForeground = false
            )
        )
        assertFalse(
            AndroidAppScopeRealtimeOwner.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = false,
                preferenceEnabled = true,
                isAppInForeground = true
            )
        )
    }

    @Test
    fun backgroundUnreadNotificationKindTreatsThreadMessagesAsThread() {
        val message =
            message(
                channelId = "thread-channel",
                threadChannelId = "thread-channel",
                parentMessageId = "parent-message"
            )

        assertEquals(
            ConversationKind.Thread,
            AndroidAppScopeRealtimeOwner.backgroundUnreadNotificationConversationKind(message)
        )
    }

    @Test
    fun backgroundUnreadNotificationKindTreatsChannelMessagesAsChannel() {
        val message = message(channelId = "channel-1")

        assertEquals(
            ConversationKind.Channel,
            AndroidAppScopeRealtimeOwner.backgroundUnreadNotificationConversationKind(message)
        )
    }

    @Test
    fun foregroundReconnectAfterLongBackgroundUsesFiveMinuteThreshold() {
        val hiddenAt = 1_000L

        assertFalse(
            AndroidAppScopeRealtimeOwner.shouldForceReconnectAfterLongBackground(
                hiddenAtMillis = null,
                shownAtMillis = hiddenAt + 10_000L
            )
        )
        assertFalse(
            AndroidAppScopeRealtimeOwner.shouldForceReconnectAfterLongBackground(
                hiddenAtMillis = hiddenAt,
                shownAtMillis = hiddenAt
            )
        )
        assertFalse(
            AndroidAppScopeRealtimeOwner.shouldForceReconnectAfterLongBackground(
                hiddenAtMillis = hiddenAt,
                shownAtMillis = hiddenAt + 299_999L
            )
        )
        assertTrue(
            AndroidAppScopeRealtimeOwner.shouldForceReconnectAfterLongBackground(
                hiddenAtMillis = hiddenAt,
                shownAtMillis = hiddenAt + 300_000L
            )
        )
    }

    private fun message(
        channelId: String? = null,
        threadChannelId: String? = null,
        parentMessageId: String? = null
    ): Message =
        Message(
            id = MessageId("message-1"),
            threadId = ThreadId(threadChannelId ?: channelId ?: "thread-1"),
            channelId = channelId?.let(::ChannelId),
            threadChannelId = threadChannelId?.let(::ChannelId),
            author =
                MemberProfile(
                    id = MemberId("member-2"),
                    displayName = "Ada",
                    handle = "ada",
                    kind = MemberKind.Human
                ),
            timestampLabel = "now",
            body = "Hello",
            parentMessageId = parentMessageId?.let(::MessageId)
        )
}
