package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.model.ConversationKind
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadHeaderProjectionAcceptance
import build.raft.app.thread.ThreadHeaderProjectionStateOwner
import build.raft.app.thread.ThreadReplyPageTitle
import build.raft.app.thread.displayModelWithKnownCandidate
import build.raft.app.thread.threadHeaderProjectionInput
import build.raft.app.thread.threadHeaderProjectionRoutePayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SharedConversationHeaderProjectionTest {
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
