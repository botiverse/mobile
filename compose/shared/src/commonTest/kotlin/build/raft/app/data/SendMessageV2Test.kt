package build.raft.app.data

import build.raft.app.ThreadUserFacingErrorReason
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.network.MultipartFile
import build.raft.app.threadSendFailureErrorCode
import build.raft.app.threadSendShouldSilentlyRetry
import build.raft.app.threadUserFacingErrorCodeReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Task #77: every app send goes to `POST /api/v2/messages`, the endpoint web
 * `messageStore.sendMessage` posts to (botiverse/slock `routes/messages.ts`
 * `createHumanMessage(…, "v2")`). Same body as v1; the answer always wraps the message and adds
 * the sender-only `unresolvedMentionHandles`.
 */
class SendMessageV2Test {
    // ── Request building ──

    @Test
    fun bothSendShapesPostTheUnchangedBodyToV2Messages() = runBlocking {
        val network = RecordingNetworkClient(sendBody = WrappedSend)
        val api = RaftThreadApiClient(NetworkRaftThreadApi(network))

        api.sendMessage("channel-1", "hi @ghost", attachmentIds = listOf("att-1"), randomId = "rid-1")
        api.sendMessageWithMentions(
            channelId = "channel-1",
            body = "hi @yezi",
            asTask = false,
            attachmentIds = emptyList(),
            randomId = "rid-2",
            mentions = listOf(ThreadSendMessageMention(type = "external", id = "projection-1", name = "yezi")),
        )

        assertEquals(
            listOf(
                """POST v2/messages Foreground {"channelId":"channel-1","content":"hi @ghost","attachmentIds":["att-1"],"asTask":false,"randomId":"rid-1"}""",
                """POST v2/messages Foreground {"channelId":"channel-1","content":"hi @yezi","attachmentIds":[],"asTask":false,"randomId":"rid-2","mentions":[{"type":"external","id":"projection-1","name":"yezi"}]}""",
            ),
            network.calls
        )
        assertEquals("v2/messages", SendMessagePath)
    }

    // ── Response parsing ──

    @Test
    fun wrappedV2AnswerCarriesTheMessageAndItsUnresolvedMentionHandles() = runBlocking {
        val page = send(WrappedSend)

        assertEquals("m-v2", page.messages.single().id)
        assertEquals(listOf("@ghost", "@outside"), page.unresolvedMentionHandles)
        assertEquals(emptyList(), page.pendingMentionActions)
    }

    @Test
    fun unresolvedMentionHandlesKeepOnlyStringItemsAsWebDoes() = runBlocking {
        val page =
            send(
                """{"message":$MessageRow,"unresolvedMentionHandles":["@a",1,null,{"x":1},"","  ","@b"]}"""
            )

        assertEquals(listOf("@a", "@b"), page.unresolvedMentionHandles)
    }

    @Test
    fun aMalformedUnresolvedMentionFieldNeverFailsTheSend() = runBlocking {
        listOf("\"@a\"", "{}", "null", "42").forEach { value ->
            val page = send("""{"message":$MessageRow,"unresolvedMentionHandles":$value}""")
            assertEquals("m-v2", page.messages.single().id, value)
            assertEquals(emptyList(), page.unresolvedMentionHandles, value)
        }
    }

    @Test
    fun aV2AnswerWithoutExtrasAndABareV1MessageBothParse() = runBlocking {
        assertEquals(emptyList(), send("""{"message":$MessageRow}""").unresolvedMentionHandles)
        val bare = send(MessageRow)
        assertEquals("m-v2", bare.messages.single().id)
        assertEquals(emptyList(), bare.unresolvedMentionHandles)
    }

    @Test
    fun wrappedV2AnswerStillCarriesPendingMentionActionsAlongsideUnresolvedHandles() = runBlocking {
        val page =
            send(
                """{"message":$MessageRow,"pendingMentionActions":[{"resolutionId":"res-1","targetType":"agent",""" +
                    """"targetHandle":"outside","availableActions":["add","notify"]}],"unresolvedMentionHandles":["@ghost"],""" +
                    """"deliveryWarnings":[{"targetType":"agent","targetId":"a1","reason":"agent_stopped"}]}"""
            )

        assertEquals(listOf("res-1"), page.pendingMentionActions.map { it.resolutionId })
        assertEquals(listOf("@ghost"), page.unresolvedMentionHandles)
    }

    @Test
    fun aWrappedAnswerWithoutAMessageIsAContractFailure() = runBlocking {
        val api = RaftThreadApiClient(NetworkRaftThreadApi(RecordingNetworkClient(sendBody = """{"unresolvedMentionHandles":["@a"]}""")))

        val failure = assertIs<ThreadApiResult.Failure>(api.sendMessage("channel-1", "hi", randomId = "rid-1"))

        assertEquals("thread_send_contract_mismatch", failure.contractCode)
    }

    @Test
    fun repositoryParserReadsTheSameV2Shape() {
        val payload =
            parseSharedSentMessagePayload(
                """{"message":${SharedRow},"unresolvedMentionHandles":["@ghost",3]}""",
                ThreadId("channel-1")
            )

        assertEquals("m-v2", payload.message?.id?.value)
        assertEquals(listOf("@ghost"), payload.unresolvedMentionHandles)
        assertFalse(payload.projectionRejected)
    }

    // ── Dedupe / optimistic reconciliation ──

    @Test
    fun theWrappedMessageEchoesTheRandomIdTheOptimisticRowIsMatchedBy() = runBlocking {
        val network = RecordingNetworkClient(sendBody = WrappedSend)
        val api = RaftThreadApiClient(NetworkRaftThreadApi(network))

        // A silent retry resends the same randomId; the server answers the first message again.
        val first = assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(api.sendMessage("channel-1", "hi @ghost", randomId = "rid-send-1"))
        network.sendBody = """{"message":$MessageRow}"""
        val replay = assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(api.sendMessage("channel-1", "hi @ghost", randomId = "rid-send-1"))

        assertEquals("rid-send-1", first.data.messages.single().randomId)
        assertEquals(first.data.messages.single().id, replay.data.messages.single().id)
        assertTrue(network.calls.all { "\"randomId\":\"rid-send-1\"" in it })
        // A replay carries no sender-only extras (messageService: `!replayed`).
        assertEquals(emptyList(), replay.data.unresolvedMentionHandles)
    }

    // ── Error mapping: same user-facing outcome as v1 ──

    @Test
    fun v2MentionBindingConflictReadsAsTheSameGenericFailureAsTheV1Http400() = runBlocking {
        val v2 =
            sendFailure(
                code = 400,
                errorBody = """{"error":"Mention @ops is bound to more than one actor.","code":"mention_binding_conflict"}"""
            )
        val v1 = sendFailure(code = 400, errorBody = """{"error":"Mention @ops is bound to more than one actor."}""")

        val v2Code = threadSendFailureErrorCode(v2.contractCode, v2.errorBodyText, v2.code)
        val v1Code = threadSendFailureErrorCode(v1.contractCode, v1.errorBodyText, v1.code)
        assertEquals("mention_binding_conflict", v2Code)
        assertEquals("http_400", v1Code)
        assertEquals(ThreadUserFacingErrorReason.Generic, threadUserFacingErrorCodeReason(v2Code))
        assertEquals(threadUserFacingErrorCodeReason(v1Code), threadUserFacingErrorCodeReason(v2Code))
        assertFalse(threadSendShouldSilentlyRetry(v2Code, v2.code))
    }

    @Test
    fun errorsSharedByV1AndV2KeepTheirCodesAndRetryPolicy() = runBlocking {
        val cases =
            listOf(
                Triple(409, """{"error":"randomId has already been used for a different message","code":"random_id_conflict"}""", "random_id_conflict"),
                Triple(403, """{"error":"Not a member","code":"peer_not_member"}""", "peer_not_member"),
                Triple(409, """{"error":"This channel is archived","code":"channel_archived"}""", "channel_archived"),
                Triple(400, """{"error":"Invalid message request body"}""", "http_400"),
                Triple(500, """{"error":"Failed to send message"}""", "http_500"),
            )
        cases.forEach { (code, body, expected) ->
            val failure = sendFailure(code, body)
            assertEquals(expected, threadSendFailureErrorCode(failure.contractCode, failure.errorBodyText, failure.code), body)
        }
        assertTrue(threadSendShouldSilentlyRetry("http_500", 500))
        assertFalse(threadSendShouldSilentlyRetry("random_id_conflict", 409))
        assertEquals(ThreadUserFacingErrorReason.ServerUnavailable, threadUserFacingErrorCodeReason("http_500"))
    }

    private suspend fun send(body: String): ThreadMessagesPage {
        val api = RaftThreadApiClient(NetworkRaftThreadApi(RecordingNetworkClient(sendBody = body)))
        return assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(api.sendMessage("channel-1", "hi", randomId = "rid-send-1")).data
    }

    private suspend fun sendFailure(code: Int, errorBody: String): ThreadApiResult.Failure {
        val api =
            RaftThreadApiClient(
                NetworkRaftThreadApi(RecordingNetworkClient(sendBody = "", sendCode = code, sendErrorBody = errorBody))
            )
        return assertIs<ThreadApiResult.Failure>(api.sendMessage("channel-1", "hi", randomId = "rid-1"))
    }

    private class RecordingNetworkClient(
        var sendBody: String,
        private val sendCode: Int = 200,
        private val sendErrorBody: String? = null,
    ) : RaftNetworkClient {
        val calls = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            val normalizedPath = path.trim('/')
            calls += "$method $normalizedPath $priority ${bodyText.orEmpty()}"
            if (method != "POST" || normalizedPath != "v2/messages") return HomeNetworkResponse(code = 404, bodyText = null)
            return if (sendCode in 200..299) {
                HomeNetworkResponse(code = sendCode, bodyText = sendBody)
            } else {
                HomeNetworkResponse(code = sendCode, bodyText = null, errorBodyText = sendErrorBody)
            }
        }

        override suspend fun uploadAttachments(
            priority: DataRequestPriority,
            channelId: ChannelId,
            files: List<MultipartFile>
        ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)
    }

    private companion object {
        const val MessageRow =
            """{"id":"m-v2","channelId":"channel-1","senderType":"user","senderId":"u1","content":"hi @ghost","randomId":"rid-send-1","seq":7}"""
        const val SharedRow =
            """{"id":"m-v2","channelId":"channel-1","senderType":"user","senderId":"u1","senderName":"Ada","messageType":"chat","content":"hi @ghost","createdAt":"2026-11-09T00:00:00Z"}"""
        const val WrappedSend = """{"message":$MessageRow,"unresolvedMentionHandles":["@ghost","@outside"]}"""
    }
}
