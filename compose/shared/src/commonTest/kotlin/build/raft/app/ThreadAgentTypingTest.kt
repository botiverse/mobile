package build.raft.app

import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadAgentTypingTest {
    @Test fun aChannelOrDmIsItselfAndAReplyThreadIsItsParentChannelAndRoot() {
        assertEquals(
            "ch-1" to null,
            RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.Channel, streamChannelId = "ch-1", sourceThreadId = "ch-1")
                .agentTypingConversation(),
        )
        assertEquals(
            "dm-1" to null,
            RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.DirectMessage, sourceThreadId = "dm-1").agentTypingConversation(),
        )
        assertEquals(
            "ch-1" to "root-1",
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                streamChannelId = "thread-ch-1",
                sourceThreadId = "thread-ch-1",
                parentChannelId = "ch-1",
                parentMessageId = "root-1",
            ).agentTypingConversation(),
        )
        // A reply thread whose parent is not known yet names nothing (never the thread channel itself).
        assertNull(
            RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.ReplyThread, streamChannelId = "thread-ch-1")
                .agentTypingConversation(),
        )
    }

    @Test fun onlyAgentsAreNamedAndUnknownIdsUseTheFallback() {
        val serverMembers =
            listOf(
                MemberProfile(MemberId("niko"), displayName = "Niko", handle = "niko", kind = MemberKind.Agent, avatarUrl = "/niko.webp"),
                MemberProfile(MemberId("artin"), displayName = "Artin", handle = "artin", kind = MemberKind.Human),
            )
        val channelMembers =
            listOf(
                RaftThreadMemberItem(id = "kirby", displayName = "Kirby", kind = "agent"),
                RaftThreadMemberItem(id = "lu", displayName = "Lu", kind = "human"),
            )
        val agents =
            resolveThreadTypingAgents(
                agentIds = listOf("niko", "artin", "kirby", "lu", "ghost"),
                serverMembers = serverMembers,
                channelMembers = channelMembers,
                fallbackName = "Agent",
            )
        assertEquals(
            listOf(
                ThreadTypingAgent("niko", "Niko", "/niko.webp"),
                ThreadTypingAgent("kirby", "Kirby", null),
                ThreadTypingAgent("ghost", "Agent", null),
            ),
            agents,
        )
    }

    @Test fun facesResolveLikeMessageRowsNotFromAvatarUrlAlone() {
        // The members store keeps an agent's pixel face in `avatar` and clears `avatarUrl`
        // (SharedServerMembersStore mergeAuthoritativeProfile): reading `avatarUrl` alone drew initials.
        val serverMembers =
            listOf(
                MemberProfile(
                    MemberId("niko"), displayName = "Niko", handle = "niko", kind = MemberKind.Agent,
                    avatarUrl = null, avatar = AvatarReference.Pixel("antenna"),
                ),
                MemberProfile(MemberId("plain"), displayName = "Plain", handle = "plain", kind = MemberKind.Agent),
            )
        val agents =
            resolveThreadTypingAgents(
                agentIds = listOf("niko", "plain", "ghost"),
                serverMembers = serverMembers,
                channelMembers = emptyList(),
                fallbackName = "Agent",
                durableAvatar = { id -> if (id == "ghost") "pixel:pencil" else null },
            )
        assertEquals(
            listOf(
                ThreadTypingAgent("niko", "Niko", "pixel:antenna"),
                // An agent with no avatar of its own wears the default face, as on its messages.
                ThreadTypingAgent("plain", "Plain", "pixel:robot"),
                // Not in the directory yet: the last persisted avatar for the id.
                ThreadTypingAgent("ghost", "Agent", "pixel:pencil"),
            ),
            agents,
        )
    }
}
