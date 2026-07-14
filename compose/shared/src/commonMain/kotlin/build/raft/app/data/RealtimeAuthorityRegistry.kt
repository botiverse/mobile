package build.raft.app.data

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

@Serializable
internal data class RealtimeAuthoritySession(
    val principalId: String,
    val serverIds: List<String>,
)

@Serializable
internal data class RealtimeAuthRevision(
    val generation: Long,
    val session: RealtimeAuthoritySession?,
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
    val authGeneration: Long,
    val connectionFamilyId: String,
    val connectionEpochId: String,
) {
    val scopeLeaseId: String
        get() = slotKey.scopeLeaseId

    val serverId: String
        get() = slotKey.serverId
}

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

internal class KeyValueRealtimeAuthRevisionStore(
    private val store: SlockKeyValueStore,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : RealtimeAuthRevisionStore {
    override fun read(): RealtimeAuthRevision? =
        store.getString(RealtimeAuthRevisionStorageKey)
            ?.let { encoded -> runCatching { json.decodeFromString<RealtimeAuthRevision>(encoded) }.getOrNull() }

    override fun write(revision: RealtimeAuthRevision) {
        store.putString(RealtimeAuthRevisionStorageKey, json.encodeToString(revision))
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
) {
    private val mutex = Mutex()
    private val mutableSnapshot = MutableStateFlow(RealtimeRegistrySnapshot.Empty)
    private var nextLeaseId = 0L
    private var nextFamilyId = 0L
    private var nextEpochId = 0L

    val snapshot: StateFlow<RealtimeRegistrySnapshot> = mutableSnapshot.asStateFlow()

    suspend fun initialize() {
        mutex.withLock {
            val durable = authRevisionStore.read() ?: RealtimeRegistrySnapshot.Empty.authRevision
            mutableSnapshot.value =
                RealtimeRegistrySnapshot.Empty.copy(authRevision = durable.normalized())
        }
    }

    suspend fun advanceAuth(revision: RealtimeAuthRevision): Boolean =
        mutex.withLock {
            val normalized = revision.normalized()
            val current = mutableSnapshot.value
            if (normalized.generation <= current.authRevision.generation) {
                return@withLock false
            }
            authRevisionStore.write(normalized)
            mutableSnapshot.value = rebuildForAuth(current, normalized)
            true
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

    private fun rebuildForAuth(
        current: RealtimeRegistrySnapshot,
        revision: RealtimeAuthRevision,
    ): RealtimeRegistrySnapshot {
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
        return session.serverIds.map { serverId ->
            RealtimeGrantRecord(
                slotKey = RealtimeSlotKey(scopeLeaseId = scopeLeaseId, serverId = serverId),
                principalId = session.principalId,
                authGeneration = revision.generation,
                connectionFamilyId = familyId,
                connectionEpochId = nextOpaqueId("epoch", ++nextEpochId),
            )
        }
    }

    private fun nextOpaqueId(kind: String, counter: Long): String =
        "$processInstanceId:$kind:$counter"
}

private fun RealtimeAuthRevision.normalized(): RealtimeAuthRevision =
    copy(
        generation = generation.coerceAtLeast(0L),
        session =
            session?.let { value ->
                RealtimeAuthoritySession(
                    principalId = value.principalId.trim(),
                    serverIds = value.serverIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                )
            },
    )
