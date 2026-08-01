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

internal interface ComposerEditResultAcceptor {
    /**
     * Atomic accept handshake — the FINAL commit authority (the machine only does
     * pick/preflight). Inside one transaction the surface must: ① CAS-check
     * [expectedGeneration] against its CURRENT session generation; ② recompute
     * maxDraftItems/bytes/dedupe (content identity, never path) against its CURRENT
     * SSOT — never the pick-launch snapshot; ③ durably persist the refs into its own
     * SSOT (Thread: persistable `staged` pre-upload stage; Feedback: draft attachment
     * list) BEFORE returning. Only Accepted relinquishes machine ownership and yields
     * [ComposerEditResult.Complete]; Rejected leaves ownership with the machine,
     * which cleans staging and yields Cancelled/Failed.
     */
    suspend fun acceptEditResult(
        expectedGeneration: ComposerGenerationToken,
        refs: List<DurableEditRef>,
    ): ComposerAcceptDecision
}
