package build.raft.app

import build.raft.app.model.ChannelId
import build.raft.app.data.SharedServerChannelMembersScope
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersState
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.canOpenThreadAuthorProfile
import build.raft.app.thread.resolvedThreadAvatarWireValue
import build.raft.app.thread.threadAuthorBadgeLabel
import build.raft.app.thread.threadMentionHandle
import build.raft.app.thread.toMessage
import build.raft.app.thread.toRaftThreadMessageItem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Focused regression coverage for [ThreadRenderSignature] / [RaftThreadScreenPayload.toRenderSignature].
 *
 * The signature is the key used to drive the `thread_render` perf marker
 * in `ThreadPage`. These tests pin the equivalence classes we care about:
 *
 * - members / settings / actions / realtime metadata changes must NOT
 *   change the signature (otherwise non-render axis churn retriggers
 *   render log hot path)
 * - message/status/tab/showReplyActions changes that actually
 *   affect the visible render axis MUST change the signature
 * - equivalent messages list content (same id sequence, same reply
 *   counts) but different list references MUST produce the same
 *   signature
 */
class ThreadRenderSignatureTest {

    private fun message(
        id: String,
        replyCount: Int = 0,
        unreadReplyCount: Int = 0
    ): RaftThreadMessageItem = RaftThreadMessageItem(
        id = id,
        authorId = "user-$id",
        authorName = "User $id",
        body = "body-$id",
        replyCount = replyCount,
        unreadReplyCount = unreadReplyCount
    )

    @Test
    fun inactiveAuthorsExposeHistoryBadgesAndDisableProfileNavigation() {
        val deletedAgent =
            RaftThreadMessageItem(
                id = "agent-message",
                authorId = "agent-deleted",
                authorName = "Deleted Agent",
                authorKind = "agent",
                avatarWireValue = "pixel:robot",
                authorDeletedAt = "2026-06-06T00:00:00.000Z"
            )
        val removedHuman =
            RaftThreadMessageItem(
                id = "human-message",
                authorId = "user-removed",
                authorName = "Removed Human",
                authorKind = "human",
                senderMembershipStatus = "removed"
            )

        assertEquals("DELETED", deletedAgent.threadAuthorBadgeLabel())
        assertEquals(false, deletedAgent.canOpenThreadAuthorProfile())
        assertEquals(null, deletedAgent.resolvedThreadAvatarWireValue())
        assertEquals("Removed", removedHuman.threadAuthorBadgeLabel())
        assertEquals(false, removedHuman.canOpenThreadAuthorProfile())
    }

    @Test
    fun threadMentionHandle_prefersMemberHandleOverDisplayName() {
        val message =
            RaftThreadMessageItem(
                id = "message-1",
                authorId = "agent-1",
                authorName = "Android Developer",
                authorHandle = "",
                authorKind = "agent"
            )
        val serverMembersState =
            SharedServerMembersState(
                channelMemberSlices =
                    mapOf(
                        SharedServerChannelMembersScope(serverId = "server-1", channelId = "channel-1") to
                            SharedServerChannelMembersSlice(
                                serverId = "server-1",
                                channelId = "channel-1",
                                members =
                                    listOf(
                                        MemberProfile(
                                            id = MemberId("agent-1"),
                                            displayName = "Android Developer",
                                            handle = "Android-Developer-2",
                                            kind = MemberKind.Agent
                                        )
                                    )
                            )
                    )
            )

        assertEquals("Android-Developer-2", message.threadMentionHandle(serverMembersState, ServerId("server-1")))
    }

    @Test
    fun threadMentionHandle_failsClosedInsteadOfUsingAuthorIdOrDisplayName() {
        val message =
            RaftThreadMessageItem(
                id = "message-1",
                authorId = "agent-1",
                authorName = "Android Developer",
                authorHandle = "Android Developer",
                authorKind = "agent"
            )

        assertEquals(null, message.threadMentionHandle())
    }

    @Test
    fun inactiveAgentStateSurvivesMessageRoundTrip() {
        val origin =
            RaftThreadMessageItem(
                id = "origin-message",
                authorId = "agent-deleted",
                authorName = "Deleted KMP Agent",
                authorKind = "agent",
                avatarWireValue = "pixel:robot",
                authorDeletedAt = "2026-06-06T00:00:00.000Z",
                body = "Parent message"
            )

        val roundTripped =
            requireNotNull(
                origin.toMessage(
                    threadId = ThreadId("parent-channel"),
                    channelId = ChannelId("parent-channel"),
                ),
            )
                .toRaftThreadMessageItem(currentUserId = null)

        assertEquals("deleted", roundTripped.senderMembershipStatus)
        assertEquals("2026-06-06T00:00:00.000Z", roundTripped.authorDeletedAt)
        assertEquals("DELETED", roundTripped.threadAuthorBadgeLabel())
        assertEquals(false, roundTripped.canOpenThreadAuthorProfile())
        assertEquals(null, roundTripped.resolvedThreadAvatarWireValue())
    }
}
