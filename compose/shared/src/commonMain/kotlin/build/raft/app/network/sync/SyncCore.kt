/*
 * Raft Sync Core — deterministic sync state machine (RFC 043 §3). KMP port of
 * packages/sync-core/src/core.ts. Pure w.r.t. IO: ingest folds
 * data in; all outbound IO is declarative SyncRequestDescription. No transport, no persistence,
 * no ambient clock/randomness (§9.4). The same input sequence, in any interleaving, yields the
 * same terminal state.
 */
package build.raft.app.network.sync

private class ScopeEntry(
    /** null = no baseline yet (pre-first-snapshot for contiguous scopes). */
    var appliedSeq: SyncSeq?,
    var acceptedFingerprint: String?,
    var epoch: SyncEpoch?,
    var repairPending: Boolean,
    var state: Any?,
)

fun createSyncCore(config: SyncCoreConfig): SyncCore = SyncCoreImpl(config)

private class SyncCoreImpl(config: SyncCoreConfig) : SyncCore {
    private val domains = LinkedHashMap<SyncDomainName, SyncDomainConfig>().apply {
        for (domain in config.domains) put(domain.name, domain)
    }
    private val scopes = LinkedHashMap<SyncDomainName, LinkedHashMap<SyncScopeId, ScopeEntry>>()
    private val violations: SyncViolationBuffer =
        config.violationBuffer ?: createSyncViolationBuffer(config.violationBufferCapacity ?: 256)

    /** Keyed by "${kind}:${domain}:${scopeId}" — requests are idempotent. Insertion order = stable. */
    private val pending = LinkedHashMap<String, SyncRequestDescription>()

    private fun domainOf(name: SyncDomainName): SyncDomainConfig =
        domains[name] ?: throw IllegalArgumentException("sync-core: unregistered domain \"$name\"")

    private fun scopeEntry(domain: SyncDomainConfig, scopeId: SyncScopeId): ScopeEntry {
        val byScope = scopes.getOrPut(domain.name) { LinkedHashMap() }
        return byScope.getOrPut(scopeId) {
            ScopeEntry(
                appliedSeq = null,
                acceptedFingerprint = null,
                epoch = null,
                repairPending = false,
                state = domain.initialState(),
            )
        }
    }

    private fun requestKey(request: SyncRequestDescription): String {
        val kind = when (request) {
            is SyncRequestDescription.Difference -> "difference"
            is SyncRequestDescription.Snapshot -> "snapshot"
        }
        return "$kind:${request.domain}:${request.scopeId}"
    }

    private fun requestSnapshot(domainName: SyncDomainName, scopeId: SyncScopeId, reason: SyncSnapshotReason) {
        val request = SyncRequestDescription.Snapshot(domainName, scopeId, reason)
        pending[requestKey(request)] = request
    }

    private fun requestDifference(domainName: SyncDomainName, scopeId: SyncScopeId, sinceSeq: SyncSeq, epoch: SyncEpoch?) {
        val request = SyncRequestDescription.Difference(domainName, scopeId, sinceSeq, epoch)
        pending[requestKey(request)] = request
    }

    private fun clearScopeRequests(domainName: SyncDomainName, scopeId: SyncScopeId) {
        pending.remove("difference:$domainName:$scopeId")
        pending.remove("snapshot:$domainName:$scopeId")
    }

    private fun epochMismatch(entry: ScopeEntry, incoming: SyncEpoch?): Boolean =
        entry.epoch != null && incoming != null && incoming != entry.epoch

    override fun ingestFrame(domain: SyncDomainName, frame: SyncFrame): SyncIngestOutcome {
        val domainConfig = domainOf(domain)
        val entry = scopeEntry(domainConfig, frame.scopeId)

        if (epochMismatch(entry, frame.epoch)) {
            violations.push(SyncViolationDraft(SyncViolationKind.CrossEpochArrival, domain, frame.scopeId, frame.seq, frame.epoch))
            requestSnapshot(domain, frame.scopeId, SyncSnapshotReason.EpochMismatch)
            entry.repairPending = true
            return SyncIngestOutcome.EpochRebaselineRequested(frame.scopeId)
        }

        // No baseline yet: contiguous scopes cannot order a lone frame — ask for a snapshot;
        // sparse scopes adopt the frame as their baseline.
        val appliedSeq = entry.appliedSeq
        if (appliedSeq == null) {
            if (domainConfig.density == SyncDensity.Contiguous) {
                requestSnapshot(domain, frame.scopeId, SyncSnapshotReason.Initial)
                entry.repairPending = true
                return SyncIngestOutcome.GapRepairRequested(frame.scopeId, fromSeq = 0uL, toSeq = frame.seq)
            }
            entry.state = domainConfig.fold(entry.state, frame.event, SyncFoldFrame(frame.scopeId, frame.seq))
            entry.appliedSeq = frame.seq
            entry.acceptedFingerprint = domainConfig.eventFingerprint?.invoke(frame.event)
            if (entry.epoch == null) entry.epoch = frame.epoch
            return SyncIngestOutcome.MaxAdvanced(frame.scopeId, frame.seq)
        }

        if (frame.seq < appliedSeq) {
            if (domainConfig.eventFingerprint != null) {
                violations.push(
                    SyncViolationDraft(
                        SyncViolationKind.VersionRegression,
                        domain,
                        frame.scopeId,
                        frame.seq,
                        frame.epoch,
                    ),
                )
            }
            return SyncIngestOutcome.DuplicateDropped(frame.scopeId, frame.seq)
        }

        if (frame.seq == appliedSeq) {
            val fingerprint = domainConfig.eventFingerprint
                ?: return SyncIngestOutcome.DuplicateDropped(frame.scopeId, frame.seq)
            if (entry.acceptedFingerprint == fingerprint(frame.event)) {
                return SyncIngestOutcome.DuplicateDropped(frame.scopeId, frame.seq)
            }
            violations.push(
                SyncViolationDraft(
                    SyncViolationKind.ProducerVersionConflict,
                    domain,
                    frame.scopeId,
                    frame.seq,
                    frame.epoch,
                ),
            )
            requestSnapshot(
                domain,
                frame.scopeId,
                if (domainConfig.density == SyncDensity.Sparse) {
                    SyncSnapshotReason.SparseRepull
                } else {
                    SyncSnapshotReason.SnapshotRequired
                },
            )
            entry.repairPending = true
            return SyncIngestOutcome.Violation(frame.scopeId, SyncViolationKind.ProducerVersionConflict)
        }

        if (domainConfig.density == SyncDensity.Sparse) {
            entry.state = domainConfig.fold(entry.state, frame.event, SyncFoldFrame(frame.scopeId, frame.seq))
            entry.appliedSeq = frame.seq
            entry.acceptedFingerprint = domainConfig.eventFingerprint?.invoke(frame.event)
            return SyncIngestOutcome.MaxAdvanced(frame.scopeId, frame.seq)
        }

        // Contiguous density.
        if (frame.seq == appliedSeq + 1uL) {
            entry.state = domainConfig.fold(entry.state, frame.event, SyncFoldFrame(frame.scopeId, frame.seq))
            entry.appliedSeq = frame.seq
            entry.acceptedFingerprint = domainConfig.eventFingerprint?.invoke(frame.event)
            return SyncIngestOutcome.Applied(frame.scopeId, frame.seq)
        }

        // Gap: stop-gate and request repair; the frame is NOT applied — the difference response
        // redelivers it in order (RFC 038 §4.1).
        val fromSeq = appliedSeq + 1uL
        requestDifference(domain, frame.scopeId, appliedSeq, entry.epoch)
        entry.repairPending = true
        return SyncIngestOutcome.GapRepairRequested(frame.scopeId, fromSeq, toSeq = frame.seq - 1uL)
    }

    override fun ingestSnapshot(domain: SyncDomainName, snapshot: SyncSnapshot): SyncIngestOutcome {
        val domainConfig = domainOf(domain)
        val entry = scopeEntry(domainConfig, snapshot.scopeId)
        // A stale same-epoch snapshot is a comparison-based no-op — never a rollback (convergence
        // invariant). Cross-epoch snapshots may lower the watermark: that is a rebaseline.
        val sameEpoch = entry.epoch == null || snapshot.epoch == null || snapshot.epoch == entry.epoch
        val appliedSeq = entry.appliedSeq
        if (sameEpoch && appliedSeq != null && snapshot.watermark <= appliedSeq) {
            violations.push(SyncViolationDraft(SyncViolationKind.VersionRegression, domain, snapshot.scopeId, snapshot.watermark, snapshot.epoch))
            return SyncIngestOutcome.DuplicateDropped(snapshot.scopeId, snapshot.watermark)
        }
        entry.state = domainConfig.fromSnapshot(snapshot)
        entry.appliedSeq = snapshot.watermark
        entry.acceptedFingerprint = null
        entry.epoch = snapshot.epoch
        entry.repairPending = false
        clearScopeRequests(domain, snapshot.scopeId)
        return SyncIngestOutcome.Applied(snapshot.scopeId, snapshot.watermark)
    }

    override fun ingestDifference(domain: SyncDomainName, response: SyncDifferenceResponse): SyncIngestOutcome {
        val domainConfig = domainOf(domain)
        val entry = scopeEntry(domainConfig, response.scopeId)

        if (epochMismatch(entry, response.epoch)) {
            violations.push(SyncViolationDraft(SyncViolationKind.CrossEpochArrival, domain, response.scopeId, null, response.epoch))
            requestSnapshot(domain, response.scopeId, SyncSnapshotReason.EpochMismatch)
            return SyncIngestOutcome.EpochRebaselineRequested(response.scopeId)
        }

        if (response.snapshotRequired) {
            clearScopeRequests(domain, response.scopeId)
            requestSnapshot(domain, response.scopeId, SyncSnapshotReason.SnapshotRequired)
            entry.repairPending = true
            return SyncIngestOutcome.EpochRebaselineRequested(response.scopeId)
        }

        val ordered = response.events.sortedBy { it.seq }
        for (item in ordered) {
            val applied = entry.appliedSeq
            if (applied != null && item.seq <= applied) continue
            entry.state = domainConfig.fold(entry.state, item.event, SyncFoldFrame(response.scopeId, item.seq))
            entry.appliedSeq = item.seq
            entry.acceptedFingerprint = domainConfig.eventFingerprint?.invoke(item.event)
        }
        val afterFold = entry.appliedSeq
        if (afterFold == null || response.toSeq > afterFold) {
            entry.appliedSeq = response.toSeq
            entry.acceptedFingerprint = null
        }
        clearScopeRequests(domain, response.scopeId)

        if (response.partial) {
            // differenceSlice loop (RFC 043 §9.6): idempotently regenerate the next request from
            // the intermediate watermark.
            val watermark = entry.appliedSeq!!
            requestDifference(domain, response.scopeId, watermark, entry.epoch)
            return SyncIngestOutcome.GapRepairRequested(response.scopeId, fromSeq = watermark + 1uL, toSeq = response.toSeq)
        }

        entry.repairPending = false
        return SyncIngestOutcome.Applied(response.scopeId, entry.appliedSeq!!)
    }

    override fun pendingRequests(): List<SyncRequestDescription> = pending.values.toList()

    override fun state(domain: SyncDomainName, scopeId: SyncScopeId): Any? =
        scopes[domain]?.get(scopeId)?.state

    override fun scopeSyncState(domain: SyncDomainName, scopeId: SyncScopeId): SyncScopeState? {
        val entry = scopes[domain]?.get(scopeId) ?: return null
        return SyncScopeState(
            appliedSeq = entry.appliedSeq ?: 0uL,
            epoch = entry.epoch,
            repairPending = entry.repairPending,
        )
    }

    override fun violations(sinceIndex: Int?): SyncViolationDrain = violations.drain(sinceIndex)
}
