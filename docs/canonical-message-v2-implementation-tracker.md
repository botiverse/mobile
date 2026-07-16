# Canonical message V2 implementation tracker

Status: **contract freeze in review; no GREEN or activation authorized**  
Mobile owner: HanXin (`#native` task #301)  
Canonical review owner: Zhao (canonical task #49)  
Audited mobile baseline: `3fb1b5c5076cc0812303b9b531c6f9b7527c6964`

## Authority and purpose

The normative architecture and rollout contract is **RFC 043 — Raft Sync Core** in
`botiverse/slock`, including the implementation ledger merged to `staging` as
`9a743b8518472e32135949f9d6f28a8782faa4d7`.

This file is a mobile implementation tracker. It records proposed seams, database migration work,
RED gates, review state and exact delivery evidence. It must not independently change the RFC contract.
If this tracker and RFC 043 disagree, implementation stops and the RFC owner adjudicates the difference.

The ordered route remains:

```text
host-ingest fold/persist
  → full normalized V2 envelope
  → committed-row sole-apply/UI drive
  → one-internal-server mobile canary
```

The current phase stops at the V2 contract/RED boundary. Production binding, eligibility, flags and
canary work are explicitly excluded.

## Current mobile state

- V1 durable consumer is `sync-core.messages.v1`, partitioned by
  `(serverId, principalId, consumerId)`.
- `messageEntity.canonicalFactJson` stores `CanonicalMessagePersistedV1(version=1)` and is the V1
  restore source.
- physical database schema is v2; `1.sqm` is the already-landed durable-partition migration.
- `CanonicalMessageV2ReactionEnvelope(schemaVersion=5, kind=normalized-message-v2)` is only a
  reaction-subtree tripwire, not the full message envelope.
- `ReactionViewerOverlaySnapshot` has no version/revision field or version gate.
- only a narrow `ReactionActorsDiscussionRef` exists; there is no complete typed discussion graph.
- `CanonicalMessageRow` still carries raw full reaction rosters, raw `commentRef`, raw
  `conversationContext`, and raw structured `actionMetadata`.
- the normal production provider does not bind `canonicalFinalIntegrationFactory`; no distinct T4
  control is registered; `CANONICAL_FACT_SOLE_APPLY_ELIGIBLE` remains `false`.

## Frozen scope of the next two commits

Subject to canonical-owner review, the next delivery may add only:

1. parallel V2 pure-data seams and the exact manifest/golden declarations;
2. typed discussion identity and membership seams;
3. receiver-private overlay version/revision seams and an independent durable store;
4. the forward SQLDelight migration for that overlay store;
5. deterministic test ports and pure RED tests.

It must not:

- change V1 bytes or the V1 fold/persistence path;
- bind the canonical-final integration in production;
- register or enable T4/sole-apply flags;
- change `CANONICAL_FACT_SOLE_APPLY_ELIGIBLE`;
- construct a production sole-apply lane/router;
- enable a canary or change legacy visibility;
- delete/rewrite the existing V1 durable partition;
- implement GREEN normalization/apply behavior before the canonical owner freezes this tracker.

## Proposed durable generation boundary

### Recommended: new V2 consumer

Use:

```text
sync-core.messages.v2
```

Do not mix V1 and V2 rows inside `sync-core.messages.v1`.

The existing durable consumer dimension is already a compatibility boundary. A new consumer makes V1/V2
fact and cursor mixing structurally impossible, obtains a clean fresh reload without deleting V1 rows, and
preserves the existing durable-partition migration. The old V1 partition remains untouched until a separate
retention decision.

A V2 scope starts unbaselined. Its first authoritative input must be a server-canonical snapshot/rebaseline.
A realtime incremental frame arriving before that baseline is held and produces a named
`v2_baseline_required` outcome plus one single-flight rebaseline request. It cannot seed an authoritative
scope and cannot become a canonical commit.

Reusing the V1 consumer would require scope schema-generation metadata, cursor-table rebuilds and mixed-row
poisoning rules. That alternative remains rejected unless the canonical owner identifies a product need.

## Proposed V2 envelope seam

V1 types remain untouched. V2 is parallel:

```kotlin
data class CanonicalMessageEnvelopeV2(
    val schemaVersion: Int = 5,
    val kind: String = "normalized-message-v2",
    val fact: CanonicalDiscussionNodeV2,
    val membership: CanonicalDiscussionMembershipV2? = null,
)

sealed interface CanonicalDiscussionNodeV2 {
    data class Message(val value: CanonicalMessageNodeV2) : CanonicalDiscussionNodeV2
    data class Comment(val value: CanonicalCommentNodeV2) : CanonicalDiscussionNodeV2
}

sealed interface CanonicalDiscussionMembershipV2 {
    data class Reply(
        val root: MessageRefV2,
        val parentScope: ParentSyncScopeRefV2,
    ) : CanonicalDiscussionMembershipV2

    data class AttachmentComment(
        val root: AttachmentRefV2,
        val parentScope: ParentSyncScopeRefV2,
        val anchor: CommentAnchorV2?,
    ) : CanonicalDiscussionMembershipV2
}
```

The serialized form uses explicit stable discriminators and exact-key rejection. Kotlin class names never
enter bytes.

Shared-fact rules:

- full reaction rosters are forbidden; shared reactions are bounded `{emoji,count,previewK}`;
- `reactedByMe`, viewer reaction emojis, read/follow/done and viewer-filtered comment counts are forbidden;
- raw `commentRef` is forbidden; surviving semantics become typed node/membership/root/anchor data;
- reply/comment parenthood is typed, never inferred from a relation string;
- embedded refs carry `serverId`, which must match the durable partition;
- task fields keep their existing separate owner;
- read/follow/done keep their existing receiver-state owners and are not duplicated into a message overlay.

## Manifest and golden gates

`canonicalMessageManifest.v5.json` becomes the V2 field ledger. Every field/nested shape records:

- exact serialized path and key set;
- Kotlin/TS type and nullability;
- shared/private provenance;
- merge policy and producer faces;
- order policy;
- backing (`SyncScope`, `ReadCache`, `ViewerOverlay`);
- invalidation parent scope;
- allowed commands for graph relations.

Mechanical closure:

```text
normalized node DTO == manifest shared-node paths
membership DTO == registered (rootKind, relation) paths
viewer overlay DTO == receiver-private paths
V1 raw debt ∩ V2 shared paths == empty
```

Keep schema version `5` and kind `normalized-message-v2`, replacing the partial reaction tripwire only when
the complete DTO exists.

Shared Web/KMP fixtures pin:

1. maximal full V2 envelope;
2. minimal full V2 envelope;
3. versioned reaction viewer overlay;
4. typed reply membership;
5. typed attachment-comment membership.

Set-like collections use a declared canonical sort; semantically ordered collections preserve producer
order. Unknown, missing or extra authoritative keys fail closed.

## Typed discussion graph

| Root × relation | Backing | Durable representation | Invalidation | Commands |
|---|---|---|---|---|
| `Message × Replies` | SyncScope | child node carries typed reply membership | own reply-scope epoch | Reply |
| `Attachment × AttachmentComments` | ReadCache | comment node carries typed attachment membership | parent message-scope epoch | Reply |
| `Message × ReactionActors(emoji)` | ReadCache | typed read key; no roster in node | parent message-scope epoch | Interaction |

Illegal combinations and cross-server membership are unconstructable or fail exact decode. Child pages never
recompute parent aggregates. `replyCount` and reaction `count/previewK` remain server-authored shared facts;
viewer-filtered counts remain overlay-only.

## Versioned receiver-private overlay

```kotlin
data class ViewerOverlayKey(
    val partition: DurablePartitionKey,
    val scopeKind: String,
    val scopeId: String,
    val subjectKind: String,
    val subjectId: String,
    val overlayKind: String,
)

data class VersionedViewerOverlayV1(
    val version: Long,
    val completeness: OverlayCompleteness,
    val payload: ViewerOverlayPayloadV1,
)

sealed interface ViewerOverlayPayloadV1 {
    data class Reactions(val reactedEmojis: List<String>) : ViewerOverlayPayloadV1
    data class AttachmentCommentCount(val count: Int) : ViewerOverlayPayloadV1
}
```

Rules:

- greater version replaces;
- equal version with equal bytes is idempotent;
- equal version with different bytes fail-stops as `viewer_overlay_version_conflict`;
- lower version is a named `viewer_overlay_version_regression` no-op;
- absence/eviction is `Unknown`, not authoritative empty;
- only receiver-private hydrate, own-mutation ACK or user-room events may author it;
- channel-room frames always produce zero overlay writes;
- version is server-authored; clients may not synthesize it from message seq, time or arrival order.

Shared and overlay restore are independent. A valid shared fact may render with an `Unknown` overlay; it does
not fabricate `false`/zero and does not block shared content.

## SQLDelight migration proposal

Database v2 remains the base. Add `2.sqm` and `databases/3.db`. The migration only creates:

```sql
CREATE TABLE messageViewerOverlayEntity (
  serverId TEXT NOT NULL,
  principalId TEXT NOT NULL,
  consumerId TEXT NOT NULL,
  scopeKind TEXT NOT NULL,
  scopeId TEXT NOT NULL,
  subjectKind TEXT NOT NULL,
  subjectId TEXT NOT NULL,
  overlayKind TEXT NOT NULL,
  overlayVersion INTEGER NOT NULL,
  completeness TEXT NOT NULL,
  payloadJson TEXT NOT NULL,
  PRIMARY KEY (
    serverId, principalId, consumerId,
    scopeKind, scopeId,
    subjectKind, subjectId, overlayKind
  )
);
```

No `IF NOT EXISTS`. Do not rebuild/drop message, cursor, outbox or other tables. Fresh-v3 and migrated-v3
`sqlite_master` definitions must be equal. Both `1.db → v2 → v3` and `2.db → v3` paths retain all existing
rows/definitions.

Overlay version compare+write is one transaction. It is not coupled to a shared-room fact transaction: the
two owners have different authority and restore lifecycles.

## V1→V2 runtime migration

1. V2 selects `sync-core.messages.v2` and never reads the V1 partition as seed.
2. V2 scopes begin unbaselined.
3. A server snapshot commits V2 facts/membership/cursor in one existing
   `FoldPersistFrame(rebaseline=true)` transaction.
4. Pre-baseline realtime holds and requests rebaseline.
5. Unknown/malformed/non-v5 bytes in the V2 partition poison scope restore and trigger rebaseline; they are
   never projected through V1.
6. Crash-before restores the complete prior V2 state; crash-after restores the complete new state.
7. V1 partitions and durable migration remain untouched.
8. Post-accept return to legacy still requires control revocation plus a successful canonical rebaseline
   barrier.

## Planned commit topology

### A — seam/schema/migration

- parallel V2 DTO/ref/membership/overlay types;
- manifest-v5 resource skeleton and constants;
- overlay store interface/table/queries;
- `2.sqm`, `3.db`, and JDBC/Android/iOS/OHOS compatibility proofs;
- no production callsite; no V1 byte change.

Existing tests remain green.

### B — pure RED

- independent full-envelope oracle and golden fixtures;
- graph, overlay version, baseline/reload, crash, poisoning and privacy REDs;
- deterministic fakes only;
- no GREEN normalizer/decoder/persistence wiring/host binding/flag change.

### C — GREEN

Not authorized until A/B shape is frozen by the canonical owner.

## RED kill table

| ID | Required assertion | Anti-implementation killed |
|---|---|---|
| R1 | full V2 rejects V1 and partial reaction envelope | partial subtree mislabeled full V2 |
| R2 | exact keys reject unknown/missing shape | lenient schema drift |
| R3 | manifest closes every shared DTO path both directions | silent field loss/addition |
| R4 | each field is shared/overlay/read-cache exactly once | double ownership |
| R5 | Web/KMP golden bytes equal | serializer/order/default drift |
| R6 | shared envelope has no roster keys | unbounded V1 debt survives |
| R7 | reaction bound is 30 and preview bound is 3 | unbounded write amplification |
| R8 | shared room cannot author viewer overlay | private bit leaks from room frame |
| R9 | shared envelope has no viewer fields | read/follow/count privacy leak |
| R10 | raw comment/reply parent fields absent | stringly graph survives |
| R11 | reply membership survives restore | reply edge lost |
| R12 | attachment-comment membership survives restore | comment flattened to message |
| R13 | illegal root/relation cannot decode | wrong backing/capability accepted |
| R14 | cross-server membership fails before persistence | server aliasing |
| R15 | parent rebaseline invalidates read cache | stale child cache survives epoch |
| R16 | child page cannot recompute parent aggregate | second parent truth |
| R17 | greater overlay version replaces | arrival order controls state |
| R18 | lower overlay version is named no-op | viewer rollback |
| R19 | equal-version/different-payload fail-stops | producer conflict hidden |
| R20 | missing overlay is Unknown | cache miss clears viewer state |
| R21 | overlay is principal+consumer partitioned | cross-account restore leak |
| R22 | overlay compare+write is one transaction | version/payload tear |
| R23 | V2 consumer never reads V1 facts/cursor | reverse projection/mixed decode |
| R24 | first V2 incremental frame requires baseline | incomplete history becomes authority |
| R25 | snapshot commits facts/membership/cursor once | generation tear |
| R26 | restore rejects malformed/mixed envelope | poison row silently dropped |
| R27 | rebaseline crash-before/after is complete | half-cleared scope |
| R28 | DB v2→v3 preserves rows/definitions | migration erases durable data |
| R29 | every DDL failure rolls back; retry succeeds | non-atomic migration |
| R30 | fresh/migrated v3 schemas exactly equal | upgrade/install divergence |
| R31 | eligibility and production binding unchanged | contract phase activates route |
| R32 | no T4 key/canary route added | rollout smuggled into V2 work |

## Pending canonical-owner rulings

No GREEN starts until these are resolved:

1. new `sync-core.messages.v2` consumer versus same-consumer schema tagging;
2. whether `threadId`/`conversationContext` fully move to typed membership/context;
3. whether `actionMetadata` becomes typed sealed data or explicitly opaque canonical JSON;
4. whether the server supplies monotonic receiver-private overlay versions;
5. whether attachment-comment membership is shared or receiver-private visibility data.

## Evidence ledger

| Date | Exact/ref | Result |
|---|---|---|
| 2026-07-16 | RFC staging `9a743b8518472e32135949f9d6f28a8782faa4d7` | route/status audit merged; activation HOLD |
| 2026-07-16 | mobile baseline `3fb1b5c5076cc0812303b9b531c6f9b7527c6964` | freeze candidate audited; no production change |

