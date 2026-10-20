package build.raft.app

import build.raft.app.model.ChannelId
import build.raft.app.data.SharedServerChannelMembersScope
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersState
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame

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

    private fun payload(
        instanceId: String = "iid",
        sourceThreadId: String? = "src",
        status: String = "content",
        selectedTab: String = "chat",
        messages: List<RaftThreadMessageItem> = emptyList(),
        tasks: List<RaftThreadTaskItem> = emptyList(),
        showReplyActions: Boolean = true,
        members: List<RaftThreadMemberItem> = emptyList(),
        settingsActions: List<RaftThreadSettingsAction> = emptyList()
    ): RaftThreadScreenPayload = RaftThreadScreenPayload(
        instanceId = instanceId,
        sourceThreadId = sourceThreadId,
        status = status,
        selectedTab = selectedTab,
        messages = messages,
        tasks = tasks,
        showReplyActions = showReplyActions,
        members = members,
        settingsActions = settingsActions
    )

    @Test
    fun membersOnlyChange_keepsSignatureEqual() {
        val a = payload(
            messages = listOf(message("m1", replyCount = 1)),
            members = listOf(RaftThreadMemberItem(id = "u1", displayName = "User 1", kind = "human"))
        )
        val b = payload(
            messages = listOf(message("m1", replyCount = 1)),
            members = listOf(
                RaftThreadMemberItem(id = "u1", displayName = "User 1", kind = "human"),
                RaftThreadMemberItem(id = "u2", displayName = "User 2", kind = "human")
            )
        )
        assertEquals(a.toRenderSignature(), b.toRenderSignature())
    }

    @Test
    fun settingsActionsChange_keepsSignatureEqual() {
        val a = payload(
            messages = listOf(message("m1")),
            settingsActions = emptyList()
        )
        val b = payload(
            messages = listOf(message("m1")),
            settingsActions = listOf(
                RaftThreadSettingsAction(id = "leave"),
                RaftThreadSettingsAction(id = "archive")
            )
        )
        assertEquals(a.toRenderSignature(), b.toRenderSignature())
    }

    @Test
    fun tasksOnlyChange_keepsSignatureEqual() {
        val a = payload(
            messages = listOf(message("m1")),
            tasks = listOf(
                RaftThreadTaskItem(
                    id = "task-1",
                    title = "old",
                    status = "Todo"
                )
            )
        )
        val b = payload(
            messages = listOf(message("m1")),
            tasks = listOf(
                RaftThreadTaskItem(
                    id = "task-1",
                    title = "new",
                    status = "Done"
                ),
                RaftThreadTaskItem(
                    id = "task-2",
                    title = "other",
                    status = "Todo"
                )
            )
        )
        assertEquals(a.toRenderSignature(), b.toRenderSignature())
    }

    @Test
    fun statusChange_changesSignature() {
        val a = payload(status = "content")
        val b = payload(status = "error")
        assertNotEquals(a.toRenderSignature(), b.toRenderSignature())
    }

    @Test
    fun selectedTabChange_changesSignature() {
        val a = payload(selectedTab = "chat")
        val b = payload(selectedTab = "files")
        assertNotEquals(a.toRenderSignature(), b.toRenderSignature())
    }

    @Test
    fun appendingMessage_changesSignature() {
        val a = payload(messages = listOf(message("m1"), message("m2", replyCount = 2)))
        val b = payload(messages = listOf(message("m1"), message("m2", replyCount = 2), message("m3")))
        assertNotEquals(a.toRenderSignature(), b.toRenderSignature())
    }

    @Test
    fun equivalentMessagesListReferenceButSameContent_keepsSignatureEqual() {
        val sharedContent = listOf(
            message("m1", replyCount = 1, unreadReplyCount = 0),
            message("m2", replyCount = 0)
        )
        val a = payload(messages = sharedContent)
        // Different list reference (a new ArrayList copy), identical content.
        val b = payload(messages = sharedContent.toList())
        val aRef: List<RaftThreadMessageItem> = sharedContent
        val bRef: List<RaftThreadMessageItem> = b.messages
        assertNotSame(aRef, bRef)
        assertEquals(a.toRenderSignature(), b.toRenderSignature())
    }

    @Test
    fun firstReplyChange_changesSignature() {
        val a = payload(messages = listOf(message("m1", replyCount = 1, unreadReplyCount = 0)))
        val b = payload(
            messages = listOf(
                message("m1", replyCount = 1, unreadReplyCount = 0),
                message("m2", replyCount = 2, unreadReplyCount = 1)
            )
        )
        // First reply shifts from m1 (id-8/1/0) to m2 (id-8/2/1).
        assertNotEquals(a.toRenderSignature(), b.toRenderSignature())
    }

    @Test
    fun showReplyActionsChange_changesSignature() {
        val a = payload(showReplyActions = true)
        val b = payload(showReplyActions = false)
        assertNotEquals(a.toRenderSignature(), b.toRenderSignature())
    }

    @Test
    fun emptyMessages_haveZeroReplyRowsAndNullFirstReply() {
        val sig = payload().toRenderSignature()
        assertEquals(0, sig.replyRowsSize)
        assertEquals(null, sig.firstReplyId8)
        assertEquals(0, sig.firstReplyCount)
        assertEquals(0, sig.firstReplyUnreadCount)
    }

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

        assertEquals("Archived", deletedAgent.threadAuthorBadgeLabel())
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
    fun threadMentionHandle_resolvesFromScopedRosterEntry() {
        val message =
            RaftThreadMessageItem(
                id = "message-1",
                authorId = "agent-1",
                authorName = "Android Developer",
                authorKind = "agent"
            )
        val entry =
            SharedServerMembersEntry(
                serverId = "server-1",
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

        assertEquals("Android-Developer-2", message.threadMentionHandle(entry, ServerId("server-1")))
    }

    @Test
    fun threadMentionHandle_usesExplicitAuthorHandleWhenMemberMissing() {
        val message =
            RaftThreadMessageItem(
                id = "message-1",
                authorId = "user-1",
                authorName = "Cindy",
                authorHandle = "@cindy"
            )

        assertEquals("cindy", message.threadMentionHandle())
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
    fun threadMentionHandle_prefersCanonicalJointChannelHandleOverOpaqueRosterHandle() {
        val authorId = "b8f607a0-d819-49e5-bdc7-59bc3a86fd37"
        val message =
            RaftThreadMessageItem(
                id = "message-1",
                authorId = authorId,
                authorName = "王小明",
                authorHandle = authorId,
                authorKind = "human"
            )
        val membersState =
            SharedServerMembersState(
                entriesByServerId =
                    mapOf(
                        "server-1" to
                            SharedServerMembersEntry(
                                serverId = "server-1",
                                members =
                                    listOf(
                                        MemberProfile(
                                            id = MemberId(authorId),
                                            displayName = "王小明",
                                            handle = authorId,
                                            kind = MemberKind.Human
                                        )
                                    )
                            )
                    ),
                channelMemberSlices =
                    mapOf(
                        SharedServerChannelMembersScope("server-1", "joint-channel-1") to
                            SharedServerChannelMembersSlice(
                                serverId = "server-1",
                                channelId = "joint-channel-1",
                                members =
                                    listOf(
                                        MemberProfile(
                                            id = MemberId(authorId),
                                            displayName = "王小明",
                                            handle = "wang-xiaoming",
                                            kind = MemberKind.Human
                                        )
                                    )
                            )
                    )
            )

        assertEquals(
            "wang-xiaoming",
            message.threadMentionHandle(membersState, ServerId("server-1"))
        )
    }

    @Test
    fun threadMentionHandle_rejectsOpaqueFirstFrameAuthorHandleWhenCanonicalMemberIsMissing() {
        val authorId = "b8f607a0-d819-49e5-bdc7-59bc3a86fd37"
        val message =
            RaftThreadMessageItem(
                id = "message-1",
                authorId = authorId,
                authorName = "王小明",
                authorHandle = authorId,
                authorKind = "human"
            )

        assertEquals(null, message.threadMentionHandle())
    }

    @Test
    fun threadMentionHandle_ignoresInactiveAuthors() {
        val deletedAgent =
            RaftThreadMessageItem(
                id = "message-1",
                authorId = "agent-1",
                authorName = "Android Developer",
                authorHandle = "Android-Developer-2",
                authorKind = "agent",
                authorDeletedAt = "2026-07-07T00:00:00.000Z"
            )

        assertEquals(null, deletedAgent.threadMentionHandle())
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
        assertEquals("Archived", roundTripped.threadAuthorBadgeLabel())
        assertEquals(false, roundTripped.canOpenThreadAuthorProfile())
        assertEquals(null, roundTripped.resolvedThreadAvatarWireValue())
    }
}
