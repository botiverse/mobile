package build.raft.app.data

import build.raft.app.network.MultipartFile
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftHttpExecutorResponse
import build.raft.app.network.RaftRequestPriority
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A 403 `SSO_REQUIRED` (botiverse/slock `requireAuth` / `respondSsoRequired`,
 * packages/server/src/middleware/auth.ts on #12904) on any request asks the person to sign in again,
 * once; a socket handshake failing with `SSO_REQUIRED` is never a reason to refresh the token.
 */
class SsoRequiredSignalTest {
    // The body respondSsoRequired sends (middleware/auth.ts).
    private val requiredBody =
        """{"error":"Your organization requires you to sign in with its single sign-on.","code":"SSO_REQUIRED","signInUrl":"https://api.raft.build/api/auth/sso/servers/6f1c2c1e-6d55-4c1b-9a59-2a4a3c4d5e6f/start"}"""

    @Test
    fun requiredBodyParsesWithItsSignInLink() {
        val parsed = parseSsoRequiredError(403, requiredBody)
        assertEquals("SSO_REQUIRED", parsed?.code)
        assertTrue(parsed?.signInUrl.orEmpty().endsWith("/start"))
    }

    @Test
    fun otherResponsesAreNotSsoRequired() {
        assertNull(parseSsoRequiredError(401, requiredBody))
        assertNull(parseSsoRequiredError(403, """{"error":"x","code":"account_deletion_pending"}"""))
        assertNull(parseSsoRequiredError(403, """{"error":"SSO_REQUIRED"}"""))
        assertNull(parseSsoRequiredError(403, "SSO_REQUIRED"))
        assertNull(parseSsoRequiredError(403, null))
    }

    @Test
    fun manyRequiredResponsesQueueOneSignInRequest() = runTest {
        val signal = RaftSsoRequiredSignal()
        assertTrue(signal.observe(403, requiredBody))
        assertFalse(signal.observe(403, requiredBody))

        val received = mutableListOf<SsoRequiredSignInRequest>()
        val collector = launch { signal.signInRequests.toList(received) }
        advanceUntilIdle()
        assertEquals(1, received.size, "one sign-in request")
        assertFalse(signal.observe(403, requiredBody))
        advanceUntilIdle()
        assertEquals(1, received.size, "still one after more responses")
        collector.cancel()
    }

    @Test
    fun theRequestCarriesTheTraceIdOfThe403() = runTest {
        val signal = RaftSsoRequiredSignal()
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        assertTrue(signal.observe(403, requiredBody, traceId))
        assertEquals(traceId, signal.signInRequests.first().traceId)
    }

    @Test
    fun aClearedSessionLetsTheNextSessionAskAgain() = runTest {
        val signal = RaftSsoRequiredSignal()
        assertTrue(signal.observe(403, requiredBody))
        signal.reset()
        assertTrue(signal.observe(403, requiredBody), "after reset the next response asks again")
        signal.signInRequests.first()
    }

    @Test
    fun socketHandshakeErrorIsRecognized() {
        assertTrue(isSsoRequiredSocketError("SSO_REQUIRED"))
        assertTrue(isSsoRequiredSocketError("[{message=SSO_REQUIRED}]"))
        assertFalse(isSsoRequiredSocketError("Invalid or expired token"))
        assertFalse(isSsoRequiredSocketError(null))
    }

    @Test
    fun sharedNetworkClientReportsARequiredResponseOnce() = runTest {
        val signal = RaftSsoRequiredSignal()
        val executor = RequiredExecutor(requiredBody)
        val client =
            SharedRaftNetworkClient(
                sessionStore =
                    InMemoryRaftAuthSessionStore(
                        StoredAuthSession(
                            user = StoredAuthUser(id = "user-1", email = "user@acme.test"),
                            accessToken = "access-token",
                            refreshToken = "refresh-token",
                            selectedServerId = "server-1",
                            servers = listOf(StoredAuthServer(id = "server-1", name = "Raft")),
                        )
                    ),
                baseUrlProvider = { SharedApiEnvironmentStore.Production.apiBaseUrl },
                executor = executor,
                onBaselineHistoryRequestSent = {},
                accountDeletionPendingSignal = RaftAccountDeletionPendingSignal(),
                ssoRequiredSignal = signal,
            )

        val first = client.request(DataRequestPriority.Visible, "channels")
        client.request(DataRequestPriority.Visible, "agents")

        assertEquals(403, first.code, "the response itself is unchanged")
        assertEquals(2, executor.calls, "no retry or refresh on the 403")
        val received = mutableListOf<SsoRequiredSignInRequest>()
        val collector = launch { signal.signInRequests.toList(received) }
        advanceUntilIdle()
        assertEquals(1, received.size)
        collector.cancel()
    }

    private class RequiredExecutor(private val body: String) : RaftHttpExecutor {
        var calls = 0

        override suspend fun execute(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean
        ): RaftHttpExecutorResponse {
            calls += 1
            return RaftHttpExecutorResponse(code = 403, bodyText = null, errorBodyText = body)
        }

        override suspend fun executeMultipart(
            priority: RaftRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>,
            onUploadProgress: ((build.raft.app.network.RaftWireTransferProgress) -> Unit)?
        ): RaftHttpExecutorResponse = RaftHttpExecutorResponse(code = 200, bodyText = "{}", errorBodyText = null)
    }
}
