package build.raft.app.network.sync

import build.raft.app.data.MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY
import build.raft.app.data.MOBILE_CURL_SOCKETIO_FEATURE_FLAG_KEY
import build.raft.app.data.MOBILE_DOH_FALLBACK_FEATURE_FLAG_KEY
import build.raft.app.data.SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY
import build.raft.app.data.SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY
import build.raft.app.data.MOBILE_THREAD_REVERSE_LAYOUT_FEATURE_FLAG_KEY
import build.raft.app.data.THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY
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
            listOf(
                MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY,
                MOBILE_CURL_SOCKETIO_FEATURE_FLAG_KEY,
                MOBILE_DOH_FALLBACK_FEATURE_FLAG_KEY,
                SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY,
                SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY,
                THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY,
                MOBILE_THREAD_REVERSE_LAYOUT_FEATURE_FLAG_KEY,
            ).sorted(),
            registry.supportedKeys,
        )
        assertEquals(
            DiagnosticFeatureFlagScopeKind.ServerExact,
            registry.snapshot(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY).scopeKind,
        )
        assertEquals(
            DiagnosticFeatureFlagScopeKind.ChannelExact,
            registry.snapshot(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY).scopeKind,
        )
        assertEquals(
            DiagnosticFeatureFlagScopeKind.ServerExact,
            registry.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).scopeKind,
        )
        assertEquals(
            DiagnosticFeatureFlagScopeKind.ServerExact,
            registry.snapshot(THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY).scopeKind,
        )
    }

    @Test
    fun `sync core messages override is bidirectional and entitlement gated`() {
        // Not entitled (release): mutation rejected, decision follows the server value.
        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Rejected>(
            registry.setMode(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        )
        assertFalse(registry.decide(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY, false, serverScope).finalEffective)

        registry.configureEntitlement(true)
        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Applied>(
            registry.setMode(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        )
        val forcedOn = registry.decide(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY, false, serverScope)
        assertTrue(forcedOn.finalEffective)
        assertFalse(forcedOn.serverEffective) // the server value stays visible next to the override
        assertEquals("diagnostic_override_force_on", forcedOn.precedence)

        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Applied>(
            registry.setMode(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        )
        val forcedOff = registry.decide(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY, true, serverScope)
        assertFalse(forcedOff.finalEffective)
        assertTrue(forcedOff.serverEffective)

        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Applied>(
            registry.setMode(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.Server)
        )
        assertTrue(registry.decide(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY, true, serverScope).finalEffective)
        assertFalse(registry.decide(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY, false, serverScope).finalEffective)

        // Process-wide gates (fold lane / shadow observers) decide without a server id: the override still applies.
        registry.setMode(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        val processWide = DiagnosticFeatureFlagScope("https://api.example", "account-a", "")
        assertTrue(registry.decide(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY, false, processWide).finalEffective)
    }

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

    @Test
    fun `activity force modes apply across the process before observation`() {
        registry.configureEntitlement(true)
        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Applied>(
            registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        )

        assertFalse(registry.decide(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, true, serverScope).finalEffective)
        val wrongServer = serverScope.copy(serverId = "server-b")
        val otherServerDecision =
            registry.decide(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, true, wrongServer)
        assertFalse(otherServerDecision.finalEffective)
        assertEquals("diagnostic_override_force_off", otherServerDecision.precedence)

        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        assertTrue(registry.decide(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, false, serverScope).finalEffective)
        assertTrue(registry.decide(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, false, wrongServer).finalEffective)

        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.Server)
        assertTrue(registry.decide(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, true, serverScope).finalEffective)
        assertFalse(registry.decide(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, false, serverScope).finalEffective)
    }

    @Test
    fun `activity mode mutation immediately recomputes the cached final decision`() {
        registry.configureEntitlement(true)
        registry.decide(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, false, serverScope)

        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)

        val forcedOn = requireNotNull(registry.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).lastDecision)
        assertTrue(forcedOn.finalEffective)
        assertEquals(DiagnosticFeatureFlagOverrideMode.ForceOn, forcedOn.clientOverride)
        assertEquals("diagnostic_override_force_on", forcedOn.precedence)

        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)

        val forcedOff = requireNotNull(registry.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).lastDecision)
        assertFalse(forcedOff.finalEffective)
        assertEquals(DiagnosticFeatureFlagOverrideMode.ForceOff, forcedOff.clientOverride)
        assertEquals("diagnostic_override_force_off", forcedOff.precedence)
    }

    @Test
    fun `per key ttl and mode changes do not clear the other consumer`() {
        registry.configureEntitlement(true)
        registry.observe(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, serverScope)
        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        now += 100L
        registry.observe(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, channelScope)
        registry.setMode(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)

        now = 1_000L + DIAGNOSTIC_FEATURE_FLAG_OVERRIDE_TTL_MILLIS

        assertFalse(registry.decide(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, false, serverScope).finalEffective)
        assertEquals("expired", registry.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).clearReason)
        assertTrue(registry.decide(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, false, channelScope).finalEffective)

        registry.setMode(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.Server)
        assertEquals(DiagnosticFeatureFlagOverrideMode.Server, registry.snapshot(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY).mode)
        assertEquals("expired", registry.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).clearReason)
    }

    @Test
    fun `read state mutation does not publish an activity presentation revision`() {
        registry.configureEntitlement(true)
        registry.observe(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, channelScope)
        val activityRevision = registry.revisionFor(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).value

        registry.setMode(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)

        assertEquals(
            activityRevision,
            registry.revisionFor(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).value,
        )
        assertTrue(registry.revisionFor(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY).value > 0L)
    }

    @Test
    fun `identity transitions preserve independent process overrides`() {
        registry.configureEntitlement(true)
        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        registry.setMode(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)

        registry.decide(
            MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY,
            false,
            serverScope.copy(accountId = "account-b"),
        )

        val activity = registry.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY)
        assertEquals(DiagnosticFeatureFlagOverrideMode.ForceOn, activity.mode)
        assertNull(activity.boundScope)
        assertEquals(null, activity.clearReason)
        assertEquals(
            DiagnosticFeatureFlagOverrideMode.ForceOn,
            registry.snapshot(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY).mode,
        )
    }

    @Test
    fun `auth observation clear preserves independent modes and expiry while refreshing both keys`() {
        registry.configureEntitlement(true)
        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        now += 100L
        registry.setMode(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        registry.decide(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, true, serverScope)
        registry.decide(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, false, channelScope)
        readStateController.recordSelection(
            owner = ReadStateV1ProductionOwner("https://api.example", "account-a", "server-a"),
            channelId = "channel-a",
            generation = 7L,
        )
        readStateController.recordPublication(homeStoreCount = 1, activityStoreCount = 1, generation = 7L)
        val activityBefore = registry.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY)
        val readStateBefore = registry.snapshot(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY)
        val activityRevision = registry.revisionFor(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).value
        val readStateRevision = registry.revisionFor(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY).value

        registry.clearObservations()

        val activityAfter = registry.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY)
        val readStateAfter = registry.snapshot(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY)
        assertEquals(DiagnosticFeatureFlagOverrideMode.ForceOff, activityAfter.mode)
        assertEquals(activityBefore.expiresAtMillis, activityAfter.expiresAtMillis)
        assertNull(activityAfter.observedScope)
        assertNull(activityAfter.boundScope)
        assertNull(activityAfter.lastDecision)
        assertEquals(DiagnosticFeatureFlagOverrideMode.ForceOn, readStateAfter.mode)
        assertEquals(readStateBefore.expiresAtMillis, readStateAfter.expiresAtMillis)
        assertNull(readStateAfter.observedScope)
        assertNull(readStateAfter.boundScope)
        assertNull(readStateAfter.lastDecision)
        assertNull(readStateAfter.evidence.selectedScope)
        assertNull(readStateAfter.evidence.witnessGeneration)
        assertNull(readStateAfter.evidence.destinationPublication)
        assertTrue(registry.revisionFor(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).value > activityRevision)
        assertTrue(registry.revisionFor(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY).value > readStateRevision)
        assertFalse(
            registry.decide(
                MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY,
                serverEffective = true,
                scope = serverScope.copy(accountId = "account-b", serverId = "server-b"),
            ).finalEffective,
        )
        assertTrue(
            registry.decide(
                SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY,
                serverEffective = false,
                scope = channelScope.copy(accountId = "account-b", serverId = "server-b", channelId = "channel-b"),
            ).finalEffective,
        )

        now = requireNotNull(activityAfter.expiresAtMillis)
        assertTrue(registry.decide(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, true, serverScope).finalEffective)
        assertEquals("expired", registry.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).clearReason)
        assertEquals(DiagnosticFeatureFlagOverrideMode.ForceOn, registry.snapshot(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY).mode)
    }

    @Test
    fun `valid unknown key is visible as unsupported no effect and rejects mutation`() {
        val key = "future_registered_flag_v1"
        val result = registry.setMode(key, DiagnosticFeatureFlagOverrideMode.ForceOn)

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
        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)

        val restarted = DiagnosticFeatureFlagOverrideRegistry({ now }, ReadStateV1DiagnosticOverrideController { now })

        assertEquals(DiagnosticFeatureFlagOverrideMode.Server, restarted.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).mode)
        assertEquals("process_start", restarted.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).clearReason)
    }

    @Test
    fun `replaced expiry callback cannot clear same millisecond successor and early fire reschedules`() {
        val scheduler = CapturingDiagnosticExpiryScheduler()
        val isolated =
            DiagnosticFeatureFlagOverrideRegistry(
                nowMillis = { now },
                readStateController = ReadStateV1DiagnosticOverrideController { now },
                expiryScheduler = scheduler,
            )
        isolated.configureEntitlement(true)
        isolated.observe(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, serverScope)
        isolated.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        isolated.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)

        scheduler.fire(index = 0, evenIfCancelled = true)
        assertEquals(
            DiagnosticFeatureFlagOverrideMode.ForceOff,
            isolated.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).mode,
        )

        now += 100L
        scheduler.fire(index = 1, evenIfCancelled = true)
        assertEquals(
            DiagnosticFeatureFlagOverrideMode.ForceOff,
            isolated.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).mode,
        )
        assertEquals(DIAGNOSTIC_FEATURE_FLAG_OVERRIDE_TTL_MILLIS - 100L, scheduler.entries.last().delayMillis)

        now = 1_000L + DIAGNOSTIC_FEATURE_FLAG_OVERRIDE_TTL_MILLIS
        scheduler.fire(index = 2)
        assertEquals(
            DiagnosticFeatureFlagOverrideMode.Server,
            isolated.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).mode,
        )
        assertEquals("expired", isolated.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).clearReason)
    }
}

private class CapturingDiagnosticExpiryScheduler : DiagnosticFeatureFlagExpiryScheduler {
    data class Entry(
        val delayMillis: Long,
        val callback: () -> Unit,
        var cancelled: Boolean = false,
    )

    val entries = mutableListOf<Entry>()

    override fun schedule(
        delayMillis: Long,
        onExpired: () -> Unit,
    ): DiagnosticFeatureFlagExpiryHandle {
        val entry = Entry(delayMillis, onExpired)
        entries += entry
        return DiagnosticFeatureFlagExpiryHandle { entry.cancelled = true }
    }

    fun fire(
        index: Int,
        evenIfCancelled: Boolean = false,
    ) {
        val entry = entries[index]
        if (evenIfCancelled || !entry.cancelled) entry.callback()
    }
}
