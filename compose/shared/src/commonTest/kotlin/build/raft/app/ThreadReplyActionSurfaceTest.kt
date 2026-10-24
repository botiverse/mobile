package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Reply in Thread" is derived from the surface kind (web: `hideThreadActions` only in the thread
 * panel), never stored. Every way of building a ReplyThread payload must give `reply = false`.
 */
class ThreadReplyActionSurfaceTest {
    private val message =
        RaftThreadMessageItem(id = "m-1", authorId = "member-1", authorName = "Member", body = "Hello")

    private fun replyCapability(payload: RaftThreadScreenPayload): Boolean =
        threadMessageMenuCapabilities(
            message = message,
            replyMetadata = message.toThreadReplyMetadata(),
            slice = payload.toThreadTimelineSlice(),
            writeActionsEnabled = true
        ).reply

    private fun assertReply(expected: Boolean, payload: RaftThreadScreenPayload) {
        assertEquals(expected, replyCapability(payload), "menu capability for ${payload.surfaceKind}")
        assertEquals(expected, payload.showReplyActions, "payload for ${payload.surfaceKind}")
        assertEquals(expected, payload.toRenderSignature().showReplyActions, "signature for ${payload.surfaceKind}")
    }

    @Test
    fun replyThreadPayloadsFromEveryFactoryHideReply() {
        // Bare constructor (the prewarm shape that used to leak the default `true`).
        assertReply(false, RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.ReplyThread))

        val wire =
            WireThreadLaunchPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "reply",
                streamChannelId = "reply",
                parentChannelId = "channel",
                parentMessageId = "message",
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelKind = "channel"
            )
        assertReply(false, wire.toInitialPayload("fallback"))

        assertReply(
            false,
            RaftThreadScreenPayload().replyThreadLoadingPayload(
                parentMessageId = "message",
                parentChannelId = "channel",
                threadChannelId = "reply"
            )
        )
        assertReply(
            false,
            RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.DirectMessage)
                .replyThreadLoadingPayload("message", "dm-1", "reply")
        )
    }

    @Test
    fun channelAndDirectMessagePayloadsOfferReply() {
        assertReply(true, RaftThreadScreenPayload())
        assertReply(true, RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.DirectMessage))
        assertReply(true, WireThreadLaunchPayload(surfaceKind = RaftThreadSurfaceKind.Channel, threadId = "channel", sourceThreadId = "channel").toInitialPayload("f"))
    }

    @Test
    fun localSendRowsStillHideReplyOnChannel() {
        val sending = message.copy(deliveryState = ThreadMessageDeliverySending)
        val reply =
            threadMessageMenuCapabilities(
                message = sending,
                replyMetadata = sending.toThreadReplyMetadata(),
                slice = RaftThreadScreenPayload().toThreadTimelineSlice(),
                writeActionsEnabled = true
            ).reply
        assertEquals(false, reply)
    }
}
