package build.raft.app.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class HttpErrorEnvelopeTest {
    private val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"

    private fun response(
        code: Int,
        bodyText: String? = null,
        errorBodyText: String? = null,
    ): HomeNetworkResponse =
        HomeNetworkResponse(
            code = code,
            bodyText = bodyText,
            errorBodyText = errorBodyText,
            responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)),
        )

    @Test
    fun parsesErrorAndCodeWithFallbackAliasesAndPreservesEdgeBlocked() {
        val primary = parseHttpErrorEnvelope("""{"error":" Channel is full ","code":" channel_full "}""")
        assertEquals(HttpErrorEnvelope(serverMessage = "Channel is full", serverCode = "channel_full"), primary)

        val primaryWins = parseHttpErrorEnvelope("""{"error":"Primary","message":"Fallback","code":"primary_code","errorCode":"fallback_code"}""")
        assertEquals(HttpErrorEnvelope(serverMessage = "Primary", serverCode = "primary_code"), primaryWins)

        val blankPrimaryFallsBack =
            parseHttpErrorEnvelope("""{"error":"  ","message":"From message","code":"","errorCode":"from_error_code"}""")
        assertEquals(HttpErrorEnvelope(serverMessage = "From message", serverCode = "from_error_code"), blankPrimaryFallsBack)

        val nonStringPrimaryFallsBack =
            parseHttpErrorEnvelope("""{"error":123,"message":"From message","code":400,"errorCode":"from_error_code"}""")
        assertEquals(HttpErrorEnvelope(serverMessage = "From message", serverCode = "from_error_code"), nonStringPrimaryFallsBack)

        val aliases = parseHttpErrorEnvelope("""{"message":"Not allowed","errorCode":"forbidden_action"}""")
        assertEquals(HttpErrorEnvelope(serverMessage = "Not allowed", serverCode = "forbidden_action"), aliases)

        val edge = parseHttpErrorEnvelope("""{"error":"edge_blocked"}""")
        assertEquals(HttpErrorEnvelope(serverMessage = "edge_blocked", serverCode = null), edge)

        val untrimmed = parseHttpErrorEnvelope("""{"error":"  task-403 raw reason  ","code":"forbidden"}""", trimMessage = false)
        assertEquals(HttpErrorEnvelope(serverMessage = "  task-403 raw reason  ", serverCode = "forbidden"), untrimmed)
    }

    @Test
    fun toleratesNonStringSiblingsBodyFallbacksIgnores2xxAndIgnoresCodeZeroTimeouts() {
        assertEquals(
            HttpErrorEnvelope(serverMessage = "Invalid input", serverCode = null),
            parseHttpErrorEnvelope("""{"code":400,"error":"Invalid input"}"""),
        )
        assertEquals(
            HttpErrorEnvelope(serverMessage = null, serverCode = "rate_limited"),
            parseHttpErrorEnvelope("""{"error":{"nested":true},"code":"rate_limited"}"""),
        )
        assertNull(parseHttpErrorEnvelope("""{"error":"   ","code":""}"""))
        assertNull(parseHttpErrorEnvelope("<html>502 Bad Gateway</html>"))

        // Blank bodyText must fall back to errorBodyText instead of masking it.
        val fromErrorBody =
            response(code = 403, bodyText = "   ", errorBodyText = """{"error":"Quota reached","code":"free_external_limit"}""")
                .parseHttpErrorEnvelope()
        assertEquals(HttpErrorEnvelope(serverMessage = "Quota reached", serverCode = "free_external_limit"), fromErrorBody)

        // Blank errorBodyText must fall back to bodyText as well.
        val fromBody =
            response(code = 409, bodyText = """{"error":"Already archived","code":"channel_archived"}""", errorBodyText = "   ")
                .parseHttpErrorEnvelope()
        assertEquals(HttpErrorEnvelope(serverMessage = "Already archived", serverCode = "channel_archived"), fromBody)

        // Successful 2xx responses never decode as error envelopes.
        assertNull(response(code = 200, bodyText = """{"error":"ignored","code":"ignored"}""").parseHttpErrorEnvelope())

        // Synthetic code == 0 client-side timeout/transport responses must never leak as server refusals.
        assertNull(
            response(code = 0, errorBodyText = """{"error":"network_timeout","message":"Request timed out"}""")
                .parseHttpErrorEnvelope()
        )
    }

    @Test
    fun toAuthSessionRequiredResultThreadsEnvelopeAndTraceId() {
        val result =
            response(
                code = 401,
                errorBodyText = """{"error":"Session revoked","code":"unauthenticated"}""",
            ).toAuthSessionRequiredResult()
        assertEquals("auth_required", result.blocker.code)
        assertEquals("auth_session", result.blocker.requiredCapability)
        assertEquals("Session revoked", result.blocker.serverMessage)
        assertEquals("unauthenticated", result.blocker.serverCode)
        assertEquals(traceId, result.blocker.traceId)
    }

    @Test
    fun tasksDataSourcePreservesServerCodeTraceIdAndClassifiesQuota403() {
        val quota =
            response(
                code = 403,
                errorBodyText = """{"error":"Channel is read-only","code":"channel_read_only"}""",
            ).toTaskDeleteResult()
        val quotaError = assertIs<DataResult.Error>(quota).error
        assertEquals("channel_read_only", quotaError.code)
        assertEquals("channel_read_only", quotaError.serverCode)
        assertEquals("Channel is read-only", quotaError.serverMessage)
        assertEquals(false, quotaError.retryable)
        assertEquals(traceId, quotaError.traceId)

        val forbidden =
            response(
                code = 403,
                errorBodyText = """{"error":"  task-403 raw reason  ","code":"task_forbidden"}""",
            ).toTaskConvertResult()
        val blocker = assertIs<DataResult.NoPermission>(forbidden).blocker
        assertEquals("tasks_403", blocker.code)
        assertEquals("  task-403 raw reason  ", blocker.serverMessage)
        assertEquals("task_forbidden", blocker.serverCode)
        assertEquals(traceId, blocker.traceId)
    }

    @Test
    fun searchAndArtifactsApisPreserveServerMessageAlongsideServerCode() = runTest {
        val webSearch =
            response(
                code = 429,
                errorBodyText = """{"error":"Slow down","code":"RATE_LIMITED"}""",
            ).toWebSearchArtifactsLoad()
        val webSearchError = assertIs<WebSearchArtifactsLoad.Failed>(webSearch).error
        assertEquals("web_search_http_429", webSearchError.code)
        assertEquals("Slow down", webSearchError.serverMessage)
        assertEquals("RATE_LIMITED", webSearchError.serverCode)
        assertEquals(traceId, webSearchError.traceId)

        val coreGroup =
            response(
                code = 403,
                errorBodyText = """{"error":"Search disabled","code":"SEARCH_FORBIDDEN"}""",
            ).toWebSearchCoreGroupLoad<String>(SearchCoreGroup.Channels) { _, _ -> null }
        val coreError = assertIs<WebSearchCoreGroupLoad.Failed>(coreGroup).error
        assertEquals("web_search_http_403", coreError.code)
        assertEquals("Search disabled", coreError.serverMessage)
        assertEquals("SEARCH_FORBIDDEN", coreError.serverCode)
        assertEquals(traceId, coreError.traceId)

        val fakeArtifactsApi =
            object : RaftArtifactsDashboardApi {
                override suspend fun all(
                    serverId: String,
                    query: ArtifactsHomeQuery,
                    cursor: String?,
                    priority: DataRequestPriority,
                ) = response(code = 403, errorBodyText = """{"error":"Artifacts access restricted","code":"FORBIDDEN"}""")

                override suspend fun authors(serverId: String, q: String) = response(code = 200, bodyText = "{}")

                override suspend fun conversations(serverId: String, q: String) = response(code = 200, bodyText = "{}")

                override suspend fun setSettled(serverId: String, artifactId: String, settled: Boolean) = response(code = 200, bodyText = "{}")
            }
        val artifactsLoad = fakeArtifactsApi.loadArtifactsHomePage("srv-1", ArtifactsHomeQuery(), cursor = null, DataRequestPriority.Visible)
        val artifactsError = assertIs<ArtifactsHomeLoad.Failed>(artifactsLoad).error
        assertEquals("artifacts_home_http_403", artifactsError.code)
        assertEquals("Artifacts access restricted", artifactsError.serverMessage)
        assertEquals("FORBIDDEN", artifactsError.serverCode)
        assertEquals(traceId, artifactsError.traceId)
    }
}
