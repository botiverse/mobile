package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tenny's request rules for the Computers page's runtime usage reads, on virtual time: at most 4
 * reads in flight, no repeat inside the 60 s cache (also across page visits), no read for an
 * offline computer or one the viewer may not see, and pull-to-refresh past the cache.
 */
class RaftComputersRackUsageTest {
    private val owner = SharedComputersOwner(environmentId = "https://api.example", accountId = "user-1", sessionGeneration = "gen-1")

    @Test
    fun pullToRefreshReadsPastTheCache() = runTest {
        val api = SlowUsageApi(latencyMillis = 0L)
        val keys = raftComputersRackUsageKeys(owner, "s1", listOf(computer("m1", runtimes = listOf("claude"))))
        val visit = RaftComputersRackUsageSession(store(api))

        visit.open(keys) { _, _ -> }
        assertEquals(1, api.calls.size)
        advanceTimeBy(5_000L)
        visit.pullToRefresh(keys) { _, _ -> }
        assertEquals(2, api.calls.size)
        // The pulled read is cached again: the visit does not read it a third time.
        visit.open(keys) { _, _ -> }
        assertEquals(2, api.calls.size)
    }

    @Test
    fun frame1PeekSyncShowsWarmUsageWithoutNetworkAndFiltersByOwnerAndServer() = runTest {
        val api = SlowUsageApi(latencyMillis = 0L)
        var liveOwner: SharedComputersOwner? = owner
        val sharedStore =
            SharedRuntimeAccountUsageStore(
                client = RaftComputersApiClient(api),
                currentIdentity = { liveOwner },
                clock = RaftEpochMillisSource { testScheduler.currentTime },
                currentIdentitySync = { liveOwner },
            )
        val authComputer = computer("m1", runtimes = listOf("claude"), canView = true)
        val firstVisit = RaftComputersRackUsageSession(sharedStore)
        firstVisit.open(raftComputersRackUsageKeys(owner, "s1", listOf(authComputer))) { _, _ -> }
        assertEquals(1, api.calls.size)

        // Revisit with first-frame cached computers (permissions not yet attached -> canView = false).
        val cachedFirstFrame = listOf(computer("m1", runtimes = listOf("claude"), canView = false))
        assertTrue(raftComputersRackUsageKeys(owner, "s1", cachedFirstFrame).isEmpty(), "network reads still wait for canView")
        val peekKeys = raftComputersRackPeekUsageKeys(owner, "s1", cachedFirstFrame, includeUnpermissionedCached = true)
        assertEquals(1, peekKeys.size)

        val revisit = RaftComputersRackUsageSession(sharedStore)
        val peeked = revisit.peekSync(peekKeys, allowExpiredLoaded = true)
        assertEquals(1, api.calls.size, "Frame-1 peek must not trigger network requests")
        val loaded = assertIs<RuntimeAccountUsageLoad.Loaded>(peeked[peekKeys.single()])

        // Merging peeked + live drops Superseded and entries from another owner or server, and lets live win.
        val otherOwnerKey = peekKeys.single().copy(owner = owner.copy(accountId = "user-2"))
        val otherServerKey = peekKeys.single().copy(serverId = "s2")
        val mergedPeekOnly =
            raftComputersRackUsageLoadsForOwner(
                peeked = peeked + (otherOwnerKey to loaded) + (otherServerKey to loaded),
                live = mapOf(peekKeys.single() to RuntimeAccountUsageLoad.Superseded),
                owner = owner,
                serverId = "s1",
            )
        assertEquals(setOf("m1"), mergedPeekOnly.keys)
        assertEquals(loaded, mergedPeekOnly["m1"]?.get(RuntimeAccountUsageProvider.Claude))

        val liveOverride = RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Missing)
        val mergedWithLive =
            raftComputersRackUsageLoadsForOwner(
                peeked = peeked,
                live = mapOf(peekKeys.single() to liveOverride),
                owner = owner,
                serverId = "s1",
            )
        assertEquals(liveOverride, mergedWithLive["m1"]?.get(RuntimeAccountUsageProvider.Claude))

        // Account switch / sign-out immediately blocks Frame-1 peek.
        liveOwner = owner.copy(sessionGeneration = "gen-2")
        assertTrue(revisit.peekSync(peekKeys, allowExpiredLoaded = true).isEmpty())
    }

    private fun TestScope.store(api: SlowUsageApi) =
        SharedRuntimeAccountUsageStore(
            client = RaftComputersApiClient(api),
            currentIdentity = { owner },
            clock = RaftEpochMillisSource { testScheduler.currentTime },
        )

    private fun computer(id: String, runtimes: List<String>, online: Boolean = true, canView: Boolean = true) =
        SettingsComputerSnapshot(
            id = id,
            name = id,
            daemonVersion = "",
            online = online,
            runtimes = runtimes,
            canViewRuntimeUsage = canView,
        )

    /** Answers every usage read with a fresh snapshot after [latencyMillis] of virtual time. */
    private class SlowUsageApi(private val latencyMillis: Long) : RaftComputersApi {
        val calls = mutableListOf<String>()
        private var inFlight = 0
        var maxInFlight = 0
            private set

        override suspend fun runtimeAccountUsage(serverId: ServerId, machineId: String, provider: String): HomeNetworkResponse {
            calls += "$machineId/$provider"
            inFlight += 1
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                delay(latencyMillis)
            } finally {
                inFlight -= 1
            }
            return HomeNetworkResponse(code = 200, bodyText = freshBody(provider))
        }

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = unexpected()
        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = unexpected()
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = unexpected()
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = unexpected()
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = unexpected()
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = unexpected()
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = unexpected()
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse =
            unexpected()

        private fun unexpected(): Nothing = error("only usage reads are expected")

        private fun freshBody(provider: String) =
            """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"$provider","collectedAt":"2026-10-30T04:00:00.000Z","accounts":[{"accountKey":"acct-1","planLabel":"Max","health":"ok","windows":[{"id":"five_hour","label":"5h","status":"ok","usedRatio":0.42,"resetsAt":"2026-10-30T09:00:00.000Z"}]}]}}"""
    }
}
