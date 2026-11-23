package build.raft.app

import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test fun theListMakesRoomForTheLineOnlyWhileItShows() {
        // Nobody typing or working: the list's own end, shorter than the line's slot, so it holds no
        // slot (web: AgentTypingListEnd renders nothing). While the line shows the end grows by
        // exactly the slot, which is taller than the line, so the newest message rests whole above it.
        val idle = threadBottomSpacerAndAnchorTrailingHeight(typingLineShown = false)
        val shown = threadBottomSpacerAndAnchorTrailingHeight(typingLineShown = true)
        assertTrue(idle < ThreadAgentTypingListEndHeight, "a $idle end holds the line's slot")
        assertEquals(idle + ThreadAgentTypingListEndHeight, shown)
        assertTrue(ThreadAgentTypingListEndHeight > ThreadAgentTypingLineHeight)
    }

    @Test fun theEndFadeUnderTheLineEndsWhereTheNewestMessageRests() {
        // The newest message rests on the list's end. While the line shows, its solid strip and the
        // fade above it stay inside that end, so they never cover the message's last line (artin 11-22);
        // some fade remains, so a message scrolled under the line still softens.
        val end = threadBottomSpacerAndAnchorTrailingHeight(typingLineShown = true)
        val shownFade = threadMessageListEndFadeGradientHeight(typingLineShown = true)
        assertTrue(ThreadAgentTypingLineHeight + shownFade <= end, "strip + fade ${ThreadAgentTypingLineHeight + shownFade} over a $end end")
        assertTrue(shownFade.value > 0f)
    }

    @Test fun aReaderRestingOnTheEndFollowsItAndOneScrolledUpKeepsTheirPlace() {
        // Opened on the newest message (Inbox, Activity): anchored on it, resting on the list's end.
        val anchoredOnTheNewest =
            LazyViewportIntent.Anchored(
                target = LazyViewportAnchorTarget.Message("m40"),
                offset = 0,
                layoutGeneration = 0L,
                placement = LazyViewportAnchorPlacement.Focused,
                fallback = LazyViewportAnchorFallback.Free,
            )

        fun follows(
            intent: LazyViewportIntent = anchoredOnTheNewest,
            restsOnListEnd: Boolean = true,
            scrollInProgress: Boolean = false,
            placementSettled: Boolean = true,
            newerPagePending: Boolean = false,
        ) =
            threadTypingLineEndFollowsLatest(
                reverseLayout = true,
                intent = intent,
                restsOnListEnd = restsOnListEnd,
                scrollInProgress = scrollInProgress,
                placementSettled = placementSettled,
                newerPagePending = newerPagePending,
            )

        // Resting on the end, the reader follows it: the newest message rises above the line instead
        // of the reading anchor holding it under the line (Android 1.27.0, #proj-mobile #227).
        assertTrue(follows())
        // Scrolled up into the history: the end changes out of view and the reader keeps their place.
        assertFalse(follows(restsOnListEnd = false))
        // The user's drag or fling owns the viewport, and so does a finger resting on the list.
        assertFalse(follows(scrollInProgress = true))
        assertFalse(follows(intent = LazyViewportIntent.UserDragging))
        // Another owner places the list (the initial anchor, a share capture, an adaptive restore).
        assertFalse(follows(placementSettled = false))
        // The loaded window stops short of the latest messages: its end is not the conversation's.
        assertFalse(follows(newerPagePending = true))
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
}
