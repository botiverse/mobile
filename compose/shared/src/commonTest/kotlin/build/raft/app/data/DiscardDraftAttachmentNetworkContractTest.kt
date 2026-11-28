@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.AttachmentId
import build.raft.app.model.ServerId
import build.raft.app.network.HEADER_SERVER_ID
import build.raft.app.network.MultipartFile
import build.raft.app.network.RAFT_ATTACHMENT_DRAFT_DISCARD_TIMEOUT_MILLIS
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftHttpExecutorResponse
import build.raft.app.network.RaftRequestPriority
import build.raft.app.network.RaftRequestScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Network & repository contract for `DELETE /api/attachments/:id/draft` (botiverse/slock #17244):
 * - Uses a 15 s whole-call timeout (`RAFT_ATTACHMENT_DRAFT_DISCARD_TIMEOUT_MILLIS`) and sends the
 *   originating `X-Server-Id`.
 * - Treats `204`, `404` (`attachment_not_found`), and `409` (`attachment_already_consumed`) as
 *   already-gone success.
 * - Keeps unmatched 404/409, 5xx, and transport errors as failures so the composer retains the
 *   reservation ID and lets Remove retry.
 */
class DiscardDraftAttachmentNetworkContractTest {
    @Test
    fun discardDraftAttachmentSendsDeleteWithFifteenSecondTimeoutAndServerIdHeader() = runTest {
        val executor =
            RecordingExecutor { _, _ ->
                RaftHttpExecutorResponse(code = 204, bodyText = null, errorBodyText = null)
            }
        val repository = repository(executor)

        val result = repository.discardDraftAttachment(ServerId(ServerTwoId), AttachmentId(AttachmentIdValue))

        assertIs<DataResult.Content<Unit>>(result)
        val call = executor.calls.single()
        assertEquals("DELETE", call.method)
        assertEquals("${ApiBaseUrl.trimEnd('/')}/attachments/$AttachmentIdValue/draft", call.url)
        assertEquals(RAFT_ATTACHMENT_DRAFT_DISCARD_TIMEOUT_MILLIS, call.totalTimeoutMillis)
        assertEquals(ServerTwoId, call.headers[HEADER_SERVER_ID])
    }

    @Test
    fun alreadyGoneAndAlreadyConsumedResponsesSucceed() = runTest {
        val cases =
            listOf(
                404 to """{"code":"attachment_not_found","error":"Draft attachment not found."}""",
                409 to """{"code":"attachment_already_consumed","error":"This attachment has already been sent."}""",
            )
        cases.forEach { (statusCode, body) ->
            val executor =
                RecordingExecutor { _, _ ->
                    RaftHttpExecutorResponse(code = statusCode, bodyText = null, errorBodyText = body)
                }
            val result =
                repository(executor).discardDraftAttachment(ServerId(ServerOneId), AttachmentId(AttachmentIdValue))
            assertIs<DataResult.Content<Unit>>(result, "status=$statusCode body=$body")
            assertEquals(1, executor.calls.size, "status=$statusCode")
        }
    }

    @Test
    fun unmatchedNotFoundConflictServerAndTransportErrorsRemainErrors() = runTest {
        val cases =
            listOf(
                RaftHttpExecutorResponse(code = 404, bodyText = null, errorBodyText = "Not Found"),
                RaftHttpExecutorResponse(
                    code = 409,
                    bodyText = null,
                    errorBodyText = """{"code":"channel_conversion_in_progress","error":"Channel is converting."}""",
                ),
                RaftHttpExecutorResponse(code = 503, bodyText = null, errorBodyText = """{"error":"unavailable"}"""),
                RaftHttpExecutorResponse(code = 0, bodyText = null, errorBodyText = "offline"),
            )
        cases.forEach { response ->
            val executor = RecordingExecutor { _, _ -> response }
            val result =
                repository(executor).discardDraftAttachment(ServerId(ServerOneId), AttachmentId(AttachmentIdValue))
            assertIs<DataResult.Error>(result, "response=$response")
        }
    }

    @Test
    fun unsafeAttachmentIdSegmentsFailBeforeExecutor() = runTest {
        val executor = RecordingExecutor { _, _ -> error("executor must not be called") }
        val repository = repository(executor)

        listOf("", "   ", ".", "..", "../other", "a/b", "a?b=1", "a#frag").forEach { badId ->
            val result = repository.discardDraftAttachment(ServerId(ServerOneId), AttachmentId(badId))
            val error = assertIs<DataResult.Error>(result, "badId=$badId").error
            assertEquals("invalid_input", error.code, "badId=$badId")
        }
        assertTrue(executor.calls.isEmpty())
    }

    private fun repository(executor: RecordingExecutor): RealConversationRepository =
        RealConversationRepository(
            sessionStore = sessionStore(),
            networkClient =
                SharedRaftNetworkClient(
                    sessionStore = sessionStore(),
                    baseUrl = ApiBaseUrl,
                    executor = executor,
                    requestScheduler = RaftRequestScheduler(),
                ),
            routeEnvironmentIdProvider = { ApiBaseUrl },
        )

    private fun sessionStore(): InMemoryRaftAuthSessionStore =
        InMemoryRaftAuthSessionStore(
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
                accessToken = "access-token",
                refreshToken = "refresh-token",
                selectedServerId = ServerOneId,
                servers =
                    listOf(
                        StoredAuthServer(id = ServerOneId, name = "Raft One"),
                        StoredAuthServer(id = ServerTwoId, name = "Raft Two"),
                    ),
            )
        )

    private class RecordingExecutor(
        private val respond: suspend (method: String, url: String) -> RaftHttpExecutorResponse,
    ) : RaftHttpExecutor {
        data class Call(
            val method: String,
            val url: String,
            val headers: Map<String, String>,
            val totalTimeoutMillis: Long?,
        )

        val calls = mutableListOf<Call>()

        override suspend fun execute(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean,
        ): RaftHttpExecutorResponse {
            calls += Call(method = method, url = url, headers = headers, totalTimeoutMillis = null)
            return respond(method, url)
        }

        override suspend fun executeWithTotalTimeout(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean,
            totalTimeoutMillis: Long,
        ): RaftHttpExecutorResponse {
            calls += Call(method = method, url = url, headers = headers, totalTimeoutMillis = totalTimeoutMillis)
            return respond(method, url)
        }

        override suspend fun executeMultipart(
            priority: RaftRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>,
            onUploadProgress: ((build.raft.app.network.RaftWireTransferProgress) -> Unit)?,
        ): RaftHttpExecutorResponse = error("unused")
    }

    private companion object {
        const val ApiBaseUrl = "https://api.raft.test/api"
        const val ServerOneId = "server-1"
        const val ServerTwoId = "server-2"
        const val AttachmentIdValue = "00000000-0000-4000-8000-000000000101"
    }
}
