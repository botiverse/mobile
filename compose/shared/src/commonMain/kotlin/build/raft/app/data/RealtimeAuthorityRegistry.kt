package build.raft.app.data

import build.raft.app.network.sync.DurablePartitionKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val RealtimeAuthRevisionStorageKey = "realtimeAuthority:authRevision:v1"

/**
 * Identity of the credentials behind an auth revision: WHO (principal) is authorized on WHICH servers,
 * reached through WHICH API environment. The environment is part of the identity, not metadata — the
 * same principal id on staging and production are two different authorities, so every grant minted from
 * this session (and every durable partition derived from a grant) carries it.
 */
internal data class RealtimeAuthoritySession(
    val principalId: String,
    val serverIds: List<String>,
    val environmentId: String,
) {
    init {
        require(environmentId.isNotBlank()) { "realtime authority environmentId must not be blank" }
    }
}

internal data class RealtimeAuthRevision(
    val generation: Long,
    val session: RealtimeAuthoritySession?,
)

/**
 * Durable wire shape of [RealtimeAuthRevision]. Revisions written before the environment dimension existed
 * carry no `environmentId`; they decode with a null session (no authority) while KEEPING their generation, so
 * the next provider reconcile advances to generation+1 with the real environment instead of restarting the
 * counter from zero and colliding with generation-keyed durable rows.
 */
@Serializable
private data class StoredRealtimeAuthRevision(
    val generation: Long,
    val session: StoredRealtimeAuthoritySession?,
)

@Serializable
private data class StoredRealtimeAuthoritySession(
    val principalId: String,
    val serverIds: List<String>,
    val environmentId: String? = null,
)

private fun RealtimeAuthRevision.toStored(): StoredRealtimeAuthRevision =
    StoredRealtimeAuthRevision(
        generation = generation,
        session =
            session?.let { value ->
                StoredRealtimeAuthoritySession(
                    principalId = value.principalId,
                    serverIds = value.serverIds,
                    environmentId = value.environmentId,
                )
            },
    )

private fun StoredRealtimeAuthRevision.toDomain(): RealtimeAuthRevision =
    RealtimeAuthRevision(
        generation = generation,
        session =
            session?.let { value ->
                val environmentId = value.environmentId?.trim()?.takeIf { it.isNotEmpty() } ?: return@let null
                RealtimeAuthoritySession(
                    principalId = value.principalId,
                    serverIds = value.serverIds,
                    environmentId = environmentId,
                )
            },
    )

internal data class RealtimeScopeLease(
    val scopeKey: String,
    val id: String,
)

internal data class RealtimeSlotKey(
    val scopeLeaseId: String,
    val serverId: String,
)

internal data class RealtimeConnectionWitness(
    val scopeLeaseId: String,
    val principalId: String,
    val authGeneration: Long,
    val connectionFamilyId: String,
)

internal data class RealtimeGrantRecord(
    val slotKey: RealtimeSlotKey,
    val principalId: String,
    val environmentId: String,
    val authGeneration: Long,
    val connectionFamilyId: String,
    val connectionEpochId: String,
) {
    val scopeLeaseId: String
        get() = slotKey.scopeLeaseId

    val serverId: String
        get() = slotKey.serverId
}

/**
 * The ONE derivation from a grant to its durable partition. Every write-path partition must come from
 * here so the identity dimensions (environment, server, principal) can never be dropped or defaulted at a
 * construction site; only the consumer id varies per ledger.
 */
internal fun RealtimeGrantRecord.durablePartition(consumerId: String): DurablePartitionKey =
    DurablePartitionKey(
        environmentId = environmentId,
        serverId = serverId,
        principalId = principalId,
        consumerId = consumerId,
    )

internal data class RealtimeRegistrySnapshot(
    val authRevision: RealtimeAuthRevision,
    val activeLeases: Map<String, RealtimeScopeLease>,
    val currentFamilyByLease: Map<String, String>,
    val currentGrantBySlot: Map<RealtimeSlotKey, RealtimeGrantRecord>,
) {
    fun grantsFor(scopeLeaseId: String): List<RealtimeGrantRecord> =
        currentGrantBySlot.values
            .filter { grant -> grant.scopeLeaseId == scopeLeaseId }
            .sortedBy { grant -> grant.serverId }

    companion object {
        val Empty =
            RealtimeRegistrySnapshot(
                authRevision = RealtimeAuthRevision(generation = 0L, session = null),
                activeLeases = emptyMap(),
                currentFamilyByLease = emptyMap(),
                currentGrantBySlot = emptyMap(),
            )
    }
}

internal interface RealtimeAuthRevisionStore {
    fun read(): RealtimeAuthRevision?

    fun write(revision: RealtimeAuthRevision)
}

internal enum class RealtimeAuthAdvanceResult {
    Advanced,
    Duplicate,
    Stale,
    Conflict,
}

internal class KeyValueRealtimeAuthRevisionStore(
    private val store: RaftKeyValueStore,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : RealtimeAuthRevisionStore {
    override fun read(): RealtimeAuthRevision? =
        store.getString(RealtimeAuthRevisionStorageKey)
            ?.let { encoded -> runCatching { json.decodeFromString<StoredRealtimeAuthRevision>(encoded) }.getOrNull() }
            ?.toDomain()

    override fun write(revision: RealtimeAuthRevision) {
        store.putString(RealtimeAuthRevisionStorageKey, json.encodeToString(revision.toStored()))
    }
}

/**
 * Foundation v1.2 authority registry core.
 *
 * This class owns the single linearization point for auth revisions, scope
 * leases, lease-wide connection families and server-slot grants. Provider,
 * persistence, shadow and sink integration must call into this registry rather
 * than creating a second owner map.
 */
internal class RealtimeAuthorityRegistry(
    private val authRevisionStore: RealtimeAuthRevisionStore,
    private val processInstanceId: String,
    private val diagnosticsLogger: (String) -> Unit = {},
) {
    init {
        require(processInstanceId.isNotBlank()) { "realtime processInstanceId must not be blank" }
    }

    private val mutex = Mutex()
    private val mutableSnapshot = MutableStateFlow(RealtimeRegistrySnapshot.Empty)
    private var nextLeaseId = 0L
    private var nextFamilyId = 0L
    private var nextEpochId = 0L
    private val terminalServerIds = mutableSetOf<String>()

    val snapshot: StateFlow<RealtimeRegistrySnapshot> = mutableSnapshot.asStateFlow()

    suspend fun initialize() {
        mutex.withLock {
            val durable = authRevisionStore.read() ?: RealtimeRegistrySnapshot.Empty.authRevision
            mutableSnapshot.value =
                RealtimeRegistrySnapshot.Empty.copy(authRevision = durable.normalized())
        }
    }

    suspend fun advanceAuth(revision: RealtimeAuthRevision): Boolean =
        advanceAuthResult(revision) == RealtimeAuthAdvanceResult.Advanced

    suspend fun advanceAuthResult(revision: RealtimeAuthRevision): RealtimeAuthAdvanceResult =
        mutex.withLock {
            val normalized = revision.normalized()
            val (current, durableConflict) = synchronizeDurableRevisionLocked()
            if (durableConflict) return@withLock RealtimeAuthAdvanceResult.Conflict
            val durable = current.authRevision
            if (normalized.generation < durable.generation) {
                return@withLock RealtimeAuthAdvanceResult.Stale
            }
            if (normalized.generation == durable.generation) {
                if (normalized == durable) {
                    return@withLock RealtimeAuthAdvanceResult.Duplicate
                }
                diagnosticsLogger(
                    "realtime_authority_auth_advance result=conflict " +
                        "reason=equal_generation_payload_conflict generation=${normalized.generation}"
                )
                return@withLock RealtimeAuthAdvanceResult.Conflict
            }
            authRevisionStore.write(normalized)
            mutableSnapshot.value = rebuildForAuth(current, normalized)
            RealtimeAuthAdvanceResult.Advanced
        }

    /**
     * Provider entry point: rereads durable truth inside the registry mutex and
     * allocates the next authority generation from that durable value. A typed
     * auth event is a wake signal, so [session] is the provider's freshly read
     * current session rather than event payload.
     */
    suspend fun advanceAuthFromCurrentSession(
        session: RealtimeAuthoritySession?,
        force: Boolean,
    ): RealtimeAuthAdvanceResult =
        mutex.withLock {
            val (current, durableConflict) = synchronizeDurableRevisionLocked()
            if (durableConflict) return@withLock RealtimeAuthAdvanceResult.Conflict
            val normalizedSession =
                RealtimeAuthRevision(generation = current.authRevision.generation, session = session)
                    .normalized()
                    .session
            if (!force && normalizedSession == current.authRevision.session) {
                return@withLock RealtimeAuthAdvanceResult.Duplicate
            }
            val next =
                RealtimeAuthRevision(
                    generation = current.authRevision.generation + 1L,
                    session = normalizedSession,
                )
            authRevisionStore.write(next)
            mutableSnapshot.value = rebuildForAuth(current, next)
            RealtimeAuthAdvanceResult.Advanced
        }

    suspend fun register(scopeKey: String): RealtimeScopeLease =
        mutex.withLock {
            val normalizedKey = scopeKey.trim().takeIf { it.isNotEmpty() }
                ?: error("realtime scope key must not be blank")
            val current = mutableSnapshot.value
            current.activeLeases.values.firstOrNull { lease -> lease.scopeKey == normalizedKey }
                ?.let { lease -> return@withLock lease }

            val lease =
                RealtimeScopeLease(
                    scopeKey = normalizedKey,
                    id = nextOpaqueId("lease", ++nextLeaseId),
                )
            val leases = current.activeLeases + (lease.id to lease)
            val session = current.authRevision.session
            if (session == null) {
                mutableSnapshot.value = current.copy(activeLeases = leases)
                return@withLock lease
            }
            val familyId = nextOpaqueId("family", ++nextFamilyId)
            val grants = grantsFor(lease.id, familyId, current.authRevision)
            mutableSnapshot.value =
                current.copy(
                    activeLeases = leases,
                    currentFamilyByLease = current.currentFamilyByLease + (lease.id to familyId),
                    currentGrantBySlot = current.currentGrantBySlot + grants.associateBy { it.slotKey },
                )
            lease
        }

    suspend fun release(scopeLeaseId: String): Boolean =
        mutex.withLock {
            val current = mutableSnapshot.value
            if (scopeLeaseId !in current.activeLeases) return@withLock false
            mutableSnapshot.value =
                current.copy(
                    activeLeases = current.activeLeases - scopeLeaseId,
                    currentFamilyByLease = current.currentFamilyByLease - scopeLeaseId,
                    currentGrantBySlot =
                        current.currentGrantBySlot.filterKeys { slot -> slot.scopeLeaseId != scopeLeaseId },
                )
            true
        }

    suspend fun reconnect(scopeLeaseId: String): Boolean =
        mutex.withLock {
            val current = mutableSnapshot.value
            if (scopeLeaseId !in current.activeLeases) return@withLock false
            if (current.authRevision.session == null) return@withLock false
            val familyId = nextOpaqueId("family", ++nextFamilyId)
            val retained = current.currentGrantBySlot.filterKeys { slot -> slot.scopeLeaseId != scopeLeaseId }
            val replacement = grantsFor(scopeLeaseId, familyId, current.authRevision)
            mutableSnapshot.value =
                current.copy(
                    currentFamilyByLease = current.currentFamilyByLease + (scopeLeaseId to familyId),
                    currentGrantBySlot = retained + replacement.associateBy { it.slotKey },
                )
            true
        }

    suspend fun currentWitness(scopeLeaseId: String): RealtimeConnectionWitness? =
        mutex.withLock {
            val current = mutableSnapshot.value
            val session = current.authRevision.session ?: return@withLock null
            val familyId = current.currentFamilyByLease[scopeLeaseId] ?: return@withLock null
            if (scopeLeaseId !in current.activeLeases) return@withLock null
            RealtimeConnectionWitness(
                scopeLeaseId = scopeLeaseId,
                principalId = session.principalId,
                authGeneration = current.authRevision.generation,
                connectionFamilyId = familyId,
            )
        }

    suspend fun currentGrant(scopeLeaseId: String, serverId: String): RealtimeGrantRecord? =
        mutex.withLock {
            mutableSnapshot.value.currentGrantBySlot[RealtimeSlotKey(scopeLeaseId, serverId)]
        }

    suspend fun resolve(
        witness: RealtimeConnectionWitness,
        eventServerId: String,
    ): RealtimeGrantRecord? =
        mutex.withLock {
            val current = mutableSnapshot.value
            val session = current.authRevision.session ?: return@withLock null
            if (witness.scopeLeaseId !in current.activeLeases) return@withLock null
            if (witness.principalId != session.principalId) return@withLock null
            if (witness.authGeneration != current.authRevision.generation) return@withLock null
            if (current.currentFamilyByLease[witness.scopeLeaseId] != witness.connectionFamilyId) {
                return@withLock null
            }
            current.currentGrantBySlot[RealtimeSlotKey(witness.scopeLeaseId, eventServerId)]
                ?.takeIf { grant ->
                    grant.principalId == witness.principalId &&
                        grant.authGeneration == witness.authGeneration &&
                        grant.connectionFamilyId == witness.connectionFamilyId
                }
        }

    suspend fun runIfCurrentGrant(
        grant: RealtimeGrantRecord,
        block: suspend () -> Unit,
    ): Boolean =
        mutex.withLock {
            val current = mutableSnapshot.value.currentGrantBySlot[grant.slotKey]
            if (current != grant) return@withLock false
            block()
            true
        }

    /**
     * Permanently closes one membership epoch before running its terminal mutation. All scope grants for the
     * server are revoked under the registry mutex, so a concurrent reconnect cannot mint a successor grant.
     * The tombstone is lifted only after durable auth first observes the server absent and then explicitly
     * present again; token rotation or a same-membership reconnect never revives it.
     */
    suspend fun terminalizeIfCurrentGrant(
        grant: RealtimeGrantRecord,
        block: suspend () -> Unit,
    ): Boolean =
        mutex.withLock {
            val current = mutableSnapshot.value
            if (current.currentGrantBySlot[grant.slotKey] != grant) return@withLock false
            terminalServerIds += grant.serverId
            mutableSnapshot.value =
                current.copy(
                    currentGrantBySlot =
                        current.currentGrantBySlot.filterValues { candidate ->
                            candidate.serverId != grant.serverId
                        },
                )
            block()
            true
        }

    private fun rebuildForAuth(
        current: RealtimeRegistrySnapshot,
        revision: RealtimeAuthRevision,
    ): RealtimeRegistrySnapshot {
        reconcileTerminalMemberships(current.authRevision.session, revision.session)
        val session = revision.session
            ?: return current.copy(
                authRevision = revision,
                currentFamilyByLease = emptyMap(),
                currentGrantBySlot = emptyMap(),
            )
        val families = mutableMapOf<String, String>()
        val grants = mutableMapOf<RealtimeSlotKey, RealtimeGrantRecord>()
        current.activeLeases.keys.sorted().forEach { leaseId ->
            val familyId = nextOpaqueId("family", ++nextFamilyId)
            families[leaseId] = familyId
            grantsFor(leaseId, familyId, revision).forEach { grant -> grants[grant.slotKey] = grant }
        }
        return current.copy(
            authRevision = revision,
            currentFamilyByLease = families,
            currentGrantBySlot = grants,
        )
    }

    private fun grantsFor(
        scopeLeaseId: String,
        familyId: String,
        revision: RealtimeAuthRevision,
    ): List<RealtimeGrantRecord> {
        val session = revision.session ?: return emptyList()
        return session.serverIds.filterNot(terminalServerIds::contains).map { serverId ->
            RealtimeGrantRecord(
                slotKey = RealtimeSlotKey(scopeLeaseId = scopeLeaseId, serverId = serverId),
                principalId = session.principalId,
                environmentId = session.environmentId,
                authGeneration = revision.generation,
                connectionFamilyId = familyId,
                connectionEpochId = nextOpaqueId("epoch", ++nextEpochId),
            )
        }
    }

    private fun reconcileTerminalMemberships(
        previous: RealtimeAuthoritySession?,
        next: RealtimeAuthoritySession?,
    ) {
        if (terminalServerIds.isEmpty()) return
        if (previous?.principalId != next?.principalId) {
            terminalServerIds.clear()
            return
        }
        val previousServerIds = previous?.serverIds.orEmpty().toSet()
        val nextServerIds = next?.serverIds.orEmpty().toSet()
        terminalServerIds.removeAll { serverId ->
            serverId !in previousServerIds && serverId in nextServerIds
        }
    }

    private fun nextOpaqueId(kind: String, counter: Long): String =
        "$processInstanceId:$kind:$counter"

    private fun synchronizeDurableRevisionLocked(): Pair<RealtimeRegistrySnapshot, Boolean> {
        val current = mutableSnapshot.value
        val durable = authRevisionStore.read()?.normalized() ?: RealtimeRegistrySnapshot.Empty.authRevision
        if (durable == current.authRevision) return current to false
        if (durable.generation < current.authRevision.generation) {
            diagnosticsLogger(
                "realtime_authority_durable_sync result=ignored reason=durable_generation_regressed " +
                    "durable=${durable.generation} memory=${current.authRevision.generation}"
            )
            return current to false
        }
        if (durable.generation == current.authRevision.generation) {
            diagnosticsLogger(
                "realtime_authority_durable_sync result=conflict " +
                    "reason=equal_generation_payload_conflict generation=${durable.generation}"
            )
            return current to true
        }
        return rebuildForAuth(current, durable).also { synchronized ->
            mutableSnapshot.value = synchronized
        } to false
    }
}

private fun RealtimeAuthRevision.normalized(): RealtimeAuthRevision =
    copy(
        generation = generation.coerceAtLeast(0L),
        session =
            session?.let { value ->
                RealtimeAuthoritySession(
                    principalId = value.principalId.trim(),
                    serverIds = value.serverIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                    environmentId = value.environmentId.trim(),
                )
            },
    )
