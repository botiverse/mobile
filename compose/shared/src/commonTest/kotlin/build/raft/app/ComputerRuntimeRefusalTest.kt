package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftComputersApi
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RuntimeAuthRequestError
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeLimitResetRefusal
import build.raft.app.data.SettingsComputerBroadcastPolicySnapshot
import build.raft.app.data.SettingsComputerControlState
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerUpgradeRefusal
import build.raft.app.data.settingsComputerUpgradeAvailabilityKey
import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComputerRuntimeRefusalTest {
    private val sampleTraceId = "62ac7fa79a3785a6bee414a074f82634"

    @Test
    fun runtimeOperationRefusalPrefersTier2CodesSurfaces4xxWordsAndScrubsInfra() {
        assertEquals(
            ComputerRuntimeOperationRefusal.ComputerOffline to null,
            RuntimeAuthRequestError(409, "computer_offline", sampleTraceId, "Computer offline.").toOperationRefusal(),
        )
        assertEquals(
            ComputerRuntimeOperationRefusal.RateLimited to null,
            RuntimeAuthRequestError(429, "rate_limited", sampleTraceId, "Slow down.").toOperationRefusal(),
        )
        assertEquals(
            ComputerRuntimeOperationRefusal.Failed to "Runtime upgrades are disabled by workspace policy.",
            RuntimeAuthRequestError(403, "policy_denied", sampleTraceId, "Runtime upgrades are disabled by workspace policy.").toOperationRefusal(),
        )
        assertEquals(
            ComputerRuntimeOperationRefusal.Failed to null,
            RuntimeAuthRequestError(500, "internal_error", sampleTraceId, "SQLSTATE panic").toOperationRefusal(),
        )
        assertEquals(
            ComputerRuntimeOperationRefusal.Failed to null,
            RuntimeAuthRequestError(403, "edge_blocked", sampleTraceId, "Blocked by WAF").toOperationRefusal(),
        )
        assertEquals(
            ComputerRuntimeOperationRefusal.Failed to null,
            RuntimeAuthRequestError(401, null, sampleTraceId, "jwt expired").toOperationRefusal(),
        )
        assertEquals(
            ComputerRuntimeOperationRefusal.Failed to null,
            RuntimeAuthRequestError(null, null, null, "Connection reset by peer").toOperationRefusal(),
        )
    }

    @Test
    fun runtimeAuthSignInSignOutAndProviderRefusalsPreserveTier2AndSurfaceScrubbedTier3() {
        val knownSignIn =
            RuntimeAuthRequestError(409, "login_in_progress", sampleTraceId, "A sign-in is already running.")
                .signInStartFailure(RuntimeAuthRuntime.Claude)
        assertEquals(ComputerRuntimeAuthProblem.Busy, knownSignIn.problem)
        assertTrue(knownSignIn.retryable)
        assertNull(knownSignIn.serverMessage)

        val forbiddenSignIn =
            RuntimeAuthRequestError(403, "owner_only", sampleTraceId, "Only the computer owner can sign in runtimes.")
                .signInStartFailure(RuntimeAuthRuntime.Claude)
        assertEquals(ComputerRuntimeAuthProblem.Forbidden, forbiddenSignIn.problem)
        assertFalse(forbiddenSignIn.retryable)
        assertEquals("Only the computer owner can sign in runtimes.", forbiddenSignIn.serverMessage)
        assertEquals(sampleTraceId, forbiddenSignIn.traceId)

        val infraSignIn =
            RuntimeAuthRequestError(502, null, sampleTraceId, "<html>502 Bad Gateway</html>")
                .signInStartFailure(RuntimeAuthRuntime.Claude)
        assertEquals(ComputerRuntimeAuthProblem.StartFailed, infraSignIn.problem)
        assertNull(infraSignIn.serverMessage)

        assertNull(RuntimeAuthRequestError(504, "computer_timeout", sampleTraceId, "timed out").signOutServerMessageOrNull())
        assertEquals(
            "Workspace policy prevents signing out shared runtimes.",
            RuntimeAuthRequestError(403, "policy_denied", sampleTraceId, "Workspace policy prevents signing out shared runtimes.").signOutServerMessageOrNull(),
        )
        assertNull(RuntimeAuthRequestError(403, "edge_blocked", sampleTraceId, "edge_blocked").signOutServerMessageOrNull())

        val providerForbidden =
            RuntimeAuthRequestError(403, "provider_locked", sampleTraceId, "Provider keys are managed centrally.")
                .providerFailure(connect = true)
        assertEquals(ComputerRuntimeAuthProblem.ProviderForbidden, providerForbidden?.problem)
        assertEquals("Provider keys are managed centrally.", providerForbidden?.serverMessage)
        assertNull(RuntimeAuthRequestError(400, "invalid_api_key", sampleTraceId, "Bad key").providerFailure(connect = true))
    }

    @Test
    fun limitResetServerMessagePreservesOutcomeUnknownSafetyAndTier2Precedence() {
        // outcomeUnknown refusals (null, 408, >=500, malformed_response) must NEVER overwrite the safety warning.
        val unknownRefusals =
            listOf(
                RuntimeLimitResetRefusal(null, null, sampleTraceId, "Connection dropped"),
                RuntimeLimitResetRefusal(408, null, sampleTraceId, "Request timeout"),
                RuntimeLimitResetRefusal(500, "internal_error", sampleTraceId, "Database error"),
                RuntimeLimitResetRefusal(504, "computer_timeout", sampleTraceId, "Computer timed out"),
                RuntimeLimitResetRefusal(200, "malformed_response", sampleTraceId, "Malformed"),
            )
        unknownRefusals.forEach { refusal ->
            assertTrue(refusal.outcomeUnknown, "Expected outcomeUnknown for $refusal")
            assertNull(refusal.limitResetServerMessageOrNull(), "Must preserve safety warning for $refusal")
        }

        // Known Tier 2 codes keep localized copy.
        assertNull(RuntimeLimitResetRefusal(409, "computer_offline", sampleTraceId, "Computer is offline.").limitResetServerMessageOrNull())
        assertNull(RuntimeLimitResetRefusal(429, "rate_limited", sampleTraceId, "Too many requests.").limitResetServerMessageOrNull())
        assertNull(RuntimeLimitResetRefusal(403, "claude_limit_reset_disabled", sampleTraceId, "Disabled.").limitResetServerMessageOrNull())

        // Definite 4xx refusal without Tier 2 code surfaces scrubbed server words.
        assertEquals(
            "Only the member who attached this computer can redeem a reset.",
            RuntimeLimitResetRefusal(403, "owner_required", sampleTraceId, "Only the member who attached this computer can redeem a reset.").limitResetServerMessageOrNull(),
        )
        assertNull(RuntimeLimitResetRefusal(403, "edge_blocked", sampleTraceId, "edge_blocked").limitResetServerMessageOrNull())
        assertNull(RuntimeLimitResetRefusal(401, null, sampleTraceId, "jwt expired").limitResetServerMessageOrNull())
    }

    @Test
    fun computerUpgradeModelSurfacesScrubbedUnknownRefusalMessageAndTraceId() {
        val computer =
            SettingsComputerSnapshot(
                id = "m1",
                name = "Work Mac",
                status = "online",
                isComputer = true,
                computerVersion = "1.0.46",
                latestComputerVersion = "1.0.50",
                remoteUpgradeSupported = true,
                upgradeAvailable = true,
                broadcastPolicy =
                    SettingsComputerBroadcastPolicySnapshot(
                        eligibility = "eligible",
                        targetVersion = "1.0.50",
                    ),
            )
        val availabilityKey = settingsComputerUpgradeAvailabilityKey(computer)
        val unknownRefusal =
            SettingsComputerControlState(
                upgradeRefusal =
                    SettingsComputerUpgradeRefusal(
                        machineId = "m1",
                        availabilityKey = availabilityKey,
                        code = "upgrade_window_closed",
                        serverMessage = "Upgrades are paused during maintenance windows.",
                        traceId = sampleTraceId,
                    )
            )
        val model = raftComputerUpgradeModel(computer, unknownRefusal, nowMillis = 1_000L)
        assertEquals(RaftComputerUpgradeRefusal.Unknown, model.refusal)
        assertEquals("Upgrades are paused during maintenance windows.", model.refusalMessage)
        assertEquals(sampleTraceId, model.refusalTraceId)

        val infraRefusal =
            SettingsComputerControlState(
                upgradeRefusal =
                    SettingsComputerUpgradeRefusal(
                        machineId = "m1",
                        availabilityKey = availabilityKey,
                        code = "server_unavailable",
                        serverMessage = "upstream 503",
                        traceId = sampleTraceId,
                    )
            )
        val scrubbedModel = raftComputerUpgradeModel(computer, infraRefusal, nowMillis = 1_000L)
        assertEquals(RaftComputerUpgradeRefusal.Unknown, scrubbedModel.refusal)
        assertNull(scrubbedModel.refusalMessage)
        assertEquals(sampleTraceId, scrubbedModel.refusalTraceId)
    }

    @Test
    fun computersApiControlAndDataErrorsPreserveServerCodeMessageAndTraceId() = runTest {
        val traceHeaders = mapOf("X-Trace-Id" to listOf(sampleTraceId))
        val api =
            object : RaftComputersApi {
                override suspend fun controlComputer(
                    serverId: ServerId,
                    machineId: String,
                    action: String,
                    bodyText: String,
                ): HomeNetworkResponse =
                    HomeNetworkResponse(
                        code = 409,
                        bodyText = null,
                        errorBodyText = """{"error":"Upgrade paused during deploy freeze.","code":"upgrade_frozen"}""",
                        responseHeaders = traceHeaders,
                    )

                override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse =
                    HomeNetworkResponse(
                        code = 403,
                        bodyText = null,
                        errorBodyText = """{"message":"Only server owners can remove computers.","errorCode":"owner_required"}""",
                        responseHeaders = traceHeaders,
                    )
            }
        val client = RaftComputersApiClient(api)
        val upgradeResult = assertIs<DataResult.Error>(client.upgradeComputer(ServerId("s1"), "m1", "1.0.50"))
        assertEquals("upgrade_frozen", upgradeResult.error.code)
        assertEquals("upgrade_frozen", upgradeResult.error.serverCode)
        assertEquals("Upgrade paused during deploy freeze.", upgradeResult.error.serverMessage)
        assertEquals(sampleTraceId, upgradeResult.error.traceId)

        val deleteResult = assertIs<DataResult.NoPermission>(client.deleteMachine(ServerId("s1"), "m1"))
        assertEquals("server_forbidden", deleteResult.blocker.code)
        assertEquals("owner_required", deleteResult.blocker.serverCode)
        assertEquals("Only server owners can remove computers.", deleteResult.blocker.serverMessage)
        assertEquals(sampleTraceId, deleteResult.blocker.traceId)
    }
}
