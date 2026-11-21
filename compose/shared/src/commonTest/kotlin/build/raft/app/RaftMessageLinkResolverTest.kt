package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftMessageContextPageRequest
import build.raft.app.data.RaftMessageContextPageSink
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedWindowNavigationCoordinator
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.WindowNavigationAction
import build.raft.app.data.WindowNavigationIntent
import build.raft.app.data.WindowNavigationReadiness
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
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

    private fun channelBody(
        type: String = "channel",
        id: String = cid,
        parentChannelId: String? = null,
        parentMessageId: String? = null,
    ): String {
        val parentFields =
            buildString {
                if (parentChannelId != null) append(""","parentChannelId":"$parentChannelId"""")
                if (parentMessageId != null) append(""","parentMessageId":"$parentMessageId"""")
            }
        return """{"id":"$id","name":"release-notes","type":"$type","serverId":"$serverId","joined":true$parentFields}"""
    }

    private fun contextBody(target: String, canonicalTarget: String? = null, channelId: String = cid) =
        """{"channelId":"$channelId","targetMessageId":"$target","hasOlder":false,"hasNewer":false,"messages":[],""" +
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
            // (slock #12371: the reader's other servers are then asked too; the link's server saw the same two.)
            requestShapes += channelDenied.requests.filter { it.serverIdOverride == ServerId(serverId) }.map { it.path to it.query }

            // … or a deleted / unknown message in a readable one.
            val messageDenied =
                FakeMessageLinkNetwork(context = HomeNetworkResponse(code, """{"error":"Message not found"}"""))
            assertIs<RaftMessageLinkResolution.NotFound>(RaftMessageLinkResolver(messageDenied).resolve(link(msg = mid), servers))
            requestShapes += messageDenied.requests.filter { it.serverIdOverride == ServerId(serverId) }.map { it.path to it.query }
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
    fun linkToATaskConversationOpensTheTaskNotAChannelPage() = runTest {
        // slock #14960: `/channel/<task conversation>?msg=<id>` (a task message's Copy link) opens the
        // task at the message; without `msg`, the task's conversation. The row says it is a task's.
        for (msg in listOf(mid, null)) {
            val network =
                FakeMessageLinkNetwork(
                    channel = HomeNetworkResponse(200, channelBody(RaftTaskConversationChannelKind)),
                    context = HomeNetworkResponse(200, contextBody(mid)),
                )
            val open = assertIs<RaftMessageLinkResolution.Open>(RaftMessageLinkResolver(network).resolve(link(msg = msg), servers))
            assertEquals(ConversationKind.Task, open.sourceIdentity.conversationKind, "msg=$msg")
            val request = openThroughCoordinator(open)
            assertEquals(ConversationKind.Task, request.conversationIdentity.kind, "msg=$msg")
            assertEquals(cid, request.conversationIdentity.channelId?.value, "msg=$msg")
            assertEquals(msg, request.anchorMessageId, "msg=$msg")
        }
        // A task conversation is never a DM.
        val underDmRoute = FakeMessageLinkNetwork(channel = HomeNetworkResponse(200, channelBody(RaftTaskConversationChannelKind)))
        assertIs<RaftMessageLinkResolution.NotFound>(
            RaftMessageLinkResolver(underDmRoute).resolve(link(kind = ConversationKind.DirectMessage), servers)
        )
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
    fun shortMessageIdsAreOpenedWithTheFullIdsTheLookupAnswers() = runTest {
        // slock #15521: a link may name its message or thread parent by its 8-hex short id.
        val shortMid = mid.take(8)
        val shortPid = pid.take(8)

        // A reply link with both ids short opens the thread by the full ids.
        val reply = FakeMessageLinkNetwork(context = HomeNetworkResponse(200, contextBody(mid, replyTarget())))
        val replyOpen = assertIs<RaftMessageLinkResolution.Open>(
            RaftMessageLinkResolver(reply).resolve(link(msg = shortMid, threadParent = shortPid), servers)
        )
        assertEquals("messages/context/$shortMid", reply.requests[1].path)
        val replyRequest = openThroughCoordinator(replyOpen)
        assertEquals(pid, replyRequest.conversationIdentity.threadParentMessageId())
        assertEquals(mid, replyRequest.anchorMessageId)

        // A thread-only link with a short parent.
        val parentOnly = FakeMessageLinkNetwork(context = HomeNetworkResponse(200, contextBody(pid)))
        val parentRequest =
            openThroughCoordinator(
                assertIs<RaftMessageLinkResolution.Open>(RaftMessageLinkResolver(parentOnly).resolve(link(threadParent = shortPid), servers))
            )
        assertEquals(pid, parentRequest.conversationIdentity.threadParentMessageId())

        // A channel link with a short message id lands on the full id.
        val channel = FakeMessageLinkNetwork(context = HomeNetworkResponse(200, contextBody(mid)))
        val channelRequest =
            openThroughCoordinator(assertIs<RaftMessageLinkResolution.Open>(RaftMessageLinkResolver(channel).resolve(link(msg = shortMid), servers)))
        assertEquals(mid, channelRequest.anchorMessageId)

        // A short parent that names another thread is still a mismatch.
        val otherThread = FakeMessageLinkNetwork(context = HomeNetworkResponse(200, contextBody(mid, replyTarget())))
        assertEquals(
            RaftMessageLinkResolution.NotFound("thread_reply_mismatch"),
            RaftMessageLinkResolver(otherThread).resolve(link(msg = shortMid, threadParent = "ffffffff"), servers)
        )
    }

    @Test
    fun aShortIdMatchesOnlyItsOwnFullId() {
        assertTrue(raftMessageIdMatches(mid.take(8), mid))
        assertTrue(raftMessageIdMatches(mid, mid))
        assertTrue(raftMessageIdMatches(mid.take(8).uppercase(), mid))
        assertTrue(!raftMessageIdMatches("ffffffff", mid))
        assertTrue(!raftMessageIdMatches(mid.take(7), mid))
        assertTrue(!raftMessageIdMatches(null, mid))
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
    fun linkToAThreadByItsOwnChannelIdOpensTheParentChannelsThread() = runTest {
        // slock #15879: `/channel/<threadChannelId>[?msg=…]` redirects to the parent conversation
        // with the thread open at `msg` (or its parent message when `msg` is omitted).
        val threadRow = channelBody(type = "thread", id = threadChannelId, parentChannelId = cid, parentMessageId = pid)

        // With full reply id (plain thread-channel context without canonicalTarget, as local threads answer).
        val offered = mutableListOf<Pair<String?, String?>>()
        val sink =
            object : RaftMessageContextPageSink {
                override suspend fun beginRequest(serverId: String?) =
                    object : RaftMessageContextPageRequest {
                        override val serverId: String = serverId.orEmpty()
                        override fun offer(bodyText: String, channelId: String?, channelType: String?) {
                            offered += channelId to channelType
                        }
                    }
            }
        val replyNetwork =
            FakeMessageLinkNetwork(
                channelsById =
                    mapOf(
                        threadChannelId to HomeNetworkResponse(200, threadRow),
                        cid to HomeNetworkResponse(200, channelBody(type = "channel", id = cid)),
                    ),
                context = HomeNetworkResponse(200, contextBody(mid, channelId = threadChannelId)),
            )
        val replyOpen =
            assertIs<RaftMessageLinkResolution.Open>(
                RaftMessageLinkResolver(replyNetwork, contextPageSink = sink).resolve(
                    link(channelId = threadChannelId, msg = mid),
                    servers,
                )
            )
        assertEquals(threadChannelId, replyOpen.sourceIdentity.threadChannelId)
        assertEquals(ConversationKind.Channel, replyOpen.sourceIdentity.parentConversationKind)
        assertEquals(listOf<Pair<String?, String?>>(threadChannelId to "thread"), offered)
        val replyRequest = openThroughCoordinator(replyOpen)
        assertEquals(ConversationKind.Thread, replyRequest.conversationIdentity.kind)
        assertEquals(cid, replyRequest.conversationIdentity.threadParentChannelId())
        assertEquals(pid, replyRequest.conversationIdentity.threadParentMessageId())
        assertEquals(threadChannelId, replyRequest.conversationIdentity.thread?.streamChannelId?.value)
        assertEquals(RaftThreadSurfaceKind.Channel, replyRequest.conversationIdentity.thread?.parentSurfaceKind)
        assertEquals(mid, replyRequest.anchorMessageId)

        // With 8-hex short reply id: expands to the full id from context lookup.
        val shortMid = mid.take(8)
        val shortNetwork =
            FakeMessageLinkNetwork(
                channelsById =
                    mapOf(
                        threadChannelId to HomeNetworkResponse(200, threadRow),
                        cid to HomeNetworkResponse(200, channelBody(type = "channel", id = cid)),
                    ),
                context = HomeNetworkResponse(200, contextBody(mid, channelId = threadChannelId)),
            )
        val shortRequest =
            openThroughCoordinator(
                assertIs<RaftMessageLinkResolution.Open>(
                    RaftMessageLinkResolver(shortNetwork).resolve(link(channelId = threadChannelId, msg = shortMid), servers)
                )
            )
        assertEquals(cid, shortRequest.conversationIdentity.threadParentChannelId())
        assertEquals(pid, shortRequest.conversationIdentity.threadParentMessageId())
        assertEquals(mid, shortRequest.anchorMessageId)

        // Without `?msg=`: probes the thread and its parent channel, then opens anchored on parentMessageId.
        val bareNetwork =
            FakeMessageLinkNetwork(
                channelsById =
                    mapOf(
                        threadChannelId to HomeNetworkResponse(200, threadRow),
                        cid to HomeNetworkResponse(200, channelBody(type = "channel", id = cid)),
                    )
            )
        val bareOpen =
            assertIs<RaftMessageLinkResolution.Open>(
                RaftMessageLinkResolver(bareNetwork).resolve(link(channelId = threadChannelId), servers)
            )
        assertEquals(listOf("channels/$threadChannelId", "channels/$cid"), bareNetwork.requests.map { it.path })
        val bareRequest = openThroughCoordinator(bareOpen)
        assertEquals(ConversationKind.Thread, bareRequest.conversationIdentity.kind)
        assertEquals(cid, bareRequest.conversationIdentity.threadParentChannelId())
        assertEquals(pid, bareRequest.conversationIdentity.threadParentMessageId())
        assertEquals(threadChannelId, bareRequest.conversationIdentity.thread?.streamChannelId?.value)
        assertEquals(RaftThreadSurfaceKind.Channel, bareRequest.conversationIdentity.thread?.parentSurfaceKind)
        assertEquals(pid, bareRequest.anchorMessageId)

        // Parent probe confirms DM and Joint parent kinds even with an empty conversation directory
        // (archived channels, Agent Lounges, or cold server switches).
        val dmParentNetwork =
            FakeMessageLinkNetwork(
                channelsById =
                    mapOf(
                        threadChannelId to HomeNetworkResponse(200, threadRow),
                        cid to HomeNetworkResponse(200, channelBody(type = "dm", id = cid)),
                    )
            )
        val dmParentOpen =
            assertIs<RaftMessageLinkResolution.Open>(
                RaftMessageLinkResolver(dmParentNetwork).resolve(link(channelId = threadChannelId), servers)
            )
        assertEquals(ConversationKind.DirectMessage, dmParentOpen.sourceIdentity.parentConversationKind)
        val dmParentRequest = openThroughCoordinator(dmParentOpen)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, dmParentRequest.conversationIdentity.thread?.parentSurfaceKind)

        val jointParentNetwork =
            FakeMessageLinkNetwork(
                channelsById =
                    mapOf(
                        threadChannelId to HomeNetworkResponse(200, threadRow),
                        cid to HomeNetworkResponse(200, channelBody(type = "joint", id = cid)),
                    ),
                context = HomeNetworkResponse(200, contextBody(mid, canonicalTarget = replyTarget(pid), channelId = threadChannelId)),
            )
        val jointParentOpen =
            assertIs<RaftMessageLinkResolution.Open>(
                RaftMessageLinkResolver(jointParentNetwork).resolve(link(channelId = threadChannelId, msg = mid), servers)
            )
        assertEquals(ConversationKind.Joint, jointParentOpen.sourceIdentity.parentConversationKind)
        val jointParentRequest = openThroughCoordinator(jointParentOpen)
        assertEquals(RaftThreadSurfaceKind.Joint, jointParentRequest.conversationIdentity.thread?.parentSurfaceKind)

        // Transient parent-probe failure keeps `parentConversationKind = null` (never NotFound)
        // and falls back to the Home conversation directory.
        val transientParentNetwork =
            FakeMessageLinkNetwork(
                channelsById =
                    mapOf(
                        threadChannelId to HomeNetworkResponse(200, threadRow),
                        cid to HomeNetworkResponse(503, null),
                    )
            )
        val transientParentOpen =
            assertIs<RaftMessageLinkResolution.Open>(
                RaftMessageLinkResolver(transientParentNetwork).resolve(link(channelId = threadChannelId), servers)
            )
        assertNull(transientParentOpen.sourceIdentity.parentConversationKind)
        val dmDirectory =
            listOf(
                SharedConversationDirectoryProjection(
                    serverId = serverId,
                    canonicalChannelId = cid,
                    aliases = setOf(cid),
                    conversationKind = ConversationKind.DirectMessage,
                    surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                    channelKind = "dm",
                    title = "Alice",
                    permissions = ConversationPermissions(),
                )
            ).toSharedConversationDirectoryState()
        val directoryFallbackRequest = openThroughCoordinator(transientParentOpen, conversationDirectory = dmDirectory)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, directoryFallbackRequest.conversationIdentity.thread?.parentSurfaceKind)
    }

    @Test
    fun threadChannelLinkEdgeCasesFollowWebAndResolverRules() = runTest {
        val threadRow = channelBody(type = "thread", id = threadChannelId, parentChannelId = cid, parentMessageId = pid)
        val validChannels =
            mapOf(
                threadChannelId to HomeNetworkResponse(200, threadRow),
                cid to HomeNetworkResponse(200, channelBody(type = "channel", id = cid)),
            )

        // `?msg=` naming the thread's own parent message (full or 8-hex short): `messages/context`
        // scoped to threadChannelId 404s because the parent lives in parentChannelId, yet the
        // thread probe already confirmed it.
        for (parentQuery in listOf(pid, pid.take(8))) {
            val parentMsgNetwork =
                FakeMessageLinkNetwork(
                    channelsById = validChannels,
                    context = HomeNetworkResponse(404, """{"error":"Message not found"}"""),
                )
            val request =
                openThroughCoordinator(
                    assertIs<RaftMessageLinkResolution.Open>(
                        RaftMessageLinkResolver(parentMsgNetwork).resolve(
                            link(channelId = threadChannelId, msg = parentQuery),
                            servers,
                        )
                    )
                )
            assertEquals(ConversationKind.Thread, request.conversationIdentity.kind)
            assertEquals(cid, request.conversationIdentity.threadParentChannelId())
            assertEquals(pid, request.conversationIdentity.threadParentMessageId())
            assertEquals(pid, request.anchorMessageId)
        }

        // Deleted / unrelated message in a readable thread stays NotFound.
        val missingMsgNetwork =
            FakeMessageLinkNetwork(
                channelsById = validChannels,
                context = HomeNetworkResponse(404, """{"error":"Message not found"}"""),
            )
        assertEquals(
            RaftMessageLinkResolution.NotFound("message_404"),
            RaftMessageLinkResolver(missingMsgNetwork).resolve(link(channelId = threadChannelId, msg = mid), servers),
        )

        // Mismatched canonicalTarget (different parentMessageId or parentChannelId) stays NotFound.
        val wrongParentMsgNetwork =
            FakeMessageLinkNetwork(
                channelsById = validChannels,
                context = HomeNetworkResponse(200, contextBody(mid, canonicalTarget = replyTarget("other-parent"), channelId = threadChannelId)),
            )
        assertEquals(
            RaftMessageLinkResolution.NotFound("thread_reply_mismatch"),
            RaftMessageLinkResolver(wrongParentMsgNetwork).resolve(link(channelId = threadChannelId, msg = mid), servers),
        )
        val wrongChannelMsgNetwork =
            FakeMessageLinkNetwork(
                channelsById = validChannels,
                context =
                    HomeNetworkResponse(
                        200,
                        contextBody(
                            mid,
                            canonicalTarget =
                                """{"kind":"thread","channelId":"other-channel","messageId":"$mid","threadParentMessageId":"$pid","threadChannelId":"$threadChannelId"}""",
                            channelId = threadChannelId,
                        ),
                    ),
            )
        assertEquals(
            RaftMessageLinkResolution.NotFound("thread_reply_mismatch"),
            RaftMessageLinkResolver(wrongChannelMsgNetwork).resolve(link(channelId = threadChannelId, msg = mid), servers),
        )

        // Reply behind the plan's history window still opens the thread on its paywall.
        val historyLimitedNetwork =
            FakeMessageLinkNetwork(
                channelsById = validChannels,
                context =
                    HomeNetworkResponse(
                        404,
                        """{"error":"history_limited","channelId":"$threadChannelId","targetMessageId":"$mid","hiddenTarget":{"target":{"id":"$mid","seq":3}}}"""
                    ),
            )
        val paywallRequest =
            openThroughCoordinator(
                assertIs<RaftMessageLinkResolution.Open>(
                    RaftMessageLinkResolver(historyLimitedNetwork).resolve(link(channelId = threadChannelId, msg = mid), servers)
                )
            )
        assertEquals(ConversationKind.Thread, paywallRequest.conversationIdentity.kind)
        assertEquals(cid, paywallRequest.conversationIdentity.threadParentChannelId())
        assertEquals(pid, paywallRequest.conversationIdentity.threadParentMessageId())
        assertEquals(mid, paywallRequest.anchorMessageId)

        // Transient context failure still opens the thread (never NotFound).
        val unavailableNetwork =
            FakeMessageLinkNetwork(
                channelsById = validChannels,
                context = HomeNetworkResponse(503, null),
            )
        val unavailableRequest =
            openThroughCoordinator(
                assertIs<RaftMessageLinkResolution.Open>(
                    RaftMessageLinkResolver(unavailableNetwork).resolve(link(channelId = threadChannelId, msg = mid), servers)
                )
            )
        assertEquals(ConversationKind.Thread, unavailableRequest.conversationIdentity.kind)
        assertEquals(cid, unavailableRequest.conversationIdentity.threadParentChannelId())
        assertEquals(pid, unavailableRequest.conversationIdentity.threadParentMessageId())
        assertEquals(mid, unavailableRequest.anchorMessageId)

        // Parent channel that is another sub-conversation (`thread` / `task`) or 404 stays NotFound.
        for (badParentType in listOf("thread", "task")) {
            val nestedParentNetwork =
                FakeMessageLinkNetwork(
                    channelsById =
                        mapOf(
                            threadChannelId to HomeNetworkResponse(200, threadRow),
                            cid to HomeNetworkResponse(200, channelBody(type = badParentType, id = cid)),
                        )
                )
            assertEquals(
                RaftMessageLinkResolution.NotFound("route_kind_mismatch"),
                RaftMessageLinkResolver(nestedParentNetwork).resolve(link(channelId = threadChannelId), servers),
            )
        }
        val missingParentChannel =
            FakeMessageLinkNetwork(
                channelsById =
                    mapOf(
                        threadChannelId to HomeNetworkResponse(200, threadRow),
                        cid to HomeNetworkResponse(404, """{"error":"Channel not found"}"""),
                    )
            )
        assertEquals(
            RaftMessageLinkResolution.NotFound("parent_channel_404"),
            RaftMessageLinkResolver(missingParentChannel).resolve(link(channelId = threadChannelId), servers),
        )

        // `/dm/<threadId>`, nested `?thread=` on a thread channel id, and a thread row missing
        // parent ids (e.g. unprojected joint thread) all stay NotFound.
        val underDm = FakeMessageLinkNetwork(channel = HomeNetworkResponse(200, threadRow))
        assertEquals(
            RaftMessageLinkResolution.NotFound("route_kind_mismatch"),
            RaftMessageLinkResolver(underDm).resolve(link(kind = ConversationKind.DirectMessage, channelId = threadChannelId), servers),
        )
        val nestedThread = FakeMessageLinkNetwork(channel = HomeNetworkResponse(200, threadRow))
        assertEquals(
            RaftMessageLinkResolution.NotFound("route_kind_mismatch"),
            RaftMessageLinkResolver(nestedThread).resolve(link(channelId = threadChannelId, threadParent = pid), servers),
        )
        val missingParent =
            FakeMessageLinkNetwork(
                channel = HomeNetworkResponse(200, channelBody(type = "thread", id = threadChannelId))
            )
        assertEquals(
            RaftMessageLinkResolution.NotFound("route_kind_mismatch"),
            RaftMessageLinkResolver(missingParent).resolve(link(channelId = threadChannelId), servers),
        )
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
        channelId: String = cid,
        msg: String? = null,
        threadParent: String? = null,
    ) = RaftMessageLink(slug, kind, channelId, messageId = msg, threadParentMessageId = threadParent)

    /** The resolved link through the coordinator a notification tap uses, on a ready Home. */
    private fun openThroughCoordinator(
        open: RaftMessageLinkResolution.Open,
        conversationDirectory: SharedConversationDirectoryState = SharedConversationDirectoryState(),
    ): HomeMessageOpenRequest {
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
                    conversationDirectory = conversationDirectory,
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
    private val channelsById: Map<String, HomeNetworkResponse> = emptyMap(),
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
            path.startsWith("channels/") -> {
                if (throwOnChannel) error("timeout")
                val id = path.removePrefix("channels/")
                channelsById[id]
                    ?: channel
                    ?: HomeNetworkResponse(200, """{"id":"$id","name":"release-notes","type":"channel"}""")
            }
            path.startsWith("messages/context/") -> {
                if (throwOnContext) error("socket closed")
                context ?: HomeNetworkResponse(200, """{"channelId":null,"messages":[]}""")
            }
            else -> error("unexpected request $path")
        }
    }
}
