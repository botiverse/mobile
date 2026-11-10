package build.raft.app.network.sync

/**
 * Durable sync-scope identity, the per-scope cursor, and the deterministic persist fault hook shared by
 * the canonical message V2 store and the read-state store.
 */

/** Identifies one scope inside an explicit durable server/principal/consumer partition. */
data class SyncScopeKey(
    val partition: DurablePartitionKey,
    val scopeId: SyncScopeId,
    val scopeKind: String,
) {
    init {
        requireOpaqueDurableIdentity("scopeId", scopeId)
        requireOpaqueDurableIdentity("scopeKind", scopeKind)
    }

    val serverId: String
        get() = partition.serverId

    val principalId: String
        get() = partition.principalId

    val consumerId: String
        get() = partition.consumerId

    val environmentId: String
        get() = partition.environmentId
}

/** The durable per-scope cursor + epoch (mirror of `syncCursorEntity`) committed with the facts. */
data class PersistedCursor(
    val latestSeq: Long?,
    val oldestLoadedSeq: Long?,
    val newestLoadedSeq: Long?,
    val hasLoadedOldest: Boolean = false,
    val hasLoadedNewest: Boolean = true,
    val epoch: SyncEpoch? = null,
    val parentScopeKind: String? = null,
    val parentScopeId: String? = null,
    val rootMessageId: String? = null,
)

/**
 * Deterministic crash-injection boundary — NEVER wall-clock. The kill instant is the test's whole
 * semantics: [BeforeCommit] tears before the single commit, [AfterCommit] after it.
 */
enum class PersistFaultPoint { None, BeforeCommit, AfterCommit }

/** Host-injected fault hook. Default never kills. */
fun interface PersistFaultHook {
    fun shouldKillAt(point: PersistFaultPoint): Boolean

    companion object {
        val Never: PersistFaultHook = PersistFaultHook { false }
    }
}

/** Thrown to model process death at a fault point; restore must then yield a complete (never torn) state. */
class PersistKilled(val point: PersistFaultPoint) : RuntimeException("persist killed at $point")
