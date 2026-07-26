package build.raft.app.shared

import build.raft.app.model.ServerId

internal fun interface AndroidRealtimePendingRelease {
    fun cancel()
}

internal fun interface AndroidRealtimeReleaseScheduler {
    fun schedule(delayMillis: Long, block: () -> Unit): AndroidRealtimePendingRelease
}

internal enum class AndroidRealtimeOwnerReleaseMode {
    ReplaceGrace,
    ExplicitClose
}

internal data class AndroidRealtimeSessionRegistrySnapshot(
    val serverId: ServerId,
    val owners: Set<String>,
    val releasePending: Boolean
)

internal data class AndroidCurrentRealtimeServerSelection(
    val serverId: ServerId?,
    val normalizedServerCount: Int,
    val rejectedMultipleServers: Boolean
)

internal fun selectAndroidCurrentRealtimeServer(serverIds: Set<String>): AndroidCurrentRealtimeServerSelection {
    val normalized = serverIds.map(String::trim).filter(String::isNotEmpty).toSet()
    return AndroidCurrentRealtimeServerSelection(
        serverId = normalized.singleOrNull()?.let(::ServerId),
        normalizedServerCount = normalized.size,
        rejectedMultipleServers = normalized.size > 1
    )
}

/** Tracks visible Android shells without letting one paused window background another. */
internal class AndroidVisibleShellOwners {
    private val owners = linkedSetOf<String>()

    @Synchronized
    fun shown(shellInstanceId: String): Boolean {
        val wasEmpty = owners.isEmpty()
        owners += shellInstanceId.normalizedRealtimeOwnerId()
        return wasEmpty && owners.isNotEmpty()
    }

    @Synchronized
    fun hidden(shellInstanceId: String): Boolean {
        if (!owners.remove(shellInstanceId.normalizedRealtimeOwnerId())) return false
        return owners.isEmpty()
    }

    @Synchronized
    fun snapshot(): Set<String> = owners.toSet()
}

/**
 * Owns one independent realtime runtime per server. Shell ownership is set-like:
 * duplicate acquire/release calls for the same stable shell identity are idempotent.
 */
internal class AndroidRealtimeSessionRegistry<RuntimeT>(
    private val createRuntime: (ServerId) -> RuntimeT,
    private val disconnectRuntime: (ServerId, RuntimeT, String) -> Unit,
    private val releaseScheduler: AndroidRealtimeReleaseScheduler,
    private val replaceGraceMillis: Long
) {
    private data class Entry<RuntimeT>(
        val runtime: RuntimeT,
        val owners: MutableSet<String> = linkedSetOf(),
        var pendingRelease: AndroidRealtimePendingRelease? = null
    )

    private val entries = linkedMapOf<ServerId, Entry<RuntimeT>>()

    @Synchronized
    fun acquire(serverId: ServerId, shellInstanceId: String): RuntimeT {
        val cleanShellId = shellInstanceId.normalizedRealtimeOwnerId()
        val entry = entries.getOrPut(serverId) { Entry(createRuntime(serverId)) }
        entry.pendingRelease?.cancel()
        entry.pendingRelease = null
        entry.owners += cleanShellId
        return entry.runtime
    }

    @Synchronized
    fun acquireCurrent(
        serverId: ServerId,
        shellInstanceId: String,
        disconnectReason: String
    ): RuntimeT {
        entries.toList()
            .filterNot { (existingServerId, _) -> existingServerId == serverId }
            .forEach { (staleServerId, staleEntry) ->
                disconnectAndRemove(staleServerId, staleEntry, disconnectReason)
            }
        return acquire(serverId, shellInstanceId)
    }

    @Synchronized
    fun release(
        serverId: ServerId,
        shellInstanceId: String,
        mode: AndroidRealtimeOwnerReleaseMode
    ): Boolean {
        val entry = entries[serverId] ?: return false
        val cleanShellId = shellInstanceId.normalizedRealtimeOwnerId()
        if (!entry.owners.remove(cleanShellId)) return false
        if (entry.owners.isNotEmpty()) return false
        if (mode == AndroidRealtimeOwnerReleaseMode.ExplicitClose || replaceGraceMillis <= 0L) {
            disconnectAndRemove(serverId, entry, "last_owner_explicit_close")
            return true
        }
        entry.pendingRelease?.cancel()
        entry.pendingRelease =
            releaseScheduler.schedule(replaceGraceMillis) {
                releaseAfterGrace(serverId, entry)
            }
        return false
    }

    @Synchronized
    fun runtime(serverId: ServerId): RuntimeT? = entries[serverId]?.runtime

    @Synchronized
    fun runtimes(): List<RuntimeT> = entries.values.map { it.runtime }

    @Synchronized
    fun snapshots(): List<AndroidRealtimeSessionRegistrySnapshot> =
        entries.map { (serverId, entry) ->
            AndroidRealtimeSessionRegistrySnapshot(
                serverId = serverId,
                owners = entry.owners.toSet(),
                releasePending = entry.pendingRelease != null
            )
        }

    @Synchronized
    fun disconnectAll(reason: String) {
        entries.toList().forEach { (serverId, entry) ->
            disconnectAndRemove(serverId, entry, reason)
        }
    }

    @Synchronized
    fun disconnectServer(serverId: ServerId, reason: String): Boolean {
        val entry = entries[serverId] ?: return false
        disconnectAndRemove(serverId, entry, reason)
        return true
    }

    @Synchronized
    private fun releaseAfterGrace(serverId: ServerId, expectedEntry: Entry<RuntimeT>) {
        val current = entries[serverId]
        if (current !== expectedEntry || current.owners.isNotEmpty()) return
        disconnectAndRemove(serverId, current, "last_owner_replace_grace_elapsed")
    }

    private fun disconnectAndRemove(serverId: ServerId, entry: Entry<RuntimeT>, reason: String) {
        entry.pendingRelease?.cancel()
        entry.pendingRelease = null
        entries.remove(serverId)
        disconnectRuntime(serverId, entry.runtime, reason)
    }

}

private fun String.normalizedRealtimeOwnerId(): String = trim().ifBlank { "legacy-app-shell" }
