package build.raft.app.network.sync

import build.raft.app.data.MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY
import build.raft.app.data.SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY
import kotlin.test.Test
import kotlin.test.assertIs

class DiagnosticFeatureFlagOverrideRegistryTest {
    private var now = 1_000L
    private val readStateController = ReadStateV1DiagnosticOverrideController { now }
    private val registry = DiagnosticFeatureFlagOverrideRegistry({ now }, readStateController)
    private val channelScope =
        DiagnosticFeatureFlagScope("https://api.example", "account-a", "server-a", "channel-a")
    private val serverScope = channelScope.copy(channelId = null)

    @Test
    fun `release entitlement rejects both registered consumers`() {
        registry.observe(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, channelScope)
        registry.observe(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, serverScope)

        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Rejected>(
            registry.setMode(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        )
        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Rejected>(
            registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        )
    }
}
