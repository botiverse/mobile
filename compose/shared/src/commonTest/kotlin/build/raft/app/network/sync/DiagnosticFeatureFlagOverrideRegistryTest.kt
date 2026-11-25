package build.raft.app.network.sync

import build.raft.app.data.MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY
import build.raft.app.data.MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY
import build.raft.app.data.MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY
import build.raft.app.data.SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiagnosticFeatureFlagOverrideRegistryTest {
    private var now = 1_000L
    private val readStateController = ReadStateV1DiagnosticOverrideController { now }
    private val registry = DiagnosticFeatureFlagOverrideRegistry({ now }, readStateController)
    private val channelScope =
        DiagnosticFeatureFlagScope("https://api.example", "account-a", "server-a", "channel-a")
    private val serverScope = channelScope.copy(channelId = null)

    @Test
    fun `release entitlement rejects registered consumers`() {
        registry.observe(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, channelScope)
        registry.observe(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, serverScope)
        registry.observe(MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY, serverScope)
        registry.observe(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, serverScope)

        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Rejected>(
            registry.setMode(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        )
        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Rejected>(
            registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        )
        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Rejected>(
            registry.setMode(MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        )
        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Rejected>(
            registry.setMode(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        )
    }

    @Test
    fun webContainerV1LocalOverrideTogglesWhenEntitled() {
        registry.configureEntitlement(true)
        assertTrue(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY in registry.supportedKeys)

        assertFalse(registry.decide(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, false, serverScope).finalEffective)

        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Applied>(
            registry.setMode(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        )
        assertTrue(registry.decide(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, false, serverScope).finalEffective)

        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Applied>(
            registry.setMode(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        )
        assertFalse(registry.decide(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, true, serverScope).finalEffective)

        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Applied>(
            registry.setMode(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.Server)
        )
        assertTrue(registry.decide(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, true, serverScope).finalEffective)
        assertFalse(registry.decide(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, false, serverScope).finalEffective)
    }

    @Test
    fun screenshotFeedbackLocalOverrideTogglesWhenEntitled() {
        registry.configureEntitlement(true)
        assertTrue(MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY in registry.supportedKeys)

        assertFalse(registry.decide(MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY, false, serverScope).finalEffective)

        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Applied>(
            registry.setMode(MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        )
        assertTrue(registry.decide(MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY, false, serverScope).finalEffective)

        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Applied>(
            registry.setMode(MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        )
        assertFalse(registry.decide(MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY, true, serverScope).finalEffective)

        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Applied>(
            registry.setMode(MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.Server)
        )
        assertTrue(registry.decide(MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY, true, serverScope).finalEffective)
        assertFalse(registry.decide(MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY, false, serverScope).finalEffective)
    }
}
