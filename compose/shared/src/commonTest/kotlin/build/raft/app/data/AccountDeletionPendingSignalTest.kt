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
 * A 403 `account_deletion_pending` (botiverse/slock `requireAuth`, packages/server/src/middleware/auth.ts
 * on #12459) on any request opens the web deletion page once.
 */
class AccountDeletionPendingSignalTest {
    // The body requireAuth sends (middleware/auth.ts).
    private val pendingBody =
        """{"error":"This account is scheduled for deletion. Cancel the deletion to use it again.","code":"account_deletion_pending","scheduledFor":"2026-12-02T07:13:03.000Z"}"""

    @Test
    fun pendingBodyParsesWithItsScheduledDate() {
        val parsed = parseAccountDeletionPendingError(403, pendingBody)
        assertEquals("account_deletion_pending", parsed?.code)
        assertEquals("2026-12-02T07:13:03.000Z", parsed?.scheduledFor)
    }

    @Test
    fun otherResponsesAreNotPending() {
        assertNull(parseAccountDeletionPendingError(401, pendingBody))
        assertNull(parseAccountDeletionPendingError(403, """{"error":"x","code":"device_session_not_allowed"}"""))
        assertNull(parseAccountDeletionPendingError(403, """{"error":"account_deletion_pending"}"""))
        assertNull(parseAccountDeletionPendingError(403, "account_deletion_pending"))
        assertNull(parseAccountDeletionPendingError(403, null))
    }

    @Test
    fun manyPendingResponsesQueueOneOpenRequest() = runTest {
        val signal = RaftAccountDeletionPendingSignal()
        assertTrue(signal.observe(403, pendingBody))
        assertFalse(signal.observe(403, pendingBody))
        assertFalse(signal.observe(403, pendingBody))

        val received = mutableListOf<Unit>()
        val collector = launch { signal.openRequests.toList(received) }
        advanceUntilIdle()
        assertEquals(1, received.size, "one open request")
        assertFalse(signal.observe(403, pendingBody))
        advanceUntilIdle()
        assertEquals(1, received.size, "still one after more pending responses")
        collector.cancel()
    }

    @Test
    fun aClearedSessionLetsTheNextSignInOpenAgain() = runTest {
        val signal = RaftAccountDeletionPendingSignal()
        assertTrue(signal.observe(403, pendingBody))
        signal.reset()
        assertTrue(signal.observe(403, pendingBody), "after reset the next pending response opens again")
        signal.openRequests.first()
    }

    @Test
    fun sharedNetworkClientReportsAPendingResponseOnce() = runTest {
        val signal = RaftAccountDeletionPendingSignal()
        val executor = PendingExecutor(pendingBody)
        val client =
            SharedRaftNetworkClient(
                sessionStore =
                    InMemoryRaftAuthSessionStore(
                        StoredAuthSession(
                            user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
                            accessToken = "access-token",
                            refreshToken = "refresh-token",
                            selectedServerId = "server-1",
                            servers = listOf(StoredAuthServer(id = "server-1", name = "Raft")),
                        )
                    ),
                baseUrlProvider = { SharedApiEnvironmentStore.Production.apiBaseUrl },
                executor = executor,
                onBaselineHistoryRequestSent = {},
                accountDeletionPendingSignal = signal,
            )

        val first = client.request(DataRequestPriority.Visible, "channels")
        client.request(DataRequestPriority.Visible, "agents")

        assertEquals(403, first.code, "the response itself is unchanged")
        assertEquals(2, executor.calls, "no retry or refresh on the 403")
        val received = mutableListOf<Unit>()
        val collector = launch { signal.openRequests.toList(received) }
        advanceUntilIdle()
        assertEquals(1, received.size)
        collector.cancel()
    }

    private class PendingExecutor(private val body: String) : RaftHttpExecutor {
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
