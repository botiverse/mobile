package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.model.ConversationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedReplyThreadParentFailurePolicyTest {
    @Test
    fun canonicalRepliesAreReadableForChannelAndDirectParentsWithoutOriginEnrichment() {
        val channel = readableReplyPayload(parentSurfaceKind = RaftThreadSurfaceKind.Channel, parentChannelKind = "joint")
        val direct = readableReplyPayload(parentSurfaceKind = RaftThreadSurfaceKind.DirectMessage, parentChannelKind = null)

        assertTrue(channel.hasReadableCanonicalReplyContent())
        assertTrue(direct.hasReadableCanonicalReplyContent())
        assertEquals("error", channel.originStatus)
        assertNull(channel.originMessage)
    }

    @Test
    fun canonicalReplyReadFallbackRequiresContentAndCompleteRouteIdentity() {
        val readable = readableReplyPayload()

        assertFalse(readable.copy(status = "loading").hasReadableCanonicalReplyContent())
        assertFalse(readable.copy(messages = emptyList()).hasReadableCanonicalReplyContent())
        assertFalse(readable.copy(streamChannelId = null, sourceThreadId = null).hasReadableCanonicalReplyContent())
        assertFalse(readable.copy(parentChannelKind = null).hasReadableCanonicalReplyContent())
    }

    @Test
    fun readOnlyParentFailureSuppressesParentTaskLookup() {
        val payload = readableReplyPayload(parentChannelId = "joint-storage-parent")

        assertEquals("joint-storage-parent", payload.resolvedTaskLookupChannelId())
        assertNull(payload.resolvedTaskLookupChannelId(writeActionsEnabled = false))
    }

    @Test
    fun replyHeaderRejectsStorageAndUuidNamesButKeepsSafeLogicalJointName() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        val payload =
            readableReplyPayload(
                parentChannelId = "joint-storage-$uuid",
                parentChannelKind = "joint"
            )
        val input = payload.threadHeaderProjectionRoutePayload().threadHeaderProjectionInput()

        listOf("joint-storage-$uuid", uuid).forEach { unsafeTitle ->
            val unsafe = parentProjection(payload.parentChannelId.orEmpty(), unsafeTitle)
            val display = input.displayModel(unsafe, payload.copy(subtitle = "#$unsafeTitle"))

            assertEquals(ThreadReplyPageTitle, display.title)
            assertNull(display.subtitle)
            assertEquals(
                "话题",
                localizedThreadHeaderTitle(
                    title = display.title,
                    surfaceKind = display.surfaceKind,
                    localizedReplyThread = "话题",
                    localizedChannel = "频道",
                    localizedDirectMessage = "私信"
                )
            )
        }

        val safe = parentProjection(payload.parentChannelId.orEmpty(), "joint-product")
        assertEquals("#joint-product", input.displayModel(safe, payload).subtitle)
    }

    private fun readableReplyPayload(
        parentSurfaceKind: String = RaftThreadSurfaceKind.Channel,
        parentChannelKind: String? = "channel",
        parentChannelId: String = "channel-1"
    ): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "reply-message-1",
            title = ThreadReplyPageTitle,
            subtitle = null,
            status = "content",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            sourceThreadId = "thread-message-1",
            streamChannelId = "thread-message-1",
            parentChannelId = parentChannelId,
            parentMessageId = "message-1",
            originParentMessageId = "message-1",
            parentSurfaceKind = parentSurfaceKind,
            parentChannelKind = parentChannelKind,
            originStatus = "error",
            originErrorCode = "channel_not_found",
            originMessage = null,
            messages =
                listOf(
                    RaftThreadMessageItem(
                        id = "reply-1",
                        authorId = "user-1",
                        authorName = "Artin",
                        body = "readable reply"
                    )
                )
        )

    private fun parentProjection(parentChannelId: String, title: String): SharedConversationDirectoryProjection =
        SharedConversationDirectoryProjection(
            serverId = "server-1",
            canonicalChannelId = parentChannelId,
            aliases = setOf(parentChannelId),
            conversationKind = ConversationKind.Joint,
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelKind = "joint",
            title = title,
            subtitle = null
        )
}
