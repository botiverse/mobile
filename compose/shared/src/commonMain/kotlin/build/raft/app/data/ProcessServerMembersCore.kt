package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal data class ProcessServerMembersOwner(
    val environmentId: String,
    val accountId: String,
    val serverId: String
) {
    init {
        require(environmentId.isNotBlank())
        require(accountId.isNotBlank())
        require(serverId.isNotBlank())
    }

    val diagnosticsKey: String
        get() = "ownerHash=${"$environmentId|$accountId|$serverId".hashCode()} serverId=$serverId"
}

internal data class ProcessServerMembersLoadResult(
    val result: DataResult<MemberSnapshot>,
    val version: Long,
    val publicationCurrent: Boolean,
    val reduceMs: Long,
    val terminalRevision: Long,
    internal val owner: ProcessServerMembersDirectoryOwner,
    internal val generation: ProcessServerMembersGeneration,
    /** Answered from the process cache (possibly a durable seed), not by a network flight. */
    val cacheHit: Boolean = false
)

/**
 * One `/agents` read ([ProcessServerMembersCore.loadAgents]). [generation] is the owner's agents
 * generation when the read started: [ProcessServerMembersCore.invalidate] (a roster change applied,
 * a forced reload, a reconnect) moves it, and an older result's rows are then dropped.
 */
internal data class ProcessServerAgentsLoadResult(
    val result: DataResult<List<MemberProfile>>,
    internal val owner: ProcessServerMembersOwner,
    internal val generation: ProcessServerMembersGeneration
)

internal data class ProcessServerMembersSeedResult(
    val version: Long,
    val inserted: Boolean
)

internal data class ProcessServerMembersGeneration(
    val process: Long,
    val owner: Long
)

internal data class ProcessServerMembersFlightPermit internal constructor(
    internal val owner: ProcessServerMembersDirectoryOwner,
    internal val generation: ProcessServerMembersGeneration,
    internal val token: Any
)

internal data class ProcessServerMembersDirectoryOwner(
    val processOwner: ProcessServerMembersOwner,
    val ownerWriteGeneration: Long
) {
    val diagnosticsKey: String
        get() = "${processOwner.diagnosticsKey} writeGeneration=$ownerWriteGeneration"
}

/**
 * Process-owned read core. It owns only immutable roster snapshots, their versions, and active
 * network flights. Shell selection, mutations, presence projection, and UI state stay outside.
 */
internal class ProcessServerMembersCore(
    private val fetchMembers: suspend (DataRequestPriority, ServerId) -> DataResult<MemberSnapshot>,
    private val fetchAgents: suspend (DataRequestPriority, ServerId) -> DataResult<List<MemberProfile>> = { _, _ ->
        DataResult.Empty(EmptyReason.NoMembers)
    },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val maxEntries: Int = 16,
    private val logger: (String) -> Unit = {},
    private val staleForcedFlightAge: Duration = StaleForcedFlightAge
) {
    init {
        require(maxEntries > 0)
    }

    private val mutex = Mutex()
    private val cache = linkedMapOf<ProcessServerMembersDirectoryOwner, CacheEntry>()
    private val activeFlights = mutableMapOf<ProcessServerMembersDirectoryOwner, ActiveFlight>()
    private val ownerGenerations = mutableMapOf<ProcessServerMembersDirectoryOwner, Long>()
    private var processGeneration = 0L
    private var nextVersion = 0L
    private var nextTerminalRevision = 0L
    private val terminalRevisions = linkedMapOf<ProcessServerMembersDirectoryOwner, Long>()
    private val agentFlights = mutableMapOf<ProcessServerMembersOwner, AgentsFlight>()
    private val agentGenerations = mutableMapOf<ProcessServerMembersOwner, Long>()

    /**
     * Incremental sync's presence refetch: `/agents` of [owner] alone, one read shared by every
     * shell store asking meanwhile. Not cached (presence is the point of the read).
     */
    suspend fun loadAgents(
        owner: ProcessServerMembersOwner,
        priority: DataRequestPriority,
        reason: String
    ): ProcessServerAgentsLoadResult {
        val (flight, started) =
            mutex.withLock {
                agentFlights[owner]?.let { active ->
                    logger("process_agents_flight result=join ${owner.diagnosticsKey} reason=$reason")
                    return@withLock active to false
                }
                val token = Any()
                val generation = agentsGeneration(owner)
                val deferred =
                    scope.async(start = CoroutineStart.LAZY) {
                        try {
                            val fetched =
                                try {
                                    fetchAgents(priority, ServerId(owner.serverId))
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (error: Throwable) {
                                    logger(
                                        "process_agents_load result=exception ${owner.diagnosticsKey} " +
                                            "type=${error::class.simpleName} reason=$reason"
                                    )
                                    DataResult.Error(ScreenError(code = "members_exception", retryable = true))
                                }
                            logger("process_agents_load result=${fetched.resultLabel()} ${owner.diagnosticsKey} reason=$reason")
                            ProcessServerAgentsLoadResult(fetched, owner, generation)
                        } finally {
                            mutex.withLock { if (agentFlights[owner]?.token === token) agentFlights.remove(owner) }
                        }
                    }
                val flight = AgentsFlight(token, deferred)
                agentFlights[owner] = flight
                logger("process_agents_flight result=start ${owner.diagnosticsKey} reason=$reason")
                flight to true
            }
        if (started) flight.result.start()
        return flight.result.await()
    }

    /** Runs [publish] when no roster change or reload moved [loadResult]'s owner since its read started. */
    suspend fun <T> commitAgentsIfCurrent(
        loadResult: ProcessServerAgentsLoadResult,
        publish: () -> T
    ): T? =
        mutex.withLock {
            if (loadResult.generation != agentsGeneration(loadResult.owner)) return@withLock null
            publish()
        }

    suspend fun load(
        owner: ProcessServerMembersOwner,
        priority: DataRequestPriority,
        force: Boolean,
        reason: String,
        ownerWriteGeneration: Long = 0L,
        onFlight: suspend (ProcessServerMembersFlightPermit) -> Unit = {}
    ): ProcessServerMembersLoadResult {
        val directoryOwner = ProcessServerMembersDirectoryOwner(owner, ownerWriteGeneration)
        val decision =
            mutex.withLock {
                activeFlights[directoryOwner]?.let { active ->
                    if (force && active.startedAt.elapsedNow() >= staleForcedFlightAge) {
                        // Task #150: a flight started during a network outage can outlive it (a
                        // transport call that never returns). A forced load (the user's Retry) must
                        // not join it forever: drop it and bump the owner generation so its late
                        // result is never published, then start a fresh flight below.
                        activeFlights.remove(directoryOwner)
                        ownerGenerations[directoryOwner] = (ownerGenerations[directoryOwner] ?: 0L) + 1L
                        logger(
                            "process_members_flight result=replace_stale ${directoryOwner.diagnosticsKey} " +
                                "ageMs=${active.startedAt.elapsedNow().inWholeMilliseconds} " +
                                "priority=${priority.name} reason=$reason"
                        )
                    } else {
                        logger(
                            "process_members_flight result=join ${directoryOwner.diagnosticsKey} " +
                                "force=$force priority=${priority.name} reason=$reason"
                        )
                        return@withLock LoadDecision.Flight(active, started = false)
                    }
                }
                if (!force) {
                    cache.remove(directoryOwner)?.let { cached ->
                        if (cached.createdAt.elapsedNow() <= CacheTtl) {
                            cache[directoryOwner] = cached
                            logger(
                                "process_members_flight result=cache_hit ${directoryOwner.diagnosticsKey} " +
                                    "version=${cached.version} priority=${priority.name} reason=$reason"
                            )
                            return@withLock LoadDecision.Cached(cached, ownerGeneration(directoryOwner))
                        }
                    }
                }
                val token = Any()
                val generation = ownerGeneration(directoryOwner)
                val deferred =
                    scope.async(start = CoroutineStart.LAZY) {
                        produce(directoryOwner, generation, token, priority, force, reason)
                    }
                val flight = ActiveFlight(token, generation, deferred)
                activeFlights[directoryOwner] = flight
                logger(
                    "process_members_flight result=start ${directoryOwner.diagnosticsKey} " +
                        "force=$force priority=${priority.name} reason=$reason"
                )
                LoadDecision.Flight(flight, started = true)
            }
        return when (decision) {
            is LoadDecision.Cached ->
                ProcessServerMembersLoadResult(
                    result = decision.entry.result,
                    version = decision.entry.version,
                    publicationCurrent = true,
                    reduceMs = 0L,
                    terminalRevision = decision.entry.terminalRevision,
                    owner = directoryOwner,
                    generation = decision.generation,
                    cacheHit = true
                )
            is LoadDecision.Flight -> {
                try {
                    onFlight(
                        ProcessServerMembersFlightPermit(
                            owner = directoryOwner,
                            generation = decision.flight.generation,
                            token = decision.flight.token
                        )
                    )
                } finally {
                    if (decision.started) decision.flight.result.start()
                }
                decision.flight.result.await()
            }
        }
    }

    suspend fun seedIfAbsent(
        owner: ProcessServerMembersOwner,
        snapshot: MemberSnapshot,
        ownerWriteGeneration: Long = 0L
    ): ProcessServerMembersSeedResult {
        val directoryOwner = ProcessServerMembersDirectoryOwner(owner, ownerWriteGeneration)
        return mutex.withLock {
            cache[directoryOwner]?.let {
                return@withLock ProcessServerMembersSeedResult(version = it.version, inserted = false)
            }
            val version = nextSnapshotVersion()
            val terminalRevision = nextOwnerTerminalRevision(directoryOwner)
            cache[directoryOwner] =
                CacheEntry(
                    result = DataResult.Content(snapshot),
                    version = version,
                    terminalRevision = terminalRevision,
                    createdAt = TimeSource.Monotonic.markNow()
                )
            trimCache()
            ProcessServerMembersSeedResult(version = version, inserted = true)
        }
    }

    suspend fun <T> commitIfCurrent(
        loadResult: ProcessServerMembersLoadResult,
        publish: () -> T
    ): T? =
        mutex.withLock {
            if (!loadResult.publicationCurrent) return@withLock null
            if (loadResult.generation != ownerGeneration(loadResult.owner)) return@withLock null
            if (terminalRevisions[loadResult.owner] != loadResult.terminalRevision) return@withLock null
            if (loadResult.version > 0L && cache[loadResult.owner]?.version != loadResult.version) {
                return@withLock null
            }
            publish()
        }

    suspend fun <T> commitFlightIfCurrent(
        permit: ProcessServerMembersFlightPermit,
        publish: () -> T
    ): T? =
        mutex.withLock {
            if (permit.generation != ownerGeneration(permit.owner)) return@withLock null
            if (activeFlights[permit.owner]?.token !== permit.token) return@withLock null
            publish()
        }

    suspend fun invalidateAll(reason: String) {
        mutex.withLock {
            processGeneration += 1L
            cache.clear()
            activeFlights.clear()
            ownerGenerations.clear()
            terminalRevisions.clear()
            agentFlights.clear()
            agentGenerations.clear()
            logger("process_members_invalidate scope=all generation=$processGeneration reason=$reason")
        }
    }

    suspend fun invalidate(owner: ProcessServerMembersOwner, reason: String) {
        mutex.withLock {
            val ownedKeys =
                (ownerGenerations.keys + cache.keys + activeFlights.keys + terminalRevisions.keys)
                    .filterTo(mutableSetOf()) { it.processOwner == owner }
            // An `/agents` read started before this point answers for an older roster.
            agentFlights.remove(owner)
            agentGenerations[owner] = (agentGenerations[owner] ?: 0L) + 1L
            ownedKeys.forEach { key ->
                val hadActiveFlight = activeFlights.remove(key) != null
                cache.remove(key)
                terminalRevisions.remove(key)
                if (hadActiveFlight) {
                    ownerGenerations[key] = (ownerGenerations[key] ?: 0L) + 1L
                } else {
                    ownerGenerations.remove(key)
                }
            }
            logger("process_members_invalidate scope=owner ${owner.diagnosticsKey} reason=$reason")
        }
    }

    private suspend fun produce(
        owner: ProcessServerMembersDirectoryOwner,
        generation: ProcessServerMembersGeneration,
        token: Any,
        priority: DataRequestPriority,
        force: Boolean,
        reason: String
    ): ProcessServerMembersLoadResult {
        try {
            val startedAt = TimeSource.Monotonic.markNow()
            val fetched =
                try {
                    fetchMembers(priority, ServerId(owner.processOwner.serverId))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    logger(
                        "process_members_load result=exception ${owner.diagnosticsKey} " +
                            "type=${error::class.simpleName} reason=$reason"
                    )
                    DataResult.Error(ScreenError(code = "members_exception", retryable = true))
                }
            val reduceStartedAt = TimeSource.Monotonic.markNow()
            val reduced =
                when (fetched) {
                    is DataResult.Content ->
                        DataResult.Content(
                            fetched.data.copy(
                                members =
                                    fetched.data.members.distinctBy { member ->
                                        "${member.kind.name}:${member.id.value.trim()}"
                                    }
                            )
                        )
                    else -> fetched
                }
            val reduceMs = reduceStartedAt.elapsedNow().inWholeMilliseconds
            return mutex.withLock {
                val current = generation == ownerGeneration(owner)
                val activeCurrent = activeFlights[owner]?.token === token
                var version = 0L
                var terminalRevision = 0L
                if (current && activeCurrent) {
                    terminalRevision = nextOwnerTerminalRevision(owner)
                }
                if (current && activeCurrent && (reduced is DataResult.Content || reduced is DataResult.Empty)) {
                    version = nextSnapshotVersion()
                    cache.remove(owner)
                    cache[owner] =
                        CacheEntry(
                            result = reduced,
                            version = version,
                            terminalRevision = terminalRevision,
                            createdAt = TimeSource.Monotonic.markNow()
                        )
                    trimCache()
                } else if (current && activeCurrent) {
                    cache.remove(owner)
                }
                if (activeCurrent) activeFlights.remove(owner)
                logger(
                    "process_members_load result=${reduced.resultLabel()} ${owner.diagnosticsKey} " +
                        "version=$version publicationCurrent=${current && activeCurrent} force=$force " +
                        "priority=${priority.name} reason=$reason " +
                        "reduceMs=$reduceMs durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
                ProcessServerMembersLoadResult(
                    result = reduced,
                    version = version,
                    publicationCurrent = current && activeCurrent,
                    reduceMs = reduceMs,
                    terminalRevision = terminalRevision,
                    owner = owner,
                    generation = generation
                )
            }
        } finally {
            mutex.withLock {
                if (activeFlights[owner]?.token === token) activeFlights.remove(owner)
                pruneDirectoryOwnerMetadataIfUnused(owner)
            }
        }
    }

    private fun ownerGeneration(owner: ProcessServerMembersDirectoryOwner): ProcessServerMembersGeneration =
        ProcessServerMembersGeneration(processGeneration, ownerGenerations[owner] ?: 0L)

    private fun agentsGeneration(owner: ProcessServerMembersOwner): ProcessServerMembersGeneration =
        ProcessServerMembersGeneration(processGeneration, agentGenerations[owner] ?: 0L)

    private fun nextSnapshotVersion(): Long {
        nextVersion += 1L
        return nextVersion
    }

    private fun nextOwnerTerminalRevision(owner: ProcessServerMembersDirectoryOwner): Long {
        nextTerminalRevision += 1L
        terminalRevisions.remove(owner)
        terminalRevisions[owner] = nextTerminalRevision
        trimTerminalRevisions()
        return nextTerminalRevision
    }

    private fun trimTerminalRevisions() {
        while (terminalRevisions.size > maxEntries) {
            val evictedOwner = terminalRevisions.keys.first()
            terminalRevisions.remove(evictedOwner)
            cache.remove(evictedOwner)
            pruneDirectoryOwnerMetadataIfUnused(evictedOwner)
        }
    }

    private fun trimCache() {
        while (cache.size > maxEntries) {
            val evictedOwner = cache.keys.first()
            cache.remove(evictedOwner)
            terminalRevisions.remove(evictedOwner)
            pruneDirectoryOwnerMetadataIfUnused(evictedOwner)
        }
    }

    private fun pruneDirectoryOwnerMetadataIfUnused(owner: ProcessServerMembersDirectoryOwner) {
        if (cache.containsKey(owner) || activeFlights.containsKey(owner) || terminalRevisions.containsKey(owner)) return
        ownerGenerations.remove(owner)
    }

    private data class CacheEntry(
        val result: DataResult<MemberSnapshot>,
        val version: Long,
        val terminalRevision: Long,
        val createdAt: TimeMark
    )

    private data class ActiveFlight(
        val token: Any,
        val generation: ProcessServerMembersGeneration,
        val result: Deferred<ProcessServerMembersLoadResult>,
        val startedAt: TimeMark = TimeSource.Monotonic.markNow()
    )

    private class AgentsFlight(
        val token: Any,
        val result: Deferred<ProcessServerAgentsLoadResult>
    )

    private sealed interface LoadDecision {
        data class Cached(
            val entry: CacheEntry,
            val generation: ProcessServerMembersGeneration
        ) : LoadDecision
        data class Flight(val flight: ActiveFlight, val started: Boolean) : LoadDecision
    }

    private companion object {
        val CacheTtl = 10.minutes
        /** A forced load replaces an active flight older than this instead of joining it (task #150). */
        val StaleForcedFlightAge = 45.seconds
    }
}

private fun DataResult<*>.resultLabel(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "no_permission:${blocker.code}"
    }
