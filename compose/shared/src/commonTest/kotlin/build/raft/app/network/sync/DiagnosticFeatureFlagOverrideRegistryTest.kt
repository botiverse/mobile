package build.raft.app.network.sync

import build.raft.app.data.MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY
import build.raft.app.data.SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticFeatureFlagOverrideRegistryTest {
    private var now = 1_000L
    private val readStateController = ReadStateV1DiagnosticOverrideController { now }
    private val registry = DiagnosticFeatureFlagOverrideRegistry({ now }, readStateController)
    private val channelScope =
        DiagnosticFeatureFlagScope("https://api.example", "account-a", "server-a", "channel-a")
    private val serverScope = channelScope.copy(channelId = null)

    @Test
    fun `initial allowlist exposes typed channel and server consumers`() {
        assertEquals(
            listOf(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY).sorted(),
            registry.supportedKeys,
        )
        assertEquals(
            DiagnosticFeatureFlagScopeKind.ChannelExact,
            registry.snapshot(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY).scopeKind,
        )
        assertEquals(
            DiagnosticFeatureFlagScopeKind.ServerExact,
            registry.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).scopeKind,
        )
    }

    @Test
    fun `release entitlement rejects both registered consumers`() {
        registry.observe(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, channelScope)
        registry.observe(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, serverScope)

        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Rejected>(
            registry.setMode(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOnExactScope)
        )
        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Rejected>(
            registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        )
    }

    @Test
    fun `activity force modes are exact server scoped and server mode preserves server result`() {
        registry.configureEntitlement(true)
        registry.observe(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, serverScope)
        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Applied>(
            registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        )

        assertFalse(registry.decide(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, true, serverScope).finalEffective)
        val wrongServer = serverScope.copy(serverId = "server-b")
        val wrongScopeDecision =
            registry.decide(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, true, wrongServer)
        assertTrue(wrongScopeDecision.finalEffective)
        assertEquals("server", wrongScopeDecision.precedence)
        assertEquals("identity_changed", registry.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).clearReason)

        registry.observe(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, serverScope)
        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOnExactScope)
        assertTrue(registry.decide(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, false, serverScope).finalEffective)

        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.Server)
        assertTrue(registry.decide(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, true, serverScope).finalEffective)
        assertFalse(registry.decide(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, false, serverScope).finalEffective)
    }

    @Test
    fun `per key ttl and mode changes do not clear the other consumer`() {
        registry.configureEntitlement(true)
        registry.observe(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, serverScope)
        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOnExactScope)
        now += 100L
        registry.observe(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, channelScope)
        registry.setMode(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOnExactScope)

        now = 1_000L + DIAGNOSTIC_FEATURE_FLAG_OVERRIDE_TTL_MILLIS

        assertFalse(registry.decide(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, false, serverScope).finalEffective)
        assertEquals("expired", registry.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).clearReason)
        assertTrue(registry.decide(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, false, channelScope).finalEffective)

        registry.setMode(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.Server)
        assertEquals(DiagnosticFeatureFlagOverrideMode.Server, registry.snapshot(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY).mode)
        assertEquals("expired", registry.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).clearReason)
    }

    @Test
    fun `activity identity transition clears only its exact binding`() {
        registry.configureEntitlement(true)
        registry.observe(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, serverScope)
        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOnExactScope)
        registry.observe(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, channelScope)
        registry.setMode(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOnExactScope)

        registry.decide(
            MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY,
            false,
            serverScope.copy(accountId = "account-b"),
        )

        val activity = registry.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY)
        assertEquals(DiagnosticFeatureFlagOverrideMode.Server, activity.mode)
        assertNull(activity.boundScope)
        assertEquals("identity_changed", activity.clearReason)
        assertEquals(
            DiagnosticFeatureFlagOverrideMode.ForceOnExactScope,
            registry.snapshot(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY).mode,
        )
    }

    @Test
    fun `valid unknown key is visible as unsupported no effect and rejects mutation`() {
        val key = "future_registered_flag_v1"
        val result = registry.setMode(key, DiagnosticFeatureFlagOverrideMode.ForceOnExactScope)

        val rejected = assertIs<DiagnosticFeatureFlagOverrideMutationResult.Rejected>(result)
        assertEquals("diagnostic_feature_flag_consumer_unsupported", rejected.code)
        assertFalse(rejected.snapshot.supported)
        assertEquals("unsupported_no_effect", rejected.snapshot.clearReason)
        assertEquals(listOf(key), registry.snapshots().filterNot { it.supported }.map { it.key })
        assertFalse(registry.decide(key, false, serverScope).finalEffective)
    }

    @Test
    fun `new registry represents process restart without inherited overrides`() {
        registry.configureEntitlement(true)
        registry.observe(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, serverScope)
        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOnExactScope)

        val restarted = DiagnosticFeatureFlagOverrideRegistry({ now }, ReadStateV1DiagnosticOverrideController { now })

        assertEquals(DiagnosticFeatureFlagOverrideMode.Server, restarted.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).mode)
        assertEquals("process_start", restarted.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).clearReason)
    }
}
