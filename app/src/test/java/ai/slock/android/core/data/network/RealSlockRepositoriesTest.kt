package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.AuthServer
import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.AuthUser
import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.InboxFilter
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.InboxItemKind
import ai.slock.android.core.model.ServerId
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class RealSlockRepositoriesTest {
    @Test
    fun channelRepositoryMapsChannelsAndDmsToHomeSnapshot() {
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                channelsJson = """
                    {"channels":[{"id":"channel-1","name":"android-dev","description":"Android work","unreadCount":3,"lastMessagePreview":"Ready"}]}
                """.trimIndent(),
                directMessagesJson = """
                    [{"id":"dm-1","peerDisplayName":"Android-Developer","unreadCount":2,"lastMessagePreview":"Done"}]
                """.trimIndent()
            )
        )

        val result = runSuspend { repository.getHome(ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals("Slock Android", snapshot.server.name)
        assertEquals("android-dev", snapshot.channels.single().name)
        assertEquals(ConversationKind.DirectMessage, snapshot.directMessages.single().kind)
        assertEquals(5, snapshot.unreadTotal)
    }

    @Test
    fun inboxRepositoryMapsPagingMetadataAndStableThreadKeys() {
        val repository = RealInboxRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                inboxJson = """
                    {
                      "items": [
                        {"kind":"thread","channelId":"thread-1","channelName":"Thread","threadChannelId":"thread-1","parentChannelId":"channel-1","parentChannelName":"android-dev","unreadCount":4,"replyCount":7,"taskStatus":"in_review"}
                      ],
                      "hasMore": true,
                      "totalCount": 12,
                      "totalUnreadCount": 4
                    }
                """.trimIndent()
            )
        )

        val result = runSuspend {
            repository.loadMore(ServerId("server-1"), InboxFilter.Unread, offset = 30, limit = 30)
        }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals(true, snapshot.hasMore)
        assertEquals(31, snapshot.nextOffset)
        assertEquals(12, snapshot.totalCount)
        assertEquals("thread:thread-1", snapshot.items.single().key)
        assertEquals(InboxItemKind.Thread, snapshot.items.single().kind)
    }

    @Test
    fun savedAndSearchMapItemsAndBlankSearchReturnsEmpty() {
        val store = InMemoryAuthSessionStore(sampleSession)
        val api = FakeSlockApi(
            savedJson = """{"items":[{"channelId":"channel-1","channelName":"android-dev","messageId":"message-1","preview":"Saved note"}],"hasMore":false}""",
            searchJson = """{"results":[{"channelId":"channel-2","channelName":"product","messageId":"message-2","content":"Search hit"}],"totalCount":1}"""
        )

        val saved = runSuspend { RealSavedRepository(store, api).loadInitial(ServerId("server-1")) }
        val search = runSuspend { RealSearchRepository(store, api).search(ServerId("server-1"), "query") }
        val blank = runSuspend { RealSearchRepository(store, api).search(ServerId("server-1"), " ") }

        assertTrue(saved is DataResult.Content)
        assertEquals("Saved note", (saved as DataResult.Content).data.items.single().lastMessagePreview)
        assertTrue(search is DataResult.Content)
        assertEquals("Search hit", (search as DataResult.Content).data.items.single().lastMessagePreview)
        assertTrue(blank is DataResult.Empty)
    }

    @Test
    fun serverUnreadRepositoryMapsSummaryAndRequiresSelectedServer() {
        val repository = RealServerUnreadRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                unreadSummaryJson = """
                    {
                      "servers": [
                        {"serverId":"server-1","totalUnreadCount":9,"inboxUnreadCount":4,"savedUnreadCount":2,"channelUnreadCount":3,"dmUnreadCount":2,"mentionCount":1},
                        {"serverId":"server-2","totalUnreadCount":5,"inboxUnreadCount":1}
                      ],
                      "totalUnreadCount": 14
                    }
                """.trimIndent()
            ),
            logger = {},
            warningLogger = {}
        )

        val result = runSuspend { repository.getUnreadSummary(ServerId("server-1")) }
        val mismatch = runSuspend { repository.getUnreadSummary(ServerId("server-2")) }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals(14, snapshot.totalUnreadCount)
        assertEquals(9, snapshot.server.unreadCount)
        assertEquals(4, snapshot.inboxUnreadCount)
        assertEquals(2, snapshot.savedUnreadCount)
        assertEquals(2, snapshot.summaries.size)
        assertEquals(1, snapshot.selectedServerSummary?.mentionCount)
        assertTrue(mismatch is DataResult.NoPermission)
    }

    @Test
    fun serverUnreadRepositoryMapsCurrentServerOnlyShape() {
        val repository = RealServerUnreadRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                unreadSummaryJson = """{"unreadCount":3,"inboxUnreadCount":2,"mentions":1}"""
            ),
            logger = {},
            warningLogger = {}
        )

        val result = runSuspend { repository.getUnreadSummary(ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals(3, snapshot.totalUnreadCount)
        assertEquals(2, snapshot.inboxUnreadCount)
        assertEquals("server-1", snapshot.selectedServerSummary?.serverId?.value)
    }

    @Test
    fun serverUnreadRepositoryThrottlesRepeatedCallsPerServer() {
        var now = 1_000L
        val api = FakeSlockApi(
            unreadSummaryJson = """{"unreadCount":3,"inboxUnreadCount":2}"""
        )
        val repository = RealServerUnreadRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            throttleWindowMillis = 5_000L,
            clockMillis = { now },
            logger = {},
            warningLogger = {}
        )

        val first = runSuspend { repository.getUnreadSummary(ServerId("server-1")) }
        val second = runSuspend { repository.getUnreadSummary(ServerId("server-1")) }
        now += 5_001L
        val third = runSuspend { repository.getUnreadSummary(ServerId("server-1")) }

        assertTrue(first is DataResult.Content)
        assertTrue(second is DataResult.Content)
        assertTrue(third is DataResult.Content)
        assertEquals(2, api.unreadSummaryCallCount)
    }

    @Test
    fun repositoriesRequireSelectedSessionServer() {
        val repository = RealInboxRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi()
        )

        val result = runSuspend {
            repository.loadInitial(ServerId("other-server"))
        }

        assertTrue(result is DataResult.NoPermission)
    }

    private class FakeSlockApi(
        private val settingsAccountJson: String = """{}""",
        private val settingsSessionsJson: String = """{"sessions":[]}""",
        private val revokeSessionJson: String = """{"ok":true}""",
        private val channelsJson: String = """{"channels":[]}""",
        private val directMessagesJson: String = """[]""",
        private val inboxJson: String = """{"items":[]}""",
        private val savedJson: String = """{"items":[]}""",
        private val searchJson: String = """{"results":[]}""",
        private val unreadSummaryJson: String = """{"unreadCount":0}""",
        private val responseCode: Int = 200
    ) : SlockApi {
        var unreadSummaryCallCount = 0
            private set

        override suspend fun settingsAccount(): Response<ResponseBody> =
            response(settingsAccountJson)

        override suspend fun updateSettingsAccount(body: RequestBody): Response<ResponseBody> =
            response(settingsAccountJson)

        override suspend fun settingsSessions(): Response<ResponseBody> =
            response(settingsSessionsJson)

        override suspend fun revokeSettingsSession(sessionId: String): Response<ResponseBody> =
            response(revokeSessionJson)

        override suspend fun channels(archived: String): Response<ResponseBody> =
            response(channelsJson)

        override suspend fun directMessages(): Response<ResponseBody> =
            response(directMessagesJson)

        override suspend fun inbox(filter: String, limit: Int, offset: Int): Response<ResponseBody> =
            response(inboxJson)

        override suspend fun saved(limit: Int, offset: Int): Response<ResponseBody> =
            response(savedJson)

        override suspend fun searchMessages(query: String, limit: Int, offset: Int): Response<ResponseBody> =
            response(searchJson)

        override suspend fun serverUnreadSummary(): Response<ResponseBody> =
            response(unreadSummaryJson).also { unreadSummaryCallCount += 1 }

        private fun response(json: String): Response<ResponseBody> =
            if (responseCode in 200..299) {
                Response.success(json.jsonBody())
            } else {
                Response.error(responseCode, """{"error":"failed"}""".jsonBody())
            }
    }

    private class InMemoryAuthSessionStore(
        private var session: AuthSession?
    ) : AuthSessionStore {
        override suspend fun readSession(): AuthSession? = session

        override suspend fun saveSession(session: AuthSession) {
            this.session = session
        }

        override suspend fun updateTokens(accessToken: String, refreshToken: String) {
            session = session?.copy(accessToken = accessToken, refreshToken = refreshToken)
        }

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
            session = session?.copy(selectedServerId = serverId, selectedServerSlug = serverSlug)
        }

        override suspend fun clearSession() {
            session = null
        }
    }

    companion object {
        private val json = "application/json; charset=utf-8".toMediaType()

        private val sampleSession = AuthSession(
            user = AuthUser(
                id = "user-1",
                email = "user@slock.ai",
                name = "User",
                displayName = "Slock User",
                description = null,
                avatarUrl = null,
                emailVerified = true
            ),
            accessToken = "sample-access",
            refreshToken = "sample-refresh",
            selectedServerId = "server-1",
            selectedServerSlug = "slock-android",
            servers = listOf(
                AuthServer(
                    id = "server-1",
                    name = "Slock Android",
                    slug = "slock-android",
                    ownerId = "user-1",
                    onboardingAgentId = "agent-1",
                    plan = "team",
                    planDowngradedAt = null,
                    role = "owner",
                    createdAt = "2026-05-06T00:00:00Z"
                )
            )
        )

        private fun String.jsonBody(): ResponseBody = toResponseBody(json)
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var value: T? = null
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                result.onSuccess { value = it }
                result.onFailure { failure = it }
            }
        }
    )
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
}
