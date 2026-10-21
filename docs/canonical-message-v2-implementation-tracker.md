# Canonical message V2 implementation tracker

Status: **freeze v0.3 — canonical exact review pending; no GREEN or activation authorized**
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

After canonical exact review of this v0.3 tracker, the next delivery may add only:

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

## Durable generation boundary — frozen

### New V2 consumer

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

Reusing the V1 consumer is rejected. It would require scope schema-generation metadata, cursor-table rebuilds
and mixed-row poisoning rules while defeating the already-landed consumer partition.

## Full V2 envelope seam — schema v6

V1 types remain untouched. V2 is parallel:

```kotlin
data class CanonicalMessageEnvelopeV2(
    val schemaVersion: Int = 6,
    val kind: String = "normalized-message-v2",
    val fact: CanonicalMessageNodeV2,
    val context: CanonicalConversationContextV2,
    val childReplyScope: ReplyScopeRefV2? = null,
    val replyMembership: ReplyMembershipV2? = null,
)

data class ReplyMembershipV2(
    val root: MessageRefV2,
    val parentScope: ReplyScopeRefV2,
)

data class ReplyScopeRefV2(
    val serverId: String,
    val scopeId: String,
)

sealed interface TopLevelScopeRefV2 {
    data class Channel(val serverId: String, val scopeId: String) : TopLevelScopeRefV2
    data class Private(val serverId: String, val scopeId: String) : TopLevelScopeRefV2
    data class DirectMessage(val serverId: String, val scopeId: String) : TopLevelScopeRefV2
    data class Joint(val serverId: String, val scopeId: String) : TopLevelScopeRefV2
}

sealed interface CanonicalConversationContextV2 {
    data class TopLevel(
        val scope: TopLevelScopeRefV2,
    ) : CanonicalConversationContextV2

    data class Thread(
        val replyScope: ReplyScopeRefV2,
        val parentTopLevelScope: TopLevelScopeRefV2,
        val rootMessageRef: MessageRefV2,
    ) : CanonicalConversationContextV2
}
```

The serialized form uses explicit stable discriminators and exact-key rejection. Kotlin class names never
enter bytes.

Shared-fact rules:

- full reaction rosters are forbidden; shared reactions are bounded `{emoji,count,previewK}`;
- `reactedByMe`, viewer reaction emojis, read/follow/done and viewer-filtered comment counts are forbidden;
- raw `commentRef`, raw `threadId` and raw `conversationContext` are forbidden;
- reply parenthood is typed, never inferred from a relation string or loaded child page;
- `TopLevelScopeRefV2` losslessly covers the producer's `channel|private|dm|joint` union; it contains no
  `peerServerId` because the current producer supplies no such field;
- `CanonicalConversationContextV2.Thread` preserves the current thread triple: reply scope, parent top-level
  scope (`parentChannelId + parentChannelType`) and root message (`parentMessageId`);
- a top-level message may independently carry `childReplyScope`; this preserves the current wire semantic
  where a parent message has a child-thread anchor while its conversation kind remains channel/private/dm/joint;
- a thread reply carries `ConversationContextV2.Thread` and a `ReplyMembershipV2`. Their reply scope and root
  message facts must be exact-equal; mismatch is a named normalization rejection;
- channel, private, DM, joint and thread contexts use this closed typed shape;
- embedded refs carry `serverId`, which must match the durable partition;
- task fields keep their existing separate owner;
- read/follow/done keep their existing receiver-state owners and are not duplicated into a message overlay.

### Typed action metadata

V2 authoritative bytes do not contain raw `JsonElement`, opaque canonical JSON, or `Unknown(rawJson)`.
`actionMetadata` is a closed typed sealed family:

- known `action-card` fields use one exact shared shape;
- a forwarded bundle is split into a privacy-safe typed shared snapshot and receiver-private provenance;
- unknown kinds fail normalization with `unsupported_action_metadata_kind` and follow the RFC 043
  pre-accept/post-accept boundary;
- forwarded provenance is not durable in either shared facts or viewer overlay in this phase because current
  producer pointers are viewer-scrubbed/room-stripped and have no monotonic version.

## Manifest and golden gates

`canonicalMessageManifest.v6.json` becomes the full V2 field ledger. Every field/nested shape records:

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
normalized node/context/child-scope DTO == manifest shared-node paths
reply membership DTO == registered Message×Replies paths
viewer overlay DTO == receiver-private paths
V1 raw debt ∩ V2 shared paths == empty
```

The full envelope is `schemaVersion=6`, `kind=normalized-message-v2`. The existing schema-v5 exact tripwire
continues to describe only `{fact:{reactions}}` during Commit A/B. The v6 decoder explicitly rejects v5;
the same `(kind,version)` never names two mutually exclusive schemas.

Required manifest ownership entries include:

| Surface | Classification/backing |
|---|---|
| typed action-card fields | shared node fact |
| privacy-safe forwarded snapshot | shared node fact |
| forwarded provenance/pointers | excluded until a versioned receiver-private producer exists |
| reaction `{emoji,count,previewK}` | shared node fact |
| reaction viewer state + `viewerVersion` | ViewerOverlay |
| ReactionActors | typed ReadCache |
| AttachmentComments/ref/count | receiver-private typed ReadCache; no shared membership/overlay payload in this phase |

Shared Web/KMP fixtures pin:

1. maximal full V2 envelope;
2. minimal full V2 envelope;
3. versioned reaction viewer overlay;
4. typed reply membership;
5. typed top-level context with orthogonal child-reply scope;
6. typed thread context preserving parent top-level scope + root message.

Set-like collections use a declared canonical sort; semantically ordered collections preserve producer
order. Unknown, missing or extra authoritative keys fail closed.

## Typed discussion graph

| Root × relation | Backing | Durable representation | Invalidation | Commands |
|---|---|---|---|---|
| `Message × Replies` | SyncScope | child node carries typed reply membership | own reply-scope epoch | Reply |
| `Attachment × AttachmentComments` | ReadCache | receiver-private typed read key/page only; no shared membership fact | parent message-scope epoch | Reply |
| `Message × ReactionActors(emoji)` | ReadCache | typed read key; no roster in node | parent message-scope epoch | Interaction |

Illegal combinations and cross-server membership are unconstructable or fail exact decode. Child pages never
recompute parent aggregates. `replyCount` and reaction `count/previewK` remain server-authored shared facts;
viewer-filtered counts remain outside shared authority. For AttachmentComments, cache miss or a room-stripped
null is `Unknown` and never clears an independently loaded receiver-private edge/page. Parent-scope epoch
performs targeted invalidation only.

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
- reaction `viewerVersion` is server-authored per `(messageId,userId)` and arrives from hydrate, mutation ACK,
  or user-room `reaction_viewer:updated`; all three sources enter the same version gate;
- idempotent no-change does not bump `viewerVersion`;
- clients may not synthesize a version from message seq, time or arrival order;
- version comparison is scoped to the complete key including principal, consumer and overlay kind;
- SQLite INTEGER/Long overflow and boundary behavior is fail-closed and RED-pinned.

No attachment-comment count/ref or forwarded provenance payload is allowed in the durable overlay in this
phase. Those producers have no monotonic version/revision and require separate server prerequisites.

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
5. Unknown/malformed/non-v6 bytes in the V2 partition poison scope restore and trigger rebaseline; they are
   never projected through V1.
6. Crash-before restores the complete prior V2 state; crash-after restores the complete new state.
7. V1 partitions and durable migration remain untouched.
8. Post-accept return to legacy still requires control revocation plus a successful canonical rebaseline
   barrier.

## Planned commit topology

### A — seam/schema/migration

- parallel V2 DTO/ref/membership/overlay types;
- complete exact manifest-v6 field ledger + stable constants while retaining the v5 reaction tripwire;
- overlay store interface/table/queries;
- `2.sqm`, `3.db`, and JDBC/Android/iOS compatibility proofs;
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
| R1 | schema-v6 full V2 rejects V1 and schema-v5 partial reaction envelope | partial subtree mislabeled full V2 |
| R2 | exact keys reject unknown/missing shape | lenient schema drift |
| R3 | manifest closes every shared DTO path both directions | silent field loss/addition |
| R4 | each field is shared/overlay/read-cache exactly once | double ownership |
| R5 | Web/KMP golden bytes equal | serializer/order/default drift |
| R6 | shared envelope has no roster keys | unbounded V1 debt survives |
| R7 | reaction bound is 30 and preview bound is 3 | unbounded write amplification |
| R8 | shared room cannot author viewer overlay | private bit leaks from room frame |
| R9 | shared envelope has no viewer fields or forwarded provenance | privacy data leaks into shared authority |
| R10 | raw `commentRef`/`threadId`/`conversationContext` absent | stringly graph/context survives |
| R11 | reply membership and thread-parent reply-scope anchor survive restore | reply edge/child-thread anchor lost |
| R12 | AttachmentComments room-null/cache-miss stays Unknown | receiver-private edge/page is cleared |
| R13 | illegal root/relation cannot decode | wrong backing/capability accepted |
| R14 | cross-server membership fails before persistence | server aliasing |
| R15 | parent rebaseline invalidates read cache | stale child cache survives epoch |
| R16 | child page cannot recompute parent aggregate | second parent truth |
| R17 | reaction hydrate/ACK/user-room sources use one version gate | source-specific last-write behavior |
| R18 | greater overlay version replaces | arrival order controls state |
| R19 | lower overlay version is named no-op | viewer rollback |
| R20 | equal-version/different-payload fail-stops | producer conflict hidden |
| R21 | missing overlay is Unknown | cache miss clears viewer state |
| R22 | overlay key includes principal/consumer/overlayKind | cross-account/family restore leak |
| R23 | overlay compare+write is one transaction | version/payload tear |
| R24 | SQLite Long boundary rejects overflow/wrap | version order wraps or truncates |
| R25 | room reaction frame produces zero overlay writes | room broadcast authors private state |
| R26 | unsupported action kind is named rejection | raw/unknown JSON becomes authority |
| R27 | forwarded provenance enters neither shared nor overlay bytes | unversioned private provenance persists |
| R28 | V2 consumer never reads V1 facts/cursor | reverse projection/mixed decode |
| R29 | first V2 incremental frame requires baseline | incomplete history becomes authority |
| R30 | snapshot commits facts/reply membership/context/cursor once | generation tear |
| R31 | restore rejects malformed/mixed/non-v6 envelope | poison row silently dropped |
| R32 | rebaseline crash-before/after is complete | half-cleared scope |
| R33 | DB v2→v3 preserves rows/definitions | migration erases durable data |
| R34 | every DDL failure rolls back; retry succeeds | non-atomic migration |
| R35 | fresh/migrated v3 schemas exactly equal | upgrade/install divergence |
| R36 | eligibility and production binding unchanged | contract phase activates route |
| R37 | no T4 key/canary route added | rollout smuggled into V2 work |
| R38 | channel/private/dm/joint/thread producer contexts normalize losslessly | a producer union arm is collapsed or dropped |
| R39 | thread context preserves reply scope, parent channel id/type, and root message | thread parent triple is truncated |
| R40 | top-level context and child reply-scope anchor coexist | parent container kind is replaced by `Thread` |
| R41 | thread context and reply membership must exact-match | duplicate root/scope facts diverge silently |

## Canonical-owner rulings incorporated in v0.3

The canonical owner ruled:

1. use new `sync-core.messages.v2`;
2. remove raw `threadId`/`conversationContext`; use closed typed context/relation and preserve the parent
   child-thread anchor with `ReplyScopeRefV2`;
3. use closed typed action metadata; reject unknown kinds; do not persist forwarded provenance yet;
4. only reaction overlay has server monotonic `viewerVersion` and may enter the durable overlay;
5. AttachmentComments is receiver-private typed ReadCache, not shared membership;
6. full envelope uses schema v6; schema v5 remains the partial reaction tripwire;
7. conversation context is a lossless two-level model: top-level channel/private/dm/joint scope, or thread
   with reply scope + parent top-level scope + root message; child reply scope is orthogonal on parent nodes;
8. Commit A lands the complete exact manifest-v6 ledger, not a skeleton.

Once the canonical owner confirms this exact v0.3 wording, Commit A and Commit B may proceed autonomously to
one stable exact for terminal review. GREEN remains a separate authorization.

## Evidence ledger

| Date | Exact/ref | Result |
|---|---|---|
| 2026-07-16 | RFC staging `9a743b8518472e32135949f9d6f28a8782faa4d7` | route/status audit merged; activation HOLD |
| 2026-07-16 | mobile baseline `3fb1b5c5076cc0812303b9b531c6f9b7527c6964` | freeze candidate audited; no production change |
| 2026-07-16 | freeze v0.2 | Zhao core rulings incorporated; two wording/model blockers found |
| 2026-07-16 | freeze v0.3 | lossless context model + complete-manifest topology wording fixed; exact review pending |
