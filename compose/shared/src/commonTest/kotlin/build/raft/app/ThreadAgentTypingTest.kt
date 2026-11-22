package build.raft.app

import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test fun theLineAndItsSlotAtTheListEndGoWithTheComposerOnly() {
        val channel = RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.Channel, sourceThreadId = "ch-1")
        val cases =
            listOf(
                channel to true,
                // Previewed without joining: the join footer.
                channel.copy(joined = false) to false,
                // Archived: the archived banner.
                channel.copy(channelArchived = true) to false,
                channel.copy(showComposer = false) to false,
                // A composer that cannot send yet is still a composer.
                channel.copy(canSend = false) to true,
            )
        cases.forEach { (payload, expected) ->
            assertEquals(expected, payload.agentTypingLineCanShow(), "$payload")
            // The list's end follows the same answer: the slot is there exactly where the line can be.
            assertEquals(expected, payload.toThreadTimelineSlice().agentTypingLineSlot, "$payload")
        }
        assertEquals(30f, threadBottomSpacerAndAnchorTrailingHeight(typingLineSlot = true).value)
        assertEquals(16f, threadBottomSpacerAndAnchorTrailingHeight(typingLineSlot = false).value)
    }

    @Test fun theEndFadeUnderTheLineEndsWhereTheNewestMessageRests() {
        // The newest message rests on the list's end. While the line shows, its solid strip and the
        // fade above it stay inside that end, so they never cover the message's last line (artin 11-22);
        // some fade remains, so a message scrolled under the line still softens.
        val end = threadBottomSpacerAndAnchorTrailingHeight(typingLineSlot = true)
        val shownFade = threadMessageListEndFadeGradientHeight(typingLineShown = true)
        assertTrue(ThreadAgentTypingLineHeight + shownFade <= end, "strip + fade ${ThreadAgentTypingLineHeight + shownFade} over a $end end")
        assertTrue(shownFade.value > 0f)
        // Hidden: the section step's fade lies over the empty end only.
        assertTrue(threadMessageListEndFadeGradientHeight(typingLineShown = false) <= end)
    }

    @Test fun onlyAgentsAreNamedAndUnknownIdsUseTheFallback() {
        val serverMembers =
            listOf(
                MemberProfile(MemberId("niko"), displayName = "Niko", handle = "niko", kind = MemberKind.Agent, avatar = AvatarReference.Url("https://cdn.raft.build/niko.webp")),
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
                ThreadTypingAgent("niko", "Niko", "https://cdn.raft.build/niko.webp"),
                ThreadTypingAgent("kirby", "Kirby", null),
                ThreadTypingAgent("ghost", "Agent", null),
            ),
            agents,
        )
    }

    @Test fun facesResolveLikeMessageRowsNotFromAvatarUrlAlone() {
        // An agent's pixel face lives in its one `avatar` field; the old URL-only copy drew initials.
        val serverMembers =
            listOf(
                MemberProfile(
                    MemberId("niko"), displayName = "Niko", handle = "niko", kind = MemberKind.Agent,
                    avatar = AvatarReference.Pixel("antenna"),
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
