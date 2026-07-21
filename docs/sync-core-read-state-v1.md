# Sync Core read-state v1

Status: production-wired selected-scope canary behind an independent default-off server flag. Broad activation
and rollout remain explicitly out of scope.

## Durable identity

The consumer is exactly `sync-core.read-state.v1`. Its canonical key is
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
   commit, and clears only that owner's read-receipt pending overlay.

## Exact-witness publication boundary

Every grant issue creates a new opaque witness, including a same-scope replacement. Projection observers and
rebaseline requesters receive that original witness; a destination must never recover or re-associate a
publication from the scope alone.

Each witness owns a destination publication fence. Final projection writes and rebaseline emissions linearize
inside that fence, while authority current-state checks use a separate lock that is never held across the
destination callback:

- if a final destination operation enters first, revoke/replacement waits for it to finish before returning;
- if revoke/replacement closes the fence first, the old callback is not invoked;
- after revoke/replacement returns, the old witness can never perform another projection or rebaseline
  publication;
- same-scope replacement closes the old witness before publishing the new witness as current, so at most one
  generation can publish for that scope.

The fenced callback is limited to the final destination guard and write/emission. It must not re-enter grant
issue/revoke or its own publication-fence management. Durable store work and candidate normalization remain
outside the destination fence.

## Persistence and recovery

`syncReadStateFactEntity` stores one complete pair per durable identity. Snapshot commit still obeys version
ordering: a lower hydrate is stale, while a verified equal-version/different-pair hydrate is the authoritative
conflict adjudicator and may replace that disputed pair. Incremental compare and write occur in one SQL
transaction. Restore accepts only a complete, valid row from the exact partition; corrupt rows fail closed,
invalidate the baseline, and coalesce a clean hydrate request. A current-grant check is made inside the
transaction before mutation and again immediately before commit. Rebaseline requests carry the opaque grant
witness and their final emission uses that witness's destination publication fence, so revoke cannot publish a
hydrate request for an old owner.

## Production selected-scope canary

The independent server flag is exactly `sync_core_read_state_v1` and defaults off. When enabled, one
process-local exact `(environment, account, server, channel)` owner may be selected. Other scopes continue on
the legacy read-state path; enabling this flag does not enable Message V2 or any other Sync Core consumer.

The selected scope is sole-apply for trusted complete pairs:

- Home hydrate preserves the wire pair in a transient carrier, durably adjudicates it before first content,
  strips the carrier before final state/equality/persistence, and performs every selected adjudication's final
  Home state/cache publication inside the original witness publication fence, including stale, rejected, and
  failure-dark results that do not project a new durable fact;
- mutation acknowledgements and owner realtime apply only after a baseline, commit durably before projection,
  and never write the legacy checkpoint for the selected scope;
- Home and Activity verify the full environment/account/server owner before a final projection; Activity keeps
  its visible patch-only behavior and never turns an identity-only read-state event into a full-list refresh;
- optimistic read-receipt predictions remain UI-only and never become durable canonical facts.

Flag-off and owner departure first replace the selection with a same-scope snapshot barrier. The old witness is
closed before the replacement is current, and legacy ownership is released only after a verified hydrate has
committed and its exact-witness App projection completes. A failed barrier remains selected and dark. Auth
lifecycle reconciliation diffs all authenticated environment/account/server memberships and revokes only
departed owners; process-wide hard clear is reserved for teardown when no Home or Activity store is visible.

## HOLD boundary

This phase does not enable the default-off flag or open traffic. It adds no broad rollout, seen-by behavior,
latest-three thread generation, peer-receipt authority, server-side behavior, UI surface, or Message V2
schema/consumer change.
