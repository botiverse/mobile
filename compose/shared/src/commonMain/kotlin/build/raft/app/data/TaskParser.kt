package build.raft.app.data

import build.raft.app.strictProjectionDiagnosticsLogger
import build.raft.app.model.TaskSummary
import kotlinx.serialization.json.Json

/**
 * Strict parser for the `/api/tasks` endpoints.
 *
 * Canonical wire contracts (see `packages/server/src/routes/tasks.ts` and
 * `services/taskService.ts`):
 *
 * - `GET  /api/tasks/server`                  → `{ tasks: TaskInfo[] }`
 * - `GET  /api/tasks/channel/:channelId`      → `{ tasks: TaskInfo[] }`
 * - `POST /api/tasks/channel/:channelId`      → `{ tasks: TaskInfo[] }`
 * - `PATCH /api/tasks/:taskId/claim`          → `{ task: TaskInfo }`
 * - `PATCH /api/tasks/:taskId/status`         → `{ task: TaskInfo }`
 * - `POST /api/tasks/convert-message`         → `{ task: TaskInfo }`
 *
 * `TaskInfo` (packages/shared/src/index.ts) is the *only* accepted shape;
 * no field aliases, no nested `data` wrappers. See [TaskWireDto] for the
 * decoded fields.
 */

private val taskParserJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

data class SharedTasksPayload(val tasks: List<TaskSummary>)

fun parseSharedServerTasksPayload(
    body: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): SharedTasksPayload? {
    val envelope = runCatching { taskParserJson.decodeFromString<TasksListEnvelope>(body) }.getOrNull()
        ?: return null
    return SharedTasksPayload(tasks = envelope.tasks.mapNotNull { it.toTaskSummary(diagnostics) })
}

fun parseSharedTaskActionPayload(
    body: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): TaskSummary? {
    val envelope = runCatching { taskParserJson.decodeFromString<TaskEnvelope>(body) }.getOrNull()
        ?: return null
    return envelope.task?.toTaskSummary(diagnostics)
}

/**
 * Parses a paginated `GET /api/tasks/server` response (task #345). Absent JSON
 * `next_cursor` is treated as end-of-pages (`null`). Unknown keys are ignored
 * for forward compatibility, but the summary contract itself is locked by
 * tests.
 */
fun parseSharedServerTasksPagePayload(
    body: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): SharedServerTasksPage? {
    val envelope = runCatching { taskParserJson.decodeFromString<TasksPageEnvelope>(body) }.getOrNull()
        ?: return null
    return SharedServerTasksPage(
        tasks = envelope.tasks.mapNotNull { it.toTaskSummary(diagnostics) },
        nextCursor = envelope.next_cursor?.takeIf { it.isNotBlank() },
    )
}
