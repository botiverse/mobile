package build.raft.app.network.sync

const val SYNC_CORE_READ_STATE_V1_DURABLE_CONSUMER_ID: String = "sync-core.read-state.v1"

data class ReadStateV1Scope(
    val serverId: String,
    val principalId: String,
    val channelId: String,
    val consumerId: String = SYNC_CORE_READ_STATE_V1_DURABLE_CONSUMER_ID,
) {
    init {
        require(serverId.isNotBlank()) { "read-state serverId must not be blank" }
        require(principalId.isNotBlank()) { "read-state principalId must not be blank" }
        require(channelId.isNotBlank()) { "read-state channelId must not be blank" }
        require(consumerId == SYNC_CORE_READ_STATE_V1_DURABLE_CONSUMER_ID) {
            "read-state v1 requires its independent durable consumer"
        }
    }
}

data class ReadStateV1Fact(
    val channelId: String,
    val readStateVersion: Long,
    val maxReadSeq: Long,
) {
    init {
        require(channelId.isNotBlank()) { "read-state fact channelId must not be blank" }
        require(readStateVersion > 0L) { "read-state version must be positive" }
        require(maxReadSeq >= 0L) { "read-state maxReadSeq must not be negative" }
    }
}

enum class ReadStateV1ApplyResult {
    Accepted,
    Current,
    Stale,
    Conflict,
}

data class ReadStateV1Transition(
    val fact: ReadStateV1Fact,
    val result: ReadStateV1ApplyResult,
)

fun reduceReadStateV1(current: ReadStateV1Fact, incoming: ReadStateV1Fact): ReadStateV1Transition {
    require(current.channelId == incoming.channelId) { "read-state reducer requires one exact channel" }
    if (incoming.readStateVersion < current.readStateVersion) {
        return ReadStateV1Transition(current, ReadStateV1ApplyResult.Stale)
    }
    if (incoming.readStateVersion == current.readStateVersion) {
        val result = if (incoming.maxReadSeq == current.maxReadSeq) {
            ReadStateV1ApplyResult.Current
        } else {
            ReadStateV1ApplyResult.Conflict
        }
        return ReadStateV1Transition(current, result)
    }
    return ReadStateV1Transition(incoming, ReadStateV1ApplyResult.Accepted)
}

enum class ReadStateV1FactSource {
    Hydrate,
    MutationAck,
    OwnerRealtime,
    PeerReceipt,
}

data class ReadStateV1WireCandidate(
    val channelId: String?,
    val readStateVersion: Long?,
    val maxReadSeq: Long?,
    val source: ReadStateV1FactSource,
)

enum class ReadStateV1CandidateRejection {
    UntrustedSource,
    IncompletePair,
    InvalidPair,
    ScopeMismatch,
}

sealed interface ReadStateV1CandidateResult {
    data class Accepted(val fact: ReadStateV1Fact) : ReadStateV1CandidateResult
    data class Rejected(val reason: ReadStateV1CandidateRejection) : ReadStateV1CandidateResult
}

fun normalizeReadStateV1Candidate(
    scope: ReadStateV1Scope,
    candidate: ReadStateV1WireCandidate,
): ReadStateV1CandidateResult {
    if (candidate.source == ReadStateV1FactSource.PeerReceipt) {
        return ReadStateV1CandidateResult.Rejected(ReadStateV1CandidateRejection.UntrustedSource)
    }
    val channelId = candidate.channelId
    val version = candidate.readStateVersion
    val maxReadSeq = candidate.maxReadSeq
    if (channelId == null || version == null || maxReadSeq == null) {
        return ReadStateV1CandidateResult.Rejected(ReadStateV1CandidateRejection.IncompletePair)
    }
    if (channelId != scope.channelId) {
        return ReadStateV1CandidateResult.Rejected(ReadStateV1CandidateRejection.ScopeMismatch)
    }
    if (version <= 0L || maxReadSeq < 0L) {
        return ReadStateV1CandidateResult.Rejected(ReadStateV1CandidateRejection.InvalidPair)
    }
    return ReadStateV1CandidateResult.Accepted(
        ReadStateV1Fact(channelId, version, maxReadSeq),
    )
}
