package build.raft.app

import build.raft.app.data.MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMode
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.ReadStateV1DiagnosticOverrideController
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityStableOrderDiagnosticOverrideTest {
    @Test
    fun `production activity decision consumes registry final effective value`() {
        val registry =
            DiagnosticFeatureFlagOverrideRegistry(
                nowMillis = { 1_000L },
                readStateController = ReadStateV1DiagnosticOverrideController { 1_000L },
            )
        registry.configureEntitlement(true)
        val decision = {
            serverEffective: Boolean ->
            resolveActivityStableOrderEnabled(
                registry = registry,
                serverEffective = serverEffective,
                environmentId = "https://api.example/",
                accountId = "account-a",
                serverId = "server-a",
            )
        }

        assertFalse(decision(false))
        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOnExactScope)
        assertTrue(decision(false))
        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        assertFalse(decision(true))
        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.Server)
        assertTrue(decision(true))
        assertFalse(decision(false))
    }
}
