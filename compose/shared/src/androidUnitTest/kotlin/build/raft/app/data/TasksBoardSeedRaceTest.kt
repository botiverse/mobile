package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * proj-mobile#103: the Board seed flag is armed by EnterBoard (caller thread) and
 * consumed by the completing load (the load coroutine's thread). A plain
 * check-then-clear let both sides observe the armed flag and seed every column
 * twice. This forces that exact interleaving with latches, deterministically:
 *
 *  1. the load commits its snapshot, then parks (at `tasks_snapshot_commit`);
 *  2. EnterBoard arms the flag and sees completed content (`tasks_board_enter`),
 *     and there it waits for the parked load to finish its own seed check.
 *
 * Exactly one of the two may seed: one first-page request per column.
 */
class TasksBoardSeedRaceTest {

    private class RecordingApi : RaftTasksApi {
        val requests: MutableList<Pair<String?, String?>> = Collections.synchronizedList(mutableListOf())

        override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority) =
            HomeNetworkResponse(code = 500, bodyText = null)

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus) =
            HomeNetworkResponse(code = 500, bodyText = null)

        override suspend fun serverTasksPage(
            serverId: ServerId,
            priority: DataRequestPriority,
            detail: String?,
            limit: Int?,
            cursor: String?,
            status: String?,
        ): HomeNetworkResponse {
            requests += cursor to status
            val wireStatus = status ?: "todo"
            val row =
                """{"id":"$wireStatus-1","messageId":"m","channelId":"c1","channelName":"alpha","taskNumber":1,
                    "title":"T","status":"$wireStatus","createdByName":"x","createdAt":"2026-01-01T00:00:00Z",
                    "updatedAt":"2026-01-01T00:00:00Z","source":"tasks","hasDescription":false,"descriptionBytes":0}"""
            return HomeNetworkResponse(code = 200, bodyText = """{"tasks":[$row],"next_cursor":null}""")
        }

        fun statusRequests(): List<Pair<String?, String?>> = synchronized(requests) { requests.filter { it.second != null } }
    }

    /** No backlog task is loaded, so the Backlog column is not seeded. */
    private val boardColumns = TaskStatus.known.filter { it != TaskStatus.Backlog }.map { it.toWireValue() }

    @Test
    fun aLoadCompletingWhileTheBoardIsEnteredSeedsEachColumnOnce() {
        val api = RecordingApi()
        val loadCommitted = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val parkedLoad = AtomicBoolean(false)
        val heldEnterBoard = AtomicBoolean(false)
        val hookFailure = mutableListOf<String>()

        val logger: (String) -> Unit = { line ->
            if (line.startsWith("tasks_snapshot_commit") && parkedLoad.compareAndSet(false, true)) {
                // (1) The load's snapshot is committed; park before its seed check.
                loadCommitted.countDown()
                if (!releaseLoad.await(5, TimeUnit.SECONDS)) hookFailure += "load was never released"
            } else if (line.startsWith("tasks_board_enter seedNow=true") && heldEnterBoard.compareAndSet(false, true)) {
                // (2) EnterBoard has armed the flag; let the parked load run its check
                // and seed, then continue EnterBoard's own check.
                releaseLoad.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (api.statusRequests().size < boardColumns.size && System.nanoTime() < deadline) {
                    Thread.sleep(2)
                }
            }
        }

        val vm = SharedTasksViewModel(
            authStore = InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "u", email = "u@slock.ai"),
                    accessToken = "t", refreshToken = "r", selectedServerId = "server-1",
                )
            ),
            dataSource = SharedTasksDataSource(api = api),
            sharedTasksDataStore = SharedTasksDataStore(
                ownerIdentityProvider = { "base|u" }, currentServerIdProvider = { "server-1" },
            ),
            diagnosticsLogger = logger,
            runtimeScopeOverride = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        // The load runs on its own thread so it can park inside its window.
        val loader = thread(name = "tasks-load") { vm.dispatch(SharedTasksRouteIntent.Start) }
        assertTrue(loadCommitted.await(5, TimeUnit.SECONDS), "the load never committed its snapshot")
        assertTrue(vm.state.value.hasCompletedTasksSnapshot(), "the board must see completed content")

        vm.dispatch(SharedTasksRouteIntent.EnterBoard)
        loader.join(5_000)
        // Let any seed still in flight land before counting.
        val settle = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)
        while (System.nanoTime() < settle) Thread.sleep(10)

        assertTrue(heldEnterBoard.get(), "EnterBoard never reached its seed check with completed content")
        assertEquals(emptyList(), hookFailure)
        val seeded = api.statusRequests()
        assertEquals(boardColumns.toSet(), seeded.mapNotNull { it.second }.toSet(), "every column is seeded")
        assertTrue(seeded.all { it.first == null }, "each seed is a first page (cursor=null)")
        assertEquals(
            boardColumns.size,
            seeded.size,
            "exactly one first-page request per column, got ${seeded.map { it.second }}"
        )
    }
}
