package build.raft.app.data

import build.raft.app.data.TasksQueryTestFixtures.task
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `detail=summary` row (no description, `descriptionLoaded` false) and the full fact of the same
 * revision are one fact: folding one over the other is never a canonical conflict (which would
 * take the task out of every list).
 */
class SharedTasksSummaryFactTest {
    private val full = task("t1", revision = 3).copy(description = "The whole description")
    private val summary = task("t1", revision = 3).copy(description = null, descriptionLoaded = false)

    @Test
    fun aSummaryOverTheFullFactOfItsRevisionKeepsTheDescription() {
        val store = SharedTasksDataStore()
        assertEquals(SharedTaskFactFoldOutcome.Accepted, store.upsertTask(ServerA, full))
        assertEquals(SharedTaskFactFoldOutcome.StaleIgnored, store.upsertTask(ServerA, summary))
    }

    @Test
    fun theFullFactOverASummaryOfItsRevisionIsTaken() {
        val store = SharedTasksDataStore()
        assertEquals(SharedTaskFactFoldOutcome.Accepted, store.upsertTask(ServerA, summary))
        assertEquals(SharedTaskFactFoldOutcome.Accepted, store.upsertTask(ServerA, full))
    }

    @Test
    fun aDifferentLoadedDescriptionAtOneRevisionIsStillAConflict() {
        val store = SharedTasksDataStore()
        store.upsertTask(ServerA, full)
        assertEquals(SharedTaskFactFoldOutcome.Conflict, store.upsertTask(ServerA, full.copy(description = "Another text")))
    }

    @Test
    fun aPageFoldsInOneGo() = runTest {
        val store = SharedTasksDataStore(currentServerIdProvider = { ServerA })
        val outcomes = store.upsertTasksForCurrentServer(ServerA, listOf(task("a"), task("b"), summary))
        assertEquals(List(3) { SharedTaskFactFoldOutcome.Accepted }, outcomes)
        assertEquals(null, SharedTasksDataStore(currentServerIdProvider = { "server-b" }).upsertTasksForCurrentServer(ServerA, listOf(task("a"))))
    }

    private companion object {
        const val ServerA = "server-a"
    }
}
