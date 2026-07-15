package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import build.raft.app.model.ServerId
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
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
    internal val owner: ProcessServerMembersOwner,
    internal val generation: ProcessServerMembersGeneration,
    internal val token: Any
)

/**
 * Process-owned read core. It owns only immutable roster snapshots, their versions, and active
 * network flights. Shell selection, mutations, presence projection, and UI state stay outside.
 */
internal class ProcessServerMembersCore(
    private val fetchMembers: suspend (DataRequestPriority, ServerId) -> DataResult<MemberSnapshot>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val maxEntries: Int = 16,
    private val logger: (String) -> Unit = {}
) {
    init {
        require(maxEntries > 0)
    }

    private val mutex = Mutex()
    private val cache = linkedMapOf<ProcessServerMembersOwner, CacheEntry>()
    private val activeFlights = mutableMapOf<ProcessServerMembersOwner, ActiveFlight>()
    private val ownerGenerations = mutableMapOf<ProcessServerMembersOwner, Long>()
    private var processGeneration = 0L
    private var nextVersion = 0L
    private var nextTerminalRevision = 0L
    private val terminalRevisions = mutableMapOf<ProcessServerMembersOwner, Long>()

    suspend fun load(
        owner: ProcessServerMembersOwner,
        priority: DataRequestPriority,
        force: Boolean,
        reason: String,
        onFlight: suspend (ProcessServerMembersFlightPermit) -> Unit = {}
    ): ProcessServerMembersLoadResult {
        val decision =
            mutex.withLock {
                activeFlights[owner]?.let { active ->
                    logger(
                        "process_members_flight result=join ${owner.diagnosticsKey} " +
                            "force=$force priority=${priority.name} reason=$reason"
                    )
                    return@withLock LoadDecision.Flight(active, started = false)
                }
                if (!force) {
                    cache.remove(owner)?.let { cached ->
                        if (cached.createdAt.elapsedNow() <= CacheTtl) {
                            cache[owner] = cached
                            logger(
                                "process_members_flight result=cache_hit ${owner.diagnosticsKey} " +
                                    "version=${cached.version} priority=${priority.name} reason=$reason"
                            )
                            return@withLock LoadDecision.Cached(cached, ownerGeneration(owner))
                        }
                    }
                }
                val token = Any()
                val generation = ownerGeneration(owner)
                val deferred =
                    scope.async(start = CoroutineStart.LAZY) {
                        produce(owner, generation, token, priority, force, reason)
                    }
                val flight = ActiveFlight(token, generation, deferred)
                activeFlights[owner] = flight
                logger(
                    "process_members_flight result=start ${owner.diagnosticsKey} " +
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
                    owner = owner,
                    generation = decision.generation
                )
            is LoadDecision.Flight -> {
                try {
                    onFlight(
                        ProcessServerMembersFlightPermit(
                            owner = owner,
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
        snapshot: MemberSnapshot
    ): ProcessServerMembersSeedResult =
        mutex.withLock {
            cache[owner]?.let {
                return@withLock ProcessServerMembersSeedResult(version = it.version, inserted = false)
            }
            val version = nextSnapshotVersion()
            val terminalRevision = nextOwnerTerminalRevision(owner)
            cache[owner] =
                CacheEntry(
                    result = DataResult.Content(snapshot),
                    version = version,
                    terminalRevision = terminalRevision,
                    createdAt = TimeSource.Monotonic.markNow()
                )
            trimCache()
            ProcessServerMembersSeedResult(version = version, inserted = true)
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
            logger("process_members_invalidate scope=all generation=$processGeneration reason=$reason")
        }
    }

    suspend fun invalidate(owner: ProcessServerMembersOwner, reason: String) {
        mutex.withLock {
            ownerGenerations[owner] = (ownerGenerations[owner] ?: 0L) + 1L
            cache.remove(owner)
            activeFlights.remove(owner)
            terminalRevisions.remove(owner)
            logger("process_members_invalidate scope=owner ${owner.diagnosticsKey} reason=$reason")
        }
    }

    private suspend fun produce(
        owner: ProcessServerMembersOwner,
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
                    fetchMembers(priority, ServerId(owner.serverId))
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
            }
        }
    }

    private fun ownerGeneration(owner: ProcessServerMembersOwner): ProcessServerMembersGeneration =
        ProcessServerMembersGeneration(processGeneration, ownerGenerations[owner] ?: 0L)

    private fun nextSnapshotVersion(): Long {
        nextVersion += 1L
        return nextVersion
    }

    private fun nextOwnerTerminalRevision(owner: ProcessServerMembersOwner): Long {
        nextTerminalRevision += 1L
        terminalRevisions[owner] = nextTerminalRevision
        return nextTerminalRevision
    }

    private fun trimCache() {
        while (cache.size > maxEntries) {
            cache.remove(cache.keys.first())
        }
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
        val result: Deferred<ProcessServerMembersLoadResult>
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
    }
}

private fun DataResult<MemberSnapshot>.resultLabel(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "no_permission:${blocker.code}"
    }
