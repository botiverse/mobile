package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ServerId
import build.raft.app.model.ServerNotificationPreferencesChanged
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Process-level refresh of the viewer's workspace notification preference on `notification_prefs:updated`
 * (server mode), pinned to botiverse/slock@eeb40c571c6fcda19e075735699c2641ee309418
 * routes/servers.ts `emitServerNotificationPrefsUpdated` (emitted to `user:<id>`).
 */
class ServerNotificationPreferencesRealtimeRefresherTest {
    private fun event(serverId: String, version: Long = 2) =
        ServerNotificationPreferencesChanged(
            serverId = ServerId(serverId),
            serverPushMode = ServerPushMode.Mentions.wireName,
            prefsVersion = version,
        )

    private fun TestScope.refresher(
        calls: MutableList<String>,
        block: suspend () -> Unit = {},
    ) = ServerNotificationPreferencesRealtimeRefresher(
        // The test scope itself (not backgroundScope), so advanceUntilIdle drives the refetches.
        scope = this,
        refresh = { principalId, serverId ->
            calls += "$principalId/$serverId"
            block()
        },
    )

    @Test
    fun eventRefetchesThatServerForTheGrantsAccount() = runTest {
        val calls = mutableListOf<String>()
        val refresher = refresher(calls)

        refresher.onEvent("user-1", "server-1", event("server-1"))
        advanceUntilIdle()

        assertEquals(listOf("user-1/server-1"), calls)
    }

    @Test
    fun eventWhosePayloadNamesAnotherServerIsIgnored() = runTest {
        val calls = mutableListOf<String>()
        val refresher = refresher(calls)

        refresher.onEvent("user-1", "server-1", event("server-2"))
        refresher.onEvent("user-1", "server-1", event(" "))
        advanceUntilIdle()

        assertEquals(emptyList(), calls)
    }

    @Test
    fun burstOfEventsRefetchesOnce() = runTest {
        val calls = mutableListOf<String>()
        val refresher = refresher(calls)

        repeat(5) { version -> refresher.onEvent("user-1", "server-1", event("server-1", version.toLong())) }
        advanceUntilIdle()

        assertEquals(listOf("user-1/server-1"), calls)
    }

    @Test
    fun eventsDuringAnInFlightRefetchCoalesceIntoOneFollowUp() = runTest {
        val calls = mutableListOf<String>()
        var gate = CompletableDeferred<Unit>()
        val refresher = refresher(calls) { gate.await() }

        refresher.onEvent("user-1", "server-1", event("server-1", 1))
        runCurrent()
        assertEquals(1, calls.size)

        // Three more changes land while the first GET is in flight: one follow-up covers them all.
        repeat(3) { refresher.onEvent("user-1", "server-1", event("server-1", 2L + it)) }
        runCurrent()
        assertEquals(1, calls.size)
        val first = gate
        gate = CompletableDeferred()
        first.complete(Unit)
        runCurrent()
        assertEquals(2, calls.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("user-1/server-1", "user-1/server-1"), calls)

        // Idle again: the next event starts a fresh refetch.
        refresher.onEvent("user-1", "server-1", event("server-1", 9))
        advanceUntilIdle()
        assertEquals(3, calls.size)
    }

    @Test
    fun differentServersRefreshIndependently() = runTest {
        val calls = mutableListOf<String>()
        val refresher = refresher(calls) { delay(1_000) }

        refresher.onEvent("user-1", "server-1", event("server-1"))
        refresher.onEvent("user-1", "server-2", event("server-2"))
        runCurrent()
        // server-2's in-flight GET does not swallow a new server-1 change: server-1 gets its follow-up.
        refresher.onEvent("user-1", "server-1", event("server-1", 3))
        advanceUntilIdle()

        assertEquals(listOf("user-1/server-1", "user-1/server-2", "user-1/server-1"), calls)
    }

    @Test
    fun failedRefetchDoesNotWedgeTheServer() = runTest {
        val calls = mutableListOf<String>()
        var fail = true
        val refresher =
            refresher(calls) {
                if (fail) {
                    fail = false
                    error("offline")
                }
            }

        refresher.onEvent("user-1", "server-1", event("server-1"))
        advanceUntilIdle()
        refresher.onEvent("user-1", "server-1", event("server-1", 3))
        advanceUntilIdle()

        assertEquals(2, calls.size)
    }

    @Test
    fun processOwnerRoutesTheMappedWireEventToTheRefresherWithNoPageOpen() = runTest {
        val calls = mutableListOf<String>()
        val refresher = refresher(calls)
        val owner = SharedRealtimeDomainEventOwner(runIfCurrentGrant = { _, block -> block(); true })
        owner.installServerNotificationPreferencesSink(refresher::onEvent)
        // The exact server payload (servers.ts serializeServerNotificationPrefs + prefsVersion).
        val mapped =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.NotificationPrefsUpdated,
                """{"serverId":"server-1","scopeId":"server-1",""" +
                    """"prefs":{"serverPushMuted":false,"serverPushMentionsOnly":true,"serverPushMode":"mentions"},""" +
                    """"prefsVersion":4}""",
            )
        assertIs<ServerNotificationPreferencesChanged>(mapped)

        owner.acceptProcessMutation(grant("server-1", "user-1"), mapped)
        // A frame accepted for another server's socket never refreshes server-1.
        owner.acceptProcessMutation(grant("server-2", "user-1"), mapped)
        advanceUntilIdle()

        assertEquals(listOf("user-1/server-1"), calls)
    }

    private fun grant(serverId: String, principalId: String) =
        RealtimeGrantRecord(
            slotKey = RealtimeSlotKey("process-realtime-host", serverId),
            principalId = principalId,
            environmentId = "env-test",
            authGeneration = 1L,
            connectionFamilyId = "family-1",
            connectionEpochId = "epoch-1",
        )
}
