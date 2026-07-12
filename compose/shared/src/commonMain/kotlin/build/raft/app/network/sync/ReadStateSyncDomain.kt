package build.raft.app.network.sync

const val READ_STATE_SYNC_DOMAIN = "read_state"

data class ReadStateSyncState(
    val readStateVersion: Long = 0L,
    val maxReadSeq: Long = 0L
)

data class ReadStateSyncEvent(
    val readStateVersion: Long,
    val maxReadSeq: Long
)

enum class ReadStateSyncApplyResult {
    Advanced,
    Regressed,
    Stale
}

data class ReadStateSyncTransition(
    val state: ReadStateSyncState,
    val result: ReadStateSyncApplyResult
)

fun reduceReadStateSyncState(
    current: ReadStateSyncState,
    event: ReadStateSyncEvent
): ReadStateSyncTransition {
    if (event.readStateVersion <= current.readStateVersion) {
        return ReadStateSyncTransition(current, ReadStateSyncApplyResult.Stale)
    }
    if (event.maxReadSeq < current.maxReadSeq) {
        return ReadStateSyncTransition(
            state = current.copy(readStateVersion = event.readStateVersion),
            result = ReadStateSyncApplyResult.Regressed
        )
    }
    return ReadStateSyncTransition(
        state =
            ReadStateSyncState(
                readStateVersion = event.readStateVersion,
                maxReadSeq = event.maxReadSeq
            ),
        result = ReadStateSyncApplyResult.Advanced
    )
}

fun readStateSyncDomain(): SyncDomainConfig =
    SyncDomainConfig(
        name = READ_STATE_SYNC_DOMAIN,
        density = SyncDensity.Sparse,
        initialState = { ReadStateSyncState() },
        fold = { state, event, frame ->
            val current = state as? ReadStateSyncState ?: ReadStateSyncState()
            val update = event as? ReadStateSyncEvent ?: return@SyncDomainConfig current
            reduceReadStateSyncState(
                current,
                update.copy(readStateVersion = frame.seq)
            ).state
        },
        fromSnapshot = { snapshot -> snapshot.state as? ReadStateSyncState ?: ReadStateSyncState() }
    )
