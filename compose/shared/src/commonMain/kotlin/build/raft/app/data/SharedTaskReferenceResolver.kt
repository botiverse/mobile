package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskSummary

internal enum class SharedTaskReferenceResolutionSource {
    LoadedChannel,
    LoadedServer,
    NetworkChannel
}

internal sealed interface SharedTaskReferenceResolution {
    data class Found(
        val task: TaskSummary,
        val source: SharedTaskReferenceResolutionSource
    ) : SharedTaskReferenceResolution

    data object NotFound : SharedTaskReferenceResolution
    data object AuthRequired : SharedTaskReferenceResolution
    data object Failed : SharedTaskReferenceResolution
}

/**
 * Task [taskNumber] of conversation [currentChannelId], as the web opens a task ref
 * (`openTaskByChannelNumber`: `GET /tasks/channel/:channelId/number/:n`). A task number is unique
 * only inside its channel, so only that channel's task ever matches: there is no server-wide search
 * and no picking among other channels' tasks of the same number. No conversation: [NotFound].
 */
internal suspend fun resolveSharedTaskReference(
    serverId: ServerId,
    taskNumber: Int,
    currentChannelId: ChannelId?,
    tasksDataSource: SharedTasksDataSource,
    tasksDataStore: SharedTasksDataStore?,
    /**
     * The resolver whose single fetch phase the network lookup runs in, so a task ref's spinner is
     * the same state as a thread or message ref's. Null: no loading state is published.
     */
    referenceResolver: SharedReferenceResolver? = null,
    /** The chip this open started from. */
    actionId: String? = null
): SharedTaskReferenceResolution {
    val channelId = currentChannelId ?: return SharedTaskReferenceResolution.NotFound
    tasksDataStore?.bindCurrentOwnerIdentity()
    tasksDataStore
        ?.loadedTaskReference(serverId = serverId.value, taskNumber = taskNumber, channelId = channelId)
        ?.let { return it }

    val fetch: suspend () -> SharedTaskReferenceResolution = {
        fetchSharedTaskReference(
            serverId = serverId,
            taskNumber = taskNumber,
            channelId = channelId,
            tasksDataSource = tasksDataSource,
            tasksDataStore = tasksDataStore
        )
    }
    return referenceResolver?.fetchPhase(actionId) { fetch() } ?: fetch()
}

/** A local miss: the channel's task by number (the web's only lookup). */
private suspend fun fetchSharedTaskReference(
    serverId: ServerId,
    taskNumber: Int,
    channelId: ChannelId,
    tasksDataSource: SharedTasksDataSource,
    tasksDataStore: SharedTasksDataStore?
): SharedTaskReferenceResolution =
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
                // The store already holds this revision (a realtime frame), only not in a loaded
                // list, or its copy conflicts: the server just answered, so the task exists and
                // opens on that answer. Null: this store is no longer on this server.
                tasksDataStore.loadedTaskReference(serverId = serverId.value, taskNumber = taskNumber, channelId = channelId)
                    ?: if (foldOutcome == null) {
                        SharedTaskReferenceResolution.Failed
                    } else {
                        SharedTaskReferenceResolution.Found(task, SharedTaskReferenceResolutionSource.NetworkChannel)
                    }
            } else {
                SharedTaskReferenceResolution.Found(task = task, source = SharedTaskReferenceResolutionSource.NetworkChannel)
            }
        }
        is DataResult.NoPermission -> SharedTaskReferenceResolution.AuthRequired
        is DataResult.Empty -> SharedTaskReferenceResolution.NotFound
        is DataResult.Error -> SharedTaskReferenceResolution.Failed
    }

/** The channel's task [taskNumber] from a loaded channel list, else from the loaded server list. */
private fun SharedTasksDataStore.loadedTaskReference(
    serverId: String,
    taskNumber: Int,
    channelId: ChannelId
): SharedTaskReferenceResolution? {
    val channelProjection = projection(serverId, SharedTasksScope.Channel(channelId))
    if (channelProjection.status == SharedTasksDataStoreStatus.Loaded && channelProjection.isExactScopeLoaded) {
        channelProjection.tasks
            .firstOrNull { task -> task.taskNumber == taskNumber }
            ?.let { return SharedTaskReferenceResolution.Found(it, SharedTaskReferenceResolutionSource.LoadedChannel) }
    }
    val serverProjection = projection(serverId, SharedTasksScope.Server)
    if (serverProjection.status != SharedTasksDataStoreStatus.Loaded || !serverProjection.isExactScopeLoaded) return null
    return serverProjection.tasks
        .firstOrNull { task -> task.taskNumber == taskNumber && task.channelId == channelId }
        ?.let { SharedTaskReferenceResolution.Found(it, SharedTaskReferenceResolutionSource.LoadedServer) }
}
