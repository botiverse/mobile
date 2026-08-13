package build.raft.app

import build.raft.app.data.ServerFeatureFlagSnapshot
import build.raft.app.data.WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
