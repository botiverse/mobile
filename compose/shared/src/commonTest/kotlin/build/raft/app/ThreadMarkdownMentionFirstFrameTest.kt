package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.MemberRepository
import build.raft.app.data.MemberSnapshot
import build.raft.app.data.PresenceNoopMemberRepository
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.markdown.threadMarkdownTagDisplayLabel
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class ThreadMarkdownMentionFirstFrameTest {
    private val serverId = ServerId("server-1")

    @Test
    fun loadedDirectoryIsReadableBeforeAnyFlowEmitsSoFirstFrameShowsTheName() = runBlocking {
        val store = SharedServerMembersStore(directoryRepository(member("u-cindy", "Cindy Chen", "cindy")))
        store.refreshMembers(serverId, priority = DataRequestPriority.Visible, force = true)

        val firstFrame = store.currentServerMembersEntry(serverId)
        val labels =
            threadMarkdownMentionDisplayLabels(
                members = emptyList(),
                memberCandidates =
                    firstFrame.members.map { RaftThreadMemberItem(id = it.id.value, displayName = it.displayName, handle = it.handle) }
            )

        assertEquals(store.serverMembersEntryFlow(serverId).first(), firstFrame)
        assertEquals("@Cindy Chen", resolveThreadMarkdownTagDisplayLabel(mention("cindy"), labels))
    }

    @Test
    fun mentionLabelsPreferChannelMembersAndKeepTheHandleWhenUnknown() {
        val labels =
            threadMarkdownMentionDisplayLabels(
                members = listOf(RaftThreadMemberItem(id = "u-1", displayName = "Channel Name", handle = "same")),
                memberCandidates = listOf(RaftThreadMemberItem(id = "u-2", displayName = "Directory Name", handle = "same"))
            )

        assertEquals("@Channel Name", resolveThreadMarkdownTagDisplayLabel(mention("same"), labels))
        assertEquals(
            threadMarkdownTagDisplayLabel("@nobody"),
            resolveThreadMarkdownTagDisplayLabel(mention("nobody"), labels)
        )
    }

    @Test
    fun sameContentMemberRefreshKeepsAnEqualLabelIndex() {
        val first = listOf(RaftThreadMemberItem(id = "u-1", displayName = "Cindy", handle = "cindy"))
        val refreshed = first.map { it.copy() }

        val before = threadMarkdownMentionDisplayLabels(first, emptyList())
        val after = threadMarkdownMentionDisplayLabels(refreshed, emptyList())

        assertNotSame(before, after)
        assertEquals(before, after, "an equal index keeps the remembered resolver and markdown token plans")
    }

    private fun mention(handle: String): ThreadMarkdownTag =
        ThreadMarkdownTag("@$handle", ThreadMarkdownTagKind.UserMention)

    private fun member(id: String, displayName: String, handle: String): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = displayName,
            handle = handle,
            kind = MemberKind.Human,
            serverId = serverId
        )

    private fun directoryRepository(vararg members: MemberProfile): MemberRepository =
        object : MemberRepository by PresenceNoopMemberRepository {
            override suspend fun getMembers(
                priority: DataRequestPriority,
                serverId: ServerId
            ): DataResult<MemberSnapshot> =
                DataResult.Content(MemberSnapshot(ServerSummary(id = serverId, name = "Raft"), members.toList()))
        }
}
