package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedReplyThreadRouteIdentityTest {
    @Test
    fun sameStreamCacheRestoresParentIdentityBeforeHeaderAndAccessProjection() {
        val route = replyPayload(
            parentChannelId = "joint-1",
            parentMessageId = "parent-1",
            parentChannelKind = JointChannelKindWireValue
        )
        val staleCache =
            route.copy(
                status = "content",
                parentSurfaceKind = null,
                parentChannelKind = null,
                joined = false,
                canSend = false,
                composer = route.composer.copy(enabled = false)
            )

        val restored = staleCache.withRouteContext(route)
        val parentProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "joint-1",
                aliases = setOf("joint-1"),
                conversationKind = ConversationKind.Joint,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = JointChannelKindWireValue,
                title = "raft-mobile-reconcile",
                subtitle = null,
                joined = true,
                archived = false,
                permissions = ConversationPermissions(canRead = true, canSend = true)
            )
        val writable =
            restored.withInitialHomeConversationAccessFallback(
                parentProjection.toConversationAccessCandidate()
            )
        val archived =
            restored.withInitialHomeConversationAccessFallback(
                parentProjection
                    .copy(joined = false, archived = true)
                    .toConversationAccessCandidate()
            )

        assertEquals(RaftThreadSurfaceKind.Channel, restored.parentSurfaceKind)
        assertEquals(JointChannelKindWireValue, restored.parentChannelKind)
        assertEquals(ConversationKind.Joint, restored.resolveThreadRouteIdentity().parentConversationKind)
        assertTrue(restored.threadHeaderProjectionInput().acceptsForDisplay(parentProjection))
        assertTrue(writable.joined)
        assertTrue(writable.canSend)
        assertTrue(writable.composer.enabled)
        assertTrue(
            shouldShowThreadComposerForReadyContent(
                joined = writable.joined,
                showComposer = writable.showComposer,
                selectedTab = writable.selectedTab
            )
        )
        assertFalse(archived.joined)
        assertFalse(archived.composer.enabled)
        assertFalse(
            shouldShowThreadComposerForReadyContent(
                joined = archived.joined,
                showComposer = archived.showComposer,
                selectedTab = archived.selectedTab
            )
        )
    }

    @Test
    fun conflictingParentPairCannotBeRelabeledByAnotherRoute() {
        val current = replyPayload("channel-a", "parent-a", "channel")
        val otherRoute = replyPayload("channel-b", "parent-b", "channel")

        val retained = current.withRouteContext(otherRoute)

        assertEquals("channel-a", retained.parentChannelId)
        assertEquals("parent-a", retained.parentMessageId)
        assertFalse(retained.parentChannelId == otherRoute.parentChannelId)
    }

    private fun replyPayload(
        parentChannelId: String,
        parentMessageId: String,
        parentChannelKind: String
    ): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "reply-$parentMessageId",
            status = "loading",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            channelKind = "thread",
            sourceThreadId = "thread-1",
            streamChannelId = "thread-1",
            parentChannelId = parentChannelId,
            parentMessageId = parentMessageId,
            originParentMessageId = parentMessageId,
            parentSurfaceKind = RaftThreadSurfaceKind.Channel,
            parentChannelKind = parentChannelKind,
            selectedTab = "chat",
            showComposer = true,
            joined = true,
            canSend = true,
            composer =
                buildThreadSurfaceComposerPayload(
                    surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                    displayName = ThreadReplyPageTitle,
                    fallbackId = parentMessageId,
                    enabled = true,
                    mentionChannelId = parentChannelId,
                    parentMessageId = parentMessageId,
                    streamChannelId = "thread-1"
                )
        )
}
