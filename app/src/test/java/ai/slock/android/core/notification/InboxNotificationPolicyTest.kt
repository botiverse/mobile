package ai.slock.android.core.notification

import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.ThreadId
import org.junit.Assert.assertEquals
import org.junit.Test

class InboxNotificationPolicyTest {
    @Test
    fun suppressesNotificationsForCurrentUserMessages() {
        val currentMemberId = MemberId("me")

        val result = shouldShowInboxNotification(
            message = message(authorId = currentMemberId),
            currentMemberId = currentMemberId,
            isVisibleThread = false
        )

        assertEquals(false, result)
    }

    @Test
    fun suppressesNotificationsForVisibleThreadMessages() {
        val result = shouldShowInboxNotification(
            message = message(authorId = MemberId("other")),
            currentMemberId = MemberId("me"),
            isVisibleThread = true
        )

        assertEquals(false, result)
    }

    @Test
    fun allowsNotificationsForOtherUnreadInboxMessages() {
        val result = shouldShowInboxNotification(
            message = message(authorId = MemberId("other")),
            currentMemberId = MemberId("me"),
            isVisibleThread = false
        )

        assertEquals(true, result)
    }

    private fun message(authorId: MemberId): Message =
        Message(
            id = MessageId("message-1"),
            threadId = ThreadId("thread-1"),
            channelId = ChannelId("channel-1"),
            author = MemberProfile(
                id = authorId,
                displayName = "Author",
                handle = "@author",
                kind = MemberKind.Human
            ),
            timestampLabel = "12:00",
            body = "New inbox message"
        )
}
