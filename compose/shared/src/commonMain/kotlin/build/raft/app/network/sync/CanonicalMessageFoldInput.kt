package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField

/** Compatibility event kind used by the existing process-local realtime dedup key. */
internal enum class CanonicalMessageFoldKind {
    New,
    Updated,
}

/**
 * The exact legacy realtime dedup string. This is deliberately not named or treated as a producer mutation
 * identity: Updated frames do not currently carry one.
 */
internal data class LegacyRealtimeDedupKey(val value: String) {
    init {
        require(value.isNotBlank()) { "legacy realtime dedup key must not be blank" }
    }
}

/** The complete canonical input that can affect the messages fold; presentation/task projections are absent. */
internal data class CanonicalMessageFoldInput(
    val kind: CanonicalMessageFoldKind,
    val row: CanonicalMessageRow,
    val presentAggregates: List<MessageAggregateField>,
) {
    companion object {
        fun from(kind: CanonicalMessageFoldKind, carry: CanonicalMessageCarry): CanonicalMessageFoldInput =
            CanonicalMessageFoldInput(
                kind = kind,
                row = carry.row,
                presentAggregates = carry.presentAggregates.sortedBy { aggregate -> aggregate.name },
            )
    }
}

/** Hash is an index hint only; callers must compare [structuralInput] after a hash match. */
internal data class CanonicalMessageFoldFingerprint(
    val structuralHash: Int,
    val structuralInput: CanonicalMessageFoldInput,
) {
    companion object {
        fun from(input: CanonicalMessageFoldInput): CanonicalMessageFoldFingerprint =
            CanonicalMessageFoldFingerprint(input.hashCode(), input)
    }
}

/**
 * T4 canonical-final commit outcomes. The V1 shadow commit lane that used to produce them was deleted
 * (message storage design 2026-10-27, Phase 0a); the canonical-final sidecar/seam keep these types until
 * Phase 4a retargets the commit step to the V2 store.
 */
internal sealed interface CurrentGrantMessageShadowCommitOutcome {
    data object Disabled : CurrentGrantMessageShadowCommitOutcome
    data object Committed : CurrentGrantMessageShadowCommitOutcome
    data object DurableNoChange : CurrentGrantMessageShadowCommitOutcome
    data object IgnoredMissingUpdatedBase : CurrentGrantMessageShadowCommitOutcome
    data class FailedOpen(val reason: String) : CurrentGrantMessageShadowCommitOutcome
    data object LegacyDedupCollision : CurrentGrantMessageShadowCommitOutcome
}

/** Rich prepared-only disposition. The old [CurrentGrantMessageShadowCommitOutcome] API remains unchanged. */
internal sealed interface PreparedCurrentGrantMessageCommitDisposition {
    data object FinalizableCommitted : PreparedCurrentGrantMessageCommitDisposition
    data object FinalizableDurableNoChange : PreparedCurrentGrantMessageCommitDisposition

    sealed interface LegacyFallback : PreparedCurrentGrantMessageCommitDisposition {
        data object MissingUpdatedBase : LegacyFallback
        data object LegacyDedupCollision : LegacyFallback
        data class DefinitelyPrewrite(val reason: String) : LegacyFallback
    }

    sealed interface Indeterminate : PreparedCurrentGrantMessageCommitDisposition {
        data class CommitOutcomeUnknown(val reason: String) : Indeterminate
        data class PostCommitReadbackFailed(val reason: String) : Indeterminate
        data class PostCommitReadbackCorrupt(val reason: String) : Indeterminate
        data class PostCommitPublishFailed(val reason: String) : Indeterminate
    }
}

/**
 * The T4 canonical-final commit step for one prepared exact-grant frame. Production installs none (T4 has no
 * production binding yet), so the owner answers [PreparedCurrentGrantMessageCommitDisposition.LegacyFallback];
 * Phase 4a binds the V2 store here.
 */
internal fun interface CanonicalFinalPreparedCommitStep {
    suspend fun commitPrepared(
        partition: DurablePartitionKey,
        legacyKey: LegacyRealtimeDedupKey,
        foldInput: CanonicalMessageFoldInput,
    ): PreparedCurrentGrantMessageCommitDisposition
}
