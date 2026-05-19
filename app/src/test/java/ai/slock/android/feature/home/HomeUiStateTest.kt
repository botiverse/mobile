package ai.slock.android.feature.home

import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationPermissions
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.ThreadId
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeUiStateTest {
    @Test
    fun withDraftIndicatorsMarksChannelsAndDirectMessagesByThreadId() {
        val content = homeContent(
            channels = listOf(
                unreadChannel(id = "channel-1", threadId = "thread-1", unreadCount = 0),
                unreadChannel(id = "channel-2", threadId = "thread-2", unreadCount = 3)
            ),
            directMessages = listOf(
                directMessage(
                    id = "dm-1",
                    threadId = "dm-thread-1",
                    name = "Agent",
                    presenceMemberId = null
                )
            ),
            unreadTotal = 3
        )

        val marked = content.withDraftIndicators(setOf("thread-1", "dm-thread-1"))

        assertEquals(true, marked.channels[0].hasDraft)
        assertEquals(false, marked.channels[1].hasDraft)
        assertEquals(true, marked.directMessages.single().hasDraft)
        assertEquals(3, marked.channels[1].unreadCount)
    }

    @Test
    fun withDraftIndicatorsClearsStaleDraftFlagsWhenNoDraftsRemain() {
        val content = homeContent(
            channels = listOf(
                unreadChannel(id = "channel-1", threadId = "thread-1", unreadCount = 0)
                    .copy(hasDraft = true)
            ),
            directMessages = listOf(
                directMessage(
                    id = "dm-1",
                    threadId = "dm-thread-1",
                    name = "Agent",
                    presenceMemberId = null
                ).copy(hasDraft = true)
            ),
            unreadTotal = 0
        )

        val marked = content.withDraftIndicators(emptySet())

        assertEquals(false, marked.channels.single().hasDraft)
        assertEquals(false, marked.directMessages.single().hasDraft)
    }

    @Test
    fun defaultSelectedChannelIdUsesFirstReadableChannelOnly() {
        val content = homeContent(
            channels = listOf(
                unreadChannel(id = "channel-1", threadId = "thread-1", unreadCount = 0)
                    .copy(permissions = ConversationPermissions(canRead = false)),
                unreadChannel(id = "channel-2", threadId = "thread-2", unreadCount = 0)
            ),
            directMessages = listOf(
                directMessage(
                    id = "dm-1",
                    threadId = "dm-thread-1",
                    name = "Agent",
                    presenceMemberId = null
                )
            ),
            unreadTotal = 0
        )

        assertEquals("thread-2", content.defaultSelectedChannelId())
    }

    @Test
    fun defaultSelectedChannelIdDoesNotSelectDirectMessages() {
        val content = homeContent(
            channels = emptyList(),
            directMessages = listOf(
                directMessage(
                    id = "dm-1",
                    threadId = "dm-thread-1",
                    name = "Agent",
                    presenceMemberId = null
                )
            ),
            unreadTotal = 0
        )

        assertEquals(null, content.defaultSelectedChannelId())
    }

    private fun homeContent(
        channels: List<ConversationSummary>,
        directMessages: List<ConversationSummary>,
        unreadTotal: Int
    ): HomeContent =
        HomeContent(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            currentMember = MemberProfile(
                id = MemberId("user-1"),
                displayName = "User",
                handle = "user",
                kind = MemberKind.Human,
                avatarUrl = null,
                presence = Presence.Offline
            ),
            channels = channels,
            directMessages = directMessages,
            unreadTotal = unreadTotal,
            serverUnreadCount = unreadTotal
        )

    private fun unreadChannel(id: String, threadId: String, unreadCount: Int): ConversationSummary =
        ConversationSummary(
            threadId = ThreadId(threadId),
            channelId = ChannelId(id),
            kind = ConversationKind.Channel,
            name = id,
            unreadCount = unreadCount,
            firstUnreadMessageId = if (unreadCount > 0) MessageId("message-1") else null,
            permissions = ConversationPermissions(canRead = true, canSend = true)
        )

    private fun directMessage(
        id: String,
        threadId: String,
        name: String,
        presenceMemberId: MemberId?
    ): ConversationSummary =
        ConversationSummary(
            threadId = ThreadId(threadId),
            channelId = ChannelId(id),
            kind = ConversationKind.DirectMessage,
            name = name,
            presenceMemberId = presenceMemberId,
            permissions = ConversationPermissions(canRead = true, canSend = true)
        )
}
