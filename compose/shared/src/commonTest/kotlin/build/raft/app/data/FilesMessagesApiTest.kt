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
        val previewData = assertIs<DataResult.Content<AttachmentPreview>>(preview).data
        assertEquals("# Hi", assertIs<AttachmentPreviewContent.Markdown>(previewData.content).markdown)
        assertEquals(true, previewData.truncated)
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
            """{"status":"ok","data":{"kind":"markdown","markdown":"# Hi"},"truncated":true}""",
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
    @Test
    fun parseSharedAttachmentPreviewCoversUnionKindsAndUnknowns() {
        val text = parseSharedAttachmentPreview("""{"status":"ok","data":{"kind":"text","text":"hello"},"truncated":false}""")
        assertEquals("hello", assertIs<AttachmentPreviewContent.Text>(text?.content).text)
        assertEquals(false, text?.truncated)

        val csv = parseSharedAttachmentPreview(
            """{"status":"ok","data":{"kind":"csv","delimiter":",","headers":["a"],"rows":[["1"],["2"]],"rowCount":9,"columnCount":1},"truncated":true}"""
        )
        val csvContent = assertIs<AttachmentPreviewContent.Csv>(csv?.content)
        assertEquals(9, csvContent.rowCount)
        assertEquals(true, csv?.truncated)

        val diff = parseSharedAttachmentPreview(
            """{"status":"ok","data":{"kind":"diff","stats":{"files":2,"hunks":3,"additions":10,"deletions":4}}}"""
        )
        assertEquals(2, assertIs<AttachmentPreviewContent.Diff>(diff?.content).files)
        val xlsx = parseSharedAttachmentPreview(
            """{"status":"ok","data":{"kind":"xlsx","sheets":[{"name":"Sheet1","headers":["A","B"],"rows":[["1","2"],["3","4"]],"rowCount":42,"columnCount":2,"truncated":false}],"sheetCount":3,"truncated":true},"truncated":false}"""
        )
        val xlsxContent = assertIs<AttachmentPreviewContent.Xlsx>(xlsx?.content)
        assertEquals(3, xlsxContent.sheetCount)
        assertEquals(true, xlsxContent.truncated)
        assertEquals(1, xlsxContent.sheets.size)
        val sheet = xlsxContent.sheets.first()
        assertEquals("Sheet1", sheet.name)
        assertEquals(listOf("A", "B"), sheet.headers)
        assertEquals(2, sheet.rows.size)
        assertEquals(42, sheet.rowCount)
        assertEquals(2, sheet.columnCount)
        assertEquals(false, sheet.truncated)

        // A sheet-less xlsx payload must fall back to the legacy path (content null), never render a blank grid.
        val sheetless = parseSharedAttachmentPreview("""{"status":"ok","data":{"kind":"xlsx","sheets":[],"sheetCount":0,"truncated":false}}""")
        assertEquals("ok", sheetless?.status)
        assertEquals(null, sheetless?.content)

        val pdf = parseSharedAttachmentPreview("""{"status":"ok","data":{"kind":"pdf"}}""")
        assertIs<AttachmentPreviewContent.Pdf>(pdf?.content)

        val unsupported = parseSharedAttachmentPreview("""{"status":"unsupported"}""")
        assertEquals("unsupported", unsupported?.status)
        assertEquals(null, unsupported?.content)
        assertEquals(false, unsupported?.isOk)

        val unknownKind = parseSharedAttachmentPreview("""{"status":"ok","data":{"kind":"hologram"}}""")
        assertEquals(null, unknownKind?.content)

        assertEquals(null, parseSharedAttachmentPreview("not json"))
    }

}
