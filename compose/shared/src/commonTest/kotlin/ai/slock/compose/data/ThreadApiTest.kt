package ai.slock.compose.data

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

    private class FakeThreadNetworkApi(
        var channelMessagesBody: String = """{"messages":[],"historyLimited":false}""",
        var syncBody: String = "[]",
        var sendBody: String = """{"id":"m1","content":"sent","seq":1}""",
        var attachmentUrlBody: String = """{"url":"https://cdn.slock.ai/default"}""",
        var createThreadBody: String = """{"threadChannelId":"thread-1"}"""
    ) : SharedThreadNetworkApi {
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

        override suspend fun attachmentUrl(attachmentId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = attachmentUrlBody)

        override suspend fun markChannelReadAll(channelId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")

        override suspend fun saveMessage(messageId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")

        override suspend fun updateTaskStatus(taskId: String, status: TaskStatus): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"task":{"id":"$taskId"}}""")

        override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = createThreadBody)
    }
}
