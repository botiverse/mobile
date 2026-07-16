package build.raft.app.data

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RealtimeCanonicalFinalProviderLifecycleRedTest {
    @Test
    fun authAdvanceReconnectInvalidatesPublishedOldGrantWithoutReconstructingRouter() = runBlocking {
        val fixture = CanonicalFinalGatewayFixture()
        var delivered = 0
        try {
            val oldGrant = fixture.grant()
            fixture.effects.mount(
                oldGrant.scopeLeaseId,
                oldGrant.serverId,
                RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain,
            ) { delivered += 1 }
            val pending = async { fixture.owner.accept(oldGrant, canonicalEvent(), "auth-rotate") }
            fixture.awaitPublishedItem()
            assertEquals(1, fixture.integration.routerConstructionCount())

            fixture.rotateSamePrincipal()
            fixture.awaitGrantChanged(oldGrant)

            assertPublishedOldGrantTerminalized(fixture, pending) { delivered }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun exactRegistrationReplacementInvalidatesPublishedOldGrantWithoutReconstructingRouter() = runBlocking {
        val fixture = CanonicalFinalGatewayFixture()
        var delivered = 0
        try {
            val oldGrant = fixture.grant()
            fixture.effects.mount(
                oldGrant.scopeLeaseId,
                oldGrant.serverId,
                RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain,
            ) { delivered += 1 }
            val pending = async { fixture.owner.accept(oldGrant, canonicalEvent(), "replace") }
            fixture.awaitPublishedItem()
            assertEquals(1, fixture.integration.routerConstructionCount())

            fixture.provider.registerScope("shell-a", "mount-b")
            val successor = fixture.grant()
            assertNotEquals(oldGrant.scopeLeaseId, successor.scopeLeaseId)

            assertPublishedOldGrantTerminalized(fixture, pending) { delivered }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun exactReleaseInvalidatesPublishedOldGrantWithoutReconstructingRouter() = runBlocking {
        val fixture = CanonicalFinalGatewayFixture()
        var delivered = 0
        try {
            val oldGrant = fixture.grant()
            fixture.effects.mount(
                oldGrant.scopeLeaseId,
                oldGrant.serverId,
                RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain,
            ) { delivered += 1 }
            val pending = async { fixture.owner.accept(oldGrant, canonicalEvent(), "release") }
            fixture.awaitPublishedItem()
            assertEquals(1, fixture.integration.routerConstructionCount())

            assertTrue(fixture.provider.releaseScope("shell-a", "mount-a"))
            assertEquals(null, fixture.provider.authorityFlow("shell-a").value)

            assertPublishedOldGrantTerminalized(fixture, pending) { delivered }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun duplicateAndStaleLifecycleSignalsDoNotTerminalCurrentGrantItem() = runBlocking {
        val fixture = CanonicalFinalGatewayFixture()
        var delivered = 0
        try {
            val grant = fixture.grant()
            fixture.effects.mount(
                grant.scopeLeaseId,
                grant.serverId,
                RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain,
            ) { delivered += 1 }
            val pending = async { fixture.owner.accept(grant, canonicalEvent(), "no-op") }
            fixture.awaitPublishedItem()

            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(1))
            yield()
            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(0))
            yield()
            assertEquals(grant.leaseAndFamilyIdentity(), fixture.grant().leaseAndFamilyIdentity())
            assertEquals(grant.scopeLeaseId, fixture.provider.registerScope("shell-a", "mount-a").id)
            assertFalse(fixture.provider.releaseScope("shell-a", "stale-registration"))
            assertFalse(pending.isCompleted, "no-op/stale lifecycle signals must not complete the current item")

            fixture.dispatcher.drain()
            assertTrue(pending.await())
            assertEquals(1, delivered)
            val debug = fixture.sidecar.debugSnapshot()
            assertEquals(0, debug.outstandingReservations)
            assertEquals(0, debug.workerKeyCount)
            assertEquals(0, debug.publishedItemCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun lifecycleOperationsNeverConstructAnAbsentRouter() = runBlocking {
        val fixture = CanonicalFinalGatewayFixture()
        try {
            assertEquals(0, fixture.integration.routerConstructionCount())
            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(1))
            yield()
            val beforeRotation = fixture.grant()
            fixture.rotateSamePrincipal()
            fixture.awaitGrantChanged(beforeRotation)
            assertEquals(0, fixture.integration.routerConstructionCount())

            assertEquals(fixture.grant().scopeLeaseId, fixture.provider.registerScope("shell-a", "mount-a").id)
            assertEquals(0, fixture.integration.routerConstructionCount())

            fixture.provider.registerScope("shell-a", "mount-b")
            assertEquals(0, fixture.integration.routerConstructionCount())
            assertFalse(fixture.provider.releaseScope("shell-a", "mount-a"))
            assertEquals(0, fixture.integration.routerConstructionCount())
            assertTrue(fixture.provider.releaseScope("shell-a", "mount-b"))
            assertEquals(0, fixture.integration.routerConstructionCount())
        } finally {
            fixture.close()
        }
    }
}

private suspend fun CanonicalFinalGatewayFixture.awaitPublishedItem() {
    repeat(1_000) {
        if (sidecar.debugSnapshot().publishedItemCount == 1) return
        yield()
    }
    error("canonical-final owner never published its queued item")
}

private suspend fun CanonicalFinalGatewayFixture.awaitGrantChanged(previous: RealtimeGrantRecord) {
    repeat(1_000) {
        val current = runCatching(::grant).getOrNull()
        if (current != null && current != previous) return
        yield()
    }
    error("provider grant did not change after lifecycle mutation")
}

private suspend fun assertPublishedOldGrantTerminalized(
    fixture: CanonicalFinalGatewayFixture,
    pending: Deferred<Boolean>,
    delivered: () -> Int,
) {
    val debug = fixture.sidecar.debugSnapshot()
    assertEquals(1, debug.releaseCount, "provider mutation must terminal the published old-grant receipt")
    assertEquals(0, debug.outstandingReservations)
    assertEquals(0, debug.publishedItemCount)
    assertEquals(0, debug.activeWorkerCount)
    assertEquals(0, debug.workerKeyCount)
    assertFalse(pending.await())
    fixture.dispatcher.drain()
    assertEquals(0, delivered())
    assertEquals(1, fixture.integration.routerConstructionCount())
}

private fun RealtimeGrantRecord.leaseAndFamilyIdentity(): Pair<String, String> =
    scopeLeaseId to connectionFamilyId
