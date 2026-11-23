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
        assertEquals(
            ComputerRuntimeOperationRefusal.Failed to null,
            RuntimeAuthRequestError(200, "malformed_response", sampleTraceId, "unexpected payload").toOperationRefusal(),
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

        val malformedSignIn =
            RuntimeAuthRequestError(200, "malformed_response", sampleTraceId, "Unexpected JSON")
                .signInStartFailure(RuntimeAuthRuntime.Claude)
        assertEquals(ComputerRuntimeAuthProblem.StartFailed, malformedSignIn.problem)
        assertNull(malformedSignIn.serverMessage)

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
    fun computerUpgradeAndRestartRefusalsScrubInfraWithoutDroppingValid4xxCodes() {
        val computer =
            SettingsComputerSnapshot(
                id = "m1",
                name = "Work Mac",
                daemonVersion = "",
                computerVersion = "1.0.46",
                isComputer = true,
                online = true,
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

        // Valid 4xx wire codes containing substrings like "mismatch" or "host" must NOT be scrubbed.
        val mismatchRefusal =
            SettingsComputerControlState(
                upgradeRefusal =
                    SettingsComputerUpgradeRefusal(
                        machineId = "m1",
                        availabilityKey = availabilityKey,
                        code = "target_version_mismatch",
                        serverMessage = "Target version 1.0.50 is no longer offered for this host.",
                        traceId = sampleTraceId,
                    )
            )
        assertEquals(
            "Target version 1.0.50 is no longer offered for this host.",
            raftComputerUpgradeModel(computer, mismatchRefusal, nowMillis = 1_000L).refusalMessage,
        )
        assertEquals(
            "Upgrades on this host are temporarily paused.",
            computerControlRefusalServerMessageOrNull(
                code = "upgrade_host_paused",
                serverMessage = "Upgrades on this host are temporarily paused.",
                traceId = sampleTraceId,
            ),
        )

        // Transport, null/blank, 5xx, auth, and edge/HTML markers must all be scrubbed.
        val scrubbedCodes = listOf(null, "", "http_0", "http_-1", "http_401", "http_500", "http_503", "auth_required", "server_unavailable", "machine_offline", "machine_offline_replay", "settings_unavailable", "malformed_response", "internal_error", "edge_blocked")
        scrubbedCodes.forEach { code ->
            assertNull(
                computerControlRefusalServerMessageOrNull(code = code, serverMessage = "raw backend failure", traceId = sampleTraceId),
                "Expected code=$code to be scrubbed",
            )
        }
        assertNull(
            computerControlRefusalServerMessageOrNull(
                code = "upgrade_window_closed",
                serverMessage = "<html>502 Bad Gateway</html>",
                traceId = sampleTraceId,
            )
        )
        val infraModel =
            raftComputerUpgradeModel(
                computer,
                SettingsComputerControlState(
                    upgradeRefusal =
                        SettingsComputerUpgradeRefusal(
                            machineId = "m1",
                            availabilityKey = availabilityKey,
                            code = "http_0",
                            serverMessage = "Connection reset by peer",
                            traceId = sampleTraceId,
                        )
                ),
                nowMillis = 1_000L,
            )
        assertEquals(RaftComputerUpgradeRefusal.Unknown, infraModel.refusal)
        assertNull(infraModel.refusalMessage)
        assertEquals(sampleTraceId, infraModel.refusalTraceId)
    }

    @Test
    fun computersApiControlAndDataErrorsPreserve4xxAndScrub401And5xx() = runTest {
        val traceHeaders = mapOf("X-Trace-Id" to listOf(sampleTraceId))
        var controlResponse =
            HomeNetworkResponse(
                code = 409,
                bodyText = null,
                errorBodyText = """{"error":"Upgrade paused during deploy freeze.","code":"upgrade_frozen"}""",
                responseHeaders = traceHeaders,
            )
        val api =
            object : RaftComputersApi {
                override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unused")
                override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
                override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
                override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
                override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
                override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
                override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse = error("unused")

                override suspend fun controlComputer(
                    serverId: ServerId,
                    machineId: String,
                    action: String,
                    body: String,
                ): HomeNetworkResponse = controlResponse

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

        // HTTP 500 with a JSON code must scrub serverMessage (Tier 4).
        controlResponse =
            HomeNetworkResponse(
                code = 500,
                bodyText = null,
                errorBodyText = """{"code":"upstream_db_error","error":"SQLSTATE[08006] connection refused"}""",
                responseHeaders = traceHeaders,
            )
        val serverFaultResult = assertIs<DataResult.Error>(client.upgradeComputer(ServerId("s1"), "m1", "1.0.50"))
        assertEquals("upstream_db_error", serverFaultResult.error.code)
        assertTrue(serverFaultResult.error.retryable)
        assertNull(serverFaultResult.error.serverMessage)
        assertEquals(sampleTraceId, serverFaultResult.error.traceId)

        // HTTP 503 without a JSON code maps to machine_offline with scrubbed serverMessage.
        controlResponse =
            HomeNetworkResponse(
                code = 503,
                bodyText = null,
                errorBodyText = """{"error":"upstream 503 backend down"}""",
                responseHeaders = traceHeaders,
            )
        val offline503Result = assertIs<DataResult.Error>(client.restartComputer(ServerId("s1"), "m1"))
        assertEquals("machine_offline", offline503Result.error.code)
        assertTrue(offline503Result.error.retryable)
        assertNull(offline503Result.error.serverMessage)

        // HTTP 401 with a JSON code still routes to auth_required NoPermission (Tier 1).
        controlResponse =
            HomeNetworkResponse(
                code = 401,
                bodyText = null,
                errorBodyText = """{"code":"token_expired","error":"jwt signature invalid"}""",
                responseHeaders = traceHeaders,
            )
        val authResult = assertIs<DataResult.NoPermission>(client.upgradeComputer(ServerId("s1"), "m1", "1.0.50"))
        assertEquals("auth_required", authResult.blocker.code)
        assertEquals(sampleTraceId, authResult.blocker.traceId)

        val deleteResult = assertIs<DataResult.NoPermission>(client.deleteMachine(ServerId("s1"), "m1"))
        assertEquals("server_forbidden", deleteResult.blocker.code)
        assertEquals("owner_required", deleteResult.blocker.serverCode)
        assertEquals("Only server owners can remove computers.", deleteResult.blocker.serverMessage)
        assertEquals(sampleTraceId, deleteResult.blocker.traceId)
    }
}
