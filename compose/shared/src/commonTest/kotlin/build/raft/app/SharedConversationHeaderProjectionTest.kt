package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.model.ConversationKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedConversationHeaderProjectionTest {
    @Test
    fun activityReplyThreadKeepsLoadedParentChannelSubtitleWithoutDirectoryProjection() {
        val payload = activityReplyThreadPayload(subtitle = "#general")
        val input = payload.threadHeaderProjectionRoutePayload().threadHeaderProjectionInput()

        val display = input.displayModel(projection = null, contentPayload = payload)

        assertEquals(ThreadReplyPageTitle, display.title)
        assertEquals("#general", display.subtitle)
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
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "general",
                subtitle = null
            )

        val display = input.displayModel(projection = projection, contentPayload = payload)

        assertEquals(ThreadReplyPageTitle, display.title)
        assertEquals("#general", display.subtitle)
    }

    private fun activityReplyThreadPayload(subtitle: String): KuiklyThreadScreenPayload =
        KuiklyThreadScreenPayload(
            instanceId = "activity-thread-1",
            title = ThreadReplyPageTitle,
            subtitle = subtitle,
            status = "content",
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            channelKind = "channel",
            sourceThreadId = "thread-1",
            streamChannelId = "thread-1",
            parentChannelId = "channel-1",
            parentMessageId = "message-1",
            parentSurfaceKind = KuiklyThreadSurfaceKind.Channel,
            parentChannelKind = "channel"
        )
}
