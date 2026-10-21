package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.model.ConversationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedConversationHeaderProjectionTest {
    @Test
    fun activityReplyThreadHidesUnverifiedParentSubtitleWithoutDirectoryProjection() {
        val payload = activityReplyThreadPayload(subtitle = "#general")
        val input = payload.threadHeaderProjectionRoutePayload().threadHeaderProjectionInput()

        val display = input.displayModel(projection = null, contentPayload = payload)

        assertEquals(ThreadReplyPageTitle, display.title)
        assertNull(display.subtitle)
    }

    @Test
    fun activityReplyThreadStillPrefersAcceptedDirectoryProjection() {
        val payload = activityReplyThreadPayload(subtitle = "#stale")
        val input = payload.threadHeaderProjectionRoutePayload().threadHeaderProjectionInput()
        val projection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "general",
                subtitle = null
            )

        val display = input.displayModel(projection = projection, contentPayload = payload)

        assertEquals(ThreadReplyPageTitle, display.title)
        assertEquals("#general", display.subtitle)
    }

    @Test
    fun replyThreadRejectsSyntheticChildProjectionAndFallbackSubtitle() {
        val payload =
            activityReplyThreadPayload(subtitle = "#thread-ab561942").copy(
                sourceThreadId = "child-thread-1",
                streamChannelId = "child-thread-1",
                parentMessageId = "ab561942-a460-49e1-88bc-2e6f0547367e"
            )
        val input = payload.threadHeaderProjectionRoutePayload().threadHeaderProjectionInput()
        val childProjection =
            channelProjection(
                canonicalChannelId = "child-thread-1",
                aliases = setOf("child-thread-1", "channel-1"),
                channelKind = "thread",
                title = "thread-ab561942"
            )
        val owner = ThreadHeaderProjectionStateOwner(input, emptyMap())

        val outcome = owner.accept(mapOf("child-thread-1" to childProjection))
        val display = input.displayModel(owner.selectedProjection(), payload)

        assertFalse(input.acceptsForDisplay(childProjection))
        assertEquals(ThreadHeaderProjectionAcceptance.PreservedNoDisplayValidProjection, outcome)
        assertNull(owner.selectedProjection())
        assertNull(display.subtitle)
    }

    @Test
    fun replyThreadShowsNothingUntilLateExactParentProjectionWins() {
        val payload = activityReplyThreadPayload(subtitle = "#proj-uiux")
        val input = payload.threadHeaderProjectionRoutePayload().threadHeaderProjectionInput()
        val owner = ThreadHeaderProjectionStateOwner(input, emptyMap())
        val initialDisplay = input.displayModel(owner.selectedProjection(), payload)
        val parentProjection =
            channelProjection(
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                channelKind = "channel",
                title = "proj-uiux-renamed"
            )

        val outcome = owner.accept(mapOf("channel-1" to parentProjection))
        val hydratedDisplay = input.displayModel(owner.selectedProjection(), payload)
        val childOutcome =
            owner.accept(
                mapOf(
                    "thread-1" to
                        channelProjection(
                            canonicalChannelId = "thread-1",
                            aliases = setOf("thread-1", "channel-1"),
                            channelKind = "thread",
                            title = "thread-message"
                        )
                )
            )

        assertNull(initialDisplay.subtitle)
        assertEquals(ThreadHeaderProjectionAcceptance.AcceptedDisplayValidProjection, outcome)
        assertEquals(ThreadHeaderProjectionAcceptance.PreservedNoDisplayValidProjection, childOutcome)
        assertEquals(parentProjection, owner.selectedProjection())
        assertEquals("#proj-uiux-renamed", hydratedDisplay.subtitle)
    }

    @Test
    fun replyThreadAcceptsOnlyExactDirectMessageParentProjection() {
        val payload =
            activityReplyThreadPayload(subtitle = "#stale").copy(
                parentChannelId = "dm-parent",
                parentSurfaceKind = RaftThreadSurfaceKind.DirectMessage,
                parentChannelKind = "dm"
            )
        val input = payload.threadHeaderProjectionRoutePayload().threadHeaderProjectionInput()
        val exactParent =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "dm-parent",
                aliases = setOf("dm-parent"),
                conversationKind = ConversationKind.DirectMessage,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                title = "Cindy"
            )
        val relatedChild = exactParent.copy(
            canonicalChannelId = "child-thread-1",
            aliases = setOf("child-thread-1", "dm-parent")
        )

        assertTrue(input.acceptsForDisplay(exactParent))
        assertFalse(input.acceptsForDisplay(relatedChild))
        assertEquals("@Cindy", input.displayModel(exactParent, payload).subtitle)
    }

    @Test
    fun channelHeaderPaintsKnownHomeRowOnFirstFrame() {
        val loading = channelLaunchPayload()
        val input = loading.threadHeaderProjectionRoutePayload().threadHeaderProjectionInput()
        val known = channelProjection("channel-1", setOf("channel-1"), "channel", "general").copy(subtitle = "Ship it")

        val display = input.displayModelWithKnownCandidate(projection = null, contentPayload = loading, knownCandidate = known)

        assertEquals("general", display.title)
        assertEquals("Ship it", display.subtitle)
    }

    @Test
    fun canonicalChannelStateReplacesKnownCandidate() {
        val known = channelProjection("channel-1", setOf("channel-1"), "channel", "general (stale)")
        val loaded = channelLaunchPayload().copy(status = "content", title = "general", subtitle = "Fresh topic")
        val input = loaded.threadHeaderProjectionRoutePayload().threadHeaderProjectionInput()
        val canonicalProjection = known.copy(title = "general (canonical)")

        val fromLoadedPayload = input.displayModelWithKnownCandidate(null, loaded, known)
        val fromCanonicalProjection = input.displayModelWithKnownCandidate(canonicalProjection, loaded, known)

        assertEquals("general", fromLoadedPayload.title)
        assertEquals("Fresh topic", fromLoadedPayload.subtitle)
        assertEquals("general (canonical)", fromCanonicalProjection.title)
    }

    @Test
    fun knownCandidateOfAnotherSurfaceIsIgnoredAndNoCandidateKeepsPlaceholder() {
        val loading = channelLaunchPayload()
        val input = loading.threadHeaderProjectionRoutePayload().threadHeaderProjectionInput()
        val dmRow =
            channelProjection("channel-1", setOf("channel-1"), "dm", "Cindy").copy(
                conversationKind = ConversationKind.DirectMessage,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage
            )

        listOf(dmRow, null).forEach { candidate ->
            val display = input.displayModelWithKnownCandidate(null, loading, candidate)
            assertEquals(threadHeaderFallbackTitle(RaftThreadSurfaceKind.Channel), display.title, "candidate=$candidate")
            assertNull(display.subtitle)
        }
    }

    @Test
    fun directMessageHeaderPaintsKnownPeerOnFirstFrame() {
        val loading =
            channelLaunchPayload().copy(
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                streamChannelId = "dm-1",
                sourceThreadId = "dm-1"
            )
        val input = loading.threadHeaderProjectionRoutePayload().threadHeaderProjectionInput()
        val known =
            channelProjection("dm-1", setOf("dm-1"), "dm", "Cindy").copy(
                conversationKind = ConversationKind.DirectMessage,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                avatarWireValue = "pixel:cindy"
            )

        val display = input.displayModelWithKnownCandidate(null, loading, known)

        assertEquals("Cindy", display.title)
        assertEquals("pixel:cindy", display.dmPeerAvatarWireValue)
    }

    private fun channelLaunchPayload(): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "channel-1",
            title = "",
            status = "loading",
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelKind = "channel",
            sourceThreadId = "channel-1",
            streamChannelId = "channel-1"
        )

    private fun activityReplyThreadPayload(subtitle: String): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "activity-thread-1",
            title = ThreadReplyPageTitle,
            subtitle = subtitle,
            status = "content",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            channelKind = "channel",
            sourceThreadId = "thread-1",
            streamChannelId = "thread-1",
            parentChannelId = "channel-1",
            parentMessageId = "message-1",
            parentSurfaceKind = RaftThreadSurfaceKind.Channel,
            parentChannelKind = "channel"
        )

    private fun channelProjection(
        canonicalChannelId: String,
        aliases: Set<String>,
        channelKind: String,
        title: String
    ): SharedConversationDirectoryProjection =
        SharedConversationDirectoryProjection(
            serverId = "server-1",
            canonicalChannelId = canonicalChannelId,
            aliases = aliases,
            conversationKind = ConversationKind.Channel,
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelKind = channelKind,
            title = title
        )
}
