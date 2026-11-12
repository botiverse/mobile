package build.raft.app.data

import build.raft.app.SharedThreadFreshnessLedger
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Hands 7e198cab: Activity invalidated every thread generation of its server on any refresh-required
 * event, so on a busy server (agents and machines changing all the time) each thread fetch in flight
 * was superseded and fetched again. Only a Home refresh (catch-up, horizon reset) can mean missed
 * messages.
 */
class SharedActivityStoreThreadFreshnessTest {
    @Test
    fun onlyAHomeRefreshSupersedesThreadFetchesInFlight() = runTest {
        val cases =
            listOf(
                RealtimeRefreshScope.Home to true,
                RealtimeRefreshScope.Agents to false,
                RealtimeRefreshScope.Machines to false,
                RealtimeRefreshScope.ServerMembers to false,
                RealtimeRefreshScope.ChannelMembers to false,
                RealtimeRefreshScope.Settings to false,
            )
        for ((refreshScope, supersedes) in cases) {
            val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            val ledger = SharedThreadFreshnessLedger()
            val store = activityStore(scope = scope, replyThreadFreshnessLedger = ledger)
            store.dispatch(SharedActivityIntent.LoadInitial)
            val inFlight = ledger.capture("server-1", "task-conv-1", "owner-1")

            store.dispatch(
                SharedActivityIntent.ApplyRealtimeEvent(RealtimeRefreshRequired(scope = refreshScope, reason = "test"))
            )

            assertEquals(!supersedes, ledger.isCurrent(inFlight), "scope=$refreshScope")
            if (supersedes) {
                assertEquals(
                    "activity_refresh_required",
                    ledger.capture("server-1", "task-conv-1", "owner-1").cause,
                    "the new generation names what moved it",
                )
            }
            scope.cancel()
        }
    }
}
