/*
 * Bounded violation ring buffer — KMP port of packages/shared/src/sync-core/violations.ts
 * (slock #4255 @ b665f42). Rollover is visible via droppedCount (RFC 043 §10.2 ruling).
 */
package build.raft.app.network.sync

private const val DEFAULT_VIOLATION_BUFFER_CAPACITY = 128

/**
 * Create a bounded ring buffer. `capacity` must be a positive integer. `sinceIndex` on drain
 * uses resume-from semantics; indexes lost to rollover surface as droppedCount.
 */
fun createSyncViolationBuffer(
    capacity: Int = DEFAULT_VIOLATION_BUFFER_CAPACITY,
    onViolation: ((SyncViolationRecord) -> Unit)? = null,
): SyncViolationBuffer {
    require(capacity > 0) { "SyncViolationBuffer capacity must be a positive integer" }
    return RingSyncViolationBuffer(capacity, onViolation)
}

private class RingSyncViolationBuffer(
    override val capacity: Int,
    override var onViolation: ((SyncViolationRecord) -> Unit)?,
) : SyncViolationBuffer {
    // ArrayDeque so the head shift on overflow is O(1), mirroring records.shift().
    private val records = ArrayDeque<SyncViolationRecord>()
    private var nextIndex = 0

    override fun push(draft: SyncViolationDraft): SyncViolationRecord {
        val indexed = SyncViolationRecord(
            index = nextIndex,
            kind = draft.kind,
            domain = draft.domain,
            scopeId = draft.scopeId,
            seq = draft.seq,
            epoch = draft.epoch,
            detail = draft.detail,
        )
        nextIndex += 1
        records.addLast(indexed)
        if (records.size > capacity) records.removeFirst()
        onViolation?.invoke(indexed)
        return indexed
    }

    override fun drain(sinceIndex: Int?): SyncViolationDrain {
        val oldestRetainedIndex = nextIndex - records.size
        val requestedSinceIndex = sinceIndex ?: oldestRetainedIndex
        require(requestedSinceIndex >= 0) { "SyncViolationBuffer drain sinceIndex must be non-negative" }
        val firstAvailableIndex = maxOf(requestedSinceIndex, oldestRetainedIndex)
        return SyncViolationDrain(
            records = records.filter { it.index >= firstAvailableIndex },
            droppedCount = maxOf(0, oldestRetainedIndex - requestedSinceIndex),
            nextIndex = nextIndex,
        )
    }
}
