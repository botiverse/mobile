package ai.slock.android.feature.home

import ai.slock.android.core.model.AvatarReference
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
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.ScreenLoadState
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeUiStateTest {
    @Test
    fun resolveHomeShellContentKeepsLastVisibleContentWhileLoadingEvenWithoutRefreshFlag() {
        val lastVisible = homeContent(
            channels = listOf(
                unreadChannel(id = "channel-1", threadId = "thread-1", unreadCount = 3)
            ),
            directMessages = emptyList(),
            unreadTotal = 3
        )
        val fallback = homeContent(
            channels = emptyList(),
            directMessages = emptyList(),
            unreadTotal = 0
        )

        val resolved = resolveHomeShellContent(
            currentContent = null,
            lastVisibleContent = lastVisible,
            homeLoadState = ScreenLoadState.Loading,
            homeRefreshInProgress = false,
            fallbackContent = fallback
        )

        assertEquals(lastVisible, resolved)
    }

    @Test
    fun resolveHomeShellContentFallsBackWhenNoVisibleContentExists() {
        val fallback = homeContent(
            channels = emptyList(),
            directMessages = emptyList(),
            unreadTotal = 0
        )

        val resolved = resolveHomeShellContent(
            currentContent = null,
            lastVisibleContent = null,
            homeLoadState = ScreenLoadState.Empty(EmptyReason.NoInboxItems),
            homeRefreshInProgress = false,
            fallbackContent = fallback
        )

        assertEquals(fallback, resolved)
    }

    @Test
    fun withPreservedConversationUnreadKeepsVisibleRowCountsButNotStaleAggregateTotals() {
        val previous = homeContent(
            channels = listOf(
                unreadChannel(id = "channel-1", threadId = "thread-1", unreadCount = 1)
            ),
            directMessages = emptyList(),
            unreadTotal = 1
        )
        val current = homeContent(
            channels = listOf(
                unreadChannel(id = "channel-1", threadId = "thread-1", unreadCount = 0)
            ),
            directMessages = emptyList(),
            unreadTotal = 0
        )

        val merged = current.withPreservedConversationUnread(previous)

        assertEquals(1, merged.channels.single().unreadCount)
        assertEquals(1, merged.unreadTotal)
    }

    @Test
    fun withPreservedConversationUnreadDoesNotKeepAggregateUnreadWhenNoRowRemainsUnread() {
        val previous = homeContent(
            channels = listOf(
                unreadChannel(id = "channel-1", threadId = "thread-1", unreadCount = 1)
            ),
            directMessages = emptyList(),
            unreadTotal = 1
        )
        val current = homeContent(
            channels = listOf(
                unreadChannel(id = "channel-1", threadId = "thread-1", unreadCount = 0)
            ),
            directMessages = emptyList(),
            unreadTotal = 0
        ).copy(channels = emptyList())

        val merged = current.withPreservedConversationUnread(previous)

        assertEquals(0, merged.channels.size)
        assertEquals(0, merged.unreadTotal)
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
}
