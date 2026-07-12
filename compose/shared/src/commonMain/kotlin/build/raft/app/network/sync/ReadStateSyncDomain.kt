package build.raft.app.network.sync

data class ReadStateSyncState(
    val readStateVersion: Long = 0L,
    val maxReadSeq: Long = 0L
)

data class ReadStateSyncEvent(
    val readStateVersion: Long,
    val maxReadSeq: Long
)

enum class ReadStateSyncApplyResult {
    Accepted,
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
    return ReadStateSyncTransition(
        state =
            ReadStateSyncState(
                readStateVersion = event.readStateVersion,
                maxReadSeq = event.maxReadSeq
            ),
        result = ReadStateSyncApplyResult.Accepted
    )
}
