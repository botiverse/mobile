package build.raft.app.network

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.RaftKeyValueStore
import com.tencent.kmm.network.export.VBTransportAndroidEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_ENVIRONMENT = "production|https://api.raft.build/api"
private const val OTHER_ENVIRONMENT = "staging|https://api-staging.raft.build/api"

class AndroidNetworkKmmRecoveryTest {
    @Test
    fun productionPolicyEnablesReviewedFiveShardRecoveryForEveryBuild() {
        val policy = raftAndroidNetworkKmmRecoveryPolicy()

        assertTrue(policy.enabled)
        assertEquals(5, policy.clientShardCount)
        assertEquals(7_000L, policy.responseHeadersWatchdogMillis)
        assertEquals(2, policy.minimumConcurrentStalledRequests)
        assertEquals(3_000L, policy.pingIntervalMillis)
    }

    @Test
    fun installerPublishesTheExactProcessWidePolicy() = withRestoredPolicy {
        val diagnostics = install(InMemoryRaftKeyValueStore())
        val environmentHash = TEST_ENVIRONMENT.hashCode().toUInt().toString(16)

        assertEquals(
            raftAndroidNetworkKmmRecoveryPolicy(),
            VBTransportAndroidEngine.reusedHttp2Recovery
        )
        assertEquals(
            "networkkmm_reused_h2_recovery scope=all_android_builds enabled=true " +
                "clientShardCount=5 responseHeadersWatchdogMs=7000 " +
                "minimumConcurrentStalledRequests=2 pingIntervalMs=3000 " +
                "environmentKey=production environmentHash=$environmentHash " +
                "remoteKillLatchRead=success remoteKillLatched=false " +
                "persistedKillEnvironmentCount=0",
            diagnostics
        )
    }

    @Test
    fun persistedEnvironmentKillDisablesStartupAndClearsAfterAuthoritativeNonKill() =
        withRestoredPolicy {
            val store =
                InMemoryRaftKeyValueStore(
                    mapOf("disabledEnvironmentIds.v1" to TEST_ENVIRONMENT)
                )

            val startup = install(store)

            assertTrue(startup.contains("enabled=false"))
            assertTrue(startup.contains("remoteKillLatched=true"))
            assertEquals(false, VBTransportAndroidEngine.reusedHttp2Recovery.enabled)

            val remoteUpdate = apply(1L, serverId = "server-a", enabled = true, reason = "default")

            assertTrue(remoteUpdate.orEmpty().contains("remoteKill=false"))
            assertTrue(remoteUpdate.orEmpty().contains("recoveryEnabled=true"))
            assertEquals(true, VBTransportAndroidEngine.reusedHttp2Recovery.enabled)
            assertNull(store.getString("disabledEnvironmentIds.v1"))
        }

    @Test
    fun missingFlagDoesNotAlterAnExistingEnvironmentKillLatch() = withRestoredPolicy {
        val store = InMemoryRaftKeyValueStore()
        install(store)

        apply(1L, serverId = "server-a", enabled = false, reason = "kill_switch")
        val ignoredMissing =
            apply(2L, serverId = "server-b", enabled = false, reason = "missing_flag")

        assertEquals(false, VBTransportAndroidEngine.reusedHttp2Recovery.enabled)
        assertEquals(TEST_ENVIRONMENT, store.getString("disabledEnvironmentIds.v1"))
        assertTrue(ignoredMissing.orEmpty().contains("applied=false"))
        assertTrue(ignoredMissing.orEmpty().contains("ignoredReason=missing_flag"))
    }

    @Test
    fun anyAuthoritativeNonKillServerClearsTheEnvironmentGlobalKill() = withRestoredPolicy {
        val store = InMemoryRaftKeyValueStore()
        install(store)

        apply(1L, serverId = "server-a", enabled = false, reason = "kill_switch")
        val cleared = apply(2L, serverId = "server-b", enabled = true, reason = "default")

        assertTrue(cleared.orEmpty().contains("remoteKill=false"))
        assertEquals(true, VBTransportAndroidEngine.reusedHttp2Recovery.enabled)
        assertNull(store.getString("disabledEnvironmentIds.v1"))
    }

    @Test
    fun ordinaryDisabledEvaluationClearsRatherThanCreatingAnEmergencyKillLatch() =
        withRestoredPolicy {
            val store = InMemoryRaftKeyValueStore()
            install(store)
            apply(1L, serverId = "server-a", enabled = false, reason = "kill_switch")

            val remoteUpdate =
                apply(2L, serverId = "server-a", enabled = false, reason = "default")

            assertTrue(remoteUpdate.orEmpty().contains("remoteKill=false"))
            assertEquals(true, VBTransportAndroidEngine.reusedHttp2Recovery.enabled)
            assertNull(store.getString("disabledEnvironmentIds.v1"))
        }

    @Test
    fun persistedKillsAreScopedToTheCurrentApiEnvironment() = withRestoredPolicy {
        val store = InMemoryRaftKeyValueStore()
        install(store)
        apply(1L, serverId = "server-a", enabled = false, reason = "kill_switch")

        RaftNetworkKmmRecoveryRemoteFlagBridge.updateCurrentEnvironment(OTHER_ENVIRONMENT)

        assertEquals(true, VBTransportAndroidEngine.reusedHttp2Recovery.enabled)
        assertEquals(TEST_ENVIRONMENT, store.getString("disabledEnvironmentIds.v1"))

        RaftNetworkKmmRecoveryRemoteFlagBridge.updateCurrentEnvironment(TEST_ENVIRONMENT)

        assertEquals(false, VBTransportAndroidEngine.reusedHttp2Recovery.enabled)
    }

    @Test
    fun inactiveLateServerEvaluationCannotMutateTheGlobalLatch() = withRestoredPolicy {
        val store = InMemoryRaftKeyValueStore()
        install(store)
        RaftNetworkKmmRecoveryRemoteFlagBridge.updateActiveSelection("shell-1", "server-a")
        apply(1L, serverId = "server-a", enabled = true, reason = "default")

        RaftNetworkKmmRecoveryRemoteFlagBridge.updateActiveSelection("shell-1", "server-b")
        val ignoredLateKill =
            apply(2L, serverId = "server-a", enabled = false, reason = "kill_switch")

        assertTrue(ignoredLateKill.orEmpty().contains("ignoredReason=server_not_active"))
        assertEquals(true, VBTransportAndroidEngine.reusedHttp2Recovery.enabled)
        assertNull(store.getString("disabledEnvironmentIds.v1"))

        RaftNetworkKmmRecoveryRemoteFlagBridge.updateActiveSelection("shell-2", "server-a")
        apply(3L, serverId = "server-a", enabled = false, reason = "kill_switch")

        assertEquals(false, VBTransportAndroidEngine.reusedHttp2Recovery.enabled)

        RaftNetworkKmmRecoveryRemoteFlagBridge.updateActiveSelection("shell-2", null)
        val ignoredInactiveClear =
            apply(4L, serverId = "server-a", enabled = true, reason = "default")

        assertTrue(ignoredInactiveClear.orEmpty().contains("ignoredReason=server_not_active"))
        assertEquals(false, VBTransportAndroidEngine.reusedHttp2Recovery.enabled)
    }

    @Test
    fun initialNullSelectionDoesNotChangeAPersistedEnvironmentKillPolicy() = withRestoredPolicy {
        val store =
            InMemoryRaftKeyValueStore(
                mapOf("disabledEnvironmentIds.v1" to TEST_ENVIRONMENT)
            )
        install(store)

        val update =
            RaftNetworkKmmRecoveryRemoteFlagBridge.updateActiveSelection("shell-unselected", null)

        assertNull(update)
        assertEquals(false, VBTransportAndroidEngine.reusedHttp2Recovery.enabled)
    }

    @Test
    fun olderGlobalEvaluationCannotOverrideANewerAuthoritativeResult() = withRestoredPolicy {
        val store = InMemoryRaftKeyValueStore()
        install(store)

        apply(2L, serverId = "server-b", enabled = true, reason = "default")
        val stale = apply(1L, serverId = "server-a", enabled = false, reason = "kill_switch")

        assertTrue(stale.orEmpty().contains("ignoredReason=stale_generation"))
        assertEquals(true, VBTransportAndroidEngine.reusedHttp2Recovery.enabled)
        assertNull(store.getString("disabledEnvironmentIds.v1"))
    }

    @Test
    fun newerMissingFlagDoesNotSuppressAnOlderAuthoritativeKill() = withRestoredPolicy {
        val store = InMemoryRaftKeyValueStore()
        install(store)

        apply(2L, serverId = "server-b", enabled = false, reason = "missing_flag")
        val authoritativeKill =
            apply(1L, serverId = "server-a", enabled = false, reason = "kill_switch")

        assertTrue(authoritativeKill.orEmpty().contains("applied=true"))
        assertEquals(false, VBTransportAndroidEngine.reusedHttp2Recovery.enabled)
        assertEquals(TEST_ENVIRONMENT, store.getString("disabledEnvironmentIds.v1"))
    }

    @Test
    fun persistenceDiagnosticRequiresAWriteReadBackMatchAndRetriesOnce() = withRestoredPolicy {
        var writes = 0
        val store =
            object : RaftKeyValueStore {
                override fun getString(key: String): String? = null

                override fun putString(key: String, value: String?) {
                    writes += 1
                }
            }
        install(store)

        val remoteUpdate = apply(1L, serverId = "server-a", enabled = false, reason = "kill_switch")

        assertTrue(remoteUpdate.orEmpty().contains("persisted=false"))
        assertEquals(2, writes)
        assertEquals(false, VBTransportAndroidEngine.reusedHttp2Recovery.enabled)
    }

    @Test
    fun startupReadFailureIsVisibleAndFallsBackToTheReviewedDefaultOnPolicy() =
        withRestoredPolicy {
            val failingStore =
                object : RaftKeyValueStore {
                    override fun getString(key: String): String? = error("read failed")

                    override fun putString(key: String, value: String?) = Unit
                }

            val diagnostics = install(failingStore)

            assertTrue(diagnostics.contains("remoteKillLatchRead=failed"))
            assertTrue(diagnostics.contains("enabled=true"))
            assertEquals(true, VBTransportAndroidEngine.reusedHttp2Recovery.enabled)
        }

    @Test
    fun apiEnvironmentReadFailureStillInstallsDefaultOnPolicyAndRemoteBridge() =
        withRestoredPolicy {
            val store = InMemoryRaftKeyValueStore()

            val diagnostics =
                installAndroidNetworkKmmReusedHttp2RecoveryAtStartup(
                    store = store,
                    currentEnvironment = { error("environment read failed") }
                )

            assertTrue(diagnostics.contains("enabled=true"))
            assertTrue(diagnostics.contains("apiEnvironmentRead=failed"))
            assertTrue(diagnostics.contains("apiEnvironmentFallback=production"))
            assertTrue(diagnostics.contains("apiEnvironmentReadErrorType=IllegalStateException"))
            assertEquals(true, VBTransportAndroidEngine.reusedHttp2Recovery.enabled)

            val remoteKill =
                apply(
                    generation = 1L,
                    serverId = "server-a",
                    enabled = false,
                    reason = "kill_switch"
                )

            assertTrue(remoteKill.orEmpty().contains("applied=true"))
            assertEquals(false, VBTransportAndroidEngine.reusedHttp2Recovery.enabled)
            assertEquals(TEST_ENVIRONMENT, store.getString("disabledEnvironmentIds.v1"))
        }

    @Test
    fun environmentIdentityIncludesCustomApiBaseUrlAndNormalizesTrailingSlash() {
        assertEquals(
            "custom|HTTPS://EXAMPLE.TEST/API",
            raftNetworkKmmRecoveryEnvironmentId(" Custom ", " HTTPS://EXAMPLE.TEST/API/ ")
        )
    }

    @Test
    fun customEnvironmentDiagnosticsNeverExposeTheRawUrlOrCredentials() = withRestoredPolicy {
        val environmentId =
            raftNetworkKmmRecoveryEnvironmentId(
                "custom",
                "https://user:password@example.test/API?api_key=secret"
            )

        val diagnostics =
            installAndroidNetworkKmmReusedHttp2Recovery(
                InMemoryRaftKeyValueStore(),
                environmentId
            )

        assertTrue(diagnostics.contains("environmentKey=custom"))
        assertTrue(diagnostics.contains("environmentHash="))
        assertFalse(diagnostics.contains("user"))
        assertFalse(diagnostics.contains("password"))
        assertFalse(diagnostics.contains("api_key"))
        assertFalse(diagnostics.contains("secret"))
        assertFalse(diagnostics.contains("example.test"))
    }

    private fun install(store: RaftKeyValueStore): String =
        installAndroidNetworkKmmReusedHttp2Recovery(store, TEST_ENVIRONMENT)

    private fun apply(
        generation: Long,
        serverId: String,
        enabled: Boolean,
        reason: String?,
        environmentId: String = TEST_ENVIRONMENT
    ): String? =
        RaftNetworkKmmRecoveryRemoteFlagBridge.apply(
            environmentId = environmentId,
            evaluationGeneration = generation,
            serverId = serverId,
            enabled = enabled,
            reason = reason
        )

    private inline fun withRestoredPolicy(block: () -> Unit) {
        val original = VBTransportAndroidEngine.reusedHttp2Recovery
        try {
            block()
        } finally {
            resetAndroidNetworkKmmRecoveryForTests()
            VBTransportAndroidEngine.reusedHttp2Recovery = original
        }
    }
}
