package build.raft.app.data

import build.raft.app.MemberKindWireContract
import build.raft.app.projectionRejectingDiagnosticsLogger
import build.raft.app.strictProjectionDiagnosticsLogger
import build.raft.app.strictMemberKindOrNull
import build.raft.app.strictTaskStatusOrNull
import build.raft.app.KuiklyThreadSurfaceKind
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
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

    override suspend fun loadChannelTasks(
        channelId: ChannelId,
        filter: TaskFilter,
        priority: DataRequestPriority
    ): DataResult<TaskSnapshot> =
        try {
            typedApi.channelTasks(channelId, priority, filter)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "tasks_network", retryable = true))
        }

    suspend fun loadChannelTaskByNumber(
        channelId: ChannelId,
        taskNumber: Int,
        priority: DataRequestPriority
    ): DataResult<TaskActionResult> =
        try {
            typedApi.taskByNumber(channelId, taskNumber, priority)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "tasks_number_network", retryable = true))
        }

    override suspend fun updateTaskStatus(
        taskId: TaskId,
        status: TaskStatus
    ): DataResult<TaskActionResult> =
        try {
            typedApi.updateTaskStatus(taskId, status)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "tasks_status_network", retryable = true))
        }

    /**
     * Batch-create channel tasks. Titles are passed through as-is here — the typed
     * client trims them when serializing — so an empty/blank title list will surface
     * upstream as a server-side validation error rather than getting silently dropped.
     * Callers should pre-filter (see [SharedTasksViewModel] which discards blanks
     * before dispatching).
     */
    override suspend fun createTasks(
        channelId: ChannelId,
        titles: List<String>
    ): DataResult<TaskSnapshot> =
        try {
            typedApi.createTasks(
                channelId = channelId,
                drafts = titles.map { TaskCreateDraft(title = it) },
                priority = DataRequestPriority.Foreground
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "tasks_create_network", retryable = true))
        }

    suspend fun convertMessageToTask(messageId: MessageId): DataResult<TaskConvertResult> =
        try {
            typedApi.convertMessage(messageId)
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
    val convertResult = tasksDataSource.convertMessageToTask(messageId)
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
        val resolvedServerId = serverId
            ?: return DataResult.Error(ScreenError(code = "selected_server_missing", retryable = false))
        val sharedStore = tasksDataStore
            ?: return DataResult.Error(ScreenError(code = "tasks_store_missing", retryable = false))
        sharedStore.upsertTask(resolvedServerId.value, responseTask)
        diagnosticsLogger(
            "tasks_convert_projection source=response taskId=${responseTask.id.value} serverId=${resolvedServerId.value}"
        )
        return DataResult.Content(responseTask)
    }

    val resolvedServerId = serverId
        ?: return DataResult.Error(ScreenError(code = "selected_server_missing", retryable = false))
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

    override suspend fun channelTasks(channelId: ChannelId, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(priority, "tasks/channel/${channelId.value}")

    override suspend fun taskByNumber(
        channelId: ChannelId,
        taskNumber: Int,
        priority: DataRequestPriority
    ): HomeNetworkResponse =
        networkClient.request(priority, "tasks/channel/${channelId.value}/number/$taskNumber")

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

    override suspend fun claimTask(taskId: TaskId): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "tasks/${taskId.value}/claim", method = "PATCH")

    override suspend fun convertMessage(messageId: MessageId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "tasks/convert-message",
            method = "POST",
            bodyText = buildSharedTaskConvertBody(messageId)
        )

    override suspend fun unclaimTask(taskId: TaskId): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "tasks/${taskId.value}/unclaim", method = "PATCH")

    override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse =
        networkClient.updateTaskStatus(
            DataRequestPriority.Foreground,
            taskId,
            buildSharedTaskStatusBody(status)
        )

    override suspend fun deleteTask(taskId: TaskId): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "tasks/${taskId.value}", method = "DELETE")
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
    suspend fun channelTasks(channelId: ChannelId, priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun taskByNumber(channelId: ChannelId, taskNumber: Int, priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun createTasks(channelId: ChannelId, drafts: List<TaskCreateDraft>, priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun claimTask(taskId: TaskId): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun convertMessage(messageId: MessageId): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun unclaimTask(taskId: TaskId): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse
    suspend fun deleteTask(taskId: TaskId): HomeNetworkResponse =
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

    suspend fun claimTask(taskId: TaskId): DataResult<TaskActionResult> =
        transport.claimTask(taskId).toTaskActionResult(source = "tasks_claim", diagnostics = diagnosticsLogger)

    suspend fun convertMessage(messageId: MessageId): DataResult<TaskConvertResult> =
        transport.convertMessage(messageId).toTaskConvertResult(
            source = "tasks_convert_message",
            diagnostics = diagnosticsLogger,
        )

    suspend fun unclaimTask(taskId: TaskId): DataResult<TaskActionResult> =
        transport.unclaimTask(taskId).toTaskActionResult(source = "tasks_unclaim", diagnostics = diagnosticsLogger)

    suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): DataResult<TaskActionResult> =
        transport.updateTaskStatus(taskId, status).toTaskActionResult(
            source = "tasks_status",
            diagnostics = diagnosticsLogger,
            requestedStatus = status,
        )

    suspend fun deleteTask(taskId: TaskId): DataResult<Unit> =
        transport.deleteTask(taskId).toTaskDeleteResult()
}

internal fun HomeNetworkResponse.toTasksResult(
    filter: TaskFilter = TaskFilter(),
    source: String = "tasks",
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): DataResult<TaskSnapshot> {
    unsupportedChannelTasksBlocker()?.let { return DataResult.NoPermission(it) }
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

internal fun HomeNetworkResponse.toTaskActionResult(
    source: String = "tasks_action",
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
    requestedStatus: TaskStatus? = null,
): DataResult<TaskActionResult> {
    unsupportedChannelTasksBlocker()?.let { return DataResult.NoPermission(it) }
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
    unsupportedChannelTasksBlocker()?.let { return DataResult.NoPermission(it) }
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
    unsupportedChannelTasksBlocker()?.let { return DataResult.NoPermission(it) }
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

private fun HomeNetworkResponse.unsupportedChannelTasksBlocker(): PermissionBlocker? {
    if (code != 409) return null
    val body = errorBodyText ?: bodyText
    if (body?.contains("joint_channel_tasks_unsupported", ignoreCase = true) != true) return null
    return PermissionBlocker(
        code = "joint_channel_tasks_unsupported",
        requiredCapability = "channel_tasks",
        serverMessage = tasksServerErrorTextOrNull()
    )
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

@Serializable
internal data class TaskEnvelope(val task: TaskWireDto? = null)

@Serializable
internal data class OkEnvelope(val ok: Boolean = false)

@Serializable
private data class TaskActionErrorEnvelope(
    val error: String? = null,
    val code: String? = null,
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
    val claimedByType: String? = null,
    val claimedById: String? = null,
    val claimedByName: String? = null,
    val claimedAt: String? = null,
    val completedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
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
        canClaim = true,
        description = description?.trim()?.takeIf { it.isNotBlank() }
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
