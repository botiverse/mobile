package build.raft.app.shared

internal enum class SlockAndroidAvatarDurableCut {
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

internal fun interface SlockAndroidAvatarDurableObserver {
    fun afterDurableCut(
        key: SlockAndroidAvatarOpaqueKey,
        cut: SlockAndroidAvatarDurableCut
    )
}

internal enum class SlockAndroidAvatarTerminalCut {
    AdmissionClosed,
    LeasesDrained,
    PhysicalZero
}

internal fun interface SlockAndroidAvatarTerminalObserver {
    fun afterTerminalCut(
        scope: SlockAndroidAvatarTerminalScope,
        cut: SlockAndroidAvatarTerminalCut
    )
}

internal enum class SlockAndroidAvatarOperationStatus {
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

internal data class SlockAndroidAvatarOperationResult(
    val status: SlockAndroidAvatarOperationStatus,
    val entry: SlockAndroidAvatarEntryIdentity? = null
)

internal data class SlockAndroidAvatarIdentityReservationResult(
    val status: SlockAndroidAvatarOperationStatus,
    val commitIdentity: SlockAndroidAvatarCommitIdentity? = null
) {
    init {
        require((status == SlockAndroidAvatarOperationStatus.Applied) == (commitIdentity != null))
    }
}

internal enum class SlockAndroidAvatarRequestStatus {
    Accepted,
    Stale,
    AdmissionClosed
}

internal data class SlockAndroidAvatarRequestFence(
    val key: SlockAndroidAvatarOpaqueKey,
    val requestGeneration: Long,
    val sessionIdentity: String,
    val terminalEpochs: SlockAndroidAvatarTerminalEpochs
) {
    init {
        require(requestGeneration > 0L)
        require(sessionIdentity.matches(SlockAndroidAvatarOpaqueComponentPattern))
    }

    val flightIdentity: SlockAndroidAvatarFlightIdentity
        get() =
            SlockAndroidAvatarFlightIdentity(
                key = key,
                requestGeneration = requestGeneration,
                sessionIdentity = sessionIdentity,
                terminalEpochs = terminalEpochs
            )
}

internal data class SlockAndroidAvatarFlightIdentity(
    val key: SlockAndroidAvatarOpaqueKey,
    val requestGeneration: Long,
    val sessionIdentity: String,
    val terminalEpochs: SlockAndroidAvatarTerminalEpochs
)

internal data class SlockAndroidAvatarRequestResult(
    val status: SlockAndroidAvatarRequestStatus,
    val fence: SlockAndroidAvatarRequestFence? = null
)

internal enum class SlockAndroidAvatarLeaseKind {
    Read,
    Validation,
    Decode,
    Delivery
}

internal enum class SlockAndroidAvatarReadMissReason {
    NoExpectedCommitIdentity,
    Empty,
    CommitIdentityMismatch,
    AdmissionClosed,
    StaleRequest,
    RecoveryPending,
    MissingOrInvalidBytes
}

internal data class SlockAndroidAvatarReadSnapshot(
    val requestFence: SlockAndroidAvatarRequestFence,
    val entryIdentity: SlockAndroidAvatarEntryIdentity,
    val bytes: ByteArray,
    val capturedEpochs: SlockAndroidAvatarTerminalEpochs
)

internal sealed interface SlockAndroidAvatarReadResult {
    data class Hit(
        val snapshot: SlockAndroidAvatarReadSnapshot,
        val lease: SlockAndroidAvatarLease
    ) : SlockAndroidAvatarReadResult

    data class Miss(val reason: SlockAndroidAvatarReadMissReason) : SlockAndroidAvatarReadResult
}

internal enum class SlockAndroidAvatarCurrentReadStatus {
    Available,
    Empty,
    AdmissionClosed,
    RecoveryPending,
    StorageFailure
}

internal data class SlockAndroidAvatarCurrentReadSnapshot(
    val entryIdentity: SlockAndroidAvatarEntryIdentity,
    val bytes: ByteArray
)

internal data class SlockAndroidAvatarCurrentReadResult(
    val status: SlockAndroidAvatarCurrentReadStatus,
    val snapshot: SlockAndroidAvatarCurrentReadSnapshot? = null
) {
    init {
        require((status == SlockAndroidAvatarCurrentReadStatus.Available) == (snapshot != null))
    }
}

internal data class SlockAndroidAvatarDecodedVariant(
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

internal data class SlockAndroidAvatarDecodedIdentity(
    val entryIdentity: SlockAndroidAvatarEntryIdentity,
    val variant: SlockAndroidAvatarDecodedVariant
)

internal interface SlockAndroidAvatarDecodedIdentityStore {
    fun register(identity: SlockAndroidAvatarDecodedIdentity): Boolean
    fun evictExact(entryIdentity: SlockAndroidAvatarEntryIdentity): Boolean
    fun evictKey(key: SlockAndroidAvatarOpaqueKey): Boolean
    fun evictMatching(scope: SlockAndroidAvatarTerminalScope): Boolean
    fun containsExact(entryIdentity: SlockAndroidAvatarEntryIdentity): Boolean
    fun containsKey(key: SlockAndroidAvatarOpaqueKey): Boolean
    fun containsMatching(scope: SlockAndroidAvatarTerminalScope): Boolean
    fun count(): Int
}

internal class SlockAndroidAvatarInMemoryDecodedIdentityStore :
    SlockAndroidAvatarDecodedIdentityStore {
    private val lock = Any()
    private val identities = linkedSetOf<SlockAndroidAvatarDecodedIdentity>()

    override fun register(identity: SlockAndroidAvatarDecodedIdentity): Boolean =
        synchronized(lock) {
            identities += identity
            true
        }

    override fun evictExact(entryIdentity: SlockAndroidAvatarEntryIdentity): Boolean =
        synchronized(lock) {
            identities.removeAll { it.entryIdentity == entryIdentity }
            true
        }

    override fun evictKey(key: SlockAndroidAvatarOpaqueKey): Boolean =
        synchronized(lock) {
            identities.removeAll { it.entryIdentity.key == key }
            true
        }

    override fun evictMatching(scope: SlockAndroidAvatarTerminalScope): Boolean =
        synchronized(lock) {
            identities.removeAll { it.entryIdentity.key.matches(scope) }
            true
        }

    override fun containsExact(entryIdentity: SlockAndroidAvatarEntryIdentity): Boolean =
        synchronized(lock) {
            identities.any { it.entryIdentity == entryIdentity }
        }

    override fun containsKey(key: SlockAndroidAvatarOpaqueKey): Boolean =
        synchronized(lock) {
            identities.any { it.entryIdentity.key == key }
        }

    override fun containsMatching(scope: SlockAndroidAvatarTerminalScope): Boolean =
        synchronized(lock) {
            identities.any { it.entryIdentity.key.matches(scope) }
        }

    override fun count(): Int = synchronized(lock) { identities.size }
}

internal class SlockAndroidAvatarLease internal constructor(
    val kind: SlockAndroidAvatarLeaseKind,
    val requestFence: SlockAndroidAvatarRequestFence,
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

internal enum class SlockAndroidAvatarTerminalStatus {
    Complete,
    CleanupPending,
    Interrupted,
    StorageFailure
}

internal data class SlockAndroidAvatarTerminalResult(
    val status: SlockAndroidAvatarTerminalStatus,
    val retiredKeys: Int
)

internal enum class SlockAndroidAvatarBudgetStatus {
    WithinBudget,
    Evicted,
    Pinned,
    CleanupPending,
    StorageFailure
}

internal data class SlockAndroidAvatarBudgetResult(
    val status: SlockAndroidAvatarBudgetStatus,
    val managedBytes: Long,
    val evictedEntries: Int
)
