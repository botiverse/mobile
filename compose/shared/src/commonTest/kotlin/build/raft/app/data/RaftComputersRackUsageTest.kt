package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tenny's request rules for the Computers page's runtime usage reads, on virtual time: at most 4
 * reads in flight, no repeat inside the 60 s cache (also across page visits), no read for an
 * offline computer or one the viewer may not see, and pull-to-refresh past the cache.
 */
class RaftComputersRackUsageTest {
    private val owner = SharedComputersOwner(environmentId = "https://api.example", accountId = "user-1", sessionGeneration = "gen-1")

    @Test
    fun neverMoreThanFourReadsInFlight() = runTest {
        val api = SlowUsageApi(latencyMillis = 1_000L)
        val store = store(api)
        val keys = raftComputersRackUsageKeys(owner, "s1", List(10) { computer("m$it", runtimes = listOf("claude")) })
        assertEquals(10, keys.size)

        RaftComputersRackUsageSession(store).open(keys) { _, _ -> }

        assertEquals(10, api.calls.size)
        assertEquals(4, api.maxInFlight)
        // 10 reads, 4 at a time, 1 s each: three rounds.
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test
    fun noRepeatReadWithinSixtySecondsEvenAcrossPageVisits() = runTest {
        val api = SlowUsageApi(latencyMillis = 0L)
        val store = store(api)
        val keys = raftComputersRackUsageKeys(owner, "s1", listOf(computer("m1", runtimes = listOf("claude", "codex"))))
        val visit = RaftComputersRackUsageSession(store)

        visit.open(keys) { _, _ -> }
        assertEquals(2, api.calls.size)
        // The same visit sees the list again (a realtime update): nothing is read again.
        visit.open(keys) { _, _ -> }
        assertEquals(2, api.calls.size)

        advanceTimeBy(RUNTIME_ACCOUNT_USAGE_CACHE_MILLIS - 1)
        // The page opened again inside 60 s: the store's cache answers.
        val results = mutableListOf<RuntimeAccountUsageLoad>()
        RaftComputersRackUsageSession(store).open(keys) { _, load -> results += load }
        assertEquals(2, api.calls.size)
        assertEquals(2, results.size)
        assertTrue(results.all { it is RuntimeAccountUsageLoad.Loaded })

        advanceTimeBy(1)
        RaftComputersRackUsageSession(store).open(keys) { _, _ -> }
        assertEquals(4, api.calls.size)
    }

    @Test
    fun offlineAndNoPermissionComputersAreNotRead() = runTest {
        val api = SlowUsageApi(latencyMillis = 0L)
        val computers =
            listOf(
                computer("m-ok", runtimes = listOf("gemini", "kimi-sdk", "codex", "claude", "kimi")),
                computer("m-offline", runtimes = listOf("claude"), online = false),
                computer("m-denied", runtimes = listOf("claude"), canView = false),
                computer("m-none", runtimes = listOf("gemini")),
            )
        val keys = raftComputersRackUsageKeys(owner, "s1", computers)
        assertEquals(
            listOf(RuntimeAccountUsageProvider.Claude, RuntimeAccountUsageProvider.Codex, RuntimeAccountUsageProvider.Kimi),
            keys.map { it.provider },
        )
        assertTrue(keys.all { it.machineId == "m-ok" })

        RaftComputersRackUsageSession(store(api)).open(keys) { _, _ -> }
        assertEquals(
            listOf("m-ok/claude", "m-ok/codex", "m-ok/kimi"),
            api.calls.sorted(),
        )
        // No owner or no server yet: nothing to read.
        assertEquals(emptyList(), raftComputersRackUsageKeys(null, "s1", computers))
        assertEquals(emptyList(), raftComputersRackUsageKeys(owner, " ", computers))
    }

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
