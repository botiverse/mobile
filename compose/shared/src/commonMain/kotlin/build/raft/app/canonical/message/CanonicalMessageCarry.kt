package build.raft.app.canonical.message

/** Task-domain fields excluded from [CanonicalMessageRow] and dual-dispatched to the legacy consumer. */
data class TaskDomainFields(
    val taskStatus: String? = null,
    val taskNumber: Int? = null,
    val taskAssigneeType: String? = null,
    val taskAssigneeId: String? = null,
    val taskClaimedAt: String? = null,
    val taskCompletedAt: String? = null,
)

/**
 * The in-process gateway→accept carry (`sync_core_messages_v1`): one raw socket frame's canonical decode,
 * produced ONCE by the flag-gated projection gateway and threaded on the MessageNew/MessageUpdated event
 * to the shadow fold+persist lane so the same frame is never decoded twice. Pure data — @Transient on the
 * event, never serialized; a deserialized event carries null and the lane skips the frame (fail-open).
 */
data class CanonicalMessageCarry(
    val row: CanonicalMessageRow,
    val presentAggregates: Set<MessageAggregateField>,
    val taskFields: TaskDomainFields? = null,
)
