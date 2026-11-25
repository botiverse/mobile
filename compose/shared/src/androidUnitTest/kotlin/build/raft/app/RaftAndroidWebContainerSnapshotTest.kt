@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package build.raft.app

import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.data.ServerFeatureFlagLastKnownBinding
import build.raft.app.data.ServerFeatureFlagLastKnownStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.model.ServerId
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMode
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.ReadStateV1DiagnosticOverrideController
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RaftAndroidWebContainerSnapshotTest {
    private fun fakeApi(responseByServer: Map<String, HomeNetworkResponse>): RaftFeatureFlagsApi =
        object : RaftFeatureFlagsApi {
            override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                responseByServer[serverId.value] ?: HomeNetworkResponse(code = 500, bodyText = null)
        }

    @Test
    fun resolvesDiagnosticOverridesLiveServerLastKnownPerServerAndPreRegistryColdStart() = runTest {
        val now = 1_000L
        val registry = DiagnosticFeatureFlagOverrideRegistry({ now }, ReadStateV1DiagnosticOverrideController { now })
        val disk = InMemoryRaftKeyValueStore()
        val binding = ServerFeatureFlagLastKnownBinding("https://api.raft.build", "user-1")
        val serverA = ServerId("server-a")
        val serverB = ServerId("server-b")

        fun newFlags(
            api: RaftFeatureFlagsApi,
            currentBinding: () -> ServerFeatureFlagLastKnownBinding? = { binding },
        ): SharedServerFeatureFlags =
            SharedServerFeatureFlags(
                api = api,
                lastKnown = ServerFeatureFlagLastKnownStore(store = disk, currentBinding = currentBinding),
            )

        // 1. Fresh install with empty disk defaults OFF.
        assertFalse(
            resolveRaftAndroidWebContainerEnabledSnapshot(
                serverFeatureFlags = null,
                registry = registry,
                serverId = "",
                preferenceStoreProvider = { disk },
            ),
        )

        // 2. Online session enables flag on serverA and explicitly disables on serverB.
        val onlineFlags =
            newFlags(
                fakeApi(
                    mapOf(
                        "server-a" to
                            HomeNetworkResponse(
                                code = 200,
                                bodyText = """{"evaluations":[{"key":"$MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY","enabled":true}]}""",
                            ),
                        "server-b" to
                            HomeNetworkResponse(
                                code = 200,
                                bodyText = """{"evaluations":[{"key":"$MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY","enabled":false}]}""",
                            ),
                    ),
                ),
            )
        onlineFlags.prefetch(serverA)
        onlineFlags.prefetch(serverB)

        // Live resolved snapshots are authoritative.
        assertTrue(
            resolveRaftAndroidWebContainerEnabledSnapshot(
                serverFeatureFlags = onlineFlags,
                registry = registry,
                serverId = "server-a",
                preferenceStoreProvider = { disk },
            ),
        )
        assertFalse(
            resolveRaftAndroidWebContainerEnabledSnapshot(
                serverFeatureFlags = onlineFlags,
                registry = registry,
                serverId = "server-b",
                preferenceStoreProvider = { disk },
            ),
        )

        // 3. Cold start BEFORE RaftSharedStoreRegistry initializes (serverFeatureFlags == null):
        //    pre-auth-store first frame sees true (last signed-in account had flag ON on serverA);
        //    explicit serverB lookup still respects persisted false.
        assertTrue(
            resolveRaftAndroidWebContainerEnabledSnapshot(
                serverFeatureFlags = null,
                registry = registry,
                serverId = "",
                preferenceStoreProvider = { disk },
            ),
        )
        assertFalse(
            resolveRaftAndroidWebContainerEnabledSnapshot(
                serverFeatureFlags = null,
                registry = registry,
                serverId = "server-b",
                preferenceStoreProvider = { disk },
            ),
        )

        // 4. Cold start AFTER RaftSharedStoreRegistry exists (unresolved online, bound lastKnown):
        //    explicit per-server false on serverB MUST NOT fall through to serverA's true.
        val coldBoundFlags = newFlags(fakeApi(emptyMap()))
        assertTrue(
            resolveRaftAndroidWebContainerEnabledSnapshot(
                serverFeatureFlags = coldBoundFlags,
                registry = registry,
                serverId = "server-a",
                preferenceStoreProvider = { disk },
            ),
        )
        assertFalse(
            resolveRaftAndroidWebContainerEnabledSnapshot(
                serverFeatureFlags = coldBoundFlags,
                registry = registry,
                serverId = "server-b",
                preferenceStoreProvider = { disk },
            ),
        )

        // 5. Entitled diagnostic overrides (ForceOff / ForceOn / Server) take precedence cleanly.
        registry.configureEntitlement(true)
        registry.setMode(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        assertFalse(
            resolveRaftAndroidWebContainerEnabledSnapshot(
                serverFeatureFlags = onlineFlags,
                registry = registry,
                serverId = "server-a",
                preferenceStoreProvider = { disk },
            ),
        )
        registry.setMode(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        assertTrue(
            resolveRaftAndroidWebContainerEnabledSnapshot(
                serverFeatureFlags = onlineFlags,
                registry = registry,
                serverId = "server-b",
                preferenceStoreProvider = { disk },
            ),
        )
        registry.setMode(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.Server)

        // 6. Signed-out or switched account/environment never inherits another account's disk entry
        //    even before async clearAll() runs.
        val unboundFlags = newFlags(fakeApi(emptyMap()), currentBinding = { null })
        assertFalse(
            resolveRaftAndroidWebContainerEnabledSnapshot(
                serverFeatureFlags = unboundFlags,
                registry = registry,
                serverId = "",
                signedOut = true,
                preferenceStoreProvider = { disk },
            ),
        )
        assertFalse(
            resolveRaftAndroidWebContainerEnabledSnapshot(
                serverFeatureFlags = unboundFlags,
                registry = registry,
                serverId = "server-a",
                environmentId = "https://api.raft.build",
                accountId = "user-2",
                preferenceStoreProvider = { disk },
            ),
        )
        assertFalse(
            resolveRaftAndroidWebContainerEnabledSnapshot(
                serverFeatureFlags = unboundFlags,
                registry = registry,
                serverId = "server-a",
                environmentId = "https://staging.raft.build",
                accountId = "user-1",
                preferenceStoreProvider = { disk },
            ),
        )

        // 7. Sign-out clears ServerFeatureFlagLastKnownStore so the next cold start defaults OFF.
        ServerFeatureFlagLastKnownStore(store = disk, currentBinding = { binding }).clearAll()
        assertFalse(
            resolveRaftAndroidWebContainerEnabledSnapshot(
                serverFeatureFlags = null,
                registry = registry,
                serverId = "",
                preferenceStoreProvider = { disk },
            ),
        )
    }
}
