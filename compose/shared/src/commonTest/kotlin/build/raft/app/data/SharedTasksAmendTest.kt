package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The task channel's store writes a title / description edit (slock #14114, `PATCH /tasks/:id`)
 * and folds the saved task into its list, so the head and every list showing the task follow; and
 * deletes a task (slock #14115, `DELETE /tasks/:id`), which then leaves the list.
 */
class SharedTasksAmendTest {
    private val authStore =
        InMemoryRaftAuthSessionStore(
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                accessToken = "token",
                refreshToken = "refresh",
                selectedServerId = "server-1",
            )
        )

    private val listed =
        TaskSummary(
            id = TaskId("task-9"),
            title = "Old title",
            status = TaskStatus.Todo,
            taskNumber = 9,
            channelId = ChannelId("channel-1"),
            description = "Why it matters",
            revision = 7L,
            updatedAtLabel = "now",
        )

    @Test
    fun aSavedEditReplacesTheTaskInTheList() = runTest {
        val api =
            TaskAmendFixtures.RecordingAmendApi {
                HomeNetworkResponse(code = 200, bodyText = TaskAmendFixtures.taskBody(title = "New title", revision = 8L))
            }
        val viewModel = viewModel(api, this)

        val result = viewModel.amendTask(listed, TaskAmendPatch.Title("New title"), expectedRevision = 7L)

        assertEquals("New title", assertIs<TaskAmendResult.Saved>(result).task.title)
        val shown = viewModel.state.value.currentTaskSummary("task-9")
        assertEquals("New title", shown?.title)
        assertEquals(8L, shown?.revision)
        assertEquals(7L, Json.parseToJsonElement(api.calls.single().body).jsonObject["expectedRevision"]?.jsonPrimitive?.long)
    }

    @Test
    fun aRefusalLeavesTheTaskAndCarriesTheSentenceAndTraceId() = runTest {
        val traceId = "fedcba9876543210fedcba9876543210"
        val api =
            TaskAmendFixtures.RecordingAmendApi {
                HomeNetworkResponse(
                    code = 403,
                    bodyText = null,
                    errorBodyText = """{"error":"You must join this channel to modify tasks"}""",
                    responseHeaders = mapOf("x-trace-id" to listOf(traceId)),
                )
            }
        val viewModel = viewModel(api, this)

        val result = viewModel.amendTask(listed, TaskAmendPatch.Description("Rewritten"), expectedRevision = 7L)

        val failed = assertIs<TaskAmendResult.Failed>(result)
        assertEquals("You must join this channel to modify tasks", failed.error.serverMessage)
        assertEquals(traceId, failed.error.traceId)
        val shown = viewModel.state.value.currentTaskSummary("task-9")
        assertEquals("Why it matters", shown?.description)
        assertEquals(7L, shown?.revision)
    }

    @Test
    fun aLostRaceIsStaleAndLeavesTheList() = runTest {
        val api =
            TaskAmendFixtures.RecordingAmendApi {
                HomeNetworkResponse(code = 409, bodyText = null, errorBodyText = """{"error":"task state changed concurrently","currentRevision":9}""")
            }
        val viewModel = viewModel(api, this)

        val result = viewModel.amendTask(listed, TaskAmendPatch.Title("Mine"), expectedRevision = 7L)

        assertEquals(TaskAmendResult.Stale(9L), result)
        assertEquals("Old title", viewModel.state.value.currentTaskSummary("task-9")?.title)
    }

    @Test
    fun aDeletedTaskLeavesTheList() = runTest {
        val api = TaskAmendFixtures.RecordingAmendApi(deleteAnswer = { HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""") }) { error("no edit") }
        val viewModel = viewModel(api, this)

        assertNull(viewModel.deleteTask(listed))

        assertEquals(listOf(TaskId("task-9")), api.deleted)
        assertNull(viewModel.state.value.currentTaskSummary("task-9"))
    }

    @Test
    fun aRefusedDeleteKeepsTheTaskAndSaysWhy() = runTest {
        val api =
            TaskAmendFixtures.RecordingAmendApi(
                deleteAnswer = {
                    HomeNetworkResponse(code = 403, bodyText = null, errorBodyText = """{"error":"Only the task creator or server admins can delete"}""")
                },
            ) { error("no edit") }
        val viewModel = viewModel(api, this)

        val refusal = viewModel.deleteTask(listed)

        assertEquals("Only the task creator or server admins can delete", refusal?.serverMessage)
        assertEquals("Old title", viewModel.state.value.currentTaskSummary("task-9")?.title)
    }

    private fun viewModel(api: RaftTasksApi, scope: kotlinx.coroutines.test.TestScope): SharedTasksViewModel =
        SharedTasksViewModel(
            authStore = authStore,
            dataSource = SharedTasksDataSource(api),
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            runtimeScopeOverride = scope.backgroundScope,
        ).also { viewModel ->
            viewModel.dispatch(
                SharedTasksRouteIntent.SeedContent(TaskSnapshot(tasks = listOf(listed), summaryCounts = mapOf(TaskStatus.Todo to 1)))
            )
        }
}
