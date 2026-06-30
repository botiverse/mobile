package build.raft.app.data

import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class FilesMessagesApiTest {
    @Test
    fun typedFilesAndMessagesApiAcceptsCanonicalUpstreamWrappers() = runBlocking {
        val transport = RecordingFilesMessagesApi()
        val client = SlockFilesMessagesApiClient(transport)

        val files = client.channelFiles(ChannelId("channel-1"))
        val url = client.attachmentUrl(AttachmentId("attachment-1"), disposition = "attachment")
        val preview = client.attachmentPreview(AttachmentId("attachment-1"))
        val html = client.attachmentHtmlPreviewUrl(AttachmentId("attachment-1"))
        val search = client.messageSearch("release")
        val context = client.messageContext(MessageId("message-1"), ChannelId("channel-1"))
        val addReaction = client.addReaction(MessageId("message-1"), "👀")
        val removeReaction = client.removeReaction(MessageId("message-1"), "👀")

        assertEquals(1, assertIs<DataResult.Content<FilesPage>>(files).data.itemCount)
        assertEquals("cursor-2", assertIs<DataResult.Content<FilesPage>>(files).data.nextCursor)
        assertEquals("https://cdn.slock.test/a.png", assertIs<DataResult.Content<AttachmentUrlTarget>>(url).data.url)
        assertEquals("markdown", assertIs<DataResult.Content<AttachmentPreviewSummary>>(preview).data.kind)
        assertEquals("https://api.slock.test/api/attachments/attachment-1/html-preview", assertIs<DataResult.Content<AttachmentUrlTarget>>(html).data.url)
        assertEquals(1, assertIs<DataResult.Content<MessageSearchPage>>(search).data.resultCount)
        assertEquals("channel-1", assertIs<DataResult.Content<MessageContextSummary>>(context).data.channelId)
        val addSummary = assertIs<DataResult.Content<MessageMutationSummary>>(addReaction).data
        assertEquals("message-1", addSummary.messageId)
        assertEquals("👀", addSummary.reactions.single().emoji)
        assertEquals(listOf("user-1"), addSummary.reactions.single().reactorIds)
        assertEquals("message-1", assertIs<DataResult.Content<MessageMutationSummary>>(removeReaction).data.messageId)
        assertEquals(
            listOf(
                "GET channels/channel-1/files limit=50 cursor=null",
                "GET attachments/attachment-1/url disposition=attachment",
                "GET attachments/attachment-1/preview",
                "GET attachments/attachment-1/html-preview-url",
                "GET messages/search q=release limit=20 offset=0 sort=null",
                "GET messages/context/message-1 channelId=channel-1",
                """POST messages/message-1/reactions body={"emoji":"👀"}""",
                """DELETE messages/message-1/reactions body={"emoji":"👀"}"""
            ),
            transport.calls
        )
    }

    @Test
    fun typedFilesAndMessagesApiRejectsGuessedWrappersAndAliases() = runBlocking {
        val client =
            SlockFilesMessagesApiClient(
                RecordingFilesMessagesApi(
                    channelFilesBody = """{"items":[{"id":"a1"}],"cursor":"next"}""",
                    attachmentUrlBody = """{"downloadUrl":"https://cdn.slock.test/a.png"}""",
                    previewBody = """{"preview":{"kind":"markdown"}}""",
                    htmlPreviewBody = """{"href":"https://cdn.slock.test/a.html"}""",
                    searchBody = """{"items":[{"id":"m1"}]}""",
                    contextBody = """{"data":{"messages":[]}}""",
                    reactionBody = """{"message":{"id":"message-1"}}"""
                )
            )

        assertEquals("channel_files_contract_mismatch", assertIs<DataResult.Error>(client.channelFiles(ChannelId("channel-1"))).error.code)
        assertEquals("attachment_url_contract_mismatch", assertIs<DataResult.Error>(client.attachmentUrl(AttachmentId("attachment-1"))).error.code)
        assertEquals("attachment_preview_contract_mismatch", assertIs<DataResult.Error>(client.attachmentPreview(AttachmentId("attachment-1"))).error.code)
        assertEquals("attachment_html_preview_url_contract_mismatch", assertIs<DataResult.Error>(client.attachmentHtmlPreviewUrl(AttachmentId("attachment-1"))).error.code)
        assertEquals("message_search_contract_mismatch", assertIs<DataResult.Error>(client.messageSearch("release")).error.code)
        assertEquals("message_context_contract_mismatch", assertIs<DataResult.Error>(client.messageContext(MessageId("message-1"))).error.code)
        assertEquals("message_reaction_contract_mismatch", assertIs<DataResult.Error>(client.addReaction(MessageId("message-1"), "👀")).error.code)
    }

    private class RecordingFilesMessagesApi(
        private val channelFilesBody: String =
            """{"files":[{"id":"attachment-1","filename":"a.png","messageId":"message-1","source":{"type":"channel"}}],"nextCursor":"cursor-2"}""",
        private val attachmentUrlBody: String =
            """{"url":"https://cdn.slock.test/a.png","expiresAt":"2026-05-22T10:00:00.000Z"}""",
        private val previewBody: String =
            """{"status":"ok","data":{"kind":"markdown","content":"# Hello"},"truncated":false}""",
        private val htmlPreviewBody: String =
            """{"url":"https://api.slock.test/api/attachments/attachment-1/html-preview","expiresAt":"2026-05-22T10:00:00.000Z"}""",
        private val searchBody: String =
            """{"results":[{"id":"message-1","content":"release","snippet":"release"}],"hasMore":false}""",
        private val contextBody: String =
            """{"channelId":"channel-1","targetMessageId":"message-1","messages":[{"id":"message-1"}],"historyLimited":false}""",
        private val reactionBody: String =
            """{"id":"message-1","content":"hello","reactions":[{"emoji":"👀","count":1,"reactorIds":["user-1"],"reactorNames":["Artin"]}]}"""
    ) : SlockFilesMessagesApi {
        val calls = mutableListOf<String>()

        override suspend fun channelFiles(channelId: ChannelId, limit: Int, cursor: String?): HomeNetworkResponse {
            calls += "GET channels/${channelId.value}/files limit=$limit cursor=$cursor"
            return ok(channelFilesBody)
        }

        override suspend fun attachmentUrl(attachmentId: AttachmentId, disposition: String?): HomeNetworkResponse {
            calls += "GET attachments/${attachmentId.value}/url disposition=$disposition"
            return ok(attachmentUrlBody)
        }

        override suspend fun attachmentPreview(attachmentId: AttachmentId): HomeNetworkResponse {
            calls += "GET attachments/${attachmentId.value}/preview"
            return ok(previewBody)
        }

        override suspend fun attachmentHtmlPreviewUrl(attachmentId: AttachmentId): HomeNetworkResponse {
            calls += "GET attachments/${attachmentId.value}/html-preview-url"
            return ok(htmlPreviewBody)
        }

        override suspend fun messageSearch(query: String, limit: Int, offset: Int, sort: String?): HomeNetworkResponse {
            calls += "GET messages/search q=$query limit=$limit offset=$offset sort=$sort"
            return ok(searchBody)
        }

        override suspend fun messageContext(messageId: MessageId, channelId: ChannelId?): HomeNetworkResponse {
            calls += "GET messages/context/${messageId.value} channelId=${channelId?.value}"
            return ok(contextBody)
        }

        override suspend fun addReaction(messageId: MessageId, emoji: String): HomeNetworkResponse {
            calls += "POST messages/${messageId.value}/reactions body=${buildSharedReactionBody(emoji)}"
            return ok(reactionBody)
        }

        override suspend fun removeReaction(messageId: MessageId, emoji: String): HomeNetworkResponse {
            calls += "DELETE messages/${messageId.value}/reactions body=${buildSharedReactionBody(emoji)}"
            return ok(reactionBody)
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }
}
