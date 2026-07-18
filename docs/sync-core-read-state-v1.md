# Sync Core read-state v1

Status: dormant contract and durable seam. Production activation and rollout are explicitly out of scope.

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

## Persistence and recovery

`syncReadStateFactEntity` stores one complete pair per durable identity. Snapshot commit still obeys version
ordering: a lower hydrate is stale, while a verified equal-version/different-pair hydrate is the authoritative
conflict adjudicator and may replace that disputed pair. Incremental compare and write occur in one SQL
transaction. Restore accepts only a complete, valid row from the exact partition; corrupt rows fail closed,
invalidate the baseline, and coalesce a clean hydrate request. A current-grant check is made inside the
transaction before mutation and again immediately before commit.

## HOLD boundary

This phase deliberately adds no production provider, realtime subscription, feature flag, sole-apply switch,
or rollout. It also adds no seen-by behavior, latest-three thread generation, peer-receipt authority, or Message
V2 schema/consumer change.
