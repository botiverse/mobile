package build.raft.app

import build.raft.app.data.ChannelMemberSnapshot
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.MemberRepository
import build.raft.app.data.MemberSnapshot
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.threadCanAddFromPrivateMention
import build.raft.app.thread.threadComposerEffectiveMentionScope
import build.raft.app.thread.threadComposerMemberScopedMentions
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivateMentionAddTest {

    @Test
    fun memberScopedMentionsCoversPrivateAndJointChannelsAndTheirReplyThreads() {
        val privateChannel = RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.Channel, channelKind = "private")
        val jointChannel = RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.Channel, channelKind = "joint")
        val publicChannel = RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.Channel, channelKind = "channel")
        val dm = RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.DirectMessage, channelKind = "dm", memberScopedMentions = true)
        val privateReply = RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.ReplyThread, parentChannelKind = "private")
        val jointReply = RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.ReplyThread, parentChannelKind = "joint")
        val publicReply = RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.ReplyThread, parentChannelKind = "channel")

        assertTrue(privateChannel.threadComposerMemberScopedMentions())
        assertTrue(jointChannel.threadComposerMemberScopedMentions())
        assertFalse(publicChannel.threadComposerMemberScopedMentions())
        assertFalse(dm.threadComposerMemberScopedMentions())
        assertTrue(privateReply.threadComposerMemberScopedMentions())
        assertTrue(jointReply.threadComposerMemberScopedMentions())
        assertFalse(publicReply.threadComposerMemberScopedMentions())
    }

    @Test
    fun canAddFromPrivateMentionRequiresFlagMemberScopedUnarchivedAndConfirmedPermission() {
        assertTrue(
            threadCanAddFromPrivateMention(
                privateMentionAddEnabled = true,
                baseMemberScopedMentions = true,
                channelArchived = false,
                canAddMembersKnown = true,
                canAddMembers = true,
            )
        )
        assertFalse(
            threadCanAddFromPrivateMention(
                privateMentionAddEnabled = false,
                baseMemberScopedMentions = true,
                channelArchived = false,
                canAddMembersKnown = true,
                canAddMembers = true,
            )
        )
        assertFalse(
            threadCanAddFromPrivateMention(
                privateMentionAddEnabled = true,
                baseMemberScopedMentions = false,
                channelArchived = false,
                canAddMembersKnown = true,
                canAddMembers = true,
            )
        )
        assertFalse(
            threadCanAddFromPrivateMention(
                privateMentionAddEnabled = true,
                baseMemberScopedMentions = true,
                channelArchived = true,
                canAddMembersKnown = true,
                canAddMembers = true,
            )
        )
        assertFalse(
            threadCanAddFromPrivateMention(
                privateMentionAddEnabled = true,
                baseMemberScopedMentions = true,
                channelArchived = false,
                canAddMembersKnown = false,
                canAddMembers = true,
            )
        )
        assertFalse(
            threadCanAddFromPrivateMention(
                privateMentionAddEnabled = true,
                baseMemberScopedMentions = true,
                channelArchived = false,
                canAddMembersKnown = true,
                canAddMembers = false,
            )
        )

        val opened = threadComposerEffectiveMentionScope(baseMemberScopedMentions = true, addFromPrivateMention = true)
        assertFalse(opened.memberScopedMentions)
        assertTrue(opened.excludeOutsiderGuests)

        val closed = threadComposerEffectiveMentionScope(baseMemberScopedMentions = true, addFromPrivateMention = false)
        assertTrue(closed.memberScopedMentions)
        assertFalse(closed.excludeOutsiderGuests)

        val publicScope = threadComposerEffectiveMentionScope(baseMemberScopedMentions = false, addFromPrivateMention = false)
        assertFalse(publicScope.memberScopedMentions)
        assertFalse(publicScope.excludeOutsiderGuests)
    }

    @Test
    fun serverMembersSuggestionsOfferNonMemberInsidersAndExcludeOutsiderGuests() = runTest {
        val serverId = ServerId("s1")
        val channelId = ChannelId("c-private")
        val inChannelMember =
            MemberProfile(MemberId("u-in"), "Alice", "alice", MemberKind.Human, serverRole = "member")
        val inChannelGuest =
            MemberProfile(MemberId("u-guest-in"), "Bound Guest", "bound-guest", MemberKind.Human, serverRole = "guest")
        val outsiderMember =
            MemberProfile(MemberId("u-out"), "Bob", "bob", MemberKind.Human, serverRole = "member")
        val outsiderAgent =
            MemberProfile(MemberId("a-out"), "Reef", "reef", MemberKind.Agent, serverRole = "member")
        val outsiderGuest =
            MemberProfile(MemberId("u-guest-out"), "Other Guest", "other-guest", MemberKind.Human, serverRole = "guest")
        val repo =
            object : MemberRepository by SharedThreadStoreTestFakes.FakeMemberRepository(
                serverMembers = listOf(inChannelMember, inChannelGuest, outsiderMember, outsiderAgent, outsiderGuest),
                channelMembers = listOf(inChannelMember, inChannelGuest),
            ) {
                override suspend fun getMembers(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> =
                    DataResult.Content(
                        MemberSnapshot(
                            server = ServerSummary(id = serverId, name = "Slock", role = "owner"),
                            members = listOf(inChannelMember, inChannelGuest, outsiderMember, outsiderAgent, outsiderGuest),
                        )
                    )

                override suspend fun getChannelMembers(
                    priority: DataRequestPriority,
                    channelId: ChannelId,
                ): DataResult<ChannelMemberSnapshot> =
                    DataResult.Content(
                        ChannelMemberSnapshot(
                            channelId = channelId,
                            members = listOf(inChannelMember, inChannelGuest),
                            canAddMembers = true,
                        )
                    )
            }
        val store = SharedServerMembersStore(repo)
        store.ensureMemberSuggestionsForServer(
            serverId = serverId,
            mentionChannelId = channelId.value,
            memberScopedMentions = false,
            excludeOutsiderGuests = true,
        )
        val suggestions =
            store.state.value
                .memberSuggestionSlice(
                    serverId = serverId,
                    mentionChannelId = channelId.value,
                    memberScopedMentions = false,
                    excludeOutsiderGuests = true,
                ).suggestions
        val byId = suggestions.associateBy { it.memberId }
        assertEquals(true, byId["u-in"]?.inMentionChannel)
        assertEquals(true, byId["u-guest-in"]?.inMentionChannel, "guest already in channel stays in channel")
        assertEquals(false, byId["u-out"]?.inMentionChannel, "same-server member offered as not in channel")
        assertEquals(false, byId["a-out"]?.inMentionChannel, "same-server agent offered as not in channel")
        assertFalse("u-guest-out" in byId, "outsider guest must never be offered in private/joint channel")
    }

    @Test
    fun pendingMentionActionDescriptionUsesPrivateChannelCopyForAddOnly() {
        val addOnly =
            RaftPendingMentionActionItem(
                resolutionId = "r1",
                channelName = "secret",
                targetType = "user",
                targetHandle = "Carol",
                reason = "not_member",
                availableActions = listOf("add"),
            )
        assertEquals(
            "@Carol is not in #secret and cannot see this message until added",
            addOnly.pendingMentionActionDescription(channelLabel = "#secret"),
        )
        val addAndNotify = addOnly.copy(availableActions = listOf("add", "notify"))
        assertEquals(
            "@Carol isn't in #secret. Add them to #secret or send a one-time notify.",
            addAndNotify.pendingMentionActionDescription(channelLabel = "#secret"),
        )
    }
}
