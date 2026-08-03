package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RealtimeCanonicalFinalSinkLifecycleEffectsTest {
    @Test
    fun appShellDomainMountAndDisposeUseExactProviderDirectory() {
        val fixture = CanonicalFinalGatewayFixture()
        try {
            val grant = fixture.grant()
            val handle = assertNotNull(
                fixture.effects.mount(
                    grant.scopeLeaseId,
                    grant.serverId,
                    RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain,
                ) {},
            )
            assertEquals(1, fixture.directory.registrationCount())
            assertTrue(fixture.effects.dispose(handle))
            assertEquals(0, fixture.directory.registrationCount())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun appShellUnreadMountAndDisposeUseIndependentSlot() {
        val fixture = CanonicalFinalGatewayFixture()
        try {
            val grant = fixture.grant()
            val domain = assertNotNull(
                fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain) {},
            )
            val unread = assertNotNull(
                fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread) {},
            )
            assertEquals(2, fixture.directory.registrationCount())
            assertTrue(fixture.effects.dispose(unread))
            assertEquals(listOf(domain), fixture.directory.capture(grant.scopeLeaseId, grant.serverId).map { it.handle })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun threadCoreAndFeatureMountAsIndependentRouteSlots() {
        val fixture = CanonicalFinalGatewayFixture()
        try {
            val grant = fixture.grant()
            val core = assertNotNull(
                fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.ThreadCore("route-a")) {},
            )
            val feature = assertNotNull(
                fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature("route-a")) {},
            )
            assertEquals(listOf(core, feature), fixture.directory.capture(grant.scopeLeaseId, grant.serverId).map { it.handle })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun connectionFamilyAndEpochChurnKeepLifecycleHandlesStable() {
        val fixture = CanonicalFinalGatewayFixture()
        try {
            val grant = fixture.grant()
            val handles =
                listOf(
                    assertNotNull(
                        fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain) {},
                    ),
                    assertNotNull(
                        fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread) {},
                    ),
                    assertNotNull(
                        fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.ThreadCore("route-a")) {},
                    ),
                    assertNotNull(
                        fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature("route-a")) {},
                    ),
                )
            val reconnect = grant.copy(authGeneration = grant.authGeneration + 1, connectionFamilyId = "family-b", connectionEpochId = "epoch-b")
            assertEquals(
                listOf(handles[2], handles[3], handles[0], handles[1]),
                fixture.directory.capture(reconnect.scopeLeaseId, reconnect.serverId).map { it.handle },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun replacementHandleMakesOldDisposeUnableToDeleteSuccessor() {
        val fixture = CanonicalFinalGatewayFixture()
        try {
            val grant = fixture.grant()
            val old = assertNotNull(
                fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain) {},
            )
            val replacement = assertNotNull(
                fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain) {},
            )
            assertFalse(fixture.effects.dispose(old))
            assertEquals(listOf(replacement), fixture.directory.capture(grant.scopeLeaseId, grant.serverId).map { it.handle })
            assertTrue(fixture.effects.dispose(replacement))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun oldDisposeCannotDeleteAReplacementThatLegallyRemountedAgain() {
        val fixture = CanonicalFinalGatewayFixture()
        try {
            val grant = fixture.grant()
            val old = assertNotNull(
                fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature("route-a")) {},
            )
            fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature("route-a")) {}
            val remounted = assertNotNull(
                fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature("route-a")) {},
            )

            assertFalse(fixture.effects.dispose(old))
            assertEquals(listOf(remounted), fixture.directory.capture(grant.scopeLeaseId, grant.serverId).map { it.handle })
            assertTrue(fixture.effects.dispose(remounted))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun selectedRankAndBackgroundIsolationUseTheSameProductionBinder() {
        val fixture = CanonicalFinalGatewayFixture()
        try {
            val grant = fixture.grant()
            val domain = assertNotNull(
                fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain) {},
            )
            val unread = assertNotNull(
                fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread) {},
            )
            val feature = assertNotNull(
                fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature("route-b")) {},
            )
            val core = assertNotNull(
                fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.ThreadCore("route-a")) {},
            )
            val selected = fixture.integration.captureForOwnerEntry(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalOwnerEntry.Selected)
            val background = fixture.integration.captureForOwnerEntry(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalOwnerEntry.BackgroundUnread)
            val process = fixture.integration.captureForOwnerEntry(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalOwnerEntry.Process)
            assertEquals(listOf(core, feature, domain), selected.map { it.handle })
            assertEquals(listOf(unread), background.map { it.handle })
            assertTrue(process.isEmpty())
        } finally {
            fixture.close()
        }
    }
}
