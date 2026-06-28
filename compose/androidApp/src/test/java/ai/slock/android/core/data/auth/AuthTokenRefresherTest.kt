package ai.slock.android.core.data.auth

import ai.slock.compose.data.AuthApiResult
import ai.slock.compose.data.AuthLoginResponse
import ai.slock.compose.data.AuthRefreshResponse
import ai.slock.compose.data.AuthSocialCompleteResponse
import ai.slock.compose.data.InMemorySlockAuthSessionStore
import ai.slock.compose.data.SlockAuthApi
import ai.slock.compose.data.StoredAuthServer
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.StoredAuthUser
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthTokenRefresherTest {
    @Test
    fun processLockCoalescesRefreshAcrossRefresherInstances() = runBlocking {
        val store =
            InMemorySlockAuthSessionStore(
                sampleSession(accessToken = "process-old-access", refreshToken = "process-old-refresh")
            )
        val api =
            FakeAuthApi(
                refreshTokenAssertion = "process-old-refresh",
                responses =
                    listOf(
                        AuthApiResult.Success(AuthRefreshResponse("process-fresh-access", "process-fresh-refresh")),
                        AuthApiResult.Failure(code = 401, errorBodyText = """{"error":"invalid refresh"}""")
                    ),
                delayMillis = 50
            )
        val firstRefresher = AuthTokenRefresher(store = store, api = api, logger = {}, warningLogger = {})
        val secondRefresher = AuthTokenRefresher(store = store, api = api, logger = {}, warningLogger = {})

        coroutineScope {
            val first = async {
                firstRefresher.refreshIfCurrent(
                    failedAccessToken = "process-old-access",
                    clearOnUnauthorized = false
                )
            }
            val second = async {
                secondRefresher.refreshIfCurrent(
                    failedAccessToken = "process-old-access",
                    clearOnUnauthorized = false
                )
            }

            assertEquals("process-fresh-access", first.await()?.accessToken)
            assertEquals("process-fresh-access", second.await()?.accessToken)
        }
        assertEquals(1, api.refreshCalls)
        assertEquals("process-fresh-access", store.readSession()?.accessToken)
    }

    @Test
    fun preservedUnauthorizedRefreshFailureIsTemporarilySuppressed() = runBlocking {
        var now = 1_000L
        val warnings = mutableListOf<String>()
        val store =
            InMemorySlockAuthSessionStore(
                sampleSession(accessToken = "preserve-old-access", refreshToken = "preserve-old-refresh")
            )
        val api =
            FakeAuthApi(
                refreshTokenAssertion = "preserve-old-refresh",
                responses =
                    listOf(
                        AuthApiResult.Failure(code = 401, errorBodyText = """{"error":"invalid refresh"}"""),
                        AuthApiResult.Success(AuthRefreshResponse("preserve-fresh-access", "preserve-fresh-refresh"))
                    )
            )
        val refresher =
            AuthTokenRefresher(
                store = store,
                api = api,
                clockMillis = { now },
                logger = {},
                warningLogger = warnings::add
            )

        val first =
            refresher.refreshIfCurrent(
                failedAccessToken = "preserve-old-access",
                clearOnUnauthorized = false
            )
        now += 1_000L
        val second =
            refresher.refreshIfCurrent(
                failedAccessToken = "preserve-old-access",
                clearOnUnauthorized = false
            )

        assertNull(first)
        assertNull(second)
        assertEquals(1, api.refreshCalls)
        assertEquals("preserve-old-access", store.readSession()?.accessToken)
        assertEquals(
            listOf(
                "refresh_failed status=401",
                "refresh_unauthorized_session_preserved",
                "refresh_skip reason=recent_unauthorized_session_preserved"
            ),
            warnings
        )
    }

    private class FakeAuthApi(
        private val refreshTokenAssertion: String,
        private val responses: List<AuthApiResult<AuthRefreshResponse>>,
        private val delayMillis: Long = 0L
    ) : SlockAuthApi {
        var refreshCalls: Int = 0
            private set

        override suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse> {
            assertEquals(refreshTokenAssertion, refreshToken)
            refreshCalls += 1
            if (delayMillis > 0) delay(delayMillis)
            return responses.getOrElse(refreshCalls - 1) { responses.last() }
        }

        override suspend fun login(email: String, password: String): AuthApiResult<AuthLoginResponse> =
            error("not used")

        override suspend fun completeSocialLogin(
            provider: String,
            bodyText: String
        ): AuthApiResult<AuthSocialCompleteResponse> =
            error("not used")

        override suspend fun register(name: String, email: String, password: String): AuthApiResult<AuthLoginResponse> =
            error("not used")

        override suspend fun forgotPassword(email: String): AuthApiResult<Unit> =
            error("not used")

        override suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser> =
            error("not used")

        override suspend fun logout(refreshToken: String): AuthApiResult<Unit> =
            error("not used")

        override suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>> =
            error("not used")

        override suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer> =
            error("not used")
    }

    private fun sampleSession(
        accessToken: String,
        refreshToken: String
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai"),
            accessToken = accessToken,
            refreshToken = refreshToken,
            selectedServerId = "server-1",
            selectedServerSlug = "slock",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
        )
}
