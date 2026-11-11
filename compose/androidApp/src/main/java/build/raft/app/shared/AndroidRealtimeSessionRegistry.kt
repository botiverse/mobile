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

/** One desired server after [AndroidRealtimeSessionRegistry.reconcileDesired]. */
internal data class AndroidRealtimeDesiredRuntime<RuntimeT>(
    val serverId: ServerId,
    val runtime: RuntimeT,
    /** True when this reconcile created the runtime (it was not kept from an earlier one or a grace window). */
    val created: Boolean,
    /** Cursor parked when this server's previous runtime was dropped as undesired; consumed by this acquire. */
    val parkedResumeSeq: Long?
)

internal data class AndroidRealtimeDesiredReconcileResult<RuntimeT>(
    val desired: List<AndroidRealtimeDesiredRuntime<RuntimeT>>,
    val added: Set<ServerId>,
    val released: Set<ServerId>,
    val parked: Set<ServerId>
) {
    val desiredServerIds: Set<ServerId>
        get() = desired.mapTo(linkedSetOf()) { it.serverId }
}

/** Trims, drops blanks and keeps the caller's order (primary server first). */
internal fun normalizeAndroidDesiredRealtimeServers(serverIds: Collection<String>): Set<ServerId> =
    serverIds.map(String::trim).filter(String::isNotEmpty).mapTo(linkedSetOf(), ::ServerId)

/** Local diagnostics line for one multi-session reconcile: server ids only, never tokens. */
internal fun androidMultiSessionReconcileDiagnosticLine(
    result: AndroidRealtimeDesiredReconcileResult<*>
): String {
    fun Collection<ServerId>.render(): String = joinToString(separator = ",", prefix = "[", postfix = "]") { it.value }
    return "multi_session_reconcile desired=${result.desiredServerIds.render()} " +
        "added=${result.added.render()} released=${result.released.render()} parked=${result.parked.render()}"
}

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
    private val replaceGraceMillis: Long,
    /** Resume cursor of a live runtime, parked when the runtime is dropped for being undesired. */
    private val resumeSeqOf: (RuntimeT) -> Long = { 0L }
) {
    private data class Entry<RuntimeT>(
        val runtime: RuntimeT,
        val owners: MutableSet<String> = linkedSetOf(),
        var pendingRelease: AndroidRealtimePendingRelease? = null
    )

    private val entries = linkedMapOf<ServerId, Entry<RuntimeT>>()

    /**
     * Resume cursors of servers whose runtime was dropped because they left the desired set.
     * The next acquire that recreates the runtime resumes from here instead of a fresh baseline.
     * Cleared by [disconnectAll] (identity replaced, process-wide end) and [disconnectServer]
     * (server-terminal end).
     */
    private val parkedResumeSeq = linkedMapOf<ServerId, Long>()

    /**
     * Multi-session reconcile: [ownerId] holds exactly the servers in [desired]. Servers it no
     * longer wants are released after [undesiredGraceMillis]; re-adding one inside that window
     * keeps its runtime. When the grace elapses the runtime's cursor is parked for the next acquire.
     */
    @Synchronized
    fun reconcileDesired(
        desired: Set<ServerId>,
        ownerId: String,
        undesiredGraceMillis: Long
    ): AndroidRealtimeDesiredReconcileResult<RuntimeT> {
        val cleanOwnerId = ownerId.normalizedRealtimeOwnerId()
        val released = linkedSetOf<ServerId>()
        entries.toList()
            .filter { (serverId, entry) -> serverId !in desired && cleanOwnerId in entry.owners }
            .forEach { (serverId, entry) ->
                entry.owners.remove(cleanOwnerId)
                if (entry.owners.isNotEmpty()) return@forEach
                released += serverId
                if (undesiredGraceMillis <= 0L) {
                    releaseUndesired(serverId, entry)
                } else {
                    entry.pendingRelease?.cancel()
                    entry.pendingRelease =
                        releaseScheduler.schedule(undesiredGraceMillis) {
                            releaseUndesiredAfterGrace(serverId, entry)
                        }
                }
            }
        val added = linkedSetOf<ServerId>()
        val desiredRuntimes =
            desired.map { serverId ->
                val created = serverId !in entries
                val parkedSeq = if (created) parkedResumeSeq.remove(serverId) else null
                if (created) added += serverId
                AndroidRealtimeDesiredRuntime(
                    serverId = serverId,
                    runtime = acquire(serverId, cleanOwnerId),
                    created = created,
                    parkedResumeSeq = parkedSeq
                )
            }
        return AndroidRealtimeDesiredReconcileResult(
            desired = desiredRuntimes,
            added = added,
            released = released,
            parked = parkedResumeSeq.keys.toSet()
        )
    }

    @Synchronized
    fun parkedResumeSeq(serverId: ServerId): Long? = parkedResumeSeq[serverId]

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
        parkedResumeSeq.clear()
        entries.toList().forEach { (serverId, entry) ->
            disconnectAndRemove(serverId, entry, reason)
        }
    }

    @Synchronized
    fun disconnectServer(serverId: ServerId, reason: String): Boolean {
        parkedResumeSeq.remove(serverId)
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

    @Synchronized
    private fun releaseUndesiredAfterGrace(serverId: ServerId, expectedEntry: Entry<RuntimeT>) {
        val current = entries[serverId]
        if (current !== expectedEntry || current.owners.isNotEmpty()) return
        releaseUndesired(serverId, current)
    }

    private fun releaseUndesired(serverId: ServerId, entry: Entry<RuntimeT>) {
        val resumeSeq = resumeSeqOf(entry.runtime)
        if (resumeSeq > 0L) parkedResumeSeq[serverId] = resumeSeq
        disconnectAndRemove(serverId, entry, "undesired_grace_elapsed")
    }

    private fun disconnectAndRemove(serverId: ServerId, entry: Entry<RuntimeT>, reason: String) {
        entry.pendingRelease?.cancel()
        entry.pendingRelease = null
        entries.remove(serverId)
        disconnectRuntime(serverId, entry.runtime, reason)
    }

}

private fun String.normalizedRealtimeOwnerId(): String = trim().ifBlank { "legacy-app-shell" }
