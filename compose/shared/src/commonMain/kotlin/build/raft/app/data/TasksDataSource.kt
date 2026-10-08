package build.raft.app.data

import build.raft.app.MemberKindWireContract
import build.raft.app.projectionRejectingDiagnosticsLogger
import build.raft.app.strictProjectionDiagnosticsLogger
import build.raft.app.strictMemberKindOrNull
import build.raft.app.strictTaskStatusOrNull
import build.raft.app.KuiklyThreadSurfaceKind
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.TimeSource

private val tasksJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

class SharedTasksDataSource(
    private val api: SlockTasksApi,
    private val diagnosticsLogger: (String) -> Unit = projectionRejectingDiagnosticsLogger
) : SharedTasksRemoteDataSource {
    private val typedApi = SlockTasksApiClient(api, diagnosticsLogger)

    override suspend fun loadTasks(
        serverId: ServerId,
        filter: TaskFilter,
        priority: DataRequestPriority
    ): DataResult<TaskSnapshot> =
        try {
            typedApi.serverTasks(serverId, priority, filter)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "tasks_network", retryable = true))
        }

    /** Paginated summary page (task #345); see [TasksPageResult]. */
    override suspend fun loadServerTasksPage(
        serverId: ServerId,
        limit: Int,
        cursor: String?,
        priority: DataRequestPriority,
        status: String?
    ): TasksPageResult =
        try {
            typedApi.serverTasksPage(serverId, priority, limit, cursor, status)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            TasksPageResult.Error(code = "tasks_page_network")
        }

    override suspend fun loadChannelTasks(
        serverId: ServerId,
        channelId: ChannelId,
        filter: TaskFilter,
        priority: DataRequestPriority
    ): DataResult<TaskSnapshot> =
        try {
            typedApi.channelTasks(serverId, channelId, priority, filter)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "tasks_network", retryable = true))
        }

    suspend fun loadChannelTaskByNumber(
        serverId: ServerId,
        channelId: ChannelId,
        taskNumber: Int,
        priority: DataRequestPriority
    ): DataResult<TaskActionResult> =
        try {
            typedApi.taskByNumber(serverId, channelId, taskNumber, priority)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "tasks_number_network", retryable = true))
        }

    override suspend fun updateTaskStatus(
        serverId: ServerId,
        taskId: TaskId,
        status: TaskStatus
    ): DataResult<TaskActionResult> =
        try {
            typedApi.updateTaskStatus(serverId, taskId, status)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "tasks_status_network", retryable = true))
        }

    override suspend fun assignTask(
        serverId: ServerId,
        taskId: TaskId,
        assignee: MemberProfile?,
        expectedRevision: Long?
    ): DataResult<TaskActionResult> =
        try {
            typedApi.assignTask(serverId, taskId, assignee, expectedRevision)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "tasks_assign_network", retryable = true))
        }

    /**
     * Batch-create channel tasks. Titles are passed through as-is here — the typed
     * client trims them when serializing — so an empty/blank title list will surface
     * upstream as a server-side validation error rather than getting silently dropped.
     * Callers should pre-filter (see [SharedTasksViewModel] which discards blanks
     * before dispatching).
     */
    override suspend fun createTasks(
        serverId: ServerId,
        channelId: ChannelId,
        titles: List<String>
    ): DataResult<TaskSnapshot> =
        try {
            typedApi.createTasks(
                serverId = serverId,
                channelId = channelId,
                drafts = titles.map { TaskCreateDraft(title = it) },
                priority = DataRequestPriority.Foreground
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "tasks_create_network", retryable = true))
        }

    suspend fun convertMessageToTask(serverId: ServerId, messageId: MessageId): DataResult<TaskConvertResult> =
        try {
            typedApi.convertMessage(serverId, messageId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "tasks_convert_message_network", retryable = true))
        }
}

/**
 * Converts the canonical message row into a task and commits the result through
 * [SharedTasksDataStore]. The server keeps task identity equal to the converted
 * message id. A successful response without a task projection therefore performs
 * one bounded server-scope refresh and accepts success only when that exact id is
 * present; it never fabricates a task id or task number.
 */
internal suspend fun convertMessageToCanonicalTask(
    serverId: ServerId?,
    messageId: MessageId,
    tasksDataSource: SharedTasksDataSource,
    tasksDataStore: SharedTasksDataStore?,
    diagnosticsLogger: (String) -> Unit = {}
): DataResult<TaskSummary> {
    val resolvedServerId = serverId
        ?: return DataResult.Error(ScreenError(code = "selected_server_missing", retryable = false))
    val convertResult = tasksDataSource.convertMessageToTask(resolvedServerId, messageId)
    val accepted =
        when (convertResult) {
            is DataResult.Content -> convertResult.data
            is DataResult.Empty -> return convertResult
            is DataResult.Error -> return convertResult
            is DataResult.NoPermission -> return convertResult
        }
    val responseTask = accepted.task
    if (responseTask != null) {
        if (responseTask.id.value != messageId.value) {
            return DataResult.Error(
                ScreenError(code = "tasks_convert_message_identity_mismatch", retryable = false)
            )
        }
        val sharedStore = tasksDataStore
            ?: return DataResult.Error(ScreenError(code = "tasks_store_missing", retryable = false))
        val outcome = sharedStore.upsertTaskForCurrentServer(resolvedServerId.value, responseTask)
            ?: return DataResult.Error(ScreenError(code = "tasks_owner_changed", retryable = true))
        if (outcome == SharedTaskFactFoldOutcome.Conflict) {
            return DataResult.Error(ScreenError(code = "tasks_canonical_conflict", retryable = true))
        }
        diagnosticsLogger(
            "tasks_convert_projection source=response taskId=${responseTask.id.value} serverId=${resolvedServerId.value}"
        )
        return DataResult.Content(responseTask)
    }

    val sharedStore = tasksDataStore
        ?: return DataResult.Error(ScreenError(code = "tasks_store_missing", retryable = false))
    diagnosticsLogger(
        "tasks_convert_projection source=canonical_refresh_start messageId=${messageId.value} " +
            "serverId=${resolvedServerId.value}"
    )
    val refreshed =
        sharedStore.loadSnapshot(
            serverId = resolvedServerId.value,
            scope = SharedTasksScope.Server,
            forceRefresh = true
        ) {
            tasksDataSource.loadTasks(
                serverId = resolvedServerId,
                filter = TaskFilter(),
                priority = DataRequestPriority.Foreground
            )
        }
    return when (refreshed) {
        is DataResult.Content -> {
            val task = refreshed.data.tasks.firstOrNull { candidate -> candidate.id.value == messageId.value }
                ?: run {
                    diagnosticsLogger(
                        "tasks_convert_projection source=canonical_refresh_missing messageId=${messageId.value} " +
                            "serverId=${resolvedServerId.value} tasks=${refreshed.data.tasks.size}"
                    )
                    return DataResult.Error(
                        ScreenError(code = "tasks_convert_message_projection_missing", retryable = true)
                    )
                }
            diagnosticsLogger(
                "tasks_convert_projection source=canonical_refresh taskId=${task.id.value} " +
                    "serverId=${resolvedServerId.value}"
            )
            DataResult.Content(task)
        }
        is DataResult.Empty -> {
            diagnosticsLogger(
                "tasks_convert_projection source=canonical_refresh_missing messageId=${messageId.value} " +
                    "serverId=${resolvedServerId.value} tasks=0"
            )
            DataResult.Error(ScreenError(code = "tasks_convert_message_projection_missing", retryable = true))
        }
        is DataResult.Error -> refreshed
        is DataResult.NoPermission -> refreshed
    }
}

data class TaskCreateDraft(
    val title: String,
    val description: String? = null
)

private suspend fun SlockNetworkClient.serverTasks(
    priority: DataRequestPriority,
    serverId: ServerId
): HomeNetworkResponse =
    request(priority, "tasks/server", serverIdOverride = serverId)

/**
 * Paginated server Tasks read (task #345). `detail=summary&limit=<n>` plus an
 * opaque `cursor` for subsequent pages. `cursor` is only sent alongside a
 * `limit` (the server 400s a cursor without a page size).
 */
private suspend fun SlockNetworkClient.serverTasksPage(
    priority: DataRequestPriority,
    serverId: ServerId,
    detail: String?,
    limit: Int?,
    cursor: String?,
    status: String? = null,
): HomeNetworkResponse {
    val query = buildList {
        detail?.let { add("detail" to it) }
        // An optional status filter composes with limit/cursor server-side.
        status?.takeIf { it.isNotBlank() }?.let { add("status" to it) }
        limit?.let { add("limit" to it.toString()) }
        // A cursor without a limit is invalid server-side; never send it alone.
        cursor?.takeIf { it.isNotBlank() && limit != null }?.let { add("cursor" to it) }
    }
    return request(priority, "tasks/server", serverIdOverride = serverId, query = query)
}

private suspend fun SlockNetworkClient.channelTasks(
    priority: DataRequestPriority,
    channelId: ChannelId,
    serverId: ServerId
): HomeNetworkResponse =
    request(priority, "tasks/channel/${channelId.value}", serverIdOverride = serverId)

private suspend fun SlockNetworkClient.claimTask(
    priority: DataRequestPriority,
    taskId: TaskId
): HomeNetworkResponse =
    request(priority, "tasks/${taskId.value}/claim", method = "PATCH")

private suspend fun SlockNetworkClient.updateTaskStatus(
    priority: DataRequestPriority,
    taskId: TaskId,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "tasks/${taskId.value}/status", method = "PATCH", bodyText = bodyText)

internal class NetworkSlockTasksApi(
    private val networkClient: SlockNetworkClient
) : SlockTasksApi {
    override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(priority, "tasks/server", serverIdOverride = serverId)

    override suspend fun serverTasksPage(
        serverId: ServerId,
        priority: DataRequestPriority,
        detail: String?,
        limit: Int?,
        cursor: String?,
        status: String?,
    ): HomeNetworkResponse =
        networkClient.serverTasksPage(priority, serverId, detail, limit, cursor, status)

    override suspend fun channelTasks(channelId: ChannelId, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(priority, "tasks/channel/${channelId.value}")

    override suspend fun channelTasks(
        serverId: ServerId,
        channelId: ChannelId,
        priority: DataRequestPriority
    ): HomeNetworkResponse =
        networkClient.request(priority, "tasks/channel/${channelId.value}", serverIdOverride = serverId)

    override suspend fun taskByNumber(
        channelId: ChannelId,
        taskNumber: Int,
        priority: DataRequestPriority
    ): HomeNetworkResponse =
        networkClient.request(priority, "tasks/channel/${channelId.value}/number/$taskNumber")

    override suspend fun taskByNumber(
        serverId: ServerId,
        channelId: ChannelId,
        taskNumber: Int,
        priority: DataRequestPriority
    ): HomeNetworkResponse =
        networkClient.request(
            priority,
            "tasks/channel/${channelId.value}/number/$taskNumber",
            serverIdOverride = serverId
        )

    override suspend fun createTasks(
        channelId: ChannelId,
        drafts: List<TaskCreateDraft>,
        priority: DataRequestPriority
    ): HomeNetworkResponse =
        networkClient.request(
            priority = priority,
            path = "tasks/channel/${channelId.value}",
            method = "POST",
            bodyText = buildSharedTasksCreateBody(drafts)
        )

    override suspend fun createTasks(
        serverId: ServerId,
        channelId: ChannelId,
        drafts: List<TaskCreateDraft>,
        priority: DataRequestPriority
    ): HomeNetworkResponse =
        networkClient.request(
            priority = priority,
            path = "tasks/channel/${channelId.value}",
            method = "POST",
            bodyText = buildSharedTasksCreateBody(drafts),
            serverIdOverride = serverId
        )

    override suspend fun claimTask(taskId: TaskId): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "tasks/${taskId.value}/claim", method = "PATCH")

    override suspend fun claimTask(serverId: ServerId, taskId: TaskId): HomeNetworkResponse =
        networkClient.request(
            DataRequestPriority.Foreground,
            "tasks/${taskId.value}/claim",
            method = "PATCH",
            serverIdOverride = serverId
        )

    override suspend fun convertMessage(messageId: MessageId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "tasks/convert-message",
            method = "POST",
            bodyText = buildSharedTaskConvertBody(messageId)
        )

    override suspend fun convertMessage(serverId: ServerId, messageId: MessageId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "tasks/convert-message",
            method = "POST",
            bodyText = buildSharedTaskConvertBody(messageId),
            serverIdOverride = serverId
        )

    override suspend fun unclaimTask(taskId: TaskId): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "tasks/${taskId.value}/unclaim", method = "PATCH")

    override suspend fun unclaimTask(serverId: ServerId, taskId: TaskId): HomeNetworkResponse =
        networkClient.request(
            DataRequestPriority.Foreground,
            "tasks/${taskId.value}/unclaim",
            method = "PATCH",
            serverIdOverride = serverId
        )

    override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse =
        networkClient.updateTaskStatus(
            DataRequestPriority.Foreground,
            taskId,
            buildSharedTaskStatusBody(status)
        )

    override suspend fun assignTask(taskId: TaskId, bodyText: String): HomeNetworkResponse =
        networkClient.request(
            DataRequestPriority.Foreground,
            "tasks/${taskId.value}/assignee",
            method = "PATCH",
            bodyText = bodyText
        )

    override suspend fun assignTask(serverId: ServerId, taskId: TaskId, bodyText: String): HomeNetworkResponse =
        networkClient.request(
            DataRequestPriority.Foreground,
            "tasks/${taskId.value}/assignee",
            method = "PATCH",
            bodyText = bodyText,
            serverIdOverride = serverId
        )

    override suspend fun updateTaskStatus(
        serverId: ServerId,
        taskId: TaskId,
        status: TaskStatus
    ): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "tasks/${taskId.value}/status",
            method = "PATCH",
            bodyText = buildSharedTaskStatusBody(status),
            serverIdOverride = serverId
        )

    override suspend fun deleteTask(taskId: TaskId): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "tasks/${taskId.value}", method = "DELETE")

    override suspend fun deleteTask(serverId: ServerId, taskId: TaskId): HomeNetworkResponse =
        networkClient.request(
            DataRequestPriority.Foreground,
            "tasks/${taskId.value}",
            method = "DELETE",
            serverIdOverride = serverId
        )
}

class RealTaskRepository(
    private val sessionStore: SlockAuthSessionStore,
    private val networkClient: SlockNetworkClient
) : TaskRepository {
    override suspend fun getTasks(
        serverId: ServerId,
        filter: TaskFilter
    ): DataResult<TaskSnapshot> {
        val context = sessionStore.taskServerContext(serverId) ?: return authRequired()
        val result =
            networkClient.serverTasks(DataRequestPriority.Visible, serverId)
                .toTasksResult(filter = filter.withoutAssigneeFilter(), source = "tasks_server")
        return result.filterAssignedToMe(filter, context.currentUserId)
    }

    override suspend fun getChannelTasks(
        serverId: ServerId,
        channelId: ChannelId,
        filter: TaskFilter
    ): DataResult<TaskSnapshot> {
        val context = sessionStore.taskServerContext(serverId) ?: return authRequired()
        val result =
            networkClient.channelTasks(DataRequestPriority.Visible, channelId, serverId)
                .toTasksResult(filter = filter.withoutAssigneeFilter(), source = "tasks_channel")
        return result.filterAssignedToMe(filter, context.currentUserId)
    }

    override suspend fun claimTask(taskId: TaskId): DataResult<TaskActionResult> {
        sessionStore.currentTaskContext() ?: return authRequired()
        return networkClient.claimTask(DataRequestPriority.Foreground, taskId)
            .toTaskActionResult(source = "tasks_claim")
    }

    override suspend fun updateTaskStatus(
        taskId: TaskId,
        status: TaskStatus
    ): DataResult<TaskActionResult> {
        sessionStore.currentTaskContext() ?: return authRequired()
        return networkClient.updateTaskStatus(
            DataRequestPriority.Foreground,
            taskId,
            buildSharedTaskStatusBody(status)
        ).toTaskActionResult(source = "tasks_status", requestedStatus = status)
    }
}

/**
 * Typed Tasks boundary.
 *
 * Server/channel task lists parse the upstream `tasks` wrapper, create uses a
 * `{ tasks: [...] }` body, message conversion uses `{ messageId }`, and status
 * mutation sends the canonical API status value. Unsupported platform routes
 * return 501 instead of exposing guessed wrappers to shared UI.
 */
interface SlockTasksApi {
    suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse
    /**
     * Paginated server Tasks read (task #345): `?detail=summary&limit=200`, then
     * follow `cursor`. A default implementation keeps existing fakes compiling;
     * real transports override it.
     */
    suspend fun serverTasksPage(
        serverId: ServerId,
        priority: DataRequestPriority,
        detail: String?,
        limit: Int?,
        cursor: String?,
        status: String? = null,
    ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun channelTasks(channelId: ChannelId, priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun channelTasks(
        serverId: ServerId,
        channelId: ChannelId,
        priority: DataRequestPriority
    ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun taskByNumber(channelId: ChannelId, taskNumber: Int, priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun taskByNumber(
        serverId: ServerId,
        channelId: ChannelId,
        taskNumber: Int,
        priority: DataRequestPriority
    ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun createTasks(channelId: ChannelId, drafts: List<TaskCreateDraft>, priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun createTasks(
        serverId: ServerId,
        channelId: ChannelId,
        drafts: List<TaskCreateDraft>,
        priority: DataRequestPriority
    ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun claimTask(taskId: TaskId): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun claimTask(serverId: ServerId, taskId: TaskId): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun convertMessage(messageId: MessageId): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun convertMessage(serverId: ServerId, messageId: MessageId): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun unclaimTask(taskId: TaskId): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun unclaimTask(serverId: ServerId, taskId: TaskId): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse
    suspend fun updateTaskStatus(serverId: ServerId, taskId: TaskId, status: TaskStatus): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)

    /**
     * `PATCH /api/tasks/:taskId/assignee` (server contract: body `{ assignee:
     * {type:'user'|'agent', id} | null, expectedRevision? }`; explicit `null`
     * unassigns; 409 + `currentRevision` on lost OCC race). Body is pre-built by
     * [buildSharedTaskAssignBody].
     */
    suspend fun assignTask(taskId: TaskId, bodyText: String): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun assignTask(serverId: ServerId, taskId: TaskId, bodyText: String): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun deleteTask(taskId: TaskId): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun deleteTask(serverId: ServerId, taskId: TaskId): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
}

@Serializable
data class TaskStatusUpdateRequestBody(val status: String)

@Serializable
data class TaskCreateRequestBody(
    val title: String,
    val description: String? = null
)

@Serializable
data class TasksCreateRequestBody(val tasks: List<TaskCreateRequestBody>)

@Serializable
data class TaskConvertRequestBody(val messageId: String)

fun TaskStatus.toTaskStatusUpdateRequestBody(): TaskStatusUpdateRequestBody =
    TaskStatusUpdateRequestBody(status = toWireValue())

fun TaskCreateDraft.toTaskCreateRequestBody(): TaskCreateRequestBody =
    TaskCreateRequestBody(
        title = title.trim(),
        description = description?.trim()?.takeIf { it.isNotBlank() }
    )

fun buildSharedTaskStatusBody(status: TaskStatus): String =
    tasksJson.encodeToString(status.toTaskStatusUpdateRequestBody())

fun buildSharedTasksCreateBody(drafts: List<TaskCreateDraft>): String =
    tasksJson.encodeToString(TasksCreateRequestBody(drafts.map { it.toTaskCreateRequestBody() }))

fun buildSharedTaskConvertBody(messageId: MessageId): String =
    tasksJson.encodeToString(TaskConvertRequestBody(messageId.value))

/** Wire value for [MemberKind] in the assignee PATCH body; System members are not assignable. */
internal fun MemberProfile.taskAssigneeTypeValueOrNull(): String? =
    when (kind) {
        MemberKind.Human -> "user"
        MemberKind.Agent -> "agent"
        MemberKind.System -> null
        // External projections are never task assignees.
        MemberKind.External -> null
    }

/**
 * `PATCH /api/tasks/:taskId/assignee` request body.
 *
 * Built manually instead of through [tasksJson] because `explicitNulls = false`
 * would drop the `assignee` key entirely, while the server treats a missing key
 * as a 400 ("assignee is required (use null to unassign)"). An explicit JSON
 * `null` is the unassign sentinel and must survive serialization.
 */
fun buildSharedTaskAssignBody(assignee: MemberProfile?, expectedRevision: Long?): String {
    val root = buildJsonObject {
        if (assignee == null) {
            put("assignee", JsonNull)
        } else {
            val type = assignee.taskAssigneeTypeValueOrNull()
                ?: throw IllegalArgumentException(
                    "MemberKind ${assignee.kind} is not a valid task assignee"
                )
            put(
                "assignee",
                buildJsonObject {
                    put("type", type)
                    put("id", assignee.id.value)
                }
            )
        }
        if (expectedRevision != null) {
            put("expectedRevision", expectedRevision)
        }
    }
    return root.toString()
}

/** Sentinel error code for a lost OCC race on the assignee write (server 409). */
internal const val TASKS_ASSIGN_CONFLICT = "tasks_assign_conflict"

internal fun taskAssignConflictErrorCode(payload: String?): Boolean {
    val text = payload?.takeIf { it.isNotBlank() } ?: return false
    val envelope =
        runCatching {
            tasksJson.decodeFromString(TaskAssignErrorEnvelope.serializer(), text)
        }.getOrNull() ?: return false
    return envelope.error?.trim()?.equals("task state changed concurrently", ignoreCase = true) == true
}

internal class SlockTasksApiClient(
    private val transport: SlockTasksApi,
    private val diagnosticsLogger: (String) -> Unit = projectionRejectingDiagnosticsLogger
) {
    suspend fun serverTasks(
        serverId: ServerId,
        priority: DataRequestPriority,
        filter: TaskFilter = TaskFilter()
    ): DataResult<TaskSnapshot> =
        transport.serverTasks(serverId, priority).toTasksResultOffUi(
            filter = filter,
            source = "tasks_server",
            diagnosticsLogger = diagnosticsLogger
        )

    /**
     * Paginated, summary-detail server Tasks page (task #345). Returns the
     * decoded page (items + opaque next cursor) or a typed error. A 400
     * `Invalid cursor` is surfaced as [TasksPageResult.InvalidCursor] so the
     * caller refreshes from the head instead of retrying.
     */
    suspend fun serverTasksPage(
        serverId: ServerId,
        priority: DataRequestPriority,
        limit: Int,
        cursor: String?,
        status: String? = null
    ): TasksPageResult =
        // The optional status filter rides the SAME transport + the SAME parser
        // (task #363): no second cursor/response codec for the per-status walk.
        transport.serverTasksPage(
            serverId = serverId,
            priority = priority,
            detail = "summary",
            limit = limit,
            cursor = cursor,
            status = status
        ).toTasksPageResult(source = "tasks_server_page", diagnosticsLogger = diagnosticsLogger)

    suspend fun channelTasks(
        channelId: ChannelId,
        priority: DataRequestPriority,
        filter: TaskFilter = TaskFilter()
    ): DataResult<TaskSnapshot> =
        transport.channelTasks(channelId, priority).toTasksResultOffUi(
            filter = filter,
            source = "tasks_channel",
            diagnosticsLogger = diagnosticsLogger
        )

    suspend fun channelTasks(
        serverId: ServerId,
        channelId: ChannelId,
        priority: DataRequestPriority,
        filter: TaskFilter = TaskFilter()
    ): DataResult<TaskSnapshot> =
        transport.channelTasks(serverId, channelId, priority).toTasksResultOffUi(
            filter = filter,
            source = "tasks_channel",
            diagnosticsLogger = diagnosticsLogger
        )

    suspend fun taskByNumber(
        serverId: ServerId,
        channelId: ChannelId,
        taskNumber: Int,
        priority: DataRequestPriority
    ): DataResult<TaskActionResult> =
        transport.taskByNumber(serverId, channelId, taskNumber, priority).toTaskActionResult(
            source = "tasks_by_number",
            diagnostics = diagnosticsLogger,
        )

    suspend fun taskByNumber(
        channelId: ChannelId,
        taskNumber: Int,
        priority: DataRequestPriority
    ): DataResult<TaskActionResult> =
        transport.taskByNumber(channelId, taskNumber, priority).toTaskActionResult(
            source = "tasks_by_number",
            diagnostics = diagnosticsLogger,
        )

    suspend fun createTasks(
        channelId: ChannelId,
        drafts: List<TaskCreateDraft>,
        priority: DataRequestPriority
    ): DataResult<TaskSnapshot> =
        transport.createTasks(channelId, drafts, priority).toTasksResultOffUi(
            source = "tasks_create",
            diagnosticsLogger = diagnosticsLogger
        )

    suspend fun createTasks(
        serverId: ServerId,
        channelId: ChannelId,
        drafts: List<TaskCreateDraft>,
        priority: DataRequestPriority
    ): DataResult<TaskSnapshot> =
        transport.createTasks(serverId, channelId, drafts, priority).toTasksResultOffUi(
            source = "tasks_create",
            diagnosticsLogger = diagnosticsLogger
        )

    suspend fun claimTask(taskId: TaskId): DataResult<TaskActionResult> =
        transport.claimTask(taskId).toTaskActionResult(source = "tasks_claim", diagnostics = diagnosticsLogger)

    suspend fun claimTask(serverId: ServerId, taskId: TaskId): DataResult<TaskActionResult> =
        transport.claimTask(serverId, taskId).toTaskActionResult(source = "tasks_claim", diagnostics = diagnosticsLogger)

    suspend fun convertMessage(messageId: MessageId): DataResult<TaskConvertResult> =
        transport.convertMessage(messageId).toTaskConvertResult(
            source = "tasks_convert_message",
            diagnostics = diagnosticsLogger,
        )

    suspend fun convertMessage(serverId: ServerId, messageId: MessageId): DataResult<TaskConvertResult> =
        transport.convertMessage(serverId, messageId).toTaskConvertResult(
            source = "tasks_convert_message",
            diagnostics = diagnosticsLogger,
        )

    suspend fun unclaimTask(taskId: TaskId): DataResult<TaskActionResult> =
        transport.unclaimTask(taskId).toTaskActionResult(source = "tasks_unclaim", diagnostics = diagnosticsLogger)

    suspend fun unclaimTask(serverId: ServerId, taskId: TaskId): DataResult<TaskActionResult> =
        transport.unclaimTask(serverId, taskId).toTaskActionResult(source = "tasks_unclaim", diagnostics = diagnosticsLogger)

    suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): DataResult<TaskActionResult> =
        transport.updateTaskStatus(taskId, status).toTaskActionResult(
            source = "tasks_status",
            diagnostics = diagnosticsLogger,
            requestedStatus = status,
        )

    suspend fun updateTaskStatus(
        serverId: ServerId,
        taskId: TaskId,
        status: TaskStatus
    ): DataResult<TaskActionResult> =
        transport.updateTaskStatus(serverId, taskId, status).toTaskActionResult(
            source = "tasks_status",
            diagnostics = diagnosticsLogger,
            requestedStatus = status,
        )

    suspend fun assignTask(
        serverId: ServerId,
        taskId: TaskId,
        assignee: MemberProfile?,
        expectedRevision: Long?
    ): DataResult<TaskActionResult> =
        transport
            .assignTask(
                serverId,
                taskId,
                buildSharedTaskAssignBody(assignee = assignee, expectedRevision = expectedRevision)
            )
            .toTaskAssignActionResult(diagnostics = diagnosticsLogger)

    suspend fun deleteTask(taskId: TaskId): DataResult<Unit> =
        transport.deleteTask(taskId).toTaskDeleteResult()

    suspend fun deleteTask(serverId: ServerId, taskId: TaskId): DataResult<Unit> =
        transport.deleteTask(serverId, taskId).toTaskDeleteResult()
}

internal fun HomeNetworkResponse.toTasksResult(
    filter: TaskFilter = TaskFilter(),
    source: String = "tasks",
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): DataResult<TaskSnapshot> {
    val serverMessage = if (isSuccessful) null else tasksServerErrorTextOrNull()
    if (code == 401 || code == 403) {
        return DataResult.NoPermission(PermissionBlocker(code = "tasks_$code", serverMessage = serverMessage))
    }
    if (!isSuccessful) {
        return DataResult.Error(ScreenError(code = "tasks_$code", retryable = true, serverMessage = serverMessage))
    }
    val body = bodyText
        ?: return DataResult.Error(ScreenError(code = "${source}_contract_mismatch", retryable = false))
    val envelope = runCatching { tasksJson.decodeFromString<TasksListEnvelope>(body) }.getOrNull()
        ?: return DataResult.Error(ScreenError(code = "${source}_contract_mismatch", retryable = false))
    val tasks = envelope.tasks.mapNotNull { it.toTaskSummary(diagnostics) }
    val snapshot = TaskSnapshot(tasks = tasks, summaryCounts = tasks.summaryCounts()).filteredBy(filter)
    return if (snapshot.tasks.isEmpty()) {
        DataResult.Empty(EmptyReason.NoTasks)
    } else {
        DataResult.Content(snapshot)
    }
}

internal suspend fun HomeNetworkResponse.toTasksResultOffUi(
    filter: TaskFilter = TaskFilter(),
    source: String = "tasks",
    diagnosticsLogger: (String) -> Unit = projectionRejectingDiagnosticsLogger
): DataResult<TaskSnapshot> {
    val responseBytes = bodyText?.encodeToByteArray()?.size ?: 0
    diagnosticsLogger(
        "tasks_network_body_received source=$source code=$code responseBytes=$responseBytes " +
            "hasBody=${bodyText != null}"
    )
    val parseStartedAt = TimeSource.Monotonic.markNow()
    diagnosticsLogger(
        "tasks_parse_start source=$source responseBytes=$responseBytes " +
            "dispatcher=$SLOCK_JSON_PARSE_DISPATCHER_LABEL"
    )
    val result =
        withContext(SlockJsonParseDispatcher) {
            toTasksResult(filter = filter, source = source, diagnostics = diagnosticsLogger)
        }
    val durationMs = parseStartedAt.elapsedNow().inWholeMilliseconds
    val taskCount = (result as? DataResult.Content)?.data?.tasks?.size ?: 0
    val resultLabel =
        when (result) {
            is DataResult.Content -> "content"
            is DataResult.Empty -> "empty:${result.reason.name}"
            is DataResult.Error -> "error:${result.error.code}"
            is DataResult.NoPermission -> "no_permission:${result.blocker.code}"
        }
    diagnosticsLogger(
        "tasks_parse_result source=$source result=$resultLabel tasks=$taskCount " +
            "durationMs=$durationMs dispatcher=$SLOCK_JSON_PARSE_DISPATCHER_LABEL"
    )
    diagnosticsLogger(
        "tasks_map_result source=$source result=$resultLabel tasks=$taskCount " +
            "durationMs=$durationMs dispatcher=$SLOCK_JSON_PARSE_DISPATCHER_LABEL"
    )
    return result
}

/** Result of a paginated server Tasks read (task #345). */
sealed interface TasksPageResult {
    /** A decoded page; [nextCursor] is null when the walk is exhausted. */
    data class Page(val tasks: List<TaskSummary>, val nextCursor: String?) : TasksPageResult

    /** The server rejected the cursor (400 `Invalid cursor`): refresh from head. */
    data object InvalidCursor : TasksPageResult

    /** Transient failure: keep what is loaded and allow a retry. */
    data class Error(val code: String) : TasksPageResult
}

/**
 * Maps a raw `GET /api/tasks/server` page response. A 400 with
 * `error == "Invalid cursor"` is distinguished from other failures so the
 * caller can drop the cursor and refresh the head (matches the Web consumer).
 */
internal fun HomeNetworkResponse.toTasksPageResult(
    source: String = "tasks_server_page",
    diagnosticsLogger: (String) -> Unit = projectionRejectingDiagnosticsLogger,
): TasksPageResult {
    val responseBytes = bodyText?.encodeToByteArray()?.size ?: 0
    diagnosticsLogger(
        "tasks_page_body_received source=$source code=$code responseBytes=$responseBytes hasBody=${bodyText != null}"
    )
    if (!isSuccessful) {
        val errorText = tasksServerErrorTextOrNull()
        return if (code == 400 && errorText == "Invalid cursor") {
            TasksPageResult.InvalidCursor
        } else {
            TasksPageResult.Error(code = errorText?.let { "tasks_page_$it" } ?: "tasks_page_http_$code")
        }
    }
    val body = bodyText ?: return TasksPageResult.Error(code = "tasks_page_empty_body")
    val page = parseSharedServerTasksPagePayload(body, diagnosticsLogger)
        ?: return TasksPageResult.Error(code = "tasks_page_contract_mismatch")
    return TasksPageResult.Page(tasks = page.tasks, nextCursor = page.nextCursor)
}

internal fun HomeNetworkResponse.toTaskActionResult(
    source: String = "tasks_action",
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
    requestedStatus: TaskStatus? = null,
): DataResult<TaskActionResult> {
    val serverMessage = if (isSuccessful) null else tasksServerErrorTextOrNull()
    if (code == 401 || code == 403) {
        return DataResult.NoPermission(PermissionBlocker(code = "tasks_$code", serverMessage = serverMessage))
    }
    if (!isSuccessful) {
        taskStatusAlreadyAppliedErrorCode(requestedStatus)?.let { errorCode ->
            return DataResult.Error(ScreenError(code = errorCode, retryable = false))
        }
        return DataResult.Error(ScreenError(code = "tasks_$code", retryable = true, serverMessage = serverMessage))
    }
    val body = bodyText
        ?: return requestedStatus.taskStatusAppliedWithoutProjectionResult(source)
    val envelope = runCatching { tasksJson.decodeFromString<TaskEnvelope>(body) }.getOrNull()
        ?: return requestedStatus.taskStatusAppliedWithoutProjectionResult(source)
    val task = envelope.task
        ?: return requestedStatus.taskStatusAppliedWithoutProjectionResult(source)
    val summary = task.toTaskSummary(diagnostics)
        ?: return requestedStatus.taskStatusAppliedWithoutProjectionResult(source)
    return DataResult.Content(TaskActionResult(summary))
}

/**
 * Mapper for `PATCH /tasks/:taskId/assignee`. Mirrors [toTaskActionResult] with one
 * contract addition: a 409 whose body reports `task state changed concurrently` is
 * the server's OCC-lost signal (`currentRevision` included when re-readable) and
 * maps to [TASKS_ASSIGN_CONFLICT] so callers can re-read + retry instead of
 * surfacing a generic failure.
 */
internal fun HomeNetworkResponse.toTaskAssignActionResult(
    source: String = "tasks_assign",
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): DataResult<TaskActionResult> {
    val serverMessage = if (isSuccessful) null else tasksServerErrorTextOrNull()
    if (code == 401 || code == 403) {
        return DataResult.NoPermission(PermissionBlocker(code = "tasks_$code", serverMessage = serverMessage))
    }
    if (!isSuccessful) {
        val conflictPayload = errorBodyText?.takeIf { it.isNotBlank() } ?: bodyText
        if (code == 409 && taskAssignConflictErrorCode(conflictPayload)) {
            return DataResult.Error(
                ScreenError(code = TASKS_ASSIGN_CONFLICT, retryable = true, serverMessage = serverMessage)
            )
        }
        return DataResult.Error(ScreenError(code = "tasks_$code", retryable = true, serverMessage = serverMessage))
    }
    val body = bodyText
        ?: return DataResult.Error(ScreenError(code = "${source}_contract_mismatch", retryable = false))
    val envelope = runCatching { tasksJson.decodeFromString<TaskEnvelope>(body) }.getOrNull()
        ?: return DataResult.Error(ScreenError(code = "${source}_contract_mismatch", retryable = false))
    val summary = envelope.task?.toTaskSummary(diagnostics)
        ?: return DataResult.Error(ScreenError(code = "${source}_contract_mismatch", retryable = false))
    return DataResult.Content(TaskActionResult(summary))
}

internal fun taskStatusAlreadyAppliedErrorCode(
    httpCode: Int,
    errorBodyText: String?,
    bodyText: String?,
    requestedStatus: TaskStatus?,
): String? {
    if (httpCode != 409 || requestedStatus == null) return null
    val payload = errorBodyText ?: bodyText ?: return null
    val envelope =
        runCatching {
            tasksJson.decodeFromString(TaskActionErrorEnvelope.serializer(), payload)
        }.getOrNull() ?: return null
    val serverCode = (envelope.code ?: envelope.error)?.trim()?.lowercase() ?: return null
    val expectedCode = "already_${requestedStatus.toWireValue()}"
    return if (serverCode == expectedCode) requestedStatus.taskStatusAlreadyAppliedErrorCode() else null
}

private fun HomeNetworkResponse.taskStatusAlreadyAppliedErrorCode(requestedStatus: TaskStatus?): String? =
    taskStatusAlreadyAppliedErrorCode(
        httpCode = code,
        errorBodyText = errorBodyText,
        bodyText = bodyText,
        requestedStatus = requestedStatus,
    )

internal fun TaskStatus.taskStatusAlreadyAppliedErrorCode(): String =
    "tasks_status_already_${toWireValue()}"

internal const val TASKS_STATUS_APPLIED_WITHOUT_PROJECTION = "tasks_status_applied_without_projection"

private fun TaskStatus?.taskStatusAppliedWithoutProjectionResult(source: String): DataResult<TaskActionResult> =
    DataResult.Error(
        ScreenError(
            code = if (this == null) "${source}_contract_mismatch" else TASKS_STATUS_APPLIED_WITHOUT_PROJECTION,
            retryable = false,
        )
    )

internal fun HomeNetworkResponse.toTaskConvertResult(
    source: String = "tasks_convert_message",
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): DataResult<TaskConvertResult> {
    val serverMessage = if (isSuccessful) null else tasksServerErrorTextOrNull()
    if (code == 401 || code == 403) {
        return DataResult.NoPermission(PermissionBlocker(code = "tasks_$code", serverMessage = serverMessage))
    }
    if (!isSuccessful) {
        return DataResult.Error(ScreenError(code = "tasks_$code", retryable = true, serverMessage = serverMessage))
    }
    val body = bodyText?.trim().orEmpty()
    if (body.isEmpty()) {
        return DataResult.Content(TaskConvertResult(task = null))
    }
    val envelope = runCatching { tasksJson.decodeFromString<TaskEnvelope>(body) }.getOrNull()
        ?: return DataResult.Error(ScreenError(code = "${source}_contract_mismatch", retryable = false))
    val task = envelope.task ?: return DataResult.Content(TaskConvertResult(task = null))
    val summary = task.toTaskSummary(diagnostics)
        ?: return DataResult.Error(ScreenError(code = "${source}_contract_mismatch", retryable = false))
    return DataResult.Content(TaskConvertResult(summary))
}

internal fun HomeNetworkResponse.toTaskDeleteResult(): DataResult<Unit> {
    val serverMessage = if (isSuccessful) null else tasksServerErrorTextOrNull()
    if (code == 401 || code == 403) {
        return DataResult.NoPermission(PermissionBlocker(code = "tasks_$code", serverMessage = serverMessage))
    }
    if (!isSuccessful) {
        return DataResult.Error(ScreenError(code = "tasks_$code", retryable = true, serverMessage = serverMessage))
    }
    val body = bodyText
        ?: return DataResult.Error(ScreenError(code = "tasks_delete_contract_mismatch", retryable = false))
    val envelope = runCatching { tasksJson.decodeFromString<OkEnvelope>(body) }.getOrNull()
    return if (envelope?.ok == true) {
        DataResult.Content(Unit)
    } else {
        DataResult.Error(ScreenError(code = "tasks_delete_contract_mismatch", retryable = false))
    }
}

private fun TaskSnapshot.filteredBy(filter: TaskFilter): TaskSnapshot {
    val filtered =
        tasks.filter { task ->
            filter.statuses.isEmpty() || task.status in filter.statuses
        }
    return copy(tasks = filtered, summaryCounts = filtered.summaryCounts())
}

private fun DataResult<TaskSnapshot>.filterAssignedToMe(
    filter: TaskFilter,
    currentUserId: String
): DataResult<TaskSnapshot> {
    if (!filter.assignedToMeOnly || this !is DataResult.Content) return this
    val filteredTasks = data.tasks.filter { it.assignee?.id?.value == currentUserId }
    return if (filteredTasks.isEmpty()) {
        DataResult.Empty(EmptyReason.NoTasks)
    } else {
        DataResult.Content(data.copy(tasks = filteredTasks, summaryCounts = filteredTasks.summaryCounts()))
    }
}

private fun TaskFilter.withoutAssigneeFilter(): TaskFilter =
    if (!assignedToMeOnly) this else copy(assignedToMeOnly = false)

/**
 * Wire-format envelopes for Tasks API responses.
 *
 * Server hands every tasks endpoint the canonical `TaskInfo` shape defined in
 * `packages/shared/src/index.ts` and enriched by `taskService.enrichWithNames` /
 * `enrichLegacyTasks`. Field names below mirror that shape exactly; do not add
 * fallback aliases here — if the server changes, fix the server (or the
 * Serializable name).
 *
 * Endpoints:
 *   GET  /api/.../tasks      -> { tasks: TaskInfo[] }     (TasksListEnvelope)
 *   POST /api/.../tasks      -> { tasks: TaskInfo[] }     (TasksListEnvelope)
 *   POST /api/tasks/:id/...  -> { task:  TaskInfo }       (TaskEnvelope)
 *   DELETE /api/tasks/:id    -> { ok: true }              (OkEnvelope)
 */
@Serializable
internal data class TasksListEnvelope(val tasks: List<TaskWireDto>)

/**
 * Envelope for the paginated `GET /api/tasks/server` read (task #345).
 *
 * `next_cursor` is the opaque server cursor; `null` (or absent) terminates the
 * walk. It is only present on the paginated shape — the legacy no-param shape
 * and the summary-without-limit shape carry no `next_cursor` key, which
 * [parseServerTasksPagePayload] treats as "no more pages".
 */
@Serializable
internal data class TasksPageEnvelope(
    val tasks: List<TaskWireDto>,
    val next_cursor: String? = null,
)

/** Decoded paginated page: items plus the opaque cursor for the next page. */
data class SharedServerTasksPage(
    val tasks: List<TaskSummary>,
    val nextCursor: String?,
)

@Serializable
internal data class TaskEnvelope(val task: TaskWireDto? = null)

@Serializable
internal data class OkEnvelope(val ok: Boolean = false)

@Serializable
private data class TaskActionErrorEnvelope(
    val error: String? = null,
    val code: String? = null,
)

/** Error payload of `PATCH /tasks/:id/assignee` (409 OCC carries `currentRevision`). */
@Serializable
internal data class TaskAssignErrorEnvelope(
    val error: String? = null,
    val currentRevision: Long? = null,
)

private fun HomeNetworkResponse.tasksServerErrorTextOrNull(): String? {
    val payload = bodyText?.takeIf { it.isNotBlank() }
        ?: errorBodyText?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching {
        tasksJson.decodeFromString(TaskActionErrorEnvelope.serializer(), payload)
            .error
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

/** Mirror of React `TaskInfo` (packages/shared/src/index.ts). */
@Serializable
internal data class TaskWireDto(
    val id: String,
    val revision: Long? = null,
    val messageId: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val channelType: String? = null,
    val threadId: String? = null,
    val taskNumber: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val status: String? = null,
    val createdByType: String? = null,
    val createdById: String? = null,
    val createdByName: String? = null,
    // `detail=summary` projection fields (task #345). `description` is absent on
    // the summary shape; these two let the client decide whether to fetch the
    // full card. Ignored by the full shape.
    val hasDescription: Boolean? = null,
    val descriptionBytes: Int? = null,
    val source: String? = null,
    val claimedByType: String? = null,
    val claimedById: String? = null,
    val claimedByName: String? = null,
    val claimedAt: String? = null,
    val completedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val readOnlyReason: String? = null
)

internal fun TaskWireDto.toTaskSummary(
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): TaskSummary? {
    val displayTitle = title?.trim().orEmpty().ifBlank { taskNumber?.let { "#$it" }.orEmpty() }
    val resolvedMessageId = (messageId ?: id).let(::MessageId)
    val resolvedThreadId = threadId?.let(::ThreadId)
    val resolvedStatus = status.strictTaskStatusOrNull("task_wire", diagnostics) ?: return null
    return TaskSummary(
        id = TaskId(id),
        revision = revision,
        title = displayTitle,
        status = resolvedStatus,
        taskNumber = taskNumber,
        messageId = resolvedMessageId,
        parentMessageId = resolvedMessageId,
        channelId = channelId?.let(::ChannelId),
        threadId = resolvedThreadId,
        isThreadTask = channelType == KuiklyThreadSurfaceKind.ReplyThread,
        assignee = memberFrom(claimedByType, claimedById, claimedByName, "task_assignee", diagnostics),
        creator = memberFrom(createdByType, createdById, createdByName, "task_creator", diagnostics),
        channelName = channelName,
        updatedAtLabel = (updatedAt ?: createdAt ?: claimedAt ?: completedAt).orEmpty(),
        canClaim = readOnlyReason.isNullOrBlank(),
        description = description?.trim()?.takeIf { it.isNotBlank() },
        readOnlyReason = readOnlyReason?.takeIf { it.isNotBlank() }
    )
}

private fun memberFrom(
    type: String?,
    id: String?,
    name: String?,
    source: String,
    diagnostics: (String) -> Unit,
): MemberProfile? {
    if (id.isNullOrBlank()) return null
    val label = name?.trim().orEmpty().ifBlank { id }
    val kind =
        type.strictMemberKindOrNull(
            source = source,
            diagnostics = diagnostics,
            contract = MemberKindWireContract.InternalActor,
        ) ?: return null
    return MemberProfile(
        id = MemberId(id),
        displayName = label,
        handle = label,
        kind = kind,
        avatarLabel = label.take(1).uppercase(),
        presence = Presence.Offline
    )
}

private data class TaskServerContext(val currentUserId: String)

private suspend fun SlockAuthSessionStore.taskServerContext(serverId: ServerId): TaskServerContext? {
    val session = readSession() ?: return null
    val requestedId = serverId.value.trim().takeIf { it.isNotBlank() } ?: return null
    if (session.servers.none { it.id == requestedId }) return null
    return TaskServerContext(currentUserId = session.user.id)
}

private suspend fun SlockAuthSessionStore.currentTaskContext(): TaskServerContext? {
    val session = readSession() ?: return null
    val selectedId = session.currentServerId ?: return null
    return taskServerContext(ServerId(selectedId))
}

private fun <T> authRequired(): DataResult<T> =
    DataResult.NoPermission(PermissionBlocker(code = "auth_required", requiredCapability = "auth_session"))
