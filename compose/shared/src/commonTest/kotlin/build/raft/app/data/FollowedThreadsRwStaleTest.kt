package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Task #164: `GET /channels/threads/followed` (first page) while the read warehouse is behind.
 * Answers as the server sends them (botiverse/slock f6ace2b9): `routes/channels.ts`
 * `/threads/followed` serves the remembered body with `X-Raft-Stale` and `"stale": true` in the
 * body; with nothing remembered it answers `503 {"error":...,"code":"rw_overloaded","retryable":true}`
 * (`routes/errorResponse.ts` `respondToRisingWaveOverload`).
 */
class FollowedThreadsRwStaleTest {
    @Test
    fun aRememberedListIsContentTaggedStaleEvenWhenEmpty() = runTest {
        listOf(followedBody(unread = 2), """{"threads":[],"stale":true}""").forEach { body ->
            val repository = repository(HomeNetworkResponse(200, body, responseHeaders = mapOf("x-raft-stale" to listOf("rw_overloaded"))))

            val result = repository.getUnreadFollowedThreads(ServerId("server-1"))

            val snapshot = assertIs<DataResult.Content<ThreadListSnapshot>>(result, "body=$body").data
            assertEquals(RaftStaleReason.RwOverloaded, snapshot.staleReason)
        }
    }

    @Test
    fun anOverloadIsATypedErrorNeverAnEmptyList() = runTest {
        val repository =
            repository(
                HomeNetworkResponse(
                    503,
                    null,
                    errorBodyText = """{"error":"Temporarily overloaded, retry shortly","code":"rw_overloaded","retryable":true}""",
                    responseHeaders = mapOf("Retry-After" to listOf("2"))
                )
            )

        val result = repository.getFollowedThreads(ServerId("server-1"))

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("server_unavailable", error.code)
        assertEquals(true, error.isRwOverloaded)
    }

    @Test
    fun aFreshAnswerCarriesNoStaleReason() = runTest {
        val result = repository(HomeNetworkResponse(200, followedBody(unread = 1))).getUnreadFollowedThreads(ServerId("server-1"))

        assertNull(assertIs<DataResult.Content<ThreadListSnapshot>>(result).data.staleReason)
    }

    private fun followedBody(unread: Int): String =
        """{"threads":[{"threadChannelId":"thread-1","parentChannelId":"channel-1","parentMessageId":"parent-1",""" +
            """"replyCount":4,"unreadCount":$unread,"lastActivityAt":"2026-10-24T09:50:00.000Z"}]}"""

    private fun repository(response: HomeNetworkResponse): RealConversationRepository =
        RealConversationRepository(
            InMemoryRaftAuthSessionStore(sampleSession()),
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?
                ): HomeNetworkResponse {
                    check(path == "channels/threads/followed" && method == "GET") { "unexpected $method $path" }
                    return response
                }
            }
        )
}
