package ai.slock.compose.data

import ai.slock.compose.model.AttachmentId
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ThreadId
import ai.slock.compose.network.MultipartFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConversationRepositoryDataSourceTest {
    @Test
    fun getThreadLoadsCanonicalMessagesAndMergesThreadSummaries() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """{"messages":[${messageJson("parent-1", "channel-1", threadId = "thread-1")}],"historyLimited":false}""",
                        "GET channels/channel-1/threads" to
                            """{"threads":[{"threadChannelId":"thread-1","parentMessageId":"parent-1","parentChannelId":"channel-1","parentChannelName":"android","parentMessagePreview":"Parent","latestActivityPreview":"Reply","replyCount":3,"unreadCount":2}]}""",
                        "GET channels/threads/followed" to """{"threads":[]}"""
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val result = repository.getThread(ThreadId("channel-1"))

        val snapshot = assertIs<DataResult.Content<ThreadSnapshot>>(result).data
        val message = snapshot.messages.single()
        assertEquals("parent-1", message.id.value)
        assertEquals("thread-1", message.threadChannelId?.value)
        assertEquals(3, message.replyCount)
        assertEquals(2, message.unreadReplyCount)
        assertEquals(
            listOf(
                RecordedConversationRequest(DataRequestPriority.Foreground, "GET", "messages/channel/channel-1", listOf("limit" to "50")),
                RecordedConversationRequest(DataRequestPriority.Visible, "GET", "channels/channel-1/threads", listOf("limit" to "50")),
                RecordedConversationRequest(DataRequestPriority.Visible, "GET", "channels/threads/followed", listOf("limit" to "50"))
            ),
            network.requests
        )
    }

    @Test
    fun sendMessageAndThreadActionsUseSharedDtoBodies() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "POST messages" to messageJson("sent-1", "channel-1", content = "ship it"),
                        "POST channels/channel-1/threads" to """{"threadChannelId":"thread-1","replyCount":0,"ok":true}""",
                        "POST channels/threads/follow" to """{"threadChannelId":"thread-1","replyCount":4,"ok":true}"""
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val sent =
            repository.sendMessage(
                channelId = ChannelId("channel-1"),
                body = "ship it",
                attachmentIds = listOf(AttachmentId("attachment-1")),
                asTask = true
            )
        val created = repository.createOrOpenThread(ChannelId("channel-1"), MessageId("parent-1"))
        val followed = repository.followThread(ChannelId("thread-1"))

        assertEquals("sent-1", assertIs<DataResult.Content<SendMessageResult>>(sent).data.message.id.value)
        assertEquals("thread-1", assertIs<DataResult.Content<ThreadActionResult>>(created).data.threadChannelId.value)
        assertEquals(4, assertIs<DataResult.Content<ThreadActionResult>>(followed).data.replyCount)
        assertEquals(
            listOf(
                """{"channelId":"channel-1","content":"ship it","attachmentIds":["attachment-1"],"asTask":true}""",
                """{"parentMessageId":"parent-1"}""",
                """{"threadChannelId":"thread-1","channelId":"thread-1"}"""
            ),
            network.bodies
        )
    }

    @Test
    fun pagingContextFilesAndUploadConsumeCanonicalDtos() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """{"messages":[${messageJson("older-1", "channel-1", seq = 7)}],"historyLimited":false}""",
                        "GET channels/channel-1/threads" to """{"threads":[]}""",
                        "GET messages/context/message-1" to
                            """{"channelId":"channel-1","targetMessageId":"message-1","hasOlder":true,"hasNewer":false,"messages":[${messageJson("message-1", "channel-1")}]}""",
                        "GET channels/channel-1/files" to
                            """{"files":[{"id":"file-1","filename":"a.png","mimeType":"image/png","sizeBytes":128,"messageId":"message-1","source":{"type":"channel"}}],"nextCursor":"cursor-2"}""",
                        "GET attachments/attachment-1/url" to """{"url":"https://cdn.slock.test/a.png","expiresAt":"2026-05-22T10:00:00.000Z"}"""
                    ),
                uploadBody =
                    """{"attachments":[{"id":"uploaded-1","filename":"upload.txt","mimeType":"text/plain","sizeBytes":5}]}"""
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val older = repository.loadOlderMessages(ThreadId("channel-1"), beforeSeq = 8)
        val context = repository.getMessageContext(MessageId("message-1"))
        val files = repository.getChannelFiles(ChannelId("channel-1"), limit = 25, cursor = "cursor-1")
        val target = repository.getAttachmentOpenTarget(AttachmentId("attachment-1"))
        val uploaded =
            repository.uploadAttachments(
                AttachmentUploadRequest(
                    channelId = ChannelId("channel-1"),
                    files =
                        listOf(
                            AttachmentUploadFile(
                                id = "local-upload-1",
                                fileName = "upload.txt",
                                mimeType = "text/plain",
                                bytes = "hello".encodeToByteArray()
                            )
                        )
                )
            )

        assertEquals("older-1", assertIs<DataResult.Content<ThreadSnapshot>>(older).data.messages.single().id.value)
        assertEquals("message-1", assertIs<DataResult.Content<ThreadSnapshot>>(context).data.highlightedMessageId?.value)
        assertEquals("cursor-2", assertIs<DataResult.Content<ChannelFilesSnapshot>>(files).data.nextCursor)
        assertEquals("https://cdn.slock.test/a.png", assertIs<DataResult.Content<AttachmentOpenTarget>>(target).data.url)
        assertEquals("uploaded-1", assertIs<DataResult.Content<AttachmentUploadResult>>(uploaded).data.attachments.single().id.value)
        assertEquals(listOf("upload.txt"), network.uploadedFileNames)
    }

    private fun sampleStore(): SlockAuthSessionStore =
        InMemorySlockAuthSessionStore(
            StoredAuthSession(
                user =
                    StoredAuthUser(
                        id = "user-1",
                        email = "artin@slock.ai",
                        displayName = "Artin"
                    ),
                accessToken = "token-a",
                refreshToken = "token-r",
                selectedServerId = "server-1",
                servers =
                    listOf(
                        StoredAuthServer(
                            id = "server-1",
                            name = "Slock Android",
                            slug = "android"
                        )
                    )
            )
        )

    private class FakeConversationNetworkClient(
        private val responses: Map<String, String> = emptyMap(),
        private val uploadBody: String = """{"attachments":[]}"""
    ) : SlockNetworkClient {
        val requests = mutableListOf<RecordedConversationRequest>()
        val bodies = mutableListOf<String>()
        val uploadedFileNames = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            val normalized = path.trim('/')
            requests += RecordedConversationRequest(priority, method, normalized, query)
            bodyText?.let { bodies += it }
            return HomeNetworkResponse(code = 200, bodyText = responses["$method $normalized"] ?: """{"threads":[]}""")
        }

        override suspend fun uploadAttachments(
            priority: DataRequestPriority,
            channelId: ChannelId,
            files: List<MultipartFile>
        ): HomeNetworkResponse {
            requests += RecordedConversationRequest(priority, "POST", "messages/channels/${channelId.value}/attachments", emptyList())
            uploadedFileNames += files.map { it.fileName }
            return HomeNetworkResponse(code = 200, bodyText = uploadBody)
        }
    }

    private data class RecordedConversationRequest(
        val priority: DataRequestPriority,
        val method: String,
        val path: String,
        val query: List<Pair<String, String>> = emptyList()
    )

    private companion object {
        fun messageJson(
            id: String,
            channelId: String,
            seq: Long = 1,
            threadId: String? = null,
            content: String = "hello"
        ): String =
            buildString {
                append("""{"id":"$id","seq":$seq,"channelId":"$channelId","senderType":"user","senderId":"user-1","senderName":"Artin","content":"$content","createdAt":"2026-05-22T10:00:00.000Z"""")
                if (threadId != null) append(""","threadId":"$threadId"""")
                append("}")
            }
    }
}
