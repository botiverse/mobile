/*
 * Task #116 — shared composer pick→edit→result behavior machine, step 1 skeleton.
 * Pure addition: zero call-site switches, zero behavior change. Consumers land in
 * later migration cuts (Feedback step 2, Thread step 3) per the frozen v1.3 design
 * (task #115, three-seat contract PASS).
 */
package build.raft.app.composer

import build.raft.app.data.PlatformFile

/** Which surface invoked the machine. Draft SSOT always stays surface-owned. */
internal data class ComposerSourceIdentity(
    val surfaceId: String,
    val accountId: String,
    /** Thread uses threadInstanceKey; surfaces without a thread key use their page scope. */
    val scopeKey: String,
)

/** Fresh per pick request; any async result arriving under a stale token is dropped. */
internal data class ComposerGenerationToken(val value: String)

/** Explicit media policy — replaces the ambiguous imageOnly Boolean (audit #21). */
internal enum class ComposerMediaPolicy {
    ImageOnly,
    ImageAndVideo,
}

/**
 * Commit-time limits (v1.3 §3.5). All four limits are named separately and are
 * re-evaluated inside the accept transaction against the adapter's CURRENT SSOT,
 * never only against the pick-launch snapshot. dedupeByContent uses content identity
 * (sha256), stable across promotion — never path (v1.3 Argus point).
 */
internal data class ComposerLimitsPolicy(
    val maxSelected: Int? = null,
    val maxDraftItems: Int? = null,
    val perFileBytes: Long? = null,
    val totalBytes: Long? = null,
    val dedupeByContent: Boolean = false,
)

internal data class ComposerPickRequest(
    val mediaPolicy: ComposerMediaPolicy,
    val limits: ComposerLimitsPolicy,
    val identity: ComposerSourceIdentity,
    val generation: ComposerGenerationToken,
    val annotationEnabled: Boolean,
)

/** Typed picker outcome — preserves partial results (206 + skippedCount, audit #17). */
internal data class ComposerPickOutcome(
    val files: List<PlatformFile>,
    val skippedCount: Int = 0,
    val errorCode: String? = null,
)

/** Terminal session state. Immutable first-terminal CAS, cross-process (v1.3). */
internal enum class ComposerSessionTerminal {
    Cancelled,
    Accepted,
}

internal data class ComposerSessionAuthorityRecord(
    val sessionId: String,
    val identity: ComposerSourceIdentity,
    val generation: ComposerGenerationToken,
    val terminal: ComposerSessionTerminal,
    val terminalAtEpochMs: Long,
    /**
     * For an Accepted winner: the durable accept receipt, so a response-loss/restart
     * retry replays the SAME Complete(refs) instead of a wrong Cancelled, and never
     * releases refs the surface SSOT already claims. Null only for Cancelled.
     */
    val acceptedReceipt: ComposerAcceptReceipt? = null,
)

/** Three-outcome CAS result — plain set/overwrite shapes are forbidden (v1.3). */
internal enum class ComposerTerminalCasOutcome {
    /** This call won the Active→terminal transition. */
    Won,

    /** Same terminal already recorded — idempotent replay, state unchanged. */
    SameTerminalReplay,

    /** Opposite terminal already recorded — rejected, winner unchanged. */
    OppositeTerminalConflict,
}

/**
 * Session authority with cross-process immutable first-terminal CAS semantics:
 * Active→Cancelled XOR Active→Accepted. Same-terminal replay is idempotent; an
 * opposite-terminal attempt loses and must not mutate the winner. Persistent
 * implementations back the tombstone so a post-restart late Done is rejected
 * forever; GC only after the event transport's provable replay horizon (never an
 * arbitrary TTL).
 */
internal interface ComposerSessionAuthority {
    fun terminalOf(sessionId: String): ComposerSessionTerminal?

    /** Full winner record (for receipt-carrying Accepted replay); null while Active. */
    fun recordOf(sessionId: String): ComposerSessionAuthorityRecord?

    fun tryTerminate(record: ComposerSessionAuthorityRecord): ComposerTerminalCasOutcome
}

/** A durable, promoted edit ref. Identity = content digest + actual stat, never path. */
internal data class DurableEditRef(
    val sessionId: String,
    val groupId: String,
    val index: Int,
    val contentSha256: String,
    val sizeBytes: Long,
    val mediaType: String,
    val durableRef: String,
    val previewRef: String?,
    val originalName: String,
)

/** The single ordered result handed to a surface (v1.3 three-state contract). */
internal sealed interface ComposerEditResult {
    /** Promotion succeeded AND the surface accepted ownership (explicit handshake). */
    data class Complete(val refs: List<DurableEditRef>) : ComposerEditResult

    /** Cancel/back/stale — zero side effects, staging cleaned. */
    data class Cancelled(val reason: String) : ComposerEditResult

    /** Zero draft writes; the surface decides presentation from the typed reason. */
    data class Failed(val reason: String) : ComposerEditResult
}

/** Surface handshake — fire-and-forget Unit callbacks are forbidden (v1.3 §3.2). */
internal enum class ComposerAcceptDecision {
    Accepted,
    Rejected,
}

/**
 * Durable accept receipt minted by [ComposerEditResultAcceptor.prepareAccept].
 * Survives process death; committing/aborting the same receipt is idempotent.
 */
internal data class ComposerAcceptReceipt(
    val receiptId: String,
    val sessionId: String,
    val generation: ComposerGenerationToken,
    val refs: List<DurableEditRef>,
)

internal sealed interface ComposerPrepareOutcome {
    /** Validated + staged against the CURRENT SSOT; durable receipt returned. */
    data class Prepared(val receipt: ComposerAcceptReceipt) : ComposerPrepareOutcome

    /** Normal outcome (generation/limits/dedupe drift) — NOT a host violation and NOT a terminal. */
    data class Rejected(val reason: String) : ComposerPrepareOutcome
}

/**
 * Two-phase accept handshake — the FINAL commit authority (the machine only does
 * pick/preflight/CAS arbitration). The split exists because the machine's
 * first-terminal tombstone CAS and the surface's SSOT transaction are two distinct
 * authorities that no single call order can make atomic:
 *
 * ① [prepareAccept]: inside one transaction the surface CAS-checks
 *    [expectedGeneration] against its CURRENT session generation, recomputes
 *    maxDraftItems/bytes/dedupe against its CURRENT SSOT (content identity, never
 *    path), and durably STAGES the refs as a pending receipt. Rejected here is a
 *    normal outcome — the machine releases the promoted refs, leaves the session
 *    Active, and reports Cancelled; the user may retry.
 * ② The machine then runs first-terminal CAS(Active→Accepted, receipt):
 *    Won → [commitPreparedAccept] — the surface flips the staged receipt to
 *    committed inside its SSOT (Thread: persistable `staged` pre-upload stage;
 *    Feedback: draft attachment list); only this relinquishes machine ownership.
 *    Lost → [abortPreparedAccept] — the surface discards the staged receipt;
 *    the machine releases the promoted refs.
 *
 * Response-loss idempotence: the Accepted authority record carries the receipt;
 * a retry replays the recorded Complete(refs) without re-preparing and WITHOUT
 * releasing refs the SSOT already claims.
 */
internal interface ComposerEditResultAcceptor {
    suspend fun prepareAccept(
        expectedGeneration: ComposerGenerationToken,
        refs: List<DurableEditRef>,
    ): ComposerPrepareOutcome

    /** Idempotent commit of a prepared receipt after the machine's CAS Won. */
    suspend fun commitPreparedAccept(receipt: ComposerAcceptReceipt): ComposerAcceptDecision

    /** Idempotent discard of a prepared receipt after the machine's CAS lost. */
    suspend fun abortPreparedAccept(receipt: ComposerAcceptReceipt)
}
