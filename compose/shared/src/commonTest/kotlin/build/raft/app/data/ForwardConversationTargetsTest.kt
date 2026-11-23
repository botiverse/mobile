package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Forward into a thread / task discussion: slock #12620 (server) / #12621 (web), pinned at
 * botiverse/slock b088f8467e9649431ef009b0f1bf6c32bf896cc2. Payloads are the shapes
 * `routes/messages.forwardToThread.api.test.ts` asserts.
 */
class ForwardConversationTargetsTest {
    private val serverId = ServerId("server-1")

    @Test
    fun conversationRowsParseAsThreadAndTaskTargets_slockB088f84() {
        val targets = parseForwardMessageTargetSearchResults(ConversationsBody, fallbackServerId = serverId)

        assertEquals(listOf("thread-current", "thread-5", "task-conv-7"), targets.map { it.id })
        val current = targets[0]
        assertEquals(ForwardMessageTargetKind.ConversationThread, current.kind)
        assertTrue(current.current)
        assertTrue(current.isForwardConversationTarget())

        // The exact row the slock conversations test deep-equals.
        val thread = targets[1]
        assertEquals(ForwardMessageTargetKind.ConversationThread, thread.kind)
        assertEquals("#general › topic 5", thread.title)
        assertEquals(ChannelId("thread-5"), thread.channelId)
        assertEquals(ConversationKind.Thread, thread.channelKind)
        assertEquals("thread", thread.channelType)
        assertEquals(ChannelId("room-1"), thread.parentChannelId)
        assertEquals("general", thread.parentChannelName)
        assertEquals("channel", thread.parentChannelType)
        assertEquals("topic 5", thread.parentMessagePreview)
        assertFalse(thread.current)
        assertEquals(ForwardMessageTargetRequiredAction.None, thread.requiredAction)
        assertTrue(thread.canForwardNow)

        val task = targets[2]
        assertEquals(ForwardMessageTargetKind.ConversationTask, task.kind)
        assertEquals("#general:#7 ship the forward picker", task.title)
        assertEquals(ChannelId("task-conv-7"), task.channelId)
        assertEquals(ConversationKind.Task, task.channelKind)
        assertEquals(7, task.taskNumber)
        assertEquals("ship the forward picker", task.taskTitle)
        assertNull(task.parentMessagePreview)
    }

    @Test
    fun aThreadRowWithoutATitleIsNamedByItsParentsReply() {
        // web ForwardConversationTargetTitle.tsx:21 (botiverse/slock #16158): with no server title, the
        // parent's preview through `extractQuoteReplyPreviewText`.
        val body =
            """
            {"targets":[
              {"type":"thread","channelType":"thread","id":"thread-q","channelId":"thread-q","title":"","subtitle":"","avatarUrl":null,"parentChannelId":"room-1","parentChannelName":"general","parentChannelType":"channel","parentMessagePreview":"> Status page banner goes live at 9:00 PT\n> msg=c54e1234\n\nCan we move it to 8:30?","joined":true,"dmExists":null,"canForwardNow":true,"requiredAction":null}
            ]}
            """.trimIndent()

        assertEquals("Can we move it to 8:30?", parseForwardMessageTargetSearchResults(body, serverId).single().title)
    }

    @Test
    fun conversationRowWithoutChannelIdAndUnknownTypesAreDropped() {
        val body =
            """
            {"targets":[
              {"type":"thread","channelType":"thread","id":"thread-x","channelId":"","title":"#general › x","subtitle":"","avatarUrl":null,"parentChannelId":"room-1","parentChannelName":"general","parentChannelType":"channel","joined":true,"dmExists":null,"canForwardNow":true,"requiredAction":null},
              {"type":"canvas","id":"future-1","title":"A future kind","channelId":"future-1"},
              {"type":"task","channelType":"task","id":"task-conv-1","channelId":"task-conv-1","title":"#general:#1 one","subtitle":"","avatarUrl":null,"parentChannelId":"room-1","parentChannelName":"general","parentChannelType":"channel","taskNumber":1,"taskTitle":"one","joined":true,"dmExists":null,"canForwardNow":true,"requiredAction":null}
            ]}
            """.trimIndent()

        assertEquals(listOf("task-conv-1"), parseForwardMessageTargetSearchResults(body, serverId).map { it.id })
    }

    @Test
    fun searchKeepsServerOrderWithThreadAndTaskRowsAfterChannelsAndPeople() {
        val body =
            """
            {"targets":[
              {"type":"channel","channelType":"channel","id":"ch-zephyr","title":"#zephyr","subtitle":"","avatarUrl":null,"channelId":"ch-zephyr","joined":true,"dmExists":null,"canForwardNow":true,"requiredAction":null},
              {"type":"human","channelType":null,"id":"user-z","title":"@Zed","subtitle":"","avatarUrl":null,"channelId":null,"joined":null,"dmExists":false,"canForwardNow":false,"requiredAction":"create_dm"},
              {"type":"thread","channelType":"thread","id":"thread-z","channelId":"thread-z","title":"#general › zephyr rollout notes","subtitle":"","avatarUrl":null,"parentChannelId":"room-1","parentChannelName":"general","parentChannelType":"channel","parentMessagePreview":"zephyr rollout notes","joined":true,"dmExists":null,"canForwardNow":true,"requiredAction":null},
              {"type":"task","channelType":"task","id":"task-z","channelId":"task-z","title":"#general:#3 zephyr picker task","subtitle":"","avatarUrl":null,"parentChannelId":"room-1","parentChannelName":"general","parentChannelType":"channel","taskNumber":3,"taskTitle":"zephyr picker task","joined":true,"dmExists":null,"canForwardNow":true,"requiredAction":null}
            ]}
            """.trimIndent()

        val targets = parseForwardMessageTargetSearchResults(body, serverId)

        assertEquals(
            listOf(
                ForwardMessageTargetKind.RecentChannel,
                ForwardMessageTargetKind.HumanDirectMessageCandidate,
                ForwardMessageTargetKind.ConversationThread,
                ForwardMessageTargetKind.ConversationTask
            ),
            targets.map { it.kind }
        )
    }

    @Test
    fun orderingPutsOneCurrentRowFirstAndCapsAtFour() {
        val rows =
            listOf("a", "b", "cur", "c", "d", "e").map { id ->
                ForwardMessageTarget(
                    serverId = serverId,
                    kind = ForwardMessageTargetKind.ConversationThread,
                    id = id,
                    title = id,
                    channelId = ChannelId(id),
                    channelKind = ConversationKind.Thread,
                    current = id == "cur"
                )
            } + ForwardMessageTarget(serverId, ForwardMessageTargetKind.RecentChannel, "ch", "#ch", channelId = ChannelId("ch"))

        val ordered = rows.orderForwardConversationTargets()

        assertEquals(listOf("cur", "a", "b", "c"), ordered.map { it.id })
        assertEquals(listOf(true, false, false, false), ordered.map { it.current })
    }

    @Test
    fun failureOrOfflineIsNoGroupAndNoRetry() = runTest {
        val network = ConversationNetworkClient()
        network.conversationsResponse = { HomeNetworkResponse(code = 404, bodyText = null, errorBodyText = "not found") }
        val repository = repository(network)

        assertEquals(emptyList(), repository.conversationTargets(serverId, ChannelId("thread-current")))
        assertEquals(1, network.calls.size, "a failed answer is never retried")

        network.conversationsResponse = { error("offline") }
        assertEquals(emptyList(), repository.conversationTargets(serverId, null))
        assertEquals(2, network.calls.size)
        assertTrue(network.logs.any { it.startsWith("forward_conversation_targets result=error code=http_404") })
    }

    @Test
    fun conversationRowResolvesToItsOwnChannelAndPostsThere() = runTest {
        val network = ConversationNetworkClient()
        network.conversationsResponse = { HomeNetworkResponse(code = 200, bodyText = ConversationsBody) }
        network.forwardResponse = { body ->
            val ids = Json.parseToJsonElement(body).jsonObject["destinationChannelIds"]!!.jsonArray.map { it.jsonPrimitive.content }
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"results":[""" +
                        ids.joinToString(",") { id ->
                            """{"destinationChannelId":"$id","status":"success","message":${postedMessage(id, if (id == "thread-5") ThreadContext else null)}}"""
                        } + "]}"
            )
        }
        val repository = repository(network)
        val thread = repository.conversationTargets(serverId, null).single { it.id == "thread-5" }
        val channel = ForwardMessageTarget(
            serverId = serverId,
            kind = ForwardMessageTargetKind.RecentChannel,
            id = "room-2",
            title = "#random",
            channelId = ChannelId("room-2")
        )

        val threadDestination =
            assertIs<DataResult.Content<ForwardMessageDestination>>(repository.resolveTargetChannel(serverId, thread)).data
        assertEquals(ChannelId("thread-5"), threadDestination.channelId)
        assertEquals(ConversationKind.Thread, threadDestination.channelKind)
        val channelDestination =
            assertIs<DataResult.Content<ForwardMessageDestination>>(repository.resolveTargetChannel(serverId, channel)).data

        val result =
            assertIs<DataResult.Content<ForwardMessageBatchResult>>(
                repository.forwardMessagesBatch(
                    ForwardMessageBatchExecuteRequest(
                        sourceServerId = serverId,
                        sourceChannelId = ChannelId("room-1"),
                        destinations = listOf(threadDestination, channelDestination),
                        messageIds = listOf("source-1"),
                        mode = ForwardMessageSendMode.Merged,
                        note = "fyi",
                        requestId = "11111111-2222-4333-8444-555555555555"
                    )
                )
            ).data

        val post = network.calls.single { it.path == "messages/forward" }
        val body = Json.parseToJsonElement(post.bodyText!!).jsonObject
        assertEquals(listOf("thread-5", "room-2"), body["destinationChannelIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(emptyList(), result.failures)
        val threadDelivery = result.deliveries.single { it.destination.channelId == ChannelId("thread-5") }
        val context = assertNotNull(threadDelivery.message.conversationContext)
        assertEquals(ConversationKind.Thread, context.channelKind)
        assertEquals(MessageId("parent-5"), context.parentMessageId)
        assertEquals(ChannelId("room-1"), context.parentChannelId)
        assertNull(result.deliveries.single { it.destination.channelId == ChannelId("room-2") }.message.conversationContext)
    }

    @Test
    fun singleForwardIntoTaskKeepsTheAnsweredTaskContext() = runTest {
        val network = ConversationNetworkClient()
        network.forwardResponse = { HomeNetworkResponse(code = 200, bodyText = postedMessage("task-conv-7", TaskContext)) }
        val repository = repository(network)

        val result =
            assertIs<DataResult.Content<ForwardMessageSendResult>>(
                repository.forwardMessages(
                    ForwardMessageExecuteRequest(
                        sourceServerId = serverId,
                        sourceChannelId = ChannelId("room-1"),
                        destination = ForwardMessageDestination(serverId, ChannelId("task-conv-7"), ConversationKind.Task),
                        messageIds = listOf("source-1"),
                        mode = ForwardMessageSendMode.Merged
                    )
                )
            ).data

        val body = Json.parseToJsonElement(network.calls.single().bodyText!!).jsonObject
        assertEquals("task-conv-7", body["destinationChannelId"]!!.jsonPrimitive.content)
        val context = assertNotNull(result.message?.conversationContext)
        assertEquals(ConversationKind.Task, context.channelKind)
        assertEquals(TaskId("task-7"), context.taskId)
        assertEquals(ChannelId("room-1"), context.parentChannelId)
    }

    /**
     * A joint thread is stored on the canonical thread, but the answer names the forwarder's local
     * face (slock messageService `buildPersistedMessageFrontendPayload`: channelId =
     * `jointThreadProjection.localThreadChannelId`), so the batch check (answer channel == destination)
     * accepts it; an answer naming any other channel stays `forward_response_invalid`.
     */
    @Test
    fun jointThreadBatchAcceptsTheLocalFaceAndRejectsAMismatchedChannel() = runTest {
        val network = ConversationNetworkClient()
        var answerChannelId = "joint-thread-local"
        network.forwardResponse = {
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"results":[{"destinationChannelId":"joint-thread-local","status":"success","message":${postedMessage(answerChannelId, JointThreadContext)}}]}"""
            )
        }
        val repository = repository(network)
        val request =
            ForwardMessageBatchExecuteRequest(
                sourceServerId = serverId,
                sourceChannelId = ChannelId("room-1"),
                destinations = listOf(ForwardMessageDestination(serverId, ChannelId("joint-thread-local"), ConversationKind.Thread)),
                messageIds = listOf("source-1"),
                mode = ForwardMessageSendMode.Merged,
                note = "",
                requestId = "11111111-2222-4333-8444-555555555555"
            )

        val local = assertIs<DataResult.Content<ForwardMessageBatchResult>>(repository.forwardMessagesBatch(request)).data
        assertEquals(1, local.deliveries.size)
        assertEquals(emptyList(), local.failures)

        answerChannelId = "joint-thread-canonical"
        val mismatched = assertIs<DataResult.Content<ForwardMessageBatchResult>>(repository.forwardMessagesBatch(request)).data
        assertEquals(emptyList(), mismatched.deliveries)
        assertEquals(listOf("forward_response_invalid"), mismatched.failures.map { it.code })
    }

    @Test
    fun externalSharePickerRetainsThreadAndTaskRowsAndDropsMalformedConversations() {
        val malformed =
            ForwardMessageTarget(
                serverId = serverId,
                kind = ForwardMessageTargetKind.ConversationThread,
                id = "thread-no-channel",
                title = "Broken thread",
                channelId = null,
                channelKind = ConversationKind.Thread
            )
        val targets = parseForwardMessageTargetSearchResults(ConversationsBody, serverId) +
            malformed +
            ForwardMessageTarget(serverId, ForwardMessageTargetKind.RecentChannel, "ch", "#ch", channelId = ChannelId("ch"))

        assertEquals(
            listOf("thread-current", "thread-5", "task-conv-7", "ch"),
            targets.externalShareTargets().map { it.id }
        )
    }

    @Test
    fun resolveConversationThreadParentReadsChannelProbeAndRetriesLifecycleRetired() = runTest {
        val network = ConversationNetworkClient()
        var attempts = 0
        network.channelResponse = { channelId ->
            attempts += 1
            if (attempts == 1) {
                HomeNetworkResponse(code = 0, bodyText = null, errorBodyText = "network_request_lifecycle_retired")
            } else {
                HomeNetworkResponse(
                    code = 200,
                    bodyText = """{"id":"$channelId","type":"thread","parentChannelId":"room-1","parentMessageId":"parent-5"}"""
                )
            }
        }
        val repository = repository(network)

        val resolved =
            assertIs<DataResult.Content<ForwardThreadParentInfo>>(
                repository.resolveConversationThreadParent(serverId, ChannelId("thread-5"))
            ).data

        assertEquals(2, attempts)
        assertEquals(ChannelId("room-1"), resolved.parentChannelId)
        assertEquals(MessageId("parent-5"), resolved.parentMessageId)
    }

    @Test
    fun conversationTargetsRetriesOnceOnLifecycleRetired() = runTest {
        val network = ConversationNetworkClient()
        var attempts = 0
        network.conversationsResponse = {
            attempts += 1
            if (attempts == 1) {
                HomeNetworkResponse(code = 0, bodyText = null, errorBodyText = "network_request_lifecycle_retired")
            } else {
                HomeNetworkResponse(code = 200, bodyText = ConversationsBody)
            }
        }
        val repository = repository(network)

        val targets = repository.conversationTargets(serverId, currentChannelId = null)

        assertEquals(2, attempts)
        assertEquals(listOf("thread-current", "thread-5", "task-conv-7"), targets.map { it.id })
    }

    @Test
    fun directoryTargetsUsesCachedHomeSnapshotWithoutColdFetchingChannelsOrDms() = runTest {
        val network = ConversationNetworkClient()
        val cachedSnapshot =
            HomeSnapshot(
                server = build.raft.app.model.ServerSummary(id = serverId, name = "Server"),
                currentMember = build.raft.app.model.MemberProfile(
                    id = build.raft.app.model.MemberId("user-1"),
                    displayName = "Ada",
                    handle = "ada",
                    kind = build.raft.app.model.MemberKind.Human
                ),
                channels = listOf(
                    HomeChannelRow(
                        threadId = build.raft.app.model.ThreadId("ch-warm"),
                        channelId = ChannelId("ch-warm"),
                        name = "general",
                        channelType = "public",
                        joined = true
                    )
                ),
                directMessages = listOf(
                    HomeDirectMessageRow(
                        threadId = build.raft.app.model.ThreadId("dm-warm"),
                        channelId = ChannelId("dm-warm"),
                        name = "Grace"
                    )
                ),
                unreadTotal = 0
            )
        val repository = repository(network, cachedHomeSnapshotProvider = { id -> cachedSnapshot.takeIf { id == serverId } })

        val page = assertIs<DataResult.Content<ForwardMessageTargetDirectoryPage>>(
            repository.directoryTargets(serverId, sessionId = "external-share:warm", pageSize = 40)
        ).data

        assertEquals(listOf("dm-warm", "ch-warm"), page.items.map { it.id })
        assertEquals(0, network.calls.count { it.path == "channels" || it.path == "channels/dm" })
    }

    @Test
    fun publicTaskSourceNamesItsTitleAndOlderOrHiddenSourcesDoNot() {
        val publicTask =
            WireForwardedBundleItem(
                sourceTargetId = "task-conv-7",
                sourceMessageId = "reply-1",
                sourceTaskId = "task-7",
                sourceTaskNumber = 7,
                sourceTaskTitle = "public task title",
                sourceTargetSnapshot = WireForwardedBundleTarget(id = "task-conv-7", type = "task", label = "#android:#7", labelVisibility = "public"),
                provenanceState = "available"
            )
        assertEquals("public task title", publicTask.visibleForwardSource()?.taskTitle)
        assertEquals("#android:#7", publicTask.visibleForwardSource()?.label)

        // An older bundle (no sourceTaskTitle) keeps the plain `from #android:#7`.
        assertNull(publicTask.copy(sourceTaskTitle = null).visibleForwardSource()?.taskTitle)

        // #12663: a viewer who can't see the source gets the pointers absent, the label restricted.
        val hidden =
            Json { ignoreUnknownKeys = true }.decodeFromString(
                WireForwardedBundleItem.serializer(),
                """{"sourceTargetSnapshot":{"id":null,"type":"task","label":"","labelVisibility":"restricted"},"contentSnapshot":"the task decision","provenanceState":"original_unavailable"}"""
            )
        assertNull(hidden.sourceTaskTitle)
        assertNull(hidden.visibleForwardSource())
    }

    private fun repository(
        network: ConversationNetworkClient,
        cachedHomeSnapshotProvider: (ServerId) -> HomeSnapshot? = { null }
    ): RealForwardMessageTargetRepository {
        val store =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@example.com"),
                    accessToken = "access",
                    refreshToken = "refresh",
                    selectedServerId = serverId.value,
                    servers = listOf(StoredAuthServer(id = serverId.value, name = "Server"))
                )
            )
        return RealForwardMessageTargetRepository(
            sessionStore = store,
            networkClient = network,
            channelRepository = RealChannelRepository(store, network),
            memberRepository = RealMemberRepository(store, network),
            warningLogger = { network.logs += it },
            cachedHomeSnapshotProvider = cachedHomeSnapshotProvider
        )
    }
}

/** slock `GET /api/messages/forward/targets/conversations` (`{ targets: ForwardConversationTarget[] }`). */
private val ConversationsBody =
    """
    {"targets":[
      {"type":"thread","channelType":"thread","id":"thread-current","channelId":"thread-current","title":"#general › topic 1","subtitle":"","avatarUrl":null,"parentChannelId":"room-1","parentChannelName":"general","parentChannelType":"channel","parentMessagePreview":"topic 1","current":true,"joined":true,"dmExists":null,"canForwardNow":true,"requiredAction":null},
      {"type":"thread","channelType":"thread","id":"thread-5","channelId":"thread-5","title":"#general › topic 5","subtitle":"","avatarUrl":null,"parentChannelId":"room-1","parentChannelName":"general","parentChannelType":"channel","parentMessagePreview":"topic 5","joined":true,"dmExists":null,"canForwardNow":true,"requiredAction":null},
      {"type":"task","channelType":"task","id":"task-conv-7","channelId":"task-conv-7","title":"#general:#7 ship the forward picker","subtitle":"","avatarUrl":null,"parentChannelId":"room-1","parentChannelName":"general","parentChannelType":"channel","taskNumber":7,"taskTitle":"ship the forward picker","joined":true,"dmExists":null,"canForwardNow":true,"requiredAction":null}
    ]}
    """.trimIndent()

private const val ThreadContext =
    """{"channelType":"thread","parentMessageId":"parent-5","parentChannelId":"room-1","parentChannelType":"channel"}"""
private const val TaskContext =
    """{"channelType":"task","parentChannelId":"room-1","parentChannelType":"channel","taskId":"task-7"}"""
private const val JointThreadContext =
    """{"channelType":"thread","parentMessageId":"root-1","parentChannelId":"joint-local","parentChannelType":"joint"}"""

private fun postedMessage(channelId: String, context: String?): String =
    """{"id":"posted-$channelId","channelId":"$channelId","senderType":"user","senderId":"user-1","senderName":"Ada","messageType":"chat","content":"Forwarded 1 message","createdAt":"2026-11-04T10:00:00.000Z"""" +
        (context?.let { ""","conversationContext":$it""" } ?: "") + "}"

private data class ConversationNetworkCall(
    val priority: DataRequestPriority,
    val path: String,
    val method: String,
    val query: List<Pair<String, String>>,
    val serverId: ServerId?,
    val bodyText: String?
)

private class ConversationNetworkClient : RaftNetworkClient {
    val calls = mutableListOf<ConversationNetworkCall>()
    val logs = mutableListOf<String>()
    var conversationsResponse: suspend () -> HomeNetworkResponse = {
        HomeNetworkResponse(code = 200, bodyText = """{"targets":[]}""")
    }
    var forwardResponse: suspend (String) -> HomeNetworkResponse = {
        HomeNetworkResponse(code = 500, bodyText = null, errorBodyText = "unexpected")
    }
    var channelResponse: suspend (String) -> HomeNetworkResponse = {
        HomeNetworkResponse(code = 404, bodyText = null, errorBodyText = "not found")
    }

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse {
        calls += ConversationNetworkCall(priority, path, method, query, serverIdOverride, bodyText)
        return when {
            path == ForwardConversationTargetsPath -> conversationsResponse()
            path == ForwardTargetSearchPath -> HomeNetworkResponse(code = 200, bodyText = """{"targets":[]}""")
            path == "messages/forward" -> forwardResponse(bodyText.orEmpty())
            path == "channels" || path == "channels/dm" -> HomeNetworkResponse(code = 200, bodyText = "[]")
            path.startsWith("channels/") -> channelResponse(path.removePrefix("channels/"))
            else -> HomeNetworkResponse(code = 404, bodyText = null, errorBodyText = "not found")
        }
    }
}
