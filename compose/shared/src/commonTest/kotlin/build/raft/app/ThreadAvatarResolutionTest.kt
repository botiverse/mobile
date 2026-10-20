package build.raft.app

import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadAvatarResolutionTest {
    @Test
    fun agentThreadAvatarUsesAppAvatarThenRobotFallback() {
        val uploadedAgent =
            RaftThreadMessageItem(
                id = "m1",
                authorId = "agent-1",
                authorName = "Build Bot",
                authorKind = "agent",
                avatarWireValue = "https://cdn.slock.ai/avatars/agents/agent-1.webp"
            )
        assertEquals(
            "https://cdn.slock.ai/avatars/agents/agent-1.webp",
            uploadedAgent.resolvedThreadAvatarWireValue()
        )
        val legacyHashAgent =
            uploadedAgent.copy(
                id = "m2",
                avatarWireValue = null
            )
        assertEquals("pixel:robot", legacyHashAgent.resolvedThreadAvatarWireValue())

        val legacyUrlAgent =
            uploadedAgent.copy(
                id = "m3",
                avatarWireValue = "https://www.gravatar.com/avatar/agenthash?s=52&d=404"
            )
        assertEquals("pixel:robot", legacyUrlAgent.resolvedThreadAvatarWireValue())
    }

    @Test
    fun messageRowsReadOnlyTheCanonicalMemberAvatar() {
        val canonicalAvatar = "https://cdn.slock.ai/avatars/agents/agent-1.webp"
        val fullHistoryRow =
            RaftThreadMessageItem(
                id = "history-message",
                authorId = "agent-1",
                authorName = "Build Bot",
                authorKind = "agent",
                avatarWireValue = canonicalAvatar
            )
        val sparseRealtimeRow =
            fullHistoryRow.copy(
                id = "realtime-message",
                avatarWireValue = null
            )

        assertEquals(canonicalAvatar, fullHistoryRow.resolvedThreadAvatarWireValue())
        assertEquals("pixel:robot", sparseRealtimeRow.resolvedThreadAvatarWireValue())
        val canonicalByAuthor = mapOf("agent-1" to canonicalAvatar)
        val resolver =
            RaftMemberAvatarResolver(
                currentAvatar = canonicalByAuthor::get,
                avatarFlow = { authorId -> flowOf(canonicalByAuthor[authorId]) }
            )

        assertEquals(canonicalAvatar, resolveThreadRowAvatarWireValue(resolver.current(fullHistoryRow.authorId)))
        assertEquals(canonicalAvatar, resolveThreadRowAvatarWireValue(resolver.current(sparseRealtimeRow.authorId)))
    }

    @Test
    fun shareSelectionSuppressesAuthorProfileNavigation() {
        val message =
            RaftThreadMessageItem(
                id = "m1",
                authorId = "user-1",
                authorName = "User 1"
            )
        val deletedAuthor = message.copy(authorDeletedAt = "2026-06-06T00:00:00.000Z")

        assertTrue(message.canOpenThreadAuthorProfile())
        assertTrue(message.canOpenThreadAuthorProfile(shareSelectionActive = false))
        assertFalse(message.canOpenThreadAuthorProfile(shareSelectionActive = true))
        assertFalse(deletedAuthor.canOpenThreadAuthorProfile(shareSelectionActive = false))
        assertFalse(deletedAuthor.canOpenThreadAuthorProfile(shareSelectionActive = true))
    }
}
