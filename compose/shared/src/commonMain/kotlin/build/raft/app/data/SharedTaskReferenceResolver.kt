package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskSummary

internal enum class SharedTaskReferenceResolutionSource {
    LoadedChannel,
    LoadedServer,
    NetworkChannel,
    NetworkServer
}

internal sealed interface SharedTaskReferenceResolution {
    data class Found(
        val task: TaskSummary,
        val source: SharedTaskReferenceResolutionSource
    ) : SharedTaskReferenceResolution

    data class Ambiguous(
        val taskNumber: Int,
        val candidates: List<TaskSummary>,
        val source: SharedTaskReferenceResolutionSource
    ) : SharedTaskReferenceResolution

    data object NotFound : SharedTaskReferenceResolution
    data object AuthRequired : SharedTaskReferenceResolution
    data object Failed : SharedTaskReferenceResolution
}

internal suspend fun resolveSharedTaskReference(
    serverId: ServerId,
    taskNumber: Int,
    currentChannelId: ChannelId?,
    tasksDataSource: SharedTasksDataSource,
    tasksDataStore: SharedTasksDataStore?,
    /**
     * The ref names its conversation (`#proj:#7`, `dm:@peer:#7`): only a task of
     * [currentChannelId] matches; another conversation's `#7` never does.
     */
    channelQualified: Boolean = false
): SharedTaskReferenceResolution {
    if (channelQualified && currentChannelId == null) return SharedTaskReferenceResolution.NotFound
    tasksDataStore?.bindCurrentOwnerIdentity()
    tasksDataStore
        ?.loadedTaskReferenceResolution(
            serverId = serverId.value,
            taskNumber = taskNumber,
            currentChannelId = currentChannelId,
            channelQualified = channelQualified
        )
        ?.let { return it }

    currentChannelId?.let { channelId ->
        when (
            val channelResult =
                tasksDataSource.loadChannelTaskByNumber(
                    serverId = serverId,
                    channelId = channelId,
                    taskNumber = taskNumber,
                    priority = DataRequestPriority.Foreground
                )
        ) {
            is DataResult.Content -> {
                val task = channelResult.data.task
                val foldOutcome = tasksDataStore?.upsertTaskForCurrentServer(serverId.value, task)
                if (tasksDataStore != null && foldOutcome != SharedTaskFactFoldOutcome.Accepted) {
                    tasksDataStore.loadedTaskReferenceResolution(
                        serverId = serverId.value,
                        taskNumber = taskNumber,
                        currentChannelId = currentChannelId,
                        channelQualified = channelQualified
                    )?.let { return it }
                    // The store already holds this revision (a realtime frame), only not in a loaded
                    // list, or its copy conflicts: the server just answered, so the task exists and
                    // opens on that answer. Null: this store is no longer on this server.
                    if (foldOutcome == null) return SharedTaskReferenceResolution.Failed
                }
                return SharedTaskReferenceResolution.Found(
                    task = task,
                    source = SharedTaskReferenceResolutionSource.NetworkChannel
                )
            }
            is DataResult.NoPermission -> return SharedTaskReferenceResolution.AuthRequired
            is DataResult.Empty,
            is DataResult.Error -> Unit
        }
    }

    val snapshot =
        when (
            val result =
                tasksDataStore?.loadSnapshot(
                    serverId = serverId.value,
                    scope = SharedTasksScope.Server,
                    forceRefresh = true
                ) {
                    tasksDataSource.loadTasks(
                        serverId = serverId,
                        filter = TaskFilter(),
                        priority = DataRequestPriority.Foreground
                    )
                } ?: tasksDataSource.loadTasks(
                    serverId = serverId,
                    filter = TaskFilter(),
                    priority = DataRequestPriority.Foreground
                )
        ) {
            is DataResult.Content -> result.data
            is DataResult.Empty -> return SharedTaskReferenceResolution.NotFound
            is DataResult.Error -> return SharedTaskReferenceResolution.Failed
            is DataResult.NoPermission -> return SharedTaskReferenceResolution.AuthRequired
        }
    return snapshot.tasks.taskReferenceResolution(
        taskNumber = taskNumber,
        currentChannelId = currentChannelId,
        source = SharedTaskReferenceResolutionSource.NetworkServer,
        channelQualified = channelQualified
    ) ?: SharedTaskReferenceResolution.NotFound
}

private fun SharedTasksDataStore.loadedTaskReferenceResolution(
    serverId: String,
    taskNumber: Int,
    currentChannelId: ChannelId?,
    channelQualified: Boolean = false
): SharedTaskReferenceResolution? {
    currentChannelId?.let { channelId ->
        val channelProjection = projection(serverId, SharedTasksScope.Channel(channelId))
        if (
            channelProjection.status == SharedTasksDataStoreStatus.Loaded &&
            channelProjection.isExactScopeLoaded
        ) {
            channelProjection.tasks
                .taskReferenceResolution(
                    taskNumber = taskNumber,
                    currentChannelId = channelId,
                    source = SharedTaskReferenceResolutionSource.LoadedChannel
                )
                ?.let { return it }
        }
    }

    val serverProjection = projection(serverId, SharedTasksScope.Server)
    if (
        serverProjection.status != SharedTasksDataStoreStatus.Loaded ||
        !serverProjection.isExactScopeLoaded
    ) {
        return null
    }
    return serverProjection.tasks.taskReferenceResolution(
        taskNumber = taskNumber,
        currentChannelId = currentChannelId,
        source = SharedTaskReferenceResolutionSource.LoadedServer,
        channelQualified = channelQualified
    )
}

private fun List<TaskSummary>.taskReferenceResolution(
    taskNumber: Int,
    currentChannelId: ChannelId?,
    source: SharedTaskReferenceResolutionSource,
    channelQualified: Boolean = false
): SharedTaskReferenceResolution? {
    val matches =
        filter { task ->
            task.taskNumber == taskNumber && (!channelQualified || task.channelId == currentChannelId)
        }
    val selected =
        when (matches.size) {
            0 -> return null
            1 -> matches.single()
            else -> {
                val currentChannelMatches =
                    currentChannelId?.let { channelId ->
                        matches.filter { task -> task.channelId == channelId }
                    }.orEmpty()
                if (currentChannelMatches.size == 1) {
                    currentChannelMatches.single()
                } else {
                    return SharedTaskReferenceResolution.Ambiguous(
                        taskNumber = taskNumber,
                        candidates = matches.taskReferenceCandidates(currentChannelId),
                        source = source
                    )
                }
            }
        }
    return SharedTaskReferenceResolution.Found(task = selected, source = source)
}

private fun List<TaskSummary>.taskReferenceCandidates(currentChannelId: ChannelId?): List<TaskSummary> =
    sortedWith(
        compareByDescending<TaskSummary> { task ->
            currentChannelId != null && task.channelId == currentChannelId
        }
            .thenBy { task -> task.channelName.orEmpty().lowercase() }
            .thenBy { task -> task.title.lowercase() }
            .thenBy { task -> task.id.value }
    )
