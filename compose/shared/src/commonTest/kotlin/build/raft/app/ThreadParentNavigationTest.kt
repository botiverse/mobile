package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class ThreadParentNavigationTest {
    @Test
    fun jointReplyThreadOpenParentRebuildsFromTypedKindWhenProjectionIsMissing() {
        val parentRequest =
            assertNotNull(
                replyPayload(parentChannelId = "joint-parent", parentChannelKind = "joint")
                    .resolveReplyParentConversationOpenRequest(
                        serverId = "server-source",
                        conversationIdentityProvider = emptyProvider()
                    )
            )

        assertEquals(ConversationKind.Joint, parentRequest.conversationIdentity.kind)
        assertEquals("joint-parent", parentRequest.conversationIdentity.channelId?.value)
        assertEquals("joint", parentRequest.conversationIdentity.resolveThreadRouteIdentity().routeKind)
    }

    @Test
    fun replyThreadOpenParentRejectsProjectionKindMismatch() {
        val channelProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-source",
                canonicalChannelId = "joint-parent",
                aliases = setOf("joint-parent"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "general"
            )

        assertNull(
            replyPayload(parentChannelId = "joint-parent", parentChannelKind = "joint")
                .resolveReplyParentConversationOpenRequest(
                    serverId = "server-source",
                    conversationIdentityProvider = emptyProvider(channelProjection)
                )
        )
    }

    @Test
    fun replyThreadOpenParentRejectsProjectionFromAnotherServer() {
        val otherServerProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-other",
                canonicalChannelId = "joint-parent",
                aliases = setOf("joint-parent"),
                conversationKind = ConversationKind.Joint,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "joint",
                title = "partner"
            )

        assertNull(
            replyPayload(parentChannelId = "joint-parent", parentChannelKind = "joint")
                .resolveReplyParentConversationOpenRequest(
                    serverId = "server-source",
                    conversationIdentityProvider = emptyProvider(otherServerProjection)
                )
        )
    }

    @Test
    fun replyThreadOpenParentRequiresTypedKindWhenProjectionIsMissing() {
        assertNull(
            replyPayload(parentChannelId = "parent-channel", parentChannelKind = null)
                .resolveReplyParentConversationOpenRequest(
                    serverId = "server-1",
                    conversationIdentityProvider = emptyProvider()
                )
        )
    }

    @Test
    fun jointReplyThreadOpenParentRejectsInternalStorageIdentity() {
        assertNull(
            replyPayload(parentChannelId = "joint-storage-parent", parentChannelKind = "joint")
                .resolveReplyParentConversationOpenRequest(
                    serverId = "server-1",
                    conversationIdentityProvider = null
                )
        )
    }

    private fun replyPayload(
        parentChannelId: String,
        parentChannelKind: String?
    ): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-reply-stream",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            channelKind = "channel",
            sourceThreadId = "reply-stream",
            streamChannelId = "reply-stream",
            parentChannelId = parentChannelId,
            parentMessageId = "parent-message",
            parentSurfaceKind = RaftThreadSurfaceKind.Channel,
            parentChannelKind = parentChannelKind,
            showOpenInChannelAction = true
        )

    private fun emptyProvider(
        projection: SharedConversationDirectoryProjection? = null
    ): SharedConversationIdentityProvider =
        object : SharedConversationIdentityProvider {
            override fun observeServer(serverId: ServerId): Flow<SharedConversationDirectoryState> =
                flowOf(SharedConversationDirectoryState())

            override fun projectionFlow(
                serverId: ServerId,
                ids: Collection<String>
            ): Flow<Map<String, SharedConversationDirectoryProjection>> = flowOf(emptyMap())

            override fun resolveProjection(
                serverId: ServerId,
                id: String
            ): SharedConversationDirectoryProjection? = projection
        }
}
