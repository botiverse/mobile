package ai.slock.android.shared

import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.ThreadId
import ai.slock.compose.model.ConversationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRealtimeBridgeManagerTest {
    @Test
    fun backgroundUnreadNotificationPolicyRequiresEnabledPreferenceAndNoConsumers() {
        assertTrue(
            AndroidRealtimeBridgeManager.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = false,
                preferenceEnabled = true
            )
        )
        assertFalse(
            AndroidRealtimeBridgeManager.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = true,
                preferenceEnabled = true
            )
        )
        assertFalse(
            AndroidRealtimeBridgeManager.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = false,
                preferenceEnabled = false
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
            AndroidRealtimeBridgeManager.backgroundUnreadNotificationConversationKind(message)
        )
    }

    @Test
    fun backgroundUnreadNotificationKindTreatsChannelMessagesAsChannel() {
        val message = message(channelId = "channel-1")

        assertEquals(
            ConversationKind.Channel,
            AndroidRealtimeBridgeManager.backgroundUnreadNotificationConversationKind(message)
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
