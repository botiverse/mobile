package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftMessageContextPageRequest
import build.raft.app.data.RaftMessageContextPageSink
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedWindowNavigationCoordinator
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.WindowNavigationAction
import build.raft.app.data.WindowNavigationIntent
import build.raft.app.data.WindowNavigationReadiness
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * A server the account is not in is read through `GET /api/public/servers/:slug`
 * (botiverse/slock@0ea95d1cd packages/server/src/routes/publicServer.ts): 200 `{ server: { id, name,
 * slug, avatarUrl, translationEnabled }, channels: [{ id, name, description }], translationAvailable }`
 * listing the channels a stranger may read, 404 `{ error: "Not found" }` for a server that is
 * missing, deleted or not public.
 */
class RaftMessageLinkResolverTest {
    private val serverId = "5e7a0c1d-0000-4000-8000-00000000a001"
    private val otherServerId = "5e7a0c1d-0000-4000-8000-00000000a002"
    private val slug = "lagoon-push-test"
    private val cid = "6f1c2b9e-1d2a-4c7b-9a51-0d3e8f7a6b21"
    private val pid = "a3b4c5d6-e7f8-4a1b-8c2d-3e4f5a6b7c8d"
    private val mid = "0b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9"
    private val threadChannelId = "7c7c7c7c-1111-4222-8333-444455556666"
    private val taskId = "b0b0b0b0-1111-4222-8333-444455556666"
    private val taskConvId = "8d8d8d8d-1111-4222-8333-444455556666"
    private val localJointTaskConvId = "9e9e9e9e-1111-4222-8333-444455556666"
    /** A server the account is not in, and another channel of it. */
    private val publicSlug = "open-source-club"
    private val publicServerId = "5e7a0c1d-0000-4000-8000-00000000a003"
    private val otherPublicChannelId = "9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a"
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

    /** `GET /api/public/servers/:slug` 200: the public server's card, listing [channelIds]. */
    private fun publicServerBody(vararg channelIds: String): String {
        val channels = channelIds.joinToString(",") { id -> """{"id":"$id","name":"release-notes","description":null}""" }
        return """{"server":{"id":"$publicServerId","name":"Open Source Club","slug":"$publicSlug","avatarUrl":null,""" +
            """"translationEnabled":false},"channels":[$channels],"translationAvailable":false}"""
    }

    @Test
    fun serverTheAccountHasNotJoinedOpensThePublicPreviewWhenItListsTheChannel() = runTest {
        // A message link, a thread link (on its parent message's channel), and a channel id in capitals.
        for (target in listOf(
            link(slug = publicSlug, msg = mid),
            link(slug = publicSlug, msg = mid, threadParent = pid),
            link(slug = publicSlug, channelId = cid.uppercase()),
        )) {
            val network = FakeMessageLinkNetwork(publicServer = HomeNetworkResponse(200, publicServerBody(otherPublicChannelId, cid)))
            val result = RaftMessageLinkResolver(network).resolve(target, servers)
            assertEquals(RaftMessageLinkResolution.PublicPreview(target), result, "link=$target")
            // One anonymous request, at the priority of an open the user waits on; no member route.
            assertEquals(
                listOf(
                    RecordedMessageLinkRequest(
                        path = "public/servers/$publicSlug",
                        query = emptyList(),
                        serverIdOverride = null,
                        priority = DataRequestPriority.Foreground,
                    )
                ),
                network.requests,
                "link=$target",
            )
        }
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
    fun warmReturnLifecycleRetiredProbesRetryOnceAndResolveThreadReplyTaskAndShortIds() = runTest {
        val retired = HomeNetworkResponse(0, null, errorBodyText = "network_request_lifecycle_retired")
        val shortMid = mid.take(8)
        val shortPid = pid.take(8)

        // Both probes retired on the background->foreground edge, then succeed on the single retry.
        val replyNetwork =
            FakeMessageLinkNetwork(
                channelResponses = listOf(retired, HomeNetworkResponse(200, channelBody())),
                contextResponses = listOf(retired, HomeNetworkResponse(200, contextBody(mid, replyTarget()))),
            )
        val replyOpen =
            assertIs<RaftMessageLinkResolution.Open>(
                RaftMessageLinkResolver(replyNetwork).resolve(link(msg = shortMid, threadParent = shortPid), servers)
            )
        assertEquals(2, replyNetwork.requests.count { it.path == "channels/$cid" })
        assertEquals(2, replyNetwork.requests.count { it.path == "messages/context/$shortMid" })
        assertEquals(threadChannelId, replyOpen.sourceIdentity.threadChannelId)
        val replyRequest = openThroughCoordinator(replyOpen)
        assertEquals(ConversationKind.Thread, replyRequest.conversationIdentity.kind)
        assertEquals(pid, replyRequest.conversationIdentity.threadParentMessageId())
        assertEquals(mid, replyRequest.anchorMessageId)

        // A task conversation link whose channel probe was retired still resolves as Task after retry.
        val taskNetwork =
            FakeMessageLinkNetwork(
                channelResponses = listOf(retired, HomeNetworkResponse(200, channelBody(RaftTaskConversationChannelKind))),
            )
        val taskOpen =
            assertIs<RaftMessageLinkResolution.Open>(
                RaftMessageLinkResolver(taskNetwork).resolve(link(), servers)
            )
        assertEquals(2, taskNetwork.requests.count { it.path == "channels/$cid" })
        assertEquals(ConversationKind.Task, openThroughCoordinator(taskOpen).conversationIdentity.kind)
    }

    @Test
    fun repeatedLifecycleRetiredStopsAfterOneRetryAndOrdinaryOfflineDoesNotRetry() = runTest {
        val retired = HomeNetworkResponse(0, null, errorBodyText = "network_request_lifecycle_retired")

        val alwaysRetired = FakeMessageLinkNetwork(channel = retired)
        assertIs<RaftMessageLinkResolution.Open>(RaftMessageLinkResolver(alwaysRetired).resolve(link(), servers))
        assertEquals(2, alwaysRetired.requests.count { it.path == "channels/$cid" })

        val ordinaryOffline = FakeMessageLinkNetwork(channel = HomeNetworkResponse(0, null))
        assertIs<RaftMessageLinkResolution.Open>(RaftMessageLinkResolver(ordinaryOffline).resolve(link(), servers))
        assertEquals(1, ordinaryOffline.requests.count { it.path == "channels/$cid" })
    }

    @Test
    fun resolvedChannelLinkOffersContextPageAndIgnoresUnknownChannelFields() = runTest {
        val offered = mutableListOf<Triple<String, String?, String?>>()
        val sink =
            object : RaftMessageContextPageSink {
                override suspend fun beginRequest(serverId: String?): RaftMessageContextPageRequest =
                    object : RaftMessageContextPageRequest {
                        override val serverId: String = serverId.orEmpty()

                        override fun offer(bodyText: String, channelId: String?, channelType: String?) {
                            offered += Triple(bodyText, channelId, channelType)
                        }
                    }
            }
        val body = contextBody(mid)
        val channelWithExtraField =
            """{"id":"$cid","name":"release-notes","type":"channel","serverId":"$serverId","legacyFlag":"activity"}"""
        val network =
            FakeMessageLinkNetwork(
                channel = HomeNetworkResponse(200, channelWithExtraField),
                context = HomeNetworkResponse(200, body),
            )

        assertIs<RaftMessageLinkResolution.Open>(
            RaftMessageLinkResolver(network, contextPageSink = sink).resolve(link(msg = mid), servers)
        )
        assertEquals(listOf<Triple<String, String?, String?>>(Triple(body, cid, "channel")), offered)
    }

    @Test
    fun taskConversationQueryParamResolvesChannelDmAndShortMessageIdWithoutPosting() = runTest {
        val shortMid = mid.take(8)
        for ((routeKind, parentType) in listOf(
            ConversationKind.Channel to "channel",
            ConversationKind.DirectMessage to "dm",
        )) {
            for (msg in listOf(mid, shortMid, null)) {
                val network =
                    FakeMessageLinkNetwork(
                        channel = HomeNetworkResponse(200, channelBody(type = parentType, id = cid)),
                        channelTasks = HomeNetworkResponse(200, tasksListBody(channelType = parentType, conversationChannelId = taskConvId)),
                        context = HomeNetworkResponse(200, contextBody(target = mid, channelId = taskConvId)),
                    )
                val open =
                    assertIs<RaftMessageLinkResolution.Open>(
                        RaftMessageLinkResolver(network).resolve(link(kind = routeKind, msg = msg, taskId = taskId), servers)
                    )
                val request = openThroughCoordinator(open)
                assertEquals(ConversationKind.Task, request.conversationIdentity.kind, "$routeKind msg=$msg")
                assertEquals(taskConvId, request.conversationIdentity.channelId?.value, "$routeKind msg=$msg")
                assertEquals(if (msg != null) mid else null, request.anchorMessageId, "$routeKind msg=$msg")
                // Never POSTs for non-joint channels/DMs.
                assertFalse(network.requests.any { it.method == "POST" }, "$routeKind msg=$msg")
                if (msg != null) {
                    val contextReq = network.requests.single { it.path.startsWith("messages/context/") }
                    assertEquals("messages/context/$msg", contextReq.path)
                    assertEquals(listOf("channelId" to taskConvId, "before" to "0", "after" to "0"), contextReq.query)
                }
            }
        }
        // Wrong route kind for parent DM/channel is rejected.
        val dmUnderChannel =
            FakeMessageLinkNetwork(
                channel = HomeNetworkResponse(200, channelBody(type = "dm", id = cid)),
                channelTasks = HomeNetworkResponse(200, tasksListBody(channelType = "dm", conversationChannelId = taskConvId)),
            )
        assertIs<RaftMessageLinkResolution.NotFound>(
            RaftMessageLinkResolver(dmUnderChannel).resolve(link(kind = ConversationKind.Channel, msg = mid, taskId = taskId), servers)
        )
        val channelUnderDm =
            FakeMessageLinkNetwork(
                channel = HomeNetworkResponse(200, channelBody(type = "channel", id = cid)),
                channelTasks = HomeNetworkResponse(200, tasksListBody(channelType = "channel", conversationChannelId = taskConvId)),
            )
        assertIs<RaftMessageLinkResolution.NotFound>(
            RaftMessageLinkResolver(channelUnderDm).resolve(link(kind = ConversationKind.DirectMessage, msg = mid, taskId = taskId), servers)
        )
    }

    @Test
    fun jointChannelTaskConversationResolvesLocalProjectionAndNeverLeaksCanonicalId() = runTest {
        for (msg in listOf(mid, null)) {
            val network =
                FakeMessageLinkNetwork(
                    channel = HomeNetworkResponse(200, channelBody(type = "joint", id = cid)),
                    channelTasks = HomeNetworkResponse(200, tasksListBody(channelType = "joint", conversationChannelId = taskConvId)),
                    openTaskConversation =
                        HomeNetworkResponse(
                            200,
                            """{"conversationChannelId":"$localJointTaskConvId","canonicalConversationChannelId":"$taskConvId","created":false}""",
                        ),
                    context = HomeNetworkResponse(200, contextBody(target = mid, channelId = localJointTaskConvId)),
                )
            val open =
                assertIs<RaftMessageLinkResolution.Open>(
                    RaftMessageLinkResolver(network).resolve(link(msg = msg, taskId = taskId), servers)
                )
            val request = openThroughCoordinator(open)
            assertEquals(ConversationKind.Task, request.conversationIdentity.kind, "msg=$msg")
            assertEquals(localJointTaskConvId, request.conversationIdentity.channelId?.value, "msg=$msg")
            assertEquals(msg, request.anchorMessageId, "msg=$msg")
            val postReq = network.requests.single { it.method == "POST" }
            assertEquals("tasks/$taskId/conversation", postReq.path)
            if (msg != null) {
                val contextReq = network.requests.single { it.path.startsWith("messages/context/") }
                assertEquals(listOf("channelId" to localJointTaskConvId, "before" to "0", "after" to "0"), contextReq.query)
            }
        }

        // If resolving the joint local projection is refused (403/404/409), fail closed and never open the canonical id.
        for (code in listOf(403, 404, 409)) {
            val refused =
                FakeMessageLinkNetwork(
                    channel = HomeNetworkResponse(200, channelBody(type = "joint", id = cid)),
                    channelTasks = HomeNetworkResponse(200, tasksListBody(channelType = "joint", conversationChannelId = taskConvId)),
                    openTaskConversation = HomeNetworkResponse(code, """{"error":"refused"}"""),
                )
            assertIs<RaftMessageLinkResolution.NotFound>(
                RaftMessageLinkResolver(refused).resolve(link(msg = mid, taskId = taskId), servers),
                "code=$code",
            )
        }
    }

    @Test
    fun taskConversationPendingLegacyMissingAndTransientFailureCases() = runTest {
        // Pending task (no conversation yet) without `msg=` opens pending route WITHOUT POSTing.
        val pendingNetwork =
            FakeMessageLinkNetwork(
                channel = HomeNetworkResponse(200, channelBody(type = "channel", id = cid)),
                channelTasks = HomeNetworkResponse(200, tasksListBody(channelType = "channel", conversationChannelId = null)),
            )
        val pending =
            assertIs<RaftMessageLinkResolution.OpenPendingTask>(
                RaftMessageLinkResolver(pendingNetwork).resolve(link(taskId = taskId), servers)
            )
        assertEquals(serverId, pending.serverId)
        assertEquals(taskId, pending.taskId)
        assertEquals(ChannelId(cid), pending.channelId)
        assertEquals(taskId, pending.task?.id?.value)
        assertNull(pending.focusedMessageId)
        assertFalse(pendingNetwork.requests.any { it.method == "POST" })

        // Pending task WITH `msg=` cannot have a task message yet -> NotFound without POSTing.
        val pendingWithMsg =
            FakeMessageLinkNetwork(
                channel = HomeNetworkResponse(200, channelBody(type = "channel", id = cid)),
                channelTasks = HomeNetworkResponse(200, tasksListBody(channelType = "channel", conversationChannelId = null)),
            )
        assertIs<RaftMessageLinkResolution.NotFound>(
            RaftMessageLinkResolver(pendingWithMsg).resolve(link(msg = mid, taskId = taskId), servers)
        )
        assertFalse(pendingWithMsg.requests.any { it.method == "POST" })

        // Legacy task and unknown task id -> NotFound.
        val legacyNetwork =
            FakeMessageLinkNetwork(
                channel = HomeNetworkResponse(200, channelBody(type = "channel", id = cid)),
                channelTasks = HomeNetworkResponse(200, tasksListBody(channelType = "channel", conversationChannelId = null, isLegacy = true)),
            )
        assertIs<RaftMessageLinkResolution.NotFound>(
            RaftMessageLinkResolver(legacyNetwork).resolve(link(taskId = taskId), servers)
        )
        val missingTaskNetwork =
            FakeMessageLinkNetwork(
                channel = HomeNetworkResponse(200, channelBody(type = "channel", id = cid)),
                channelTasks = HomeNetworkResponse(200, """{"tasks":[]}"""),
            )
        assertIs<RaftMessageLinkResolution.NotFound>(
            RaftMessageLinkResolver(missingTaskNetwork).resolve(link(taskId = taskId), servers)
        )

        // Transient 503 on tasks list opens pending route with focusedMessageId preserved (never NotFound).
        val offlineNetwork =
            FakeMessageLinkNetwork(
                channel = HomeNetworkResponse(200, channelBody(type = "channel", id = cid)),
                channelTasks = HomeNetworkResponse(503, null),
            )
        val offlinePending =
            assertIs<RaftMessageLinkResolution.OpenPendingTask>(
                RaftMessageLinkResolver(offlineNetwork).resolve(link(msg = mid, taskId = taskId), servers)
            )
        assertEquals(mid, offlinePending.focusedMessageId)
        assertNull(offlinePending.task)

        // Server outside the account never opens public preview for a task link.
        val outsideNetwork = FakeMessageLinkNetwork()
        assertIs<RaftMessageLinkResolution.PublicNotFound>(
            RaftMessageLinkResolver(outsideNetwork).resolve(link(slug = publicSlug, msg = mid, taskId = taskId), servers)
        )
        assertTrue(outsideNetwork.requests.isEmpty())
    }

    private fun tasksListBody(
        id: String = taskId,
        channelType: String = "channel",
        conversationChannelId: String? = taskConvId,
        isLegacy: Boolean = false,
    ): String {
        val convJson = conversationChannelId?.let { "\"$it\"" } ?: "null"
        return """{"tasks":[{"id":"$id","channelId":"$cid","channelName":"release-notes","channelType":"$channelType",""" +
            """"taskNumber":7,"title":"Ship task backlink","status":"in_progress","conversationChannelId":$convJson,""" +
            """"conversationAnchored":${!isLegacy},"isLegacy":$isLegacy,"revision":1}]}"""
    }

    private fun link(
        slug: String = this.slug,
        kind: ConversationKind = ConversationKind.Channel,
        channelId: String = cid,
        msg: String? = null,
        threadParent: String? = null,
        taskId: String? = null,
    ) = RaftMessageLink(slug, kind, channelId, messageId = msg, threadParentMessageId = threadParent, taskId = taskId)

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
    val method: String = "GET",
)

private class FakeMessageLinkNetwork(
    private val channel: HomeNetworkResponse? = null,
    private val channelsById: Map<String, HomeNetworkResponse> = emptyMap(),
    channelResponses: List<HomeNetworkResponse> = emptyList(),
    private val context: HomeNetworkResponse? = null,
    contextResponses: List<HomeNetworkResponse> = emptyList(),
    private val channelTasks: HomeNetworkResponse? = null,
    private val openTaskConversation: HomeNetworkResponse? = null,
    private val throwOnContext: Boolean = false,
    private val throwOnChannel: Boolean = false,
    /** `GET /api/public/servers/:slug`; unset, the request is unexpected. */
    private val publicServer: HomeNetworkResponse? = null,
    private val throwOnPublicServer: Boolean = false,
) : RaftNetworkClient {
    val requests = mutableListOf<RecordedMessageLinkRequest>()
    private val queuedChannelResponses = ArrayDeque(channelResponses)
    private val queuedContextResponses = ArrayDeque(contextResponses)

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?,
    ): HomeNetworkResponse {
        requests += RecordedMessageLinkRequest(path, query, serverIdOverride, priority, method)
        return when {
            path.startsWith("channels/") -> {
                if (throwOnChannel) error("timeout")
                val id = path.removePrefix("channels/")
                queuedChannelResponses.removeFirstOrNull()
                    ?: channelsById[id]
                    ?: channel
                    ?: HomeNetworkResponse(200, """{"id":"$id","name":"release-notes","type":"channel"}""")
            }
            path.startsWith("tasks/channel/") ->
                channelTasks ?: error("unexpected request $method $path")
            method == "POST" && path.startsWith("tasks/") && path.endsWith("/conversation") ->
                openTaskConversation ?: error("unexpected request $method $path")
            path.startsWith("messages/context/") -> {
                if (throwOnContext) error("socket closed")
                queuedContextResponses.removeFirstOrNull()
                    ?: context
                    ?: HomeNetworkResponse(200, """{"channelId":null,"messages":[]}""")
            }
            path.startsWith("public/servers/") -> {
                if (throwOnPublicServer) error("offline")
                publicServer ?: error("unexpected request $path")
            }
            else -> error("unexpected request $method $path")
        }
    }
}
