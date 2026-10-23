package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * artin 10-23 (dots.jpg): a server switch never finished — the target kept its pending marker
 * and the header spinner kept spinning. Every way a switch can end must clear the pending
 * server and stop the spinner; the ways that don't bring the server's Home count a failure
 * (the UI shows "Could not switch servers" once, web ServerSwitcherMenu `switchFailed`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedHomeStoreServerSwitchTest {
    @Test
    fun aSwitchThatLoadsClearsPendingWithoutAFailure() = runTest {
        val h = harness()
        h.store.accept(SharedHomeIntent.SelectServer("server-2"))
        assertEquals("server-2", h.serverId())
        assertNull(h.switch().pendingServerId)
        assertEquals(0, h.switch().failureCount, h.logs.joinToString("\n"))
        assertFalse(h.refreshing())
        h.close()
    }

    @Test
    fun aSwitchWhoseLoadFailsOrThrowsStopsWaitingAndCountsAFailure() = runTest {
        listOf(Outcome.Error, Outcome.Throw).forEach { outcome ->
            val h = harness()
            h.api.outcomes["server-2"] = outcome
            h.store.accept(SharedHomeIntent.SelectServer("server-2"))
            assertNull(h.switch().pendingServerId, "$outcome: the list stops waiting")
            assertEquals("server-2", h.switch().failedServerId, "$outcome")
            assertEquals(1, h.switch().failureCount, "$outcome: one failure to show")
            assertFalse(h.refreshing(), "$outcome: no spinner left")
            h.close()
        }
    }

    @Test
    fun aSwitchWhoseResultIsDroppedStopsWaiting() = runTest {
        // The root cause: the load's result was dropped (here the auth authority moved to
        // another server while the request was on the network) and nothing ended the switch.
        val h = harness()
        val gate = h.api.gate("server-2")
        val switching = launch { h.store.accept(SharedHomeIntent.SelectServer("server-2")) }
        runCurrent()
        assertEquals("server-2", h.switch().pendingServerId)
        h.auth.updateSelectedServer(serverId = "server-1", serverSlug = "one")
        gate.complete(Unit)
        switching.join()
        assertNull(h.switch().pendingServerId)
        assertEquals(1, h.switch().failureCount)
        assertFalse(h.refreshing())
        h.close()
    }

    @Test
    fun aHungSwitchTimesOutAndALateLoadStillLands() = runTest {
        val h = harness(timeoutMillis = 1_000L)
        val gate = h.api.gate("server-2")
        val switching = launch { h.store.accept(SharedHomeIntent.SelectServer("server-2")) }
        runCurrent()
        advanceTimeBy(999L)
        runCurrent()
        assertEquals("server-2", h.switch().pendingServerId, "still within the timeout")
        advanceTimeBy(2L)
        runCurrent()
        assertNull(h.switch().pendingServerId, "the timeout ends the wait")
        assertEquals(1, h.switch().failureCount)
        assertFalse(h.refreshing())

        gate.complete(Unit)
        switching.join()
        assertEquals("server-2", h.serverId(), "the late Home still lands")
        assertNull(h.switch().pendingServerId)
        assertEquals(1, h.switch().failureCount, "and doesn't count again")
        h.close()
    }

    @Test
    fun reselectingTheServerBeingSwitchedToKeepsWaitingForItsLoad() = runTest {
        // The photo's case: the cached Home of the target shows (it is "current", with the
        // spinner) while its load is out; tapping it again is a refresh of that server.
        val h = harness()
        h.store.accept(SharedHomeIntent.SelectServer("server-2"))
        h.store.accept(SharedHomeIntent.SelectServer("server-1"))
        val gate = h.api.gate("server-2")
        val first = launch { h.store.accept(SharedHomeIntent.SelectServer("server-2")) }
        runCurrent()
        assertEquals("server-2", h.serverId(), "the cached Home shows")
        assertTrue(h.refreshing())
        assertEquals("server-2", h.switch().pendingServerId)

        h.store.accept(SharedHomeIntent.SelectServer("server-2"))
        assertNull(h.switch().pendingServerId, "the re-select's own load brought the server")
        assertFalse(h.refreshing())

        gate.complete(Unit)
        first.join()
        assertNull(h.switch().pendingServerId)
        assertEquals(0, h.switch().failureCount, "the superseded first load fails nothing")
        h.close()
    }

    @Test
    fun switchingAgainMidSwitchWaitsForTheNewTargetOnly() = runTest {
        val h = harness()
        val gateTwo = h.api.gate("server-2")
        val first = launch { h.store.accept(SharedHomeIntent.SelectServer("server-2")) }
        runCurrent()
        val gateThree = h.api.gate("server-3")
        val second = launch { h.store.accept(SharedHomeIntent.SelectServer("server-3")) }
        runCurrent()
        assertEquals("server-3", h.switch().pendingServerId)

        gateTwo.complete(Unit)
        first.join()
        assertEquals("server-3", h.switch().pendingServerId, "the superseded load neither lands nor fails the new switch")
        assertEquals(0, h.switch().failureCount, h.logs.joinToString("\n"))

        gateThree.complete(Unit)
        second.join()
        assertEquals("server-3", h.serverId())
        assertNull(h.switch().pendingServerId)
        assertEquals(0, h.switch().failureCount, h.logs.joinToString("\n"))
        h.close()
    }

    @Test
    fun switchStateFailIsANoOpWithoutAPendingSwitch() {
        val idle = HomeServerSwitchState()
        assertEquals(idle, idle.fail())
        val failed = idle.select("server-2").fail()
        assertNull(failed.pendingServerId)
        assertEquals("server-2", failed.selectedServerId, "the selection stays for a late load")
        assertEquals(1, failed.failureCount)
        assertTrue(failed.isCurrent(failed.requestGeneration, ServerId("server-2")))
    }

    private enum class Outcome { Content, Error, Throw }

    /** Home API per server: answers, fails, throws, or waits on a gate. */
    private class SwitchHomeApi : RaftHomeApi {
        val outcomes = mutableMapOf<String, Outcome>()
        private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

        fun gate(serverId: String): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates[serverId] = it }

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse = RootEmptyHomeApi.channels(priority)

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
            RootEmptyHomeApi.directMessages(priority)

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
            RootEmptyHomeApi.channelUnreadCounts(priority)

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
            RootEmptyHomeApi.serverUnreadSummary(priority)

        override suspend fun channels(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            gates.remove(serverId.value)?.await()
            if (outcomes[serverId.value] == Outcome.Throw) throw IllegalStateException("server unreachable")
            return RootEmptyHomeApi.channels(priority)
        }

        override suspend fun directMessages(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            RootEmptyHomeApi.directMessages(priority)

        override suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            RootEmptyHomeApi.channelUnreadCounts(priority)

        override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            RootEmptyHomeApi.serverUnreadSummary(priority)
    }

    private class Harness(
        val scope: CoroutineScope,
        val store: SharedHomeStore,
        val auth: InMemoryRaftAuthSessionStore,
        val api: SwitchHomeApi,
        val logs: List<String>,
    ) {
        fun switch(): HomeServerSwitchState = store.state.value.serverSwitchState

        fun serverId(): String? = (store.state.value.content as? SharedHomeContentState.Content)?.snapshot?.server?.id?.value

        fun refreshing(): Boolean = (store.state.value.content as? SharedHomeContentState.Content)?.contentRefreshing == true

        fun close() = scope.cancel()
    }

    // Virtual time jumps whenever the test waits on an idle scheduler, so only the timeout case
    // runs with a reachable timeout; the others are about how loads end.
    private suspend fun TestScope.harness(timeoutMillis: Long = 3_600_000L): Harness {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val servers = authServerFixtures() + StoredAuthServer(id = "server-3", name = "Three", slug = "three")
        val auth = InMemoryRaftAuthSessionStore(authSessionFixture(servers = servers))
        val api = SwitchHomeApi()
        val logs = mutableListOf<String>()
        val store =
            SharedHomeStore(
                authStore = auth,
                dataSource =
                    SharedHomeDataSource(
                        sessionStore = auth,
                        api = api,
                        parseHome = { payload, _ ->
                            if (api.outcomes[payload.serverId.value] == Outcome.Error) {
                                DataResult.Error(ScreenError(code = "home_network", retryable = true))
                            } else {
                                DataResult.Content(home(payload.serverId))
                            }
                        }
                    ),
                scope = scope,
                diagnosticsLogger = { line -> if ("switch" in line || "drop" in line) logs += line },
                serverSwitchTimeoutMillis = timeoutMillis,
            )
        store.accept(SharedHomeIntent.Start)
        return Harness(scope, store, auth, api, logs)
    }

    private fun home(serverId: ServerId): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(serverId, serverId.value),
            currentMember =
                MemberProfile(
                    id = MemberId("user-1"),
                    displayName = "Me",
                    handle = "user-1@example.com",
                    kind = MemberKind.Human,
                    presence = Presence.Active
                ),
            channels = emptyList(),
            directMessages = emptyList(),
            unreadTotal = 0
        )
}
