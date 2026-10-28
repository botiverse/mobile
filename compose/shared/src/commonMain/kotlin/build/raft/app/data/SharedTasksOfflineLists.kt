package build.raft.app.data

import build.raft.app.model.TaskSummary
import kotlinx.serialization.Serializable

/**
 * One Tasks list as stored offline (artin: everything fetched is stored locally): the server board's or a
 * channel's tasks as the app last held them, first page only ([TasksOfflineListMaxTasks]).
 */
@Serializable
internal data class StoredTaskList(val tasks: List<TaskSummary>) {
    fun toSnapshot(): TaskSnapshot = TaskSnapshot(tasks = tasks, summaryCounts = tasks.summaryCounts())
}

internal const val TasksOfflineListMaxTasks = 200

/** Params of a stored Tasks list: the server board, or one channel's list. */
internal fun SharedTasksScope.offlineListId(): String =
    when (this) {
        SharedTasksScope.Server -> "server"
        is SharedTasksScope.Channel -> "channel:${channelId.value.trim()}"
    }

internal val TaskListResourceSpec: RaftOfflineResourceSpec<String, StoredTaskList> =
    RaftOfflineResourceSpec(
        name = "task_list",
        version = 1,
        serializer = StoredTaskList.serializer(),
        paramsId = { listId -> listId },
        // The board plus the channel lists opened recently, per account (every server).
        maxEntriesPerAccount = 48,
        maxBytesPerEntry = 2 * 1024 * 1024,
        fit = { list -> if (list.tasks.size <= TasksOfflineListMaxTasks) list else list.copy(tasks = list.tasks.take(TasksOfflineListMaxTasks)) },
    )

/** Environment + account of the Tasks store's bound owner. */
internal data class TasksOfflineOwner(val environmentId: String, val accountId: String) {
    companion object {
        /**
         * The Tasks store's owner identity, `"<apiBaseUrl>|<accountId>"` (see the provider's
         * `ownerIdentityProvider`), split back into its parts; null when it is not that shape.
         */
        fun fromOwnerIdentity(identity: String?): TasksOfflineOwner? {
            val value = identity ?: return null
            val separator = value.lastIndexOf('|')
            if (separator <= 0 || separator == value.lastIndex) return null
            val environment = value.substring(0, separator).normalizedEnvironmentId().takeIf { it.isNotEmpty() } ?: return null
            val account = value.substring(separator + 1).trim().takeIf { it.isNotEmpty() } ?: return null
            return TasksOfflineOwner(environment, account)
        }
    }
}

/**
 * The stored Tasks lists: written from the shared Tasks store's loaded (and confirmed) projections, read as a
 * read-only stand-in by a Tasks list that has nothing to show yet. Wiped per server with every other offline
 * resource when access to the server is lost.
 */
internal class SharedTasksOfflineLists(
    private val resource: RaftOfflineResource<String, StoredTaskList>,
) {
    /** Now, on the offline cache's clock: taken when the state to be stored was observed. */
    fun now(): Long = resource.fetchStarted()

    suspend fun read(owner: TasksOfflineOwner, serverId: String, scope: SharedTasksScope): StoredTaskList? {
        val key = key(owner, serverId, scope, mintedAt = null) ?: return null
        return resource.read(key)
    }

    /** Stores [tasks] for the list, fenced from [observedAt] (a wipe after it wins). */
    suspend fun write(
        owner: TasksOfflineOwner,
        serverId: String,
        scope: SharedTasksScope,
        tasks: List<TaskSummary>,
        observedAt: Long?,
    ): Boolean {
        val key = key(owner, serverId, scope, observedAt) ?: return false
        return resource.write(key, StoredTaskList(tasks))
    }

    /** The list is gone for this account (empty / lost access): its stored copy goes. */
    suspend fun remove(owner: TasksOfflineOwner, serverId: String, scope: SharedTasksScope) {
        val key = key(owner, serverId, scope, mintedAt = null) ?: return
        resource.remove(key)
    }

    // Under the offline cache's current environment only: another environment's list is never mixed in.
    private fun key(
        owner: TasksOfflineOwner,
        serverId: String,
        scope: SharedTasksScope,
        mintedAt: Long?,
    ): RaftOfflineResourceKey<String>? =
        resource.key(owner.accountId, serverId, scope.offlineListId(), mintedAt)
            ?.takeIf { it.environmentId == owner.environmentId }
}
