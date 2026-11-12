package build.raft.app.data

import build.raft.app.model.TaskStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Tasks page's server-side query of the task board (botiverse/slock staging
 * c6580f2f41a617f1c406f13ffdcac2150ea3b044):
 *
 * - `GET /api/tasks/server` (packages/server/src/routes/tasks.ts `taskRouter.get("/server")`,
 *   `parseServerTaskFilters`), the board filters:
 *   - `status=<s>,...`        board statuses; with any board filter `in_progress` also reads stored
 *     `in_review` rows. A lone `status=` with no other board param is the legacy exact match, so the
 *     web sends In progress as the list `in_progress,in_review` (tasksPageApi.ts
 *     `serverTaskFilterParams`); [serverTaskFilterQuery] does the same.
 *   - `channel=<uuid>,...`    local channel ids
 *   - `assignee=<user:id|agent:id|unassigned>,...`
 *   - `creator=<user:id|agent:id>,...`
 *   - `relation=assigned_to_me|created_by_me|from_my_message` (one value)
 *   - `completed_since=<ISO>` done tasks completed before it are left out
 *   - `q=<text>`              title words (every one) or a task number (`12` / `#12`), <= 200 chars
 *   and the page params `sort=activity|activity_asc|created`, `limit=1..500`, `cursor=<opaque>`
 *   (a cursor resumes only the sort that made it; 400 `Invalid cursor` otherwise) and
 *   `detail=summary`. Answer: `{ tasks: ServerTaskSummary[], next_cursor: string | null }`
 *   (parsed by [parseSharedServerTasksPagePayload]).
 * - `GET /api/tasks/server/counts` (`taskRouter.get("/server/counts")`, taskService.ts
 *   `countServerTasks`): the same filters, no limit / cursor / detail / sort (each a 400).
 *   Answer [ServerTaskCountsWire]: `{ counts: { backlog, todo, in_progress, done, closed },
 *   relations: { assigned_to_me, created_by_me, from_my_message } }`.
 *
 * List params are joined with commas in a stable (sorted) order, as the web's.
 */
enum class ServerTaskSort(val wire: String) {
    /** Most recently updated first (`activityAt` = lastActivityAt ?? updatedAt). */
    Activity("activity"),
    /** Least recently updated first (the exact reverse of [Activity]). */
    ActivityAsc("activity_asc"),
    /** Most recently created first. */
    Created("created");

    companion object {
        val Default: ServerTaskSort = Activity

        fun fromWire(value: String?): ServerTaskSort? = entries.firstOrNull { it.wire == value }
    }
}

/** The person's relation to a task (server taskService.ts `SERVER_TASK_RELATIONS`; exclusive, in this order). */
enum class ServerTaskRelation(val wire: String) {
    AssignedToMe("assigned_to_me"),
    CreatedByMe("created_by_me"),
    FromMyMessage("from_my_message"),
}

/** The board statuses the page filters and shows (task v2: no In review; its rows count as In progress). */
val ServerTaskBoardStatuses: List<TaskStatus> =
    listOf(TaskStatus.Backlog, TaskStatus.Todo, TaskStatus.InProgress, TaskStatus.Done, TaskStatus.Closed)

/** One filter set of a board read (web tasksPageApi.ts `ServerTaskBoardFilters`). Empty lists send nothing. */
data class ServerTaskBoardFilters(
    val statuses: List<TaskStatus> = emptyList(),
    val channelIds: List<String> = emptyList(),
    /** `user:<id>` / `agent:<id>` / `unassigned`. */
    val assignees: List<String> = emptyList(),
    /** `user:<id>` / `agent:<id>`. */
    val creators: List<String> = emptyList(),
    val relation: ServerTaskRelation? = null,
    /** An ISO instant with its offset. */
    val completedSince: String? = null,
    /** The search, trimmed; blank is no search. */
    val query: String = "",
)

/** The server takes a search this long at most (`q`). */
const val ServerTaskQueryMaxLength: Int = 200

/** The query of [filters], in the web's stable order: repeated keys are not used, lists are comma-joined and sorted. */
fun serverTaskFilterQuery(filters: ServerTaskBoardFilters): List<Pair<String, String>> =
    buildList {
        fun list(key: String, values: List<String>) {
            if (values.isNotEmpty()) add(key to values.distinct().sorted().joinToString(","))
        }
        val statuses = filters.statuses.filter { it.isKnown }.map { it.toWireValue() }
        list("status", if (TaskStatus.InProgress in filters.statuses) statuses + TaskStatus.InReview.toWireValue() else statuses)
        list("channel", filters.channelIds)
        list("assignee", filters.assignees)
        list("creator", filters.creators)
        filters.relation?.let { add("relation" to it.wire) }
        filters.completedSince?.let { add("completed_since" to it) }
        filters.query.trim().take(ServerTaskQueryMaxLength).takeIf { it.isNotEmpty() }?.let { add("q" to it) }
    }

/** A page read of [filters]: the filters, then `sort`, `limit`, `detail=summary` and the [cursor] when there is one. */
fun serverTaskPageQuery(
    filters: ServerTaskBoardFilters,
    sort: ServerTaskSort,
    limit: Int,
    cursor: String?,
): List<Pair<String, String>> =
    serverTaskFilterQuery(filters) +
        listOfNotNull(
            "sort" to sort.wire,
            "limit" to limit.toString(),
            "detail" to "summary",
            cursor?.takeIf { it.isNotBlank() }?.let { "cursor" to it },
        )

/** `GET /api/tasks/server/counts` (taskService.ts `ServerTaskCounts`). */
@Serializable
internal data class ServerTaskCountsWire(
    val counts: ServerTaskColumnCountsWire,
    val relations: ServerTaskRelationCountsWire,
)

@Serializable
internal data class ServerTaskColumnCountsWire(
    val backlog: Int = 0,
    val todo: Int = 0,
    @SerialName("in_progress") val inProgress: Int = 0,
    val done: Int = 0,
    val closed: Int = 0,
)

@Serializable
internal data class ServerTaskRelationCountsWire(
    @SerialName("assigned_to_me") val assignedToMe: Int = 0,
    @SerialName("created_by_me") val createdByMe: Int = 0,
    @SerialName("from_my_message") val fromMyMessage: Int = 0,
)

/** The counts of one filter set: per board column (`in_progress` includes stored in_review rows) and per relation (open work). */
data class ServerTaskCounts(
    val statuses: Map<TaskStatus, Int>,
    val relations: Map<ServerTaskRelation, Int>,
) {
    /** Every task the filters match: the columns summed (web `totalOf`). */
    val total: Int
        get() = ServerTaskBoardStatuses.sumOf { statuses[it] ?: 0 }
}

private val serverTaskCountsJson = Json { ignoreUnknownKeys = true }

/** Parses a counts answer; null when it is not the `{ counts, relations }` shape. Negative numbers read as 0. */
fun parseSharedServerTaskCounts(body: String): ServerTaskCounts? {
    val wire = runCatching { serverTaskCountsJson.decodeFromString(ServerTaskCountsWire.serializer(), body) }.getOrNull() ?: return null
    return ServerTaskCounts(
        statuses =
            mapOf(
                TaskStatus.Backlog to wire.counts.backlog.coerceAtLeast(0),
                TaskStatus.Todo to wire.counts.todo.coerceAtLeast(0),
                TaskStatus.InProgress to wire.counts.inProgress.coerceAtLeast(0),
                TaskStatus.Done to wire.counts.done.coerceAtLeast(0),
                TaskStatus.Closed to wire.counts.closed.coerceAtLeast(0),
            ),
        relations =
            mapOf(
                ServerTaskRelation.AssignedToMe to wire.relations.assignedToMe.coerceAtLeast(0),
                ServerTaskRelation.CreatedByMe to wire.relations.createdByMe.coerceAtLeast(0),
                ServerTaskRelation.FromMyMessage to wire.relations.fromMyMessage.coerceAtLeast(0),
            ),
    )
}

/** Result of a counts read. */
sealed interface ServerTaskCountsResult {
    data class Counts(val counts: ServerTaskCounts) : ServerTaskCountsResult

    /** [traceId]: the failed response's `X-Trace-Id`, when it got one. */
    data class Error(val code: String, val traceId: String? = null) : ServerTaskCountsResult
}

internal fun HomeNetworkResponse.toServerTaskCountsResult(): ServerTaskCountsResult {
    if (!isSuccessful) {
        return ServerTaskCountsResult.Error(code = "tasks_counts_http_$code", traceId = traceId)
    }
    val body = bodyText ?: return ServerTaskCountsResult.Error(code = "tasks_counts_empty_body", traceId = traceId)
    val counts = parseSharedServerTaskCounts(body) ?: return ServerTaskCountsResult.Error(code = "tasks_counts_contract_mismatch", traceId = traceId)
    return ServerTaskCountsResult.Counts(counts)
}
