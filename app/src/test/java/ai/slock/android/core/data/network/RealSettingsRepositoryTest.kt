package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.AuthServer
import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.AuthUser
import ai.slock.android.core.data.repository.AccountProfileUpdateRequest
import ai.slock.android.core.data.repository.ConnectedAccountStatus
import ai.slock.android.core.data.repository.DataResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class RealSettingsRepositoryTest {
    @Test
    fun accountMapsProfileAndConnectedAccounts() {
        val repository = RealSettingsRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                accountJson = """
                    {
                      "user": {
                        "id": "user-1",
                        "email": "user@slock.ai",
                        "displayName": "Slock User",
                        "handle": "@slock-user",
                        "emailVerified": true
                      },
                      "connectedAccounts": [
                        {"provider":"Google","identifier":"user@slock.ai","status":"connected"},
                        {"provider":"GitHub","identifier":"github.com/slock","status":"not_connected"}
                      ],
                      "canEditProfile": true
                    }
                """.trimIndent()
            )
        )

        val result = runSuspend { repository.getAccount() }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals("Slock User", snapshot.profile.displayName)
        assertEquals("@slock-user", snapshot.profile.handle)
        assertEquals(true, snapshot.profile.verified)
        assertEquals("Owner", snapshot.profile.roleLabel)
        assertEquals(ConnectedAccountStatus.NotConnected, snapshot.connectedAccounts[1].status)
    }

    @Test
    fun sessionsMapRowsAndCurrentSessionCannotBeRevoked() {
        val repository = RealSettingsRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                sessionsJson = """
                    {
                      "sessions": [
                        {"id":"current","deviceName":"Android","browserLabel":"Slock Android","locationLabel":"Current device","lastActiveLabel":"Active now","current":true},
                        {"id":"web","device":"MacBook","browser":"Chrome","location":"Shanghai","lastActiveAt":"2026-05-06T12:00:00Z"}
                      ],
                      "canRevokeOtherSessions": true
                    }
                """.trimIndent()
            )
        )

        val result = runSuspend { repository.getSessions() }

        assertTrue(result is DataResult.Content)
        val sessions = (result as DataResult.Content).data.sessions
        assertEquals(2, sessions.size)
        assertEquals(false, sessions[0].revokeEnabled)
        assertEquals(true, sessions[1].revokeEnabled)
        assertEquals("MacBook", sessions[1].deviceName)
    }

    @Test
    fun updateProfileAndRevokeSessionReturnActionResults() {
        val repository = RealSettingsRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                accountJson = """{"user":{"id":"user-1","email":"new@slock.ai","displayName":"New Name","handle":"@new","emailVerified":true}}""",
                revokeJson = """{"sessionId":"web","revoked":true}"""
            )
        )

        val account = runSuspend {
            repository.updateProfile(AccountProfileUpdateRequest("New Name", "@new", "new@slock.ai"))
        }
        val revoked = runSuspend { repository.revokeSession("web") }

        assertTrue(account is DataResult.Content)
        assertEquals("New Name", (account as DataResult.Content).data.profile.displayName)
        assertTrue(revoked is DataResult.Content)
        val revokedResult = (revoked as DataResult.Content).data
        assertEquals("web", revokedResult.sessionId)
        assertEquals(true, revokedResult.revoked)
    }

    @Test
    fun settingsRepositoryMapsAuthPermissionAndServerErrors() {
        val noSession = runSuspend {
            RealSettingsRepository(InMemoryAuthSessionStore(null), FakeSlockApi()).getAccount()
        }
        val forbidden = runSuspend {
            RealSettingsRepository(InMemoryAuthSessionStore(sampleSession), FakeSlockApi(responseCode = 403)).getSessions()
        }
        val unavailable = runSuspend {
            RealSettingsRepository(InMemoryAuthSessionStore(sampleSession), FakeSlockApi(responseCode = 503)).getAccount()
        }
        val parse = runSuspend {
            RealSettingsRepository(
                InMemoryAuthSessionStore(sampleSession),
                FakeSlockApi(accountJson = """{"user":""")
            ).getAccount()
        }

        assertTrue(noSession is DataResult.NoPermission)
        assertEquals("auth_required", (noSession as DataResult.NoPermission).blocker.code)
        assertTrue(forbidden is DataResult.NoPermission)
        assertEquals("server_forbidden", (forbidden as DataResult.NoPermission).blocker.code)
        assertTrue(unavailable is DataResult.Error)
        assertEquals("server_unavailable", (unavailable as DataResult.Error).error.code)
        assertTrue(parse is DataResult.Error)
        assertEquals("network_parse_failed", (parse as DataResult.Error).error.code)
    }

    @Test
    fun sessionsAndRevokeMapMissingEndpointsToContractMismatch() {
        val repository = RealSettingsRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(responseCode = 404)
        )

        val sessions = runSuspend { repository.getSessions() }
        val revoke = runSuspend { repository.revokeSession("web") }

        assertTrue(sessions is DataResult.Error)
        assertEquals("settings_sessions_contract_mismatch", (sessions as DataResult.Error).error.code)
        assertEquals(false, sessions.error.retryable)
        assertTrue(revoke is DataResult.Error)
        assertEquals("settings_session_revoke_contract_mismatch", (revoke as DataResult.Error).error.code)
        assertEquals(false, revoke.error.retryable)
    }

    @Test
    fun computersServerAndMobileReturnUnavailableSnapshotsWithoutGuessingEndpoints() {
        val repository = RealSettingsRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(responseCode = 404)
        )

        val computers = runSuspend { repository.getComputers() }
        val server = runSuspend { repository.getServerSettings() }
        val mobile = runSuspend { repository.getMobileSettings() }

        assertTrue(computers is DataResult.Content)
        val computerSnapshot = (computers as DataResult.Content).data
        assertEquals(1, computerSnapshot.computers.size)
        assertEquals(false, computerSnapshot.canAddComputer)
        assertEquals("daemon unavailable", computerSnapshot.computers.first().daemonVersion)

        assertTrue(server is DataResult.Content)
        val serverSnapshot = (server as DataResult.Content).data
        assertEquals("Slock Android", serverSnapshot.profile.name)
        assertEquals(false, serverSnapshot.profile.canSave)
        assertTrue(serverSnapshot.sections.any { it.key == "danger" })

        assertTrue(mobile is DataResult.Content)
        val mobileSnapshot = (mobile as DataResult.Content).data
        assertEquals(true, mobileSnapshot.pushNotifications.available)
        assertEquals(false, mobileSnapshot.muteServer.available)
        assertEquals(true, mobileSnapshot.sendTestEnabled)
    }

    private class FakeSlockApi(
        private val accountJson: String = """{"user":{"id":"user-1","email":"user@slock.ai","displayName":"Slock User"}}""",
        private val sessionsJson: String = """{"sessions":[]}""",
        private val revokeJson: String = """{"ok":true}""",
        private val responseCode: Int = 200
    ) : SlockApi {
        override suspend fun settingsAccount(): Response<ResponseBody> = response(accountJson)

        override suspend fun updateSettingsAccount(body: RequestBody): Response<ResponseBody> = response(accountJson)

        override suspend fun settingsSessions(): Response<ResponseBody> = response(sessionsJson)

        override suspend fun revokeSettingsSession(sessionId: String): Response<ResponseBody> = response(revokeJson)

        override suspend fun channels(archived: String): Response<ResponseBody> = response("""{"channels":[]}""")

        override suspend fun directMessages(): Response<ResponseBody> = response("""[]""")

        override suspend fun inbox(filter: String, limit: Int, offset: Int): Response<ResponseBody> =
            response("""{"items":[]}""")

        override suspend fun saved(limit: Int, offset: Int): Response<ResponseBody> = response("""{"items":[]}""")

        override suspend fun searchMessages(query: String, limit: Int, offset: Int): Response<ResponseBody> =
            response("""{"results":[]}""")

        override suspend fun serverUnreadSummary(): Response<ResponseBody> = response("""{"unreadCount":0}""")

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
