package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.network.MultipartFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ThreadApiTest {
    @Test
    fun attachmentCommentsFailureParsesOnlyCanonicalErrorEnvelope() {
        assertEquals(
            ThreadAttachmentCommentsFailureProjection(
                errorCode = "attachment_comments_disabled",
                serverMessage = "Attachment comments are not enabled on this server"
            ),
            ThreadApiResult.Failure(
                code = 403,
                errorBodyText =
                    """{"error":"Attachment comments are not enabled on this server","code":"attachment_comments_disabled"}"""
            ).toThreadAttachmentCommentsFailureProjection()
        )
        assertEquals(
            ThreadAttachmentCommentsFailureProjection(
                errorCode = "http_500",
                serverMessage = "Failed to load comments"
            ),
            ThreadApiResult.Failure(
                code = 500,
                errorBodyText = """{"error":"Failed to load comments"}"""
            ).toThreadAttachmentCommentsFailureProjection()
        )
    }

    @Test
    fun attachmentCommentsFailureRejectsAliasesMalformedPlainAndEmptyBodies() {
        listOf(
            """{"message":"must not be guessed","errorCode":"wrong_alias"}""",
            "{",
            "plain failure",
            "   ",
            """{"error":" ","code":" "}"""
        ).forEach { body ->
            assertEquals(
                ThreadAttachmentCommentsFailureProjection(
                    errorCode = "http_400",
                    serverMessage = null
                ),
                ThreadApiResult.Failure(
                    code = 400,
                    errorBodyText = body
                ).toThreadAttachmentCommentsFailureProjection(),
                body
            )
        }
    }

    @Test
    fun threadFailureProjectionPreservesCanonicalErrorTextVerbatim() {
        val exactReason = "  添付コメントは監査ポリシーで拒否されました  "

        val projection =
            ThreadApiResult.Failure(
                code = 403,
                errorBodyText = """{"error":"$exactReason","code":"attachment_comments_forbidden"}"""
            ).toThreadAttachmentCommentsFailureProjection()

        assertEquals("attachment_comments_forbidden", projection.errorCode)
        assertEquals(exactReason, projection.serverMessage)
    }

    @Test
    fun channelMessagesAcceptsCanonicalUpstreamWrapperOnly() = runBlocking {
        val api =
            RaftThreadApiClient(
                FakeThreadNetworkApi(
                    channelMessagesBody =
                        """
                        {"messages":[{"id":"m1","content":"hello","seq":1}],"historyLimited":false}
                        """.trimIndent()
                )
            )

        val result = api.channelMessages("channel-1")

        val page = assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(result).data
        assertEquals(1, page.messageCount)
        assertEquals(false, page.canLoadMoreBefore)
    }

    @Test
    fun channelMessagesRejectsLegacyNestedDataWrapper() = runBlocking {
        val api =
            RaftThreadApiClient(
                FakeThreadNetworkApi(
                    channelMessagesBody =
                        """
                        {"data":{"messages":[{"id":"m1","content":"hello","seq":1}]}}
                        """.trimIndent()
                )
            )

        val result = api.channelMessages("channel-1")

        assertEquals("thread_messages_contract_mismatch", assertIs<ThreadApiResult.Failure>(result).contractCode)
    }

    @Test
    fun attachmentOpenTargetRequiresCanonicalUrlField() = runBlocking {
        val api = RaftThreadApiClient(FakeThreadNetworkApi(attachmentUrlBody = """{"url":"https://cdn.slock.ai/a.png","expiresAt":null}"""))

        val result = api.attachmentUrl(DataRequestPriority.Visible, "att-1")

        assertEquals("https://cdn.slock.ai/a.png", assertIs<ThreadApiResult.Success<ThreadAttachmentOpenTarget>>(result).data.url)
    }

    @Test
    fun attachmentOpenTargetRejectsDownloadUrlAlias() = runBlocking {
        val api = RaftThreadApiClient(FakeThreadNetworkApi(attachmentUrlBody = """{"downloadUrl":"https://cdn.slock.ai/a.png"}"""))

        val result = api.attachmentUrl(DataRequestPriority.Visible, "att-1")

        assertEquals("thread_attachment_url_contract_mismatch", assertIs<ThreadApiResult.Failure>(result).contractCode)
    }

    @Test
    fun attachmentCommentsParseCanonicalPayload() = runBlocking {
        val api =
            RaftThreadApiClient(
                FakeThreadNetworkApi(
                    attachmentCommentsBody =
                        """
                        {
                          "threadChannelId": "thread-comments",
                          "viewer": {"canComment": true, "reason": "ok"},
                          "comments": [
                            {
                              "id": "comment-1",
                              "channelId": "thread-comments",
                              "senderType": "user",
                              "senderId": "u1",
                              "senderName": "Artin",
                              "content": "Please revise this section",
                              "createdAt": "2026-06-20T01:00:00Z",
                              "reactions": [{"emoji": "✅", "reactorType": "user", "reactorId": "u2"}],
                              "anchor": {"type": "text", "data": {"label": "line 12"}}
                            }
                          ]
                        }
                        """.trimIndent()
                )
            )

        val result = api.attachmentComments("att-1")

        val page = assertIs<ThreadApiResult.Success<ThreadAttachmentCommentsPage>>(result).data
        assertEquals("thread-comments", page.threadChannelId)
        assertEquals(true, page.viewer?.canComment)
        val comment = page.comments.single()
        assertEquals("comment-1", comment.id)
        assertEquals("line 12", comment.anchorLabel)
        assertEquals("✅", comment.reactions.single().emoji)
    }

    @Test
    fun attachmentCommentAnchorsUseWebSectionAndLineLabels() = runBlocking {
        val api =
            RaftThreadApiClient(
                FakeThreadNetworkApi(
                    attachmentCommentsBody =
                        """
                        {
                          "threadChannelId": "thread-comments",
                          "viewer": {"canComment": true, "reason": "ok"},
                          "comments": [
                            {"id": "c1", "channelId": "t", "senderType": "user", "senderId": "u1", "senderName": "A",
                             "content": "x", "createdAt": "2026-06-20T01:00:00Z", "reactions": [],
                             "anchor": {"type": "md-section", "data": {"headingId": "intro", "headingTitle": "Intro"}}},
                            {"id": "c2", "channelId": "t", "senderType": "user", "senderId": "u1", "senderName": "A",
                             "content": "x", "createdAt": "2026-06-20T01:00:00Z", "reactions": [],
                             "anchor": {"type": "lines", "data": {"start": 3, "end": 7}}},
                            {"id": "c3", "channelId": "t", "senderType": "user", "senderId": "u1", "senderName": "A",
                             "content": "x", "createdAt": "2026-06-20T01:00:00Z", "reactions": [],
                             "anchor": {"type": "lines", "data": {"start": 4}}}
                          ]
                        }
                        """.trimIndent()
                )
            )

        val page = assertIs<ThreadApiResult.Success<ThreadAttachmentCommentsPage>>(api.attachmentComments("att-1")).data

        assertEquals(listOf("§ Intro", "L3–7", "L4"), page.comments.map { it.anchorLabel })
    }

    @Test
    fun syncRequiresCanonicalRootArray() = runBlocking {
        val api = RaftThreadApiClient(FakeThreadNetworkApi(syncBody = """[{"id":"m2","content":"new","seq":2}]"""))

        val result = api.messageSync("channel-1", sinceSeq = 1)

        assertEquals(1, assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(result).data.messageCount)
    }

    @Test
    fun sendMessageParsesCanonicalRandomIdEcho() = runBlocking {
        val api =
            RaftThreadApiClient(
                FakeThreadNetworkApi(
                    sendBody = """{"id":"m-send","channelId":"channel-1","senderType":"user","senderId":"u1","content":"sent","randomId":"rid-send-1","seq":1}"""
                )
            )

        val result = api.sendMessage("channel-1", "sent", randomId = "rid-send-1")

        val message = assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(result).data.messages.single()
        assertEquals("m-send", message.id)
        assertEquals("rid-send-1", message.randomId)
    }

    @Test
    fun messageSyncEmitsOffUiParseDiagnostics() = runBlocking {
        val logs = mutableListOf<String>()
        val api =
            RaftThreadApiClient(
                FakeThreadNetworkApi(syncBody = """[{"id":"m2","content":"new","seq":2}]"""),
                diagnosticsLogger = logs::add
            )

        val result = api.messageSync("channel-1", sinceSeq = 1)

        assertEquals(1, assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(result).data.messageCount)
        assertTrue(logs.any { it.contains("thread_parse_start source=thread_sync") && it.contains("dispatcher=JsonParse") })
        assertTrue(
            logs.any {
                it.contains("thread_parse_result source=thread_sync result=success messages=1") &&
                    it.contains("dispatcher=JsonParse")
            }
        )
    }

    @Test
    fun channelMessagesPreserveTaskLifecycleSystemRows() = runBlocking {
        val api =
            RaftThreadApiClient(
                FakeThreadNetworkApi(
                    channelMessagesBody =
                        """
                        {
                          "messages": [
                            {
                              "id": "sys-done-1",
                              "channelId": "thread-1",
                              "senderType": "user",
                              "senderId": "system",
                              "senderName": "System",
                              "messageType": "system",
                              "content": "✅ artin moved #252 \"Implement Thread timeline system-message rendering\" to Done",
                              "createdAt": "2026-06-13T01:00:00Z",
                              "seq": 44
                            }
                          ],
                          "historyLimited": false
                        }
                        """.trimIndent()
                )
            )

        val result = api.channelMessages("thread-1")

        val message = assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(result).data.messages.single()
        assertEquals("sys-done-1", message.id)
        assertEquals("system", message.messageType)
        assertEquals("✅ artin moved #252 \"Implement Thread timeline system-message rendering\" to Done", message.content)
    }

    @Test
    fun syncPreservesTaskLifecycleSystemRows() = runBlocking {
        val api =
            RaftThreadApiClient(
                FakeThreadNetworkApi(
                    syncBody =
                        """
                        [
                          {
                            "id": "sys-claim-1",
                            "channelId": "thread-1",
                            "senderType": "user",
                            "senderId": "system",
                            "senderName": "System",
                            "messageType": "system",
                            "content": "claimed #252 \"Implement Thread timeline system-message rendering\"",
                            "createdAt": "2026-06-13T01:00:00Z",
                            "seq": 45
                          }
                        ]
                        """.trimIndent()
                )
            )

        val result = api.messageSync("thread-1", sinceSeq = 44)

        val message = assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(result).data.messages.single()
        assertEquals("sys-claim-1", message.id)
        assertEquals("system", message.messageType)
        assertEquals("claimed #252 \"Implement Thread timeline system-message rendering\"", message.content)
    }

    @Test
    fun syncRejectsNestedWrapper() = runBlocking {
        val api = RaftThreadApiClient(FakeThreadNetworkApi(syncBody = """{"data":[{"id":"m2","content":"new","seq":2}]}"""))

        val result = api.messageSync("channel-1", sinceSeq = 1)

        assertEquals("thread_sync_contract_mismatch", assertIs<ThreadApiResult.Failure>(result).contractCode)
    }

    @Test
    fun sendMessageAcceptsRawMessageResponseWithoutPendingActions() = runBlocking {
        val api = RaftThreadApiClient(FakeThreadNetworkApi(sendBody = """{"id":"m3","channelId":"channel-1","content":"sent","seq":3}"""))

        val result = api.sendMessage("channel-1", "sent")

        val page = assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(result).data
        assertEquals("m3", page.messages.single().id)
        assertEquals(emptyList(), page.pendingMentionActions)
    }

    @Test
    fun sendMessageAcceptsWrappedMessageResponseWithPendingMentionActions() = runBlocking {
        val api =
            RaftThreadApiClient(
                FakeThreadNetworkApi(
                    sendBody =
                        """
                        {
                          "message": {"id":"m3","channelId":"channel-1","content":"hi @outside","seq":3},
                          "pendingMentionActions": [
                            {
                              "resolutionId": "res-1",
                              "messageId": "m3",
                              "channelId": "channel-1",
                              "channelName": "对话流专修",
                              "targetType": "agent",
                              "targetHandle": "outside",
                              "targetAvatarUrl": "https://cdn.slock.ai/avatars/agents/outside.webp",
                              "reason": "not_member",
                              "availableActions": ["add", "notify"],
                              "expiresAt": "2026-06-21T00:00:00Z"
                            },
                            {
                              "resolutionId": "res-2",
                              "messageId": "m3",
                              "channelId": "channel-1",
                              "channelName": "对话流专修",
                              "targetType": "agent",
                              "targetHandle": "blank-avatar",
                              "targetAvatarUrl": "   ",
                              "reason": "not_member",
                              "availableActions": ["notify"]
                            }
                          ]
                        }
                        """.trimIndent()
                )
            )

        val result = api.sendMessage("channel-1", "hi @outside")

        val page = assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(result).data
        assertEquals("m3", page.messages.single().id)
        assertEquals(2, page.pendingMentionActions.size)
        val pending = page.pendingMentionActions.first()
        assertEquals("res-1", pending.resolutionId)
        assertEquals("m3", pending.messageId?.value)
        assertEquals("channel-1", pending.channelId?.value)
        assertEquals("对话流专修", pending.channelName)
        assertEquals("agent", pending.targetType)
        assertEquals("outside", pending.targetHandle)
        assertEquals("https://cdn.slock.ai/avatars/agents/outside.webp", pending.targetAvatarUrl)
        assertEquals(listOf("add", "notify"), pending.availableActions)
        assertEquals(null, page.pendingMentionActions[1].targetAvatarUrl)
    }

    @Test
    fun executePendingMentionActionsNormalizesLegacyInviteToCanonicalAdd() = runBlocking {
        val transport =
            FakeThreadNetworkApi(
                mentionActionBody = """{"results":[{"resolutionId":"res-1","status":"delivered","reason":null}]}"""
            )
        val api = RaftThreadApiClient(transport)

        val result = api.executePendingMentionActions("invite", listOf("res-1"))

        val row = assertIs<ThreadApiResult.Success<List<PendingMentionActionExecuteResult>>>(result).data.single()
        assertEquals("res-1", row.resolutionId)
        assertEquals("delivered", row.status)
        assertEquals("""{"action":"add","resolutionIds":["res-1"]}""", transport.lastMentionActionBody)
    }

    @Test
    fun executePendingMentionActionsPreservesHttpFailureForUserFacingClassification() = runBlocking {
        val rawError = """{"error":"must not be shown verbatim","code":"raw_permission_code"}"""
        val api =
            RaftThreadApiClient(
                FakeThreadNetworkApi(
                    mentionActionCode = 403,
                    mentionActionErrorBody = rawError
                )
            )

        val failure =
            assertIs<ThreadApiResult.Failure>(
                api.executePendingMentionActions("add", listOf("res-1"))
            )

        assertEquals(403, failure.code)
        assertEquals(rawError, failure.errorBodyText)
        assertEquals(null, failure.contractCode)
    }

    @Test
    fun executePendingMentionActionsMarksMalformedSuccessAsContractFailure() = runBlocking {
        val api = RaftThreadApiClient(FakeThreadNetworkApi(mentionActionBody = """{"results":"invalid"}"""))

        val failure =
            assertIs<ThreadApiResult.Failure>(
                api.executePendingMentionActions("add", listOf("res-1"))
            )

        assertEquals(200, failure.code)
        assertEquals("thread_mention_action_contract_mismatch", failure.contractCode)
    }

    @Test
    fun createThreadRequiresCanonicalThreadChannelId() = runBlocking {
        val api =
            RaftThreadApiClient(
                FakeThreadNetworkApi(createThreadBody = """{"threadChannelId":"thread-1","parentMessageId":"m1","created":true,"replyCount":2}""")
            )

        val result = api.createOrOpenThread("channel-1", "m1")

        val action = assertIs<ThreadApiResult.Success<ThreadActionResult>>(result).data
        assertEquals("thread-1", action.threadChannelId.value)
        assertEquals("m1", action.parentMessageId?.value)
        assertEquals(2, action.replyCount)
    }

    @Test
    fun createThreadRejectsNestedGuessedResponse() = runBlocking {
        val api =
            RaftThreadApiClient(
                FakeThreadNetworkApi(createThreadBody = """{"data":{"thread":{"threadChannelId":"thread-1"}}}""")
            )

        val result = api.createOrOpenThread("channel-1", "m1")

        assertEquals("thread_create_contract_mismatch", assertIs<ThreadApiResult.Failure>(result).contractCode)
    }

    @Test
    fun unsaveMessageCallsCanonicalTransport() = runBlocking {
        val transport = FakeThreadNetworkApi()
        val api = RaftThreadApiClient(transport)

        val result = api.unsaveMessage("msg-1")

        assertIs<ThreadApiResult.Success<Unit>>(result)
        assertEquals(listOf("msg-1"), transport.unsavedMessageIds)
    }

    @Test
    fun networkThreadApiUsesRaftNetworkClientRoutesAndBodies() = runBlocking {
        val networkClient = RecordingThreadNetworkClient()
        val api = RaftThreadApiClient(NetworkRaftThreadApi(networkClient))

        assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(api.channelMessages("thread-1", limit = 25, beforeSeq = 99))
        assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(api.channelMessages("thread-1", limit = 25, afterSeq = 100))
        assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(api.messageSync("channel-1", sinceSeq = 10, limit = 30))
        assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(
            api.sendMessage("channel-1", "hello", asTask = true, attachmentIds = listOf("att-1"), randomId = "rid-1")
        )
        assertIs<ThreadApiResult.Success<ThreadAttachmentOpenTarget>>(api.attachmentUrl(DataRequestPriority.Visible, "att-1", disposition = "inline"))
        // A gallery neighbour's preload resolves its URL in the background lane.
        assertIs<ThreadApiResult.Success<ThreadAttachmentOpenTarget>>(api.attachmentUrl(DataRequestPriority.Background, "att-2", disposition = "inline"))
        assertIs<ThreadApiResult.Success<ThreadAttachmentCommentsPage>>(api.attachmentComments("att-1"))
        assertIs<ThreadApiResult.Success<Unit>>(api.sendAttachmentComment("att-1", "looks good"))
        assertIs<ThreadApiResult.Success<Unit>>(api.markChannelReadToSeq("channel-1", 42L))
        assertIs<ThreadApiResult.Success<Unit>>(api.markChannelReadAll("channel-1"))
        assertIs<ThreadApiResult.Success<Unit>>(api.saveMessage("msg-1"))
        assertIs<ThreadApiResult.Success<Unit>>(api.unsaveMessage("msg-1"))
        assertIs<ThreadApiResult.Success<MessageMutationSummary>>(api.addReaction("msg-1", "rocket"))
        assertIs<ThreadApiResult.Success<MessageMutationSummary>>(api.removeReaction("msg-1", "rocket"))
        assertIs<ThreadApiResult.Success<ThreadActionResult>>(api.createOrOpenThread("channel-1", "parent-1"))
        assertIs<ThreadApiResult.Success<List<PendingMentionActionExecuteResult>>>(
            api.executePendingMentionActions("notify", listOf("res-1"))
        )

        assertEquals(
            listOf(
                "GET messages/channel/thread-1 priority=Visible query=limit=25&before=99 server= body=",
                "GET messages/channel/thread-1 priority=Visible query=limit=25&after=100 server= body=",
                "GET messages/sync priority=Visible query=since_seq=10&limit=30&channel_id=channel-1 server= body=",
                """POST messages priority=Foreground query= server= body={"channelId":"channel-1","content":"hello","attachmentIds":["att-1"],"asTask":true,"randomId":"rid-1"}""",
                "GET attachments/att-1/url priority=Visible query=disposition=inline server= body=",
                "GET attachments/att-2/url priority=Background query=disposition=inline server= body=",
                "GET attachments/att-1/comments priority=Visible query= server= body=",
                """POST attachments/att-1/comments priority=Foreground query= server= body={"content":"looks good"}""",
                """POST channels/channel-1/read priority=Foreground query= server= body={"seq":42}""",
                "POST channels/channel-1/read-all priority=Foreground query= server= body=",
                """POST channels/saved priority=Foreground query= server= body={"messageId":"msg-1"}""",
                "DELETE channels/saved/msg-1 priority=Foreground query= server= body=",
                """POST messages/msg-1/reactions priority=Foreground query= server= body={"emoji":"rocket"}""",
                """DELETE messages/msg-1/reactions priority=Foreground query= server= body={"emoji":"rocket"}""",
                """POST channels/channel-1/threads priority=Foreground query= server= body={"parentMessageId":"parent-1"}""",
                """POST messages/mention-actions/execute priority=Foreground query= server= body={"action":"notify","resolutionIds":["res-1"]}"""
            ),
            networkClient.calls
        )
    }

    @Test
    fun sendMessageBodyOmitsRandomIdWhenMissingOrBlank() {
        assertEquals(
            """{"channelId":"channel-1","content":"hello","attachmentIds":[],"asTask":false}""",
            buildSharedThreadSendMessageBody(channelId = "channel-1", body = "hello")
        )
        assertEquals(
            """{"channelId":"channel-1","content":"hello","attachmentIds":[],"asTask":false}""",
            buildSharedThreadSendMessageBody(channelId = "channel-1", body = "hello", randomId = " ")
        )
    }

    private class FakeThreadNetworkApi(
        var channelMessagesBody: String = """{"messages":[],"historyLimited":false}""",
        var syncBody: String = "[]",
        var sendBody: String = """{"id":"m1","content":"sent","seq":1}""",
        var attachmentUrlBody: String = """{"url":"https://cdn.slock.ai/default"}""",
        var attachmentCommentsBody: String = """{"comments":[],"threadChannelId":"thread-comments"}""",
        var reactionBody: String = """{"id":"m1","reactions":[]}""",
        var createThreadBody: String = """{"threadChannelId":"thread-1"}""",
        var contextBody: String = """{"channelId":"c1","targetMessageId":"m1","messages":[],"historyLimited":false}""",
        var mentionActionBody: String = """{"results":[]}""",
        var mentionActionCode: Int = 200,
        var mentionActionErrorBody: String? = null
    ) : SharedThreadNetworkApi {
        val unsavedMessageIds = mutableListOf<String>()
        var lastMentionActionBody: String? = null
        var lastReadToSeqRequest: Pair<String, Long>? = null
        override suspend fun channelMessages(
            threadId: String,
            limit: Int,
            beforeSeq: Long?,
            afterSeq: Long?
        ): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = channelMessagesBody)

        override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = syncBody)

        override suspend fun sendMessage(
            channelId: String,
            body: String,
            asTask: Boolean,
            attachmentIds: List<String>,
            randomId: String?
        ): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = sendBody)

        override suspend fun executePendingMentionActions(action: String, resolutionIds: List<String>): HomeNetworkResponse {
            lastMentionActionBody = buildPendingMentionActionExecuteBody(action, resolutionIds)
            return HomeNetworkResponse(
                code = mentionActionCode,
                bodyText = mentionActionBody.takeIf { mentionActionCode in 200..299 },
                errorBodyText = mentionActionErrorBody
            )
        }

        override suspend fun attachmentUrl(priority: DataRequestPriority, attachmentId: String, disposition: String?): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = attachmentUrlBody)

        override suspend fun attachmentHtmlPreviewUrl(attachmentId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = attachmentUrlBody)

        override suspend fun attachmentComments(attachmentId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = attachmentCommentsBody)

        override suspend fun sendAttachmentComment(attachmentId: String, body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")

        override suspend fun markChannelReadToSeq(channelId: String, seq: Long): HomeNetworkResponse {
            lastReadToSeqRequest = channelId to seq
            return HomeNetworkResponse(code = 200, bodyText = """{"ok":true,"seq":$seq}""")
        }

        override suspend fun markChannelReadAll(channelId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")

        override suspend fun saveMessage(messageId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")

        override suspend fun unsaveMessage(messageId: String): HomeNetworkResponse {
            unsavedMessageIds += messageId
            return HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
        }

        override suspend fun addReaction(messageId: String, emoji: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = reactionBody)

        override suspend fun removeReaction(messageId: String, emoji: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = reactionBody)

        override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = createThreadBody)

        override suspend fun messageContext(messageId: String, channelId: String?): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = contextBody)
    }

    private class RecordingThreadNetworkClient : RaftNetworkClient {
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
            calls +=
                "$method $normalizedPath priority=$priority query=${query.joinToString("&") { "${it.first}=${it.second}" }} " +
                    "server=${serverIdOverride?.value.orEmpty()} body=${bodyText.orEmpty()}"
            return when {
                normalizedPath.startsWith("messages/channel/") -> ok(channelMessagesBody)
                normalizedPath == "messages/sync" -> ok(syncBody)
                normalizedPath == "messages" && method == "POST" -> ok(sendBody)
                normalizedPath.startsWith("attachments/") && normalizedPath.endsWith("/comments") -> ok("""{"comments":[],"threadChannelId":"thread-comments"}""")
                normalizedPath == "messages/mention-actions/execute" && method == "POST" -> ok(mentionActionBody)
                normalizedPath.startsWith("attachments/") -> ok(attachmentUrlBody)
                normalizedPath.endsWith("/read") || normalizedPath.endsWith("/read-all") -> ok("""{"ok":true}""")
                normalizedPath == "channels/saved" && method == "POST" -> ok("""{"ok":true}""")
                normalizedPath.startsWith("channels/saved/") && method == "DELETE" -> ok("""{"ok":true}""")
                normalizedPath.endsWith("/reactions") -> ok(reactionBody)
                normalizedPath.endsWith("/threads") -> ok(createThreadBody)
                else -> HomeNetworkResponse(code = 404, bodyText = null, errorBodyText = "not found")
            }
        }

        override suspend fun uploadAttachments(
            priority: DataRequestPriority,
            channelId: ChannelId,
            files: List<MultipartFile>
        ): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)

        private companion object {
            const val channelMessagesBody =
                """{"messages":[{"id":"m1","content":"hello","seq":1}],"historyLimited":false}"""
            const val syncBody = """[{"id":"m2","content":"sync","seq":2}]"""
            const val sendBody = """{"id":"m3","content":"hello","seq":3}"""
            const val attachmentUrlBody = """{"url":"https://cdn.slock.ai/a.png","expiresAt":null}"""
            const val reactionBody = """{"id":"m1","reactions":[]}"""
            const val createThreadBody = """{"threadChannelId":"thread-1","parentMessageId":"parent-1","created":true}"""
            const val mentionActionBody = """{"results":[{"resolutionId":"res-1","status":"delivered"}]}"""
        }
    }
}
