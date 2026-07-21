package build.raft.app

import build.raft.app.data.MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMode
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.DiagnosticFeatureFlagExpiryHandle
import build.raft.app.network.sync.DiagnosticFeatureFlagExpiryScheduler
import build.raft.app.network.sync.DIAGNOSTIC_FEATURE_FLAG_OVERRIDE_TTL_MILLIS
import build.raft.app.network.sync.ReadStateV1DiagnosticOverrideController
import kotlin.test.assertEquals
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
        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        assertTrue(decision(false))
        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        assertFalse(decision(true))
        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.Server)
        assertTrue(decision(true))
        assertFalse(decision(false))
    }

    @Test
    fun `mounted activity receives expiry revision and returns to server without another registry access`() {
        var now = 1_000L
        val scheduler = RecordingDiagnosticExpiryScheduler()
        val registry =
            DiagnosticFeatureFlagOverrideRegistry(
                nowMillis = { now },
                readStateController = ReadStateV1DiagnosticOverrideController { now },
                expiryScheduler = scheduler,
            )
        registry.configureEntitlement(true)
        assertFalse(
            resolveActivityStableOrderEnabled(
                registry,
                serverEffective = false,
                environmentId = "https://api.example",
                accountId = "account-a",
                serverId = "server-a",
            )
        )
        registry.setMode(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        val mountedRevision = registry.revisionFor(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).value
        assertTrue(
            resolveActivityStableOrderEnabled(
                registry,
                serverEffective = false,
                environmentId = "https://api.example",
                accountId = "account-a",
                serverId = "server-a",
            )
        )

        now += DIAGNOSTIC_FEATURE_FLAG_OVERRIDE_TTL_MILLIS
        scheduler.fire()

        assertTrue(registry.revisionFor(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).value > mountedRevision)
        assertEquals("expired", registry.snapshot(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY).clearReason)
        assertFalse(
            resolveActivityStableOrderEnabled(
                registry,
                serverEffective = false,
                environmentId = "https://api.example",
                accountId = "account-a",
                serverId = "server-a",
            )
        )
    }
}

private class RecordingDiagnosticExpiryScheduler : DiagnosticFeatureFlagExpiryScheduler {
    private var callback: (() -> Unit)? = null
    private var cancelled = false

    override fun schedule(
        delayMillis: Long,
        onExpired: () -> Unit,
    ): DiagnosticFeatureFlagExpiryHandle {
        assertEquals(DIAGNOSTIC_FEATURE_FLAG_OVERRIDE_TTL_MILLIS, delayMillis)
        callback = onExpired
        cancelled = false
        return DiagnosticFeatureFlagExpiryHandle { cancelled = true }
    }

    fun fire() {
        if (!cancelled) callback?.invoke()
    }
}
