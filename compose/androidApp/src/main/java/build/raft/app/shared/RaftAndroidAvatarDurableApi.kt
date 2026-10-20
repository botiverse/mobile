package build.raft.app.shared

internal enum class RaftAndroidAvatarDurableCut {
    Prepared,
    CandidateFileDurable,
    CandidateDurable,
    SharedCommitted,
    CandidateRenamed,
    CandidateRenamedRecorded,
    IndexSwitched,
    IndexSwitchedRecorded,
    RollbackRepointed,
    RollbackCleanup,
    CommitCleanup,
    Idle
}

internal fun interface RaftAndroidAvatarDurableObserver {
    fun afterDurableCut(
        key: RaftAndroidAvatarOpaqueKey,
        cut: RaftAndroidAvatarDurableCut
    )
}

internal enum class RaftAndroidAvatarTerminalCut {
    AdmissionClosed,
    LeasesDrained,
    PhysicalZero
}

internal fun interface RaftAndroidAvatarTerminalObserver {
    fun afterTerminalCut(
        scope: RaftAndroidAvatarTerminalScope,
        cut: RaftAndroidAvatarTerminalCut
    )
}

internal enum class RaftAndroidAvatarOperationStatus {
    Applied,
    AlreadyApplied,
    StaleRequest,
    AdmissionClosed,
    Busy,
    IdentityMismatch,
    NotFound,
    CleanupPending,
    StorageFailure,
    CorruptState
}

internal data class RaftAndroidAvatarOperationResult(
    val status: RaftAndroidAvatarOperationStatus,
    val entry: RaftAndroidAvatarEntryIdentity? = null
)

internal data class RaftAndroidAvatarIdentityReservationResult(
    val status: RaftAndroidAvatarOperationStatus,
    val commitIdentity: RaftAndroidAvatarCommitIdentity? = null
) {
    init {
        require((status == RaftAndroidAvatarOperationStatus.Applied) == (commitIdentity != null))
    }
}

internal enum class RaftAndroidAvatarRequestStatus {
    Accepted,
    Stale,
    AdmissionClosed
}

internal data class RaftAndroidAvatarRequestFence(
    val key: RaftAndroidAvatarOpaqueKey,
    val requestGeneration: Long,
    val sessionIdentity: String,
    val terminalEpochs: RaftAndroidAvatarTerminalEpochs
) {
    init {
        require(requestGeneration > 0L)
        require(sessionIdentity.matches(RaftAndroidAvatarOpaqueComponentPattern))
    }

    val flightIdentity: RaftAndroidAvatarFlightIdentity
        get() =
            RaftAndroidAvatarFlightIdentity(
                key = key,
                requestGeneration = requestGeneration,
                sessionIdentity = sessionIdentity,
                terminalEpochs = terminalEpochs
            )
}

internal data class RaftAndroidAvatarFlightIdentity(
    val key: RaftAndroidAvatarOpaqueKey,
    val requestGeneration: Long,
    val sessionIdentity: String,
    val terminalEpochs: RaftAndroidAvatarTerminalEpochs
)

internal data class RaftAndroidAvatarRequestResult(
    val status: RaftAndroidAvatarRequestStatus,
    val fence: RaftAndroidAvatarRequestFence? = null
)

internal enum class RaftAndroidAvatarLeaseKind {
    Read,
    Validation,
    Decode,
    Delivery
}

internal enum class RaftAndroidAvatarReadMissReason {
    NoExpectedCommitIdentity,
    Empty,
    CommitIdentityMismatch,
    AdmissionClosed,
    StaleRequest,
    RecoveryPending,
    MissingOrInvalidBytes
}

internal data class RaftAndroidAvatarReadSnapshot(
    val requestFence: RaftAndroidAvatarRequestFence,
    val entryIdentity: RaftAndroidAvatarEntryIdentity,
    val bytes: ByteArray,
    val capturedEpochs: RaftAndroidAvatarTerminalEpochs
)

internal sealed interface RaftAndroidAvatarReadResult {
    data class Hit(
        val snapshot: RaftAndroidAvatarReadSnapshot,
        val lease: RaftAndroidAvatarLease
    ) : RaftAndroidAvatarReadResult

    data class Miss(val reason: RaftAndroidAvatarReadMissReason) : RaftAndroidAvatarReadResult
}

internal enum class RaftAndroidAvatarCurrentReadStatus {
    Available,
    Empty,
    AdmissionClosed,
    RecoveryPending,
    StorageFailure
}

internal data class RaftAndroidAvatarCurrentReadSnapshot(
    val entryIdentity: RaftAndroidAvatarEntryIdentity,
    val bytes: ByteArray
)

internal data class RaftAndroidAvatarCurrentReadResult(
    val status: RaftAndroidAvatarCurrentReadStatus,
    val snapshot: RaftAndroidAvatarCurrentReadSnapshot? = null
) {
    init {
        require((status == RaftAndroidAvatarCurrentReadStatus.Available) == (snapshot != null))
    }
}

internal data class RaftAndroidAvatarDecodedVariant(
    val width: Int,
    val height: Int,
    val scaleIdentity: String
) {
    init {
        require(width >= 0)
        require(height >= 0)
        require(scaleIdentity.isNotBlank())
    }
}

internal data class RaftAndroidAvatarDecodedIdentity(
    val entryIdentity: RaftAndroidAvatarEntryIdentity,
    val variant: RaftAndroidAvatarDecodedVariant
)

internal interface RaftAndroidAvatarDecodedIdentityStore {
    fun register(identity: RaftAndroidAvatarDecodedIdentity): Boolean
    fun evictExact(entryIdentity: RaftAndroidAvatarEntryIdentity): Boolean
    fun evictKey(key: RaftAndroidAvatarOpaqueKey): Boolean
    fun evictMatching(scope: RaftAndroidAvatarTerminalScope): Boolean
    fun containsExact(entryIdentity: RaftAndroidAvatarEntryIdentity): Boolean
    fun containsKey(key: RaftAndroidAvatarOpaqueKey): Boolean
    fun containsMatching(scope: RaftAndroidAvatarTerminalScope): Boolean
    fun count(): Int
}

internal class RaftAndroidAvatarInMemoryDecodedIdentityStore :
    RaftAndroidAvatarDecodedIdentityStore {
    private val lock = Any()
    private val identities = linkedSetOf<RaftAndroidAvatarDecodedIdentity>()

    override fun register(identity: RaftAndroidAvatarDecodedIdentity): Boolean =
        synchronized(lock) {
            identities += identity
            true
        }

    override fun evictExact(entryIdentity: RaftAndroidAvatarEntryIdentity): Boolean =
        synchronized(lock) {
            identities.removeAll { it.entryIdentity == entryIdentity }
            true
        }

    override fun evictKey(key: RaftAndroidAvatarOpaqueKey): Boolean =
        synchronized(lock) {
            identities.removeAll { it.entryIdentity.key == key }
            true
        }

    override fun evictMatching(scope: RaftAndroidAvatarTerminalScope): Boolean =
        synchronized(lock) {
            identities.removeAll { it.entryIdentity.key.matches(scope) }
            true
        }

    override fun containsExact(entryIdentity: RaftAndroidAvatarEntryIdentity): Boolean =
        synchronized(lock) {
            identities.any { it.entryIdentity == entryIdentity }
        }

    override fun containsKey(key: RaftAndroidAvatarOpaqueKey): Boolean =
        synchronized(lock) {
            identities.any { it.entryIdentity.key == key }
        }

    override fun containsMatching(scope: RaftAndroidAvatarTerminalScope): Boolean =
        synchronized(lock) {
            identities.any { it.entryIdentity.key.matches(scope) }
        }

    override fun count(): Int = synchronized(lock) { identities.size }
}

internal class RaftAndroidAvatarLease internal constructor(
    val kind: RaftAndroidAvatarLeaseKind,
    val requestFence: RaftAndroidAvatarRequestFence,
    internal val leaseId: Long,
    private val closeAction: (Long) -> Unit
) : AutoCloseable {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true
        }
        closeAction(leaseId)
    }
}

internal enum class RaftAndroidAvatarTerminalStatus {
    Complete,
    CleanupPending,
    Interrupted,
    StorageFailure
}

internal data class RaftAndroidAvatarTerminalResult(
    val status: RaftAndroidAvatarTerminalStatus,
    val retiredKeys: Int
)

internal enum class RaftAndroidAvatarBudgetStatus {
    WithinBudget,
    Evicted,
    Pinned,
    CleanupPending,
    StorageFailure
}

internal data class RaftAndroidAvatarBudgetResult(
    val status: RaftAndroidAvatarBudgetStatus,
    val managedBytes: Long,
    val evictedEntries: Int
)
