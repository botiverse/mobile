package build.raft.app.data

import build.raft.app.FeedbackDiagnosticsReference
import build.raft.app.model.ServerId
import build.raft.app.network.MultipartFile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings › Feedback "New Feedback" filed through the Raft server: the `POST /api/product-feedback`
 * form the web sends (createHandsFeedbackTransport.ts `createTicket` + productFeedbackForm.ts; the
 * diagnostics reference of web standalone/diagnostics.ts), and the server's answers.
 */
class SettingsFeedbackSubmitApiTest {
    private data class MultipartCall(
        val path: String,
        val headers: Map<String, String>,
        val fields: Map<String, String>,
        val files: List<MultipartFile>,
    )

    private class FakeNetwork(private val answer: () -> HomeNetworkResponse) : RaftNetworkClient {
        val calls = mutableListOf<MultipartCall>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse = error("feedback is filed as a multipart form: $path")

        override suspend fun requestMultipartWithHeaders(
            priority: DataRequestPriority,
            path: String,
            headers: Map<String, String>,
            fields: Map<String, String>,
            files: List<MultipartFile>,
        ): HomeNetworkResponse {
            calls += MultipartCall(path, headers, fields, files)
            return answer()
        }
    }

    private val diagnostics =
        FeedbackDiagnosticsReference(
            r2Key = "feedback/raft-android/presigned/11111111-2222-4333-8444-555555555555-slock-feedback.zip",
            filename = "slock-feedback.zip",
            contentType = "application/zip",
            size = 4096L,
        )

    private val screenshot =
        PlatformFile(
            id = "a",
            fileName = "shot.png",
            mimeType = "image/png",
            sizeBytes = 1234L,
            fileRef = "draft://shot",
            source = "settings_feedback_draft",
        )

    private val receipt = """{"id":"11111111-2222-4333-8444-555555555555","status":"received","reference":"FB-1","attachments":1}"""

    private fun repository(network: FakeNetwork) =
        NetworkSettingsFeedbackSubmitRepository(network, clientKind = "android", clientVersion = "1.23.0")

    private fun request(attachments: List<PlatformFile> = listOf(screenshot), diagnostics: FeedbackDiagnosticsReference? = this.diagnostics) =
        SettingsFeedbackSubmitRequest(
            message = "Crash on launch",
            attachments = attachments,
            submissionId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
            locale = "zh-CN",
            diagnostics = diagnostics,
        )

    @Test
    fun filesTheWebFormWithTheClientKindAndAnswersTheTicketId() = runTest {
        val network = FakeNetwork { HomeNetworkResponse(code = 201, bodyText = receipt) }

        val result = repository(network).submit(request())

        assertEquals(FeedbackResult.Ok("11111111-2222-4333-8444-555555555555"), result)
        val call = network.calls.single()
        assertEquals("product-feedback", call.path)
        assertEquals(mapOf("X-Raft-Client-Kind" to "android"), call.headers)
        assertEquals(listOf("type", "message", "metadata", "submission_id", "may_contact", "diagnostics"), call.fields.keys.toList())
        assertEquals("problem", call.fields["type"])
        assertEquals("Crash on launch", call.fields["message"])
        assertEquals("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", call.fields["submission_id"])
        assertEquals("false", call.fields["may_contact"])
        val metadata = Json.parseToJsonElement(call.fields.getValue("metadata")).jsonObject
        assertEquals("android", metadata["client_kind"]?.jsonPrimitive?.content)
        assertEquals("android", metadata["platform"]?.jsonPrimitive?.content)
        assertEquals("1.23.0", metadata["client_version"]?.jsonPrimitive?.content)
        assertEquals("zh-CN", metadata["locale"]?.jsonPrimitive?.content)
        val ref = Json.parseToJsonElement(call.fields.getValue("diagnostics")).jsonObject
        // Exactly the four reference fields (server parseProductFeedbackDiagnostics).
        assertEquals(setOf("r2_key", "filename", "content_type", "size"), ref.keys)
        assertEquals(diagnostics.r2Key, ref["r2_key"]?.jsonPrimitive?.content)
        assertEquals("application/zip", ref["content_type"]?.jsonPrimitive?.content)
        assertEquals(4096L, ref["size"]?.jsonPrimitive?.long)
        val file = call.files.single()
        assertEquals("attachments", file.name)
        assertEquals("shot.png", file.fileName)
        assertEquals("image/png", file.mimeType)
        assertEquals("draft://shot", file.fileRef)
        assertTrue(file.bytes.isEmpty())
    }

    @Test
    fun sendsTheFormsIdeaChoiceAsTheIdeaType() = runTest {
        val network = FakeNetwork { HomeNetworkResponse(code = 201, bodyText = receipt) }

        repository(network).submit(request().copy(type = SettingsFeedbackType.Idea))

        assertEquals("idea", network.calls.single().fields["type"])
    }

    @Test
    fun omitsTheDiagnosticsFieldWithoutAnArchive() = runTest {
        val network = FakeNetwork { HomeNetworkResponse(code = 201, bodyText = receipt) }

        repository(network).submit(request(attachments = emptyList(), diagnostics = null))

        val call = network.calls.single()
        assertFalse("diagnostics" in call.fields)
        assertTrue(call.files.isEmpty())
    }

    @Test
    fun sendsAnImageWithItsAcceptedTypeFromTheExtension() = runTest {
        val network = FakeNetwork { HomeNetworkResponse(code = 201, bodyText = receipt) }
        val jpeg = screenshot.copy(fileName = "photo.JPG", mimeType = "application/octet-stream")

        repository(network).submit(request(attachments = listOf(jpeg)))

        assertEquals("image/jpeg", network.calls.single().files.single().mimeType)
    }

    @Test
    fun refusesANonImageAttachmentWithoutARequest() = runTest {
        val network = FakeNetwork { error("no request for a refused attachment") }
        val log = screenshot.copy(fileName = "app.log", mimeType = "text/plain")

        val result = repository(network).submit(request(attachments = listOf(screenshot, log)))

        val failure = assertIs<FeedbackResult.Failed>(result).failure
        assertEquals(FeedbackErrorKind.Invalid, failure.kind)
        assertEquals("feedback_attachment_type_invalid", failure.code)
        assertTrue(network.calls.isEmpty())
    }

    @Test
    fun readsTheRateLimitAndItsRetryAfter() = runTest {
        val network =
            FakeNetwork {
                HomeNetworkResponse(
                    code = 429,
                    bodyText = null,
                    errorBodyText = """{"error":"Too many","code":"feedback_rate_limited"}""",
                    responseHeaders = mapOf("Retry-After" to listOf("30")),
                )
            }

        val failure = assertIs<FeedbackResult.Failed>(repository(network).submit(request())).failure

        assertEquals(FeedbackErrorKind.RateLimited, failure.kind)
        assertEquals(30L, failure.retryAfterSeconds)
    }

    @Test
    fun aReceiptWithoutAnIdIsAContractMismatch() = runTest {
        val network = FakeNetwork { HomeNetworkResponse(code = 201, bodyText = """{"status":"received"}""") }

        val failure = assertIs<FeedbackResult.Failed>(repository(network).submit(request())).failure

        assertEquals("feedback_contract_mismatch", failure.code)
    }

    @Test
    fun theMetadataJsonIsSharedWithTheAgentIssueTicket() {
        val metadata = Json.parseToJsonElement(productFeedbackMetadataJson("", "1.23.0", "en")).jsonObject

        // A blank client kind files as web: no native client facts.
        assertEquals(setOf("locale"), metadata.keys)
        assertNull(metadata["client_version"])
    }
}
