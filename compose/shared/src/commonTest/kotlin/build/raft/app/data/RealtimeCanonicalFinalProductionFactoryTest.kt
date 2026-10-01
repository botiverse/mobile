package build.raft.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RealtimeCanonicalFinalProductionFactoryTest {
    @Test
    fun providerFactoryDefaultsCanonicalFinalIntegrationToNull() = runBlocking {
        val auth = FakeCanonicalFinalAuthStore()
        val provider =
            createSharedRealtimeAuthorityProvider(
            onAuthRevisionAdvanced = {},
                registry =
                    RealtimeAuthorityRegistry(
                        authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemorySlockKeyValueStore()),
                        processInstanceId = "factory-default-null",
                    ),
                authStore = auth,
                authEvents = auth,
                currentEnvironmentId = { "env-test" },
            )
        assertNull(provider.canonicalFinalRegistrationDirectoryOrNull())
        assertNull(provider.canonicalFinalOwnerGatewayOrNull())
        assertNull(provider.canonicalFinalLifecycleEffectsOrNull())
    }

    @Test
    fun providerFactoryConfiguredInjectionExposesOneGatewayAndLazyRouter() {
        val fixture = CanonicalFinalGatewayFixture(initialSnapshot = snapshot(true, true), lane = lane { true })
        try {
            assertNotNull(fixture.provider.canonicalFinalRegistrationDirectoryOrNull())
            assertSame(
                fixture.provider.canonicalFinalOwnerGatewayOrNull(),
                fixture.provider.canonicalFinalOwnerGatewayOrNull(),
            )
            assertNotNull(fixture.provider.canonicalFinalLifecycleEffectsOrNull())
            assertEquals(0, fixture.integration.routerConstructionCount())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun ownerFactoryWithDefaultProviderInstallsOldLaneAndKeepsLegacy() = runBlocking {
        var checks = 0
        var legacy = 0
        val fixture = CanonicalFinalGatewayFixture(configured = false, lane = lane { checks += 1; true })
        try {
            fixture.owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(applyHomeShell = { _, _ -> legacy += 1 }),
            )
            assertTrue(fixture.owner.accept(fixture.grant(), canonicalEvent(), "factory-old"))
            assertEquals(listOf(1, 1), listOf(checks, legacy))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun legacyAndShellOwnerFactoriesShareConfiguredProviderGateway() = runBlocking {
        var final = 0
        val fixture = CanonicalFinalGatewayFixture(initialSnapshot = snapshot(true, true), lane = lane { true })
        try {
            val secondOwner =
                createSharedRealtimeDomainEventOwner(
                    tasksDataStore = null,
                    cursorStore = null,
                    authorityProvider = fixture.provider,
                    currentGrantMessageShadowCommitLane = lane("m2") { true },
                )
            fixture.effects.mount(
                fixture.grant().scopeLeaseId,
                "server-a",
                RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain,
            ) { final += 1 }
            val accepted =
                coroutineScope {
                    val first = async { fixture.owner.accept(fixture.grant(), canonicalEvent("m1"), "legacy-factory") }
                    val second = async { secondOwner.accept(fixture.grant(), canonicalEvent("m2"), "shell-factory") }
                    yield()
                    fixture.dispatcher.drain()
                    listOf(first.await(), second.await())
                }
            assertEquals(listOf(true, true), accepted)
            assertEquals(2, final)
            assertEquals(1, fixture.integration.routerConstructionCount())
        } finally {
            fixture.close()
        }
    }
}
