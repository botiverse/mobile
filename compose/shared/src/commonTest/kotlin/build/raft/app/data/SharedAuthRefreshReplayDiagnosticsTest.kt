package build.raft.app.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feedback 6feec67c: a refresh was in flight when the OS froze the app for 24 minutes. On resume the
 * socket path replayed the same attempt id after the server's 15-minute replay receipt had expired,
 * got 401, and the session was cleared. These cases pin the journal lines that name that sequence;
 * the clear/preserve decisions themselves are unchanged.
 */
class SharedAuthRefreshReplayDiagnosticsTest {
    @Test
    fun replayedAttemptLogsAgeAndRejectionAgainstReceiptTtl() = runTest {
        data class Case(val ageMillis: Long, val likelyExpired: Boolean, val outcome: String)
        val cases =
            listOf(
                Case(ageMillis = 24L * MINUTE, likelyExpired = true, outcome = "lost_rotation_after_freeze"),
                Case(ageMillis = 5L * MINUTE, likelyExpired = false, outcome = "replay_rejected"),
            )
        cases.forEachIndexed { index, case ->
            val access = "replay-access-$index"
            val refresh = "replay-refresh-$index"
            val createdAt = 1_000L
            val persisted =
                StoredAuthRefreshAttempt(
                    attemptId = "arf_00000000000000a$index",
                    installationId = INSTALLATION_ID,
                    accountId = "user-1",
                    predecessorAccessToken = access,
                    predecessorRefreshToken = refresh,
                    createdAtMillis = createdAt,
                )
            val store = InMemoryRaftAuthSessionStore(session(access, refresh).copy(refreshAttempt = persisted))
            store.readOrCreateAuthInstallationId(INSTALLATION_ID)
            val api =
                FakeRefreshAuthApi(
                    refreshTokenAssertion = refresh,
                    responses = listOf(AuthApiResult.Failure(code = 401, errorBodyText = STAGING_401_BODY)),
                )
            val logs = mutableListOf<String>()
            val critical = mutableListOf<String>()
            val coordinator =
                SharedAuthRefreshCoordinator(
                    sessionStore = store,
                    authApi = api,
                    clockMillis = { createdAt + case.ageMillis },
                    logger = logs::add,
                    criticalLogger = critical::add,
                    attemptIdFactory = { "arf_ffffffffffffff0$index" },
                    installationIdFactory = { INSTALLATION_ID },
                )

            val result =
                coordinator.refreshSession(
                    failedAccessToken = access,
                    clearOnUnauthorized = false,
                    reason = "connect_error",
                    caller = "android_socket",
                )

            assertEquals(401, (result as SharedAuthRefreshResult.Failure).response.code)
            assertEquals(listOf(persisted.attemptId), api.attempts.map { it.attemptId })
            assertEquals(access, store.readSession()?.accessToken, "logging must not change the preserve decision")

            val reused = critical.event("refresh_attempt_reused")
            assertEquals("android_socket", reused["caller"])
            assertEquals(case.ageMillis.toString(), reused["attemptAgeMs"])
            assertEquals(AUTH_REFRESH_REPLAY_RECEIPT_TTL_MILLIS.toString(), reused["replayReceiptTtlMs"])
            assertEquals(case.likelyExpired.toString(), reused["replayReceiptLikelyExpired"])
            assertEquals(
                if (case.likelyExpired) "likely_expired_server_replay_receipt" else null,
                reused["diagnosis"],
            )
            assertEquals("true", logs.event("refresh_start")["replayed"])

            val rejected = critical.event("refresh_replay_rejected")
            assertEquals("401", rejected["status"])
            assertEquals(case.ageMillis.toString(), rejected["attemptAgeMs"])
            assertEquals(case.outcome, rejected["outcome"])
            assertNull(rejected["serverCode"])

            val preserved = critical.event("refresh_unauthorized_session_preserved")
            assertEquals("refresh_replay_rejected", preserved["cause"])
            assertEquals(case.ageMillis.toString(), preserved["attemptAgeMs"])
            assertNoTokenValues(logs + critical, access, refresh)
        }
    }

    @Test
    fun rotationSupersededIsIgnoredWhenNewerSessionIsHeld() = runTest {
        var now = 5_000L
        val store = InMemoryRaftAuthSessionStore(session("superseded-old-access", "superseded-old-refresh"))
        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = "superseded-old-refresh",
                responses = listOf(AuthApiResult.Failure(code = 401, errorBodyText = SUPERSEDED_401_BODY)),
                beforeResponse = {
                    // Another writer committed the successor while this request was outstanding.
                    store.updateTokens("superseded-newer-access", "superseded-newer-refresh")
                    now += 3L * MINUTE
                },
            )
        val critical = mutableListOf<String>()
        val coordinator =
            SharedAuthRefreshCoordinator(
                sessionStore = store,
                authApi = api,
                clockMillis = { now },
                criticalLogger = critical::add,
                attemptIdFactory = { "arf_00000000000000b1" },
                installationIdFactory = { INSTALLATION_ID },
            )

        val result =
            coordinator.refreshSession(
                failedAccessToken = "superseded-old-access",
                clearOnUnauthorized = true,
                reason = "auth_restore_401",
                caller = "auth_restore",
            )

        assertEquals("superseded-newer-access", (result as SharedAuthRefreshResult.Success).session.accessToken)
        assertEquals("superseded-newer-refresh", store.readSession()?.refreshToken)
        val superseded = critical.event("refresh_rotation_superseded")
        assertEquals("401", superseded["status"])
        assertEquals("false", superseded["replayed"])
        assertEquals((3L * MINUTE).toString(), superseded["attemptAgeMs"])
        val ignored = critical.event("refresh_unauthorized_ignored")
        assertEquals("newer_session_held", ignored["decision"])
        assertEquals(AUTH_REFRESH_ROTATION_SUPERSEDED_CODE, ignored["cause"])
        assertFalse(critical.any { it.startsWith("refresh_unauthorized_session_cleared") })
        assertNoTokenValues(critical, "superseded-old-access", "superseded-newer-refresh")
    }

    @Test
    fun rotationSupersededWithoutNewerSessionClearsAndNamesTheReason() = runTest {
        var now = 7_000L
        val store = InMemoryRaftAuthSessionStore(session("frozen-access", "frozen-refresh"))
        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = "frozen-refresh",
                responses = listOf(AuthApiResult.Failure(code = 401, errorBodyText = SUPERSEDED_401_BODY)),
                beforeResponse = { now += 24L * MINUTE },
            )
        val critical = mutableListOf<String>()
        val coordinator =
            SharedAuthRefreshCoordinator(
                sessionStore = store,
                authApi = api,
                clockMillis = { now },
                criticalLogger = critical::add,
                attemptIdFactory = { "arf_00000000000000c1" },
                installationIdFactory = { INSTALLATION_ID },
            )

        val result =
            coordinator.refreshSession(
                failedAccessToken = "frozen-access",
                clearOnUnauthorized = true,
                reason = "auth_restore_401",
                caller = "auth_restore",
            )

        assertEquals(401, (result as SharedAuthRefreshResult.Failure).response.code)
        assertNull(store.readSession())
        val superseded = critical.event("refresh_rotation_superseded")
        assertEquals((24L * MINUTE).toString(), superseded["attemptAgeMs"])
        assertEquals("true", superseded["replayReceiptLikelyExpired"])
        val failed = critical.event("refresh_failed")
        assertEquals(AUTH_REFRESH_ROTATION_SUPERSEDED_CODE, failed["serverCode"])
        val cleared = critical.event("refresh_unauthorized_session_cleared")
        assertEquals(AUTH_REFRESH_ROTATION_SUPERSEDED_CODE, cleared["cause"])
        assertEquals((24L * MINUTE).toString(), cleared["attemptAgeMs"])
        assertNoTokenValues(critical, "frozen-access", "frozen-refresh")
    }

    @Test
    fun refreshErrorCodeIsReadFromTheServerCodeFieldOnly() {
        val cases =
            listOf(
                SUPERSEDED_401_BODY to AUTH_REFRESH_ROTATION_SUPERSEDED_CODE,
                STAGING_401_BODY to null,
                """{"error":"refresh_rotation_superseded"}""" to null,
                """{"error":"x","code":"  "}""" to null,
                "connection reset" to null,
                "" to null,
            )
        cases.forEach { (body, expected) ->
            assertEquals(expected, parseAuthRefreshErrorCode(body), body)
        }
        assertNull(parseAuthRefreshErrorCode(null))
    }

    private fun session(accessToken: String, refreshToken: String): StoredAuthSession =
        authSessionFixture().copy(accessToken = accessToken, refreshToken = refreshToken)

    private fun List<String>.event(name: String): Map<String, String> {
        val line = singleOrNull { it.substringBefore(' ') == name } ?: error("expected one $name line in $this")
        return line.split(' ').drop(1).filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    private fun assertNoTokenValues(logs: List<String>, vararg tokens: String) {
        tokens.forEach { token -> assertTrue(logs.none { token in it }, "token value leaked into diagnostics") }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val INSTALLATION_ID = "ari_00000000000000000000000000000abc"
        const val STAGING_401_BODY = """{"error":"Invalid or expired refresh token"}"""
        const val SUPERSEDED_401_BODY =
            """{"error":"Refresh token already rotated","code":"refresh_rotation_superseded"}"""
    }
}
