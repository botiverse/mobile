# Sync Core read-state v1

Status: selected-scope production canary. Broad rollout remains explicitly out of scope.

## Durable identity

The consumer is exactly `sync-core.read-state.v1`. Production derives `principalId` from the exact
`(environmentId, accountId)` pair before using the durable key
`(serverId, principalId, consumerId, channelId)`, where `consumerId` is fixed to that value. It is an
independent fact family: it does not share the `sync-core.messages.v2` partition and it does not treat the
presentation-oriented `channelReadStateEntity` as canonical authority.

The one authoritative fact is the complete pair `(readStateVersion, maxReadSeq)`. A missing member is not a
partial update and never advances durable state.

## Ordering and conflict rules

`readStateVersion` is the sole ordering authority:

- higher version replaces the complete pair, including a legal lower `maxReadSeq` after mark-unread;
- lower version is stale and does nothing;
- equal version plus equal watermark is idempotent/current;
- equal version plus a different watermark is a conflict, does not overwrite, invalidates the baseline, and
  requests a rebaseline;
- non-positive versions and negative watermarks are invalid.

## Trusted sources

Only verified owner full-pair facts may bind:

1. hydrate snapshot;
2. mutation acknowledgement;
3. owner `user:<id>` realtime `read_state:updated`.

Peer `scope_read:updated` receipts are a different derived family and are rejected here. Optimistic
`ReadReceiptCoordinator` predictions remain process-local commands/pending overlay and are never persisted.

## Four inherited gates

1. **Pre-baseline hold:** incremental facts before a verified hydrate/restore are held and coalesce to one
   rebaseline request.
2. **Current grant:** every restore/write/projection is tied to the exact server/principal/channel grant.
3. **Commit before projection:** a snapshot or accepted incremental is projected only after its SQL transaction
   commits.
4. **Revoke barrier:** revocation invalidates the baseline, rolls back a write whose grant changes before
   commit, then holds that exact scope dark behind a new grant until a verified snapshot is committed and
   projected. Only after that barrier may flag-off return to legacy or owner revoke release the scope. Auth
   teardown may hard-clear because the old owner can no longer project.

## Production canary binding

The independent server feature flag is exactly `sync_core_read_state_v1` and defaults OFF. OFF keeps the
legacy Home, Activity, realtime, mutation, persistence, and UI projection paths authoritative. ON leases the
first exact `(environment, account, server, channel)` scope that presents a complete valid owner fact in the
process; empty version-zero or incomplete hydrate carriers do not seize the lease. Every other scope remains
legacy. The lease is revoked with its read-receipt owner and a later exact scope may then be selected.

Channel-list, channel-detail, and DM hydrate responses carry the two nullable wire members in a dedicated
`ReadStateV1HydrateCarrier`. That carrier is not the legacy `readStateMaxReadSeq` field and is never consumed by
the legacy reducer or persisted slice. Missing either member stays incomplete and dark. Mutation responses
preserve the server's acknowledgement pair; the optimistic prediction remains UI-only. Owner realtime is the
existing `user:<id>` full-pair event. For the selected scope, all three sources enter the generation engine and
legacy apply is suppressed even when candidate validation, commit, or rebaseline fails.

Only the generation engine's post-commit observer may project a selected fact into Home and Activity. Those
projections do not write the legacy version checkpoint; the durable v1 row remains sole authority. A verified
hydrate equal to the durable pair establishes the baseline and projects it without rewriting the row.
Home performs selected hydrate restore/commit/adjudication before constructing or reducing the first App
snapshot; while the scope is selected, the legacy checkpoint filler is disabled even when canonical work fails.
Projection fan-out revalidates the complete environment/account/server owner before touching a store.

## Persistence and recovery

`syncReadStateFactEntity` stores one complete pair per durable identity. Snapshot commit still obeys version
ordering: a lower hydrate is stale, while a verified equal-version/different-pair hydrate is the authoritative
conflict adjudicator and may replace that disputed pair. Incremental compare and write occur in one SQL
transaction. Restore accepts only a complete, valid row from the exact partition; corrupt rows fail closed,
invalidate the baseline, and coalesce a clean hydrate request. A current-grant check is made inside the
transaction before mutation and again immediately before commit. Rebaseline requests carry the opaque grant
witness, and current-check + request-state installation + request emission share one grant-authority critical
section, so revoke cannot publish a hydrate request for an old owner.

## HOLD boundary

This phase deliberately adds no broad rollout, new realtime subscription, seen-by behavior, latest-three thread
generation, peer-receipt authority, or Message V2 schema/consumer change. It does not change server behavior,
visuals, text, attachment comments, or thread-scope generation.
