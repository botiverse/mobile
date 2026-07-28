/*
 * Raft Sync Core — type seam (RFC 043). KMP port of packages/sync-core/src/types.ts.
 * The core is a pure deterministic sync state machine: frames /
 * snapshots / difference responses in; domain state + declarative request descriptions out.
 * No transport, no persistence, no ambient clock or randomness — `now` and ids are injected
 * (RFC 043 §9.4). All IO leaves the core as SyncRequestDescription; retry timing/backoff is
 * the host's (§3 retry boundary). Event/state are opaque (Any?) exactly as TS `unknown`.
 */
package build.raft.app.network.sync

/** Scope identity within a domain (channel id, agent id, "me", …). */
typealias SyncScopeId = String

/** Domain name — the RFC 037/038 domain the events belong to. */
typealias SyncDomainName = String

/** Exact unsigned 64-bit sequence/watermark carried by the sync protocol. */
typealias SyncSeq = ULong

fun Long.toSyncSeq(): SyncSeq {
    require(this >= 0L) { "sync seq must be unsigned" }
    return toULong()
}

fun String.toSyncSeq(): SyncSeq {
    val parsed = toULongOrNull()
    require(parsed != null && parsed.toString() == this) {
        "sync seq must be canonical uint64 decimal"
    }
    return parsed
}

fun SyncSeq.toLongExact(): Long {
    require(this <= Long.MAX_VALUE.toULong()) { "sync seq does not fit the legacy signed-long boundary" }
    return toLong()
}

/** Canonical cross-platform runner/wire representation; never emit SyncSeq as a JSON number. */
fun SyncSeq.toCanonicalDecimalString(): String = toString()

/** Epoch discriminator (RFC 038 §3): watermarks compare only within one epoch; null = pre-first-contact. */
typealias SyncEpoch = String

/**
 * Density is a property of the (scope, endpoint) pair — RFC 038 §9 correction. `Contiguous`
 * scopes gap-detect on seq+1 and stop-gate into difference repair; `Sparse` scopes only
 * max-advance and repair via repull (stop-gating forbidden).
 */
enum class SyncDensity { Contiguous, Sparse }

/** A live push frame for one scope. Payload is the domain's event type (opaque here). */
data class SyncFrame(
    val scopeId: SyncScopeId,
    val seq: SyncSeq,
    val epoch: SyncEpoch? = null,
    val event: Any? = null,
)

/** A hydrate snapshot for one scope: state + the watermark it was taken at. */
data class SyncSnapshot(
    val scopeId: SyncScopeId,
    val watermark: SyncSeq,
    val epoch: SyncEpoch? = null,
    val state: Any? = null,
)

/** One event in a difference response. */
data class SyncDifferenceEvent(val seq: SyncSeq, val event: Any? = null)

/**
 * A difference response (RFC 038 §3.2). `snapshotRequired` = server verdict the gap is
 * unserviceable; `partial` marks a differenceSlice page — the core idempotently regenerates
 * the next request from the intermediate watermark (RFC 043 §9.6).
 */
data class SyncDifferenceResponse(
    val scopeId: SyncScopeId,
    val epoch: SyncEpoch? = null,
    val fromSeq: SyncSeq,
    val toSeq: SyncSeq,
    val events: List<SyncDifferenceEvent> = emptyList(),
    val partial: Boolean = false,
    val snapshotRequired: Boolean = false,
)

/** Per-scope bookkeeping the core maintains (egress view). */
data class SyncScopeState(
    val appliedSeq: SyncSeq,
    val epoch: SyncEpoch?,
    /** A repair (difference/snapshot) request is outstanding for this scope. */
    val repairPending: Boolean,
)

/** Reason a snapshot request was raised. */
enum class SyncSnapshotReason { EpochMismatch, SnapshotRequired, Initial, SparseRepull }

/**
 * Declarative IO: the core never performs requests, it describes them. The host executes and
 * feeds results back via ingest*. `holdHash` enables server NotModified short-circuits
 * (RFC 043 §5); hosts that cannot hash pass null and lose only efficiency.
 */
sealed interface SyncRequestDescription {
    val domain: SyncDomainName
    val scopeId: SyncScopeId

    data class Difference(
        override val domain: SyncDomainName,
        override val scopeId: SyncScopeId,
        val sinceSeq: SyncSeq,
        val epoch: SyncEpoch?,
        val holdHash: String? = null,
        /** Client-declared resnapshot threshold (RFC 043 §6). */
        val resnapshotOverGap: SyncSeq? = null,
    ) : SyncRequestDescription

    data class Snapshot(
        override val domain: SyncDomainName,
        override val scopeId: SyncScopeId,
        val reason: SyncSnapshotReason,
        val holdHash: String? = null,
    ) : SyncRequestDescription
}

/**
 * Violation record — RFC 040 negative-space vocabulary, pure data so it enters the
 * cross-platform equivalence suite (RFC 043 §9.3). `index` is assigned monotonically by the
 * ring buffer. Wire names match the TS string union exactly.
 */
enum class SyncViolationKind(val wire: String) {
    ProducerSeqConflict("producer_seq_conflict"),
    CrossEpochArrival("cross_epoch_arrival"),
    StaleFlood("stale_flood"),
    VersionRegression("version_regression"),
    ProducerVersionConflict("producer_version_conflict"),
}

data class SyncViolationRecord(
    val index: Int,
    val kind: SyncViolationKind,
    val domain: SyncDomainName,
    val scopeId: SyncScopeId,
    val seq: SyncSeq? = null,
    val epoch: SyncEpoch? = null,
    /** Closed-set diagnostic fields only — never raw payload (RFC 040 §3). */
    val detail: Map<String, Any?>? = null,
)

/** A violation before the ring buffer assigns its index. */
data class SyncViolationDraft(
    val kind: SyncViolationKind,
    val domain: SyncDomainName,
    val scopeId: SyncScopeId,
    val seq: SyncSeq? = null,
    val epoch: SyncEpoch? = null,
    val detail: Map<String, Any?>? = null,
)

/** Drain result — rollover is visible, never silent (RFC 043 §10.2 ruling). */
data class SyncViolationDrain(
    val records: List<SyncViolationRecord>,
    /** Count of records lost to ring-buffer rollover since the last drain. */
    val droppedCount: Int,
    /** The index the next record will receive; hosts detect gaps by index. */
    val nextIndex: Int,
)

/**
 * Bounded ring buffer contract (canonical egress per §10.2). `onViolation` is an optional
 * doorbell — same record as payload, wiring it is never required for correctness.
 */
interface SyncViolationBuffer {
    fun push(draft: SyncViolationDraft): SyncViolationRecord

    /**
     * Resume-from semantics: `sinceIndex` is the first index the caller has NOT seen (pass the
     * previous drain's `nextIndex`); returned records have index >= sinceIndex. Null = everything
     * retained. `droppedCount` = indexes lost to rollover between sinceIndex and oldest retained.
     */
    fun drain(sinceIndex: Int? = null): SyncViolationDrain
    val capacity: Int
    var onViolation: ((SyncViolationRecord) -> Unit)?
}

/**
 * Host-injected storage (RFC 043 §10.1 ruling: KV-with-versioned-values). Prefix
 * enumeration/deletion is a hard requirement (epoch rebaseline cleanup); placement
 * (memory/file) belongs to the host by key pattern. Skeleton keeps state in-memory; storage
 * lands with T4b persistence.
 */
interface SyncStorageAdapter {
    fun get(key: String): Any?
    fun set(key: String, value: Any?)
    fun listPrefix(prefix: String): List<String>
    fun deletePrefix(prefix: String)
}

/** The (scopeId, seq) coordinate handed to a domain fold. */
data class SyncFoldFrame(val scopeId: SyncScopeId, val seq: SyncSeq)

/**
 * Domain registration: the pure fold plus density declaration. The fold is RFC 037's
 * hydrate/patch/reconcile applied to (state, event) — pure and total (unknown events fold to
 * an unchanged state, never throw).
 */
data class SyncDomainConfig(
    val name: SyncDomainName,
    val density: SyncDensity,
    val initialState: () -> Any?,
    /** Stable identity of the complete accepted fact at one sequence. */
    val eventFingerprint: ((event: Any?) -> String)? = null,
    /** Apply one in-order event. Purity is a contract (§9.4). */
    val fold: (state: Any?, event: Any?, frame: SyncFoldFrame) -> Any?,
    /** Replace state from a snapshot (rebaseline). */
    val fromSnapshot: (snapshot: SyncSnapshot) -> Any?,
)

data class SyncCoreConfig(
    val domains: List<SyncDomainConfig>,
    /** Injected clock — ambient wall-clock reads inside the core are a named finding. */
    val now: (() -> Long)? = null,
    val storage: SyncStorageAdapter? = null,
    val violationBufferCapacity: Int? = null,
    /** Test/host override of the ring buffer (defaults to the built-in bounded buffer). */
    val violationBuffer: SyncViolationBuffer? = null,
)

/** Outcome of one ingest step — pure data, test-assertable (structural equality). */
sealed interface SyncIngestOutcome {
    val scopeId: SyncScopeId

    data class Applied(override val scopeId: SyncScopeId, val seq: SyncSeq) : SyncIngestOutcome
    data class DuplicateDropped(override val scopeId: SyncScopeId, val seq: SyncSeq) : SyncIngestOutcome
    data class GapRepairRequested(override val scopeId: SyncScopeId, val fromSeq: SyncSeq, val toSeq: SyncSeq) : SyncIngestOutcome
    data class MaxAdvanced(override val scopeId: SyncScopeId, val seq: SyncSeq) : SyncIngestOutcome
    data class EpochRebaselineRequested(override val scopeId: SyncScopeId) : SyncIngestOutcome
    data class Violation(override val scopeId: SyncScopeId, val violation: SyncViolationKind) : SyncIngestOutcome
}

interface SyncCore {
    fun ingestFrame(domain: SyncDomainName, frame: SyncFrame): SyncIngestOutcome
    fun ingestSnapshot(domain: SyncDomainName, snapshot: SyncSnapshot): SyncIngestOutcome
    fun ingestDifference(domain: SyncDomainName, response: SyncDifferenceResponse): SyncIngestOutcome

    /** Declarative pending IO; drained by the host. Stable order, idempotent. */
    fun pendingRequests(): List<SyncRequestDescription>
    fun state(domain: SyncDomainName, scopeId: SyncScopeId): Any?
    fun scopeSyncState(domain: SyncDomainName, scopeId: SyncScopeId): SyncScopeState?
    fun violations(sinceIndex: Int? = null): SyncViolationDrain
}
