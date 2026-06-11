package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ServerId
import ai.slock.compose.network.MultipartFile
import ai.slock.compose.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class ThreadApiTest {
    @Test
    fun channelMessagesAcceptsCanonicalUpstreamWrapperOnly() = runBlocking {
        val api =
            SlockThreadApiClient(
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
            SlockThreadApiClient(
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
        val api = SlockThreadApiClient(FakeThreadNetworkApi(attachmentUrlBody = """{"url":"https://cdn.slock.ai/a.png","expiresAt":null}"""))

        val result = api.attachmentUrl("att-1")

        assertEquals("https://cdn.slock.ai/a.png", assertIs<ThreadApiResult.Success<ThreadAttachmentOpenTarget>>(result).data.url)
    }

    @Test
    fun attachmentOpenTargetRejectsDownloadUrlAlias() = runBlocking {
        val api = SlockThreadApiClient(FakeThreadNetworkApi(attachmentUrlBody = """{"downloadUrl":"https://cdn.slock.ai/a.png"}"""))

        val result = api.attachmentUrl("att-1")

        assertEquals("thread_attachment_url_contract_mismatch", assertIs<ThreadApiResult.Failure>(result).contractCode)
    }

    @Test
    fun syncRequiresCanonicalRootArray() = runBlocking {
        val api = SlockThreadApiClient(FakeThreadNetworkApi(syncBody = """[{"id":"m2","content":"new","seq":2}]"""))

        val result = api.messageSync("channel-1", sinceSeq = 1)

        assertEquals(1, assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(result).data.messageCount)
    }

    @Test
    fun syncRejectsNestedWrapper() = runBlocking {
        val api = SlockThreadApiClient(FakeThreadNetworkApi(syncBody = """{"data":[{"id":"m2","content":"new","seq":2}]}"""))

        val result = api.messageSync("channel-1", sinceSeq = 1)

        assertEquals("thread_sync_contract_mismatch", assertIs<ThreadApiResult.Failure>(result).contractCode)
    }

    @Test
    fun createThreadRequiresCanonicalThreadChannelId() = runBlocking {
        val api =
            SlockThreadApiClient(
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
            SlockThreadApiClient(
                FakeThreadNetworkApi(createThreadBody = """{"data":{"thread":{"threadChannelId":"thread-1"}}}""")
            )

        val result = api.createOrOpenThread("channel-1", "m1")

        assertEquals("thread_create_contract_mismatch", assertIs<ThreadApiResult.Failure>(result).contractCode)
    }

    @Test
    fun unsaveMessageCallsCanonicalTransport() = runBlocking {
        val transport = FakeThreadNetworkApi()
        val api = SlockThreadApiClient(transport)

        val result = api.unsaveMessage("msg-1")

        assertIs<ThreadApiResult.Success<Unit>>(result)
        assertEquals(listOf("msg-1"), transport.unsavedMessageIds)
    }

    @Test
    fun networkThreadApiUsesSlockNetworkClientRoutesAndBodies() = runBlocking {
        val networkClient = RecordingThreadNetworkClient()
        val api = SlockThreadApiClient(NetworkSlockThreadApi(networkClient))

        assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(api.channelMessages("thread-1", limit = 25, beforeSeq = 99))
        assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(api.messageSync("channel-1", sinceSeq = 10, limit = 30))
        assertIs<ThreadApiResult.Success<ThreadMessagesPage>>(
            api.sendMessage("channel-1", "hello", asTask = true, attachmentIds = listOf("att-1"))
        )
        assertIs<ThreadApiResult.Success<ThreadAttachmentOpenTarget>>(api.attachmentUrl("att-1", disposition = "inline"))
        assertIs<ThreadApiResult.Success<Unit>>(api.markChannelReadAll("channel-1"))
        assertIs<ThreadApiResult.Success<Unit>>(api.saveMessage("msg-1"))
        assertIs<ThreadApiResult.Success<Unit>>(api.unsaveMessage("msg-1"))
        assertIs<ThreadApiResult.Success<MessageMutationSummary>>(api.addReaction("msg-1", "rocket"))
        assertIs<ThreadApiResult.Success<MessageMutationSummary>>(api.removeReaction("msg-1", "rocket"))
        assertIs<ThreadApiResult.Success<Unit>>(api.updateTaskStatus("task-1", TaskStatus.InReview))
        assertIs<ThreadApiResult.Success<ThreadActionResult>>(api.createOrOpenThread("channel-1", "parent-1"))

        assertEquals(
            listOf(
                "GET messages/channel/thread-1 priority=Visible query=limit=25&before=99 server= body=",
                "GET messages/sync priority=Visible query=since_seq=10&limit=30&channel_id=channel-1 server= body=",
                """POST messages priority=Foreground query= server= body={"channelId":"channel-1","content":"hello","attachmentIds":["att-1"],"asTask":true}""",
                "GET attachments/att-1/url priority=Visible query=disposition=inline server= body=",
                "POST channels/channel-1/read-all priority=Foreground query= server= body=",
                """POST channels/saved priority=Foreground query= server= body={"messageId":"msg-1"}""",
                "DELETE channels/saved/msg-1 priority=Foreground query= server= body=",
                """POST messages/msg-1/reactions priority=Foreground query= server= body={"emoji":"rocket"}""",
                """DELETE messages/msg-1/reactions priority=Foreground query= server= body={"emoji":"rocket"}""",
                """PATCH tasks/task-1/status priority=Foreground query= server= body={"status":"in_review"}""",
                """POST channels/channel-1/threads priority=Foreground query= server= body={"parentMessageId":"parent-1"}"""
            ),
            networkClient.calls
        )
    }

    private class FakeThreadNetworkApi(
        var channelMessagesBody: String = """{"messages":[],"historyLimited":false}""",
        var syncBody: String = "[]",
        var sendBody: String = """{"id":"m1","content":"sent","seq":1}""",
        var attachmentUrlBody: String = """{"url":"https://cdn.slock.ai/default"}""",
        var reactionBody: String = """{"id":"m1","reactions":[]}""",
        var createThreadBody: String = """{"threadChannelId":"thread-1"}"""
    ) : SharedThreadNetworkApi {
        val unsavedMessageIds = mutableListOf<String>()
        override suspend fun channelMessages(threadId: String, limit: Int, beforeSeq: Long?): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = channelMessagesBody)

        override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = syncBody)

        override suspend fun sendMessage(
            channelId: String,
            body: String,
            asTask: Boolean,
            attachmentIds: List<String>
        ): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = sendBody)

        override suspend fun attachmentUrl(attachmentId: String, disposition: String?): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = attachmentUrlBody)

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

        override suspend fun updateTaskStatus(taskId: String, status: TaskStatus): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"task":{"id":"$taskId"}}""")

        override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = createThreadBody)
    }

    private class RecordingThreadNetworkClient : SlockNetworkClient {
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
                normalizedPath.startsWith("attachments/") -> ok(attachmentUrlBody)
                normalizedPath.endsWith("/read-all") -> ok("""{"ok":true}""")
                normalizedPath == "channels/saved" && method == "POST" -> ok("""{"ok":true}""")
                normalizedPath.startsWith("channels/saved/") && method == "DELETE" -> ok("""{"ok":true}""")
                normalizedPath.endsWith("/reactions") -> ok(reactionBody)
                normalizedPath.startsWith("tasks/") && normalizedPath.endsWith("/status") -> ok("""{"ok":true}""")
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
        }
    }
}
