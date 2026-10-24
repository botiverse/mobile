package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.SharedWindowNavigationCoordinator
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.WindowNavigationAction
import build.raft.app.data.WindowNavigationIntent
import build.raft.app.data.WindowNavigationReadiness
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Message link → identity for the window navigation coordinator, or the one "not found" answer.
 * Wire shapes follow botiverse/slock@175cf23e9: `GET /api/channels/:id` answers the channel row
 * (`{ id, type, … }`), `GET /api/messages/context/:id?channelId=` answers `{ channelId,
 * targetMessageId, canonicalTarget?, messages, … }` (a thread reply's canonicalTarget is
 * `{ kind: "thread", channelId: <parent channel>, messageId, threadParentMessageId,
 * threadChannelId }`), and a target behind the history window answers 404 with `hiddenTarget`.
 */
class RaftMessageLinkResolverTest {
    private val serverId = "5e7a0c1d-0000-4000-8000-00000000a001"
    private val otherServerId = "5e7a0c1d-0000-4000-8000-00000000a002"
    private val slug = "lagoon-push-test"
    private val cid = "6f1c2b9e-1d2a-4c7b-9a51-0d3e8f7a6b21"
    private val pid = "a3b4c5d6-e7f8-4a1b-8c2d-3e4f5a6b7c8d"
    private val mid = "0b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9"
    private val threadChannelId = "7c7c7c7c-1111-4222-8333-444455556666"
    private val servers =
        listOf(
            StoredAuthServer(id = otherServerId, name = "Other", slug = "other"),
            StoredAuthServer(id = serverId, name = "Lagoon Push Test", slug = slug),
        )

    private fun channelBody(type: String = "channel") =
        """{"id":"$cid","name":"release-notes","type":"$type","serverId":"$serverId","joined":true}"""

    private fun contextBody(target: String, canonicalTarget: String? = null) =
        """{"channelId":"$cid","targetMessageId":"$target","hasOlder":false,"hasNewer":false,"messages":[],""" +
            (canonicalTarget?.let { "\"canonicalTarget\":$it," } ?: "") +
            """"threadSummariesByParentMessageId":{},"historyLimited":false,"channelArchived":false}"""

    private fun replyTarget(parent: String = pid) =
        """{"kind":"thread","channelId":"$cid","messageId":"$mid","threadParentMessageId":"$parent","threadChannelId":"$threadChannelId"}"""

    @Test
    fun serverTheAccountHasNotJoinedGoesToThePublicPreviewWithoutMemberRequests() = runTest {
        val network = FakeMessageLinkNetwork()
        val target = link(slug = "someone-elses", msg = mid)
        val result = RaftMessageLinkResolver(network).resolve(target, servers)
        assertEquals(RaftMessageLinkResolution.PublicPreview(target), result)
        assertTrue(network.requests.isEmpty())
    }

    @Test
    fun noAccessDeletedOrBadIdsAreOneNotFoundAnswerWithIdenticalRequests() = runTest {
        val requestShapes = mutableSetOf<List<Pair<String, List<Pair<String, String>>>>>()
        for (code in listOf(400, 403, 404)) {
            // No access to the conversation (or an id naming none) …
            val channelDenied = FakeMessageLinkNetwork(channel = HomeNetworkResponse(code, """{"error":"Channel not found"}"""))
            assertIs<RaftMessageLinkResolution.NotFound>(RaftMessageLinkResolver(channelDenied).resolve(link(msg = mid), servers))
            requestShapes += channelDenied.requests.map { it.path to it.query }

            // … or a deleted / unknown message in a readable one.
            val messageDenied =
                FakeMessageLinkNetwork(context = HomeNetworkResponse(code, """{"error":"Message not found"}"""))
            assertIs<RaftMessageLinkResolution.NotFound>(RaftMessageLinkResolver(messageDenied).resolve(link(msg = mid), servers))
            requestShapes += messageDenied.requests.map { it.path to it.query }
        }
        // Every not-found case sent exactly the same two requests.
        assertEquals(1, requestShapes.size, "requests differ between not-found cases: $requestShapes")
        assertEquals(listOf("channels/$cid", "messages/context/$mid"), requestShapes.single().map { it.first })
    }

    @Test
    fun channelMessageOpensTheChannelOnTheMessageThroughTheNotificationCoordinator() = runTest {
        val network = FakeMessageLinkNetwork(context = HomeNetworkResponse(200, contextBody(mid)))
        val result = RaftMessageLinkResolver(network).resolve(link(msg = mid), servers)

        val open = assertIs<RaftMessageLinkResolution.Open>(result)
        assertEquals(serverId, open.serverId)
        assertEquals(ConversationOpenSource.MessageLink, open.sourceIdentity.source)
        // Both probes ran on the link's server, with the channel scoping the message lookup.
        assertEquals(listOf("channels/$cid", "messages/context/$mid"), network.requests.map { it.path })
        assertTrue(network.requests.all { it.serverIdOverride == ServerId(serverId) })
        assertEquals(
            listOf("channelId" to cid, "before" to "0", "after" to "0"),
            network.requests[1].query
        )

        val request = openThroughCoordinator(open)
        assertEquals(ConversationKind.Channel, request.conversationIdentity.kind)
        assertEquals(cid, request.conversationIdentity.channelId?.value)
        assertEquals(mid, request.anchorMessageId)
    }

    @Test
    fun channelLinkWithoutMessageOnlyOpensTheChannel() = runTest {
        val network = FakeMessageLinkNetwork()
        val open = assertIs<RaftMessageLinkResolution.Open>(RaftMessageLinkResolver(network).resolve(link(), servers))
        assertEquals(listOf("channels/$cid"), network.requests.map { it.path })
        val request = openThroughCoordinator(open)
        assertEquals(ConversationKind.Channel, request.conversationIdentity.kind)
        assertNull(request.anchorMessageId)
    }

    @Test
    fun dmLinkMustNameADirectMessage() = runTest {
        val dm = FakeMessageLinkNetwork(channel = HomeNetworkResponse(200, channelBody("dm")))
        val open = assertIs<RaftMessageLinkResolution.Open>(
            RaftMessageLinkResolver(dm).resolve(link(kind = ConversationKind.DirectMessage), servers)
        )
        assertEquals(ConversationKind.DirectMessage, openThroughCoordinator(open).conversationIdentity.kind)

        val notDm = FakeMessageLinkNetwork(channel = HomeNetworkResponse(200, channelBody("channel")))
        assertIs<RaftMessageLinkResolution.NotFound>(
            RaftMessageLinkResolver(notDm).resolve(link(kind = ConversationKind.DirectMessage), servers)
        )
        val dmUnderChannelRoute = FakeMessageLinkNetwork(channel = HomeNetworkResponse(200, channelBody("dm")))
        assertIs<RaftMessageLinkResolution.NotFound>(RaftMessageLinkResolver(dmUnderChannelRoute).resolve(link(), servers))
    }

    @Test
    fun threadReplyOpensItsThreadWithTheParentOnTopAndTheReplyHighlighted() = runTest {
        val network = FakeMessageLinkNetwork(context = HomeNetworkResponse(200, contextBody(mid, replyTarget())))
        val open = assertIs<RaftMessageLinkResolution.Open>(
            RaftMessageLinkResolver(network).resolve(link(msg = mid, threadParent = pid), servers)
        )
        assertEquals(threadChannelId, open.sourceIdentity.threadChannelId)
        val request = openThroughCoordinator(open)
        assertEquals(ConversationKind.Thread, request.conversationIdentity.kind)
        assertEquals(cid, request.conversationIdentity.threadParentChannelId())
        assertEquals(pid, request.conversationIdentity.threadParentMessageId())
        assertEquals(mid, request.anchorMessageId)
    }

    @Test
    fun replyOfAnotherThreadOrATopLevelMessageIsNotFoundForAThreadLink() = runTest {
        val otherThread =
            FakeMessageLinkNetwork(context = HomeNetworkResponse(200, contextBody(mid, replyTarget(parent = "ffffffff-0000-4000-8000-000000000000"))))
        assertEquals(
            RaftMessageLinkResolution.NotFound("thread_reply_mismatch"),
            RaftMessageLinkResolver(otherThread).resolve(link(msg = mid, threadParent = pid), servers)
        )
        val topLevel = FakeMessageLinkNetwork(context = HomeNetworkResponse(200, contextBody(mid)))
        assertEquals(
            RaftMessageLinkResolution.NotFound("thread_reply_mismatch"),
            RaftMessageLinkResolver(topLevel).resolve(link(msg = mid, threadParent = pid), servers)
        )
    }

    @Test
    fun threadWithoutMessageOpensTheThreadOnItsParent() = runTest {
        val network = FakeMessageLinkNetwork(context = HomeNetworkResponse(200, contextBody(pid)))
        val open = assertIs<RaftMessageLinkResolution.Open>(
            RaftMessageLinkResolver(network).resolve(link(threadParent = pid), servers)
        )
        assertEquals("messages/context/$pid", network.requests.last().path)
        val request = openThroughCoordinator(open)
        assertEquals(ConversationKind.Thread, request.conversationIdentity.kind)
        assertEquals(pid, request.conversationIdentity.threadParentMessageId())
        assertEquals(pid, request.anchorMessageId)
    }

    @Test
    fun channelLinkToAReplyOpensItsThreadAsTheWebDoes() = runTest {
        val network = FakeMessageLinkNetwork(context = HomeNetworkResponse(200, contextBody(mid, replyTarget())))
        val open = assertIs<RaftMessageLinkResolution.Open>(RaftMessageLinkResolver(network).resolve(link(msg = mid), servers))
        val request = openThroughCoordinator(open)
        assertEquals(ConversationKind.Thread, request.conversationIdentity.kind)
        assertEquals(pid, request.conversationIdentity.threadParentMessageId())
        assertEquals(mid, request.anchorMessageId)
    }

    @Test
    fun messageBehindTheHistoryWindowStillOpensOnItsPaywall() = runTest {
        val network =
            FakeMessageLinkNetwork(
                context =
                    HomeNetworkResponse(
                        404,
                        """{"error":"history_limited","channelId":"$cid","targetMessageId":"$mid","hiddenTarget":{"target":{"id":"$mid","seq":3}}}"""
                    )
            )
        assertIs<RaftMessageLinkResolution.Open>(RaftMessageLinkResolver(network).resolve(link(msg = mid), servers))
    }

    @Test
    fun serverErrorTimeoutOrOfflineNeverShowsNotFoundButOpensTheConversation() = runTest {
        // 500/502/503, 408 timeout, 429, 401 (auth layer), 0 = no HTTP status (offline/DNS).
        for (code in listOf(500, 502, 503, 408, 429, 401, 0)) {
            for (network in listOf(
                FakeMessageLinkNetwork(context = HomeNetworkResponse(code, null)),
                FakeMessageLinkNetwork(channel = HomeNetworkResponse(code, null)),
            )) {
                val result = RaftMessageLinkResolver(network).resolve(link(msg = mid), servers)
                // Open: the conversation page keeps its own retry/offline UI.
                assertIs<RaftMessageLinkResolution.Open>(result, "code=$code")
            }
        }
        // A thrown transport error (socket timeout, DNS failure) is the same.
        for (network in listOf(FakeMessageLinkNetwork(throwOnContext = true), FakeMessageLinkNetwork(throwOnChannel = true))) {
            assertIs<RaftMessageLinkResolution.Open>(RaftMessageLinkResolver(network).resolve(link(msg = mid), servers))
        }
    }

    @Test
    fun linkSlugMatchesCaseInsensitively() = runTest {
        val open = assertIs<RaftMessageLinkResolution.Open>(
            RaftMessageLinkResolver(FakeMessageLinkNetwork()).resolve(link(slug = "Lagoon-Push-Test"), servers)
        )
        assertEquals(serverId, open.serverId)
    }

    private fun link(
        slug: String = this.slug,
        kind: ConversationKind = ConversationKind.Channel,
        msg: String? = null,
        threadParent: String? = null,
    ) = RaftMessageLink(slug, kind, cid, messageId = msg, threadParentMessageId = threadParent)

    /** The resolved link through the coordinator a notification tap uses, on a ready Home. */
    private fun openThroughCoordinator(open: RaftMessageLinkResolution.Open): HomeMessageOpenRequest {
        val coordinator = SharedWindowNavigationCoordinator()
        val intent =
            WindowNavigationIntent(
                serverId = open.serverId,
                sourceIdentity = open.sourceIdentity,
                source = MessageLinkNavigationSource,
                launchId = "message-link-1",
            )
        assertTrue(coordinator.enqueue(intent))
        val action =
            coordinator.advance(
                WindowNavigationReadiness(
                    knownServerIds = setOf(serverId, otherServerId),
                    canonicalServerId = open.serverId,
                    homeSnapshotServerId = open.serverId,
                    homeSessionServerId = open.serverId,
                )
            )
        return assertIs<WindowNavigationAction.OpenConversation>(action).request
    }
}

private data class RecordedMessageLinkRequest(
    val path: String,
    val query: List<Pair<String, String>>,
    val serverIdOverride: ServerId?,
    val priority: DataRequestPriority,
)

private class FakeMessageLinkNetwork(
    private val channel: HomeNetworkResponse? = null,
    private val context: HomeNetworkResponse? = null,
    private val throwOnContext: Boolean = false,
    private val throwOnChannel: Boolean = false,
) : RaftNetworkClient {
    val requests = mutableListOf<RecordedMessageLinkRequest>()

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?,
    ): HomeNetworkResponse {
        requests += RecordedMessageLinkRequest(path, query, serverIdOverride, priority)
        return when {
            path.startsWith("channels/") ->
                if (throwOnChannel) error("timeout") else channel ?: HomeNetworkResponse(200, """{"id":"${path.removePrefix("channels/")}","name":"release-notes","type":"channel"}""")
            path.startsWith("messages/context/") -> {
                if (throwOnContext) error("socket closed")
                context ?: HomeNetworkResponse(200, """{"channelId":null,"messages":[]}""")
            }
            else -> error("unexpected request $path")
        }
    }
}
