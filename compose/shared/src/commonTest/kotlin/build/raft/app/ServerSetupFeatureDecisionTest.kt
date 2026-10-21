package build.raft.app

import build.raft.app.data.ServerFeatureFlagSnapshot
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.data.WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

class ServerSetupFeatureDecisionTest {
    @Test
    fun onlyExplicitResolvedEnablementEntersNewAdmission() {
        assertEquals(
            ServerSetupFeatureDecision.Checking,
            serverSetupFeatureDecision(ServerFeatureFlagSnapshot.Unresolved),
        )
        assertEquals(ServerSetupFeatureDecision.Bypassed, serverSetupFeatureDecision(snapshot(emptyMap())))
        assertEquals(
            ServerSetupFeatureDecision.Bypassed,
            serverSetupFeatureDecision(snapshot(mapOf(WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY to false))),
        )
        assertEquals(
            ServerSetupFeatureDecision.Enabled,
            serverSetupFeatureDecision(snapshot(mapOf(WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY to true))),
        )
    }

    @Test
    fun bypassUsesLegacyHomeWhileEnabledGateRequiresAuthoritativeAdmission() {
        val identity = ServerSetupAdmissionIdentity("env", "account", "server", "generation:1")
        assertFalse(serverSetupAllowsHome(ServerSetupFeatureDecision.Checking, null))
        assertTrue(serverSetupAllowsHome(ServerSetupFeatureDecision.Bypassed, null))
        assertFalse(
            serverSetupAllowsHome(
                ServerSetupFeatureDecision.Enabled,
                ServerSetupAdmissionState.Loading(identity),
            )
        )
        assertFalse(
            serverSetupAllowsHome(
                ServerSetupFeatureDecision.Enabled,
                ServerSetupAdmissionState.Blocked(identity, projection(blocksChat = true)),
            )
        )
        assertTrue(
            serverSetupAllowsHome(
                ServerSetupFeatureDecision.Enabled,
                ServerSetupAdmissionState.Admitted(identity, projection(blocksChat = false)),
            )
        )
    }

    @Test
    fun aServerThatPassedBeforeKeepsHomeWhileItsCheckReRuns() {
        val identity = ServerSetupAdmissionIdentity("env", "account", "server", "generation:2")
        // A server switch restarts the check: Checking / no state / Loading keep Home for a known server.
        assertTrue(serverSetupStillAllowsHome(previouslyAllowed = true, state = null))
        assertTrue(serverSetupStillAllowsHome(true, ServerSetupAdmissionState.Loading(identity)))
        // A fresh Blocked result still takes the screen; an unknown server still waits.
        assertFalse(serverSetupStillAllowsHome(true, ServerSetupAdmissionState.Blocked(identity, projection(blocksChat = true))))
        assertFalse(serverSetupStillAllowsHome(false, ServerSetupAdmissionState.Loading(identity)))
    }

    @Test
    fun admissionMemoryRecordsAllowedServersAndForgetsBlockedOnes() {
        ServerSetupAdmissionMemory.clearForTest()
        val identity = ServerSetupAdmissionIdentity("env", "account", "server", "generation:3")
        val key = ServerSetupAdmissionMemory.key("env", "account", "server")
        assertFalse(ServerSetupAdmissionMemory.contains(key))
        ServerSetupAdmissionMemory.record(key, allowsHome = true, state = null)
        assertTrue(ServerSetupAdmissionMemory.contains(key))
        // A check in flight neither adds nor forgets.
        ServerSetupAdmissionMemory.record(key, allowsHome = false, state = ServerSetupAdmissionState.Loading(identity))
        assertTrue(ServerSetupAdmissionMemory.contains(key))
        ServerSetupAdmissionMemory.record(
            key,
            allowsHome = false,
            state = ServerSetupAdmissionState.Blocked(identity, projection(blocksChat = true)),
        )
        assertFalse(ServerSetupAdmissionMemory.contains(key))
        assertFalse(ServerSetupAdmissionMemory.contains(ServerSetupAdmissionMemory.key("env", "other-account", "server")))
        ServerSetupAdmissionMemory.clearForTest()
    }

    @Test
    fun cancelledOldIdentityFetchCannotConvertIntoFailureBypass() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<HomeNetworkResponse>()
        val flags = SharedServerFeatureFlags(
            object : RaftFeatureFlagsApi {
                override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse {
                    started.complete(Unit)
                    return never.await()
                }
            }
        )

        val decision = async { resolveServerSetupFeatureDecision(flags, ServerId("old-server")) }
        started.await()
        decision.cancelAndJoin()

        assertTrue(decision.isCancelled)
        assertEquals(ServerSetupFeatureDecision.Checking, serverSetupFeatureDecision(flags.snapshotFor(ServerId("old-server"))))
    }

    private fun snapshot(values: Map<String, Boolean>) =
        ServerFeatureFlagSnapshot(ServerId("server"), resolved = true, values = values)

    private fun projection(blocksChat: Boolean) =
        build.raft.app.data.ServerSetupProjection(
            surface = if (blocksChat) "computer_runtime" else "complete",
            phase = if (blocksChat) "in_progress" else "complete",
            currentStep = null,
            blocksChat = blocksChat,
            allowedExits = emptySet(),
            gateReason = null,
        )
}
