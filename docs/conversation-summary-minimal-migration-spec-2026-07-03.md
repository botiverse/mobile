# ConversationSummary Minimal Migration Spec

Date: 2026-07-03

Status: proposed incremental migration plan for #520. This is a spec-only
change; it does not change product behavior.

## Goal

Move Thread launch, header, access, and directory fallback boundaries away from
`ConversationSummary` with the lowest practical risk.

The intended end state is:

- Production route and Kuikly `pageData` carry only identity and intent.
- `ThreadLaunchConversationIdentity` carries only launch identity.
- Header fallback uses `SharedConversationHeaderEntry` or
  `SharedConversationDirectoryProjection`, not a full `ConversationSummary`.
- Access fallback uses a narrow access candidate matched to the current route.
- Home directory projection is built directly from `HomeConversationRow`.
- Inbox/Search/Saved can still use row aggregates internally until their row
  models are split, but they cannot smuggle display/access state into Thread
  launch.

## Non-Goals

- Do not delete `ConversationSummary` in one pass.
- Do not rename `ConversationSummary` while it still powers Home/Inbox row
  compatibility.
- Do not change route semantics, sorting, unread badges, composer behavior, or
  visible Thread chrome as part of this spec.
- Do not introduce a new broad fallback payload that bundles header, access,
  origin, row activity, and route identity together again.
- Do not use broad production-code grep as the final safety gate; use targeted
  descriptor/parity tests around known boundaries.

## Contract

This migration follows `slock-launch-contract`:

- Route/pageData inputs may include identity, selected tab, focus/anchor intent,
  composer seed intent, local time formatting, and host context.
- Route/pageData inputs must not include product display state such as title,
  subtitle, avatar, messages, members, tasks, files, joined/archive state, or
  permissions.
- Cache, Home row, Inbox row, sparse API response, and realtime data are
  candidate snapshots. They must match route identity before use.
- A candidate may fill missing state but must not override canonical
  repository/store state.

## Current Boundary Debt

`WireThreadLaunchPayload` is already mostly aligned with the contract. It carries
route identity plus intent fields such as `focusedMessageId`, `selectedTab`,
row-affordance flags, composer seed, and local time context.

The remaining debt is around compatibility helpers:

- `ThreadLaunchConversationIdentity` still contains `name`, `subtitle`,
  `joined`, `archived`, joint metadata, and `permissions`, then converts back to
  `ConversationSummary`.
- `buildAppShellThreadPayload()` and
  `buildStandaloneConversationThreadLaunchPayload()` still call
  `ThreadLaunchConversationIdentity.toConversationSummary()` to recover route
  shape and capability fields.
- `resolveConversationHeaderFallback()` returns a `ConversationSummary` from the
  Home snapshot, and `withConversationAccessFallback()` consumes that full
  summary for access state.
- `conversationHeaderEntryFrom(conversation: ConversationSummary?)` converts a
  summary into `SharedConversationHeaderEntry`, even though the target header
  entry type already exists.
- `SharedConversationDirectoryStore` builds
  `HomeConversationRow -> ConversationSummary -> SharedConversationDirectoryProjection`.
- `HomeMessageOpenRequest` is better than the old route payload path because
  `ThreadLaunchOriginFallback` is already outside `ConversationSummary`, but it
  still constructs launch identity through `InboxItem.toConversationSummary()`.

## Target Types

### ThreadLaunchConversationIdentity

Keep the existing name during migration to reduce call-site churn, but narrow it
to route identity only.

Allowed fields:

- `threadId`
- `channelId`
- `channelType` or a normalized channel kind
- `kind`
- `thread` identity, or an equivalent split of:
  - stream channel id
  - parent channel id
  - parent message id
  - parent surface/channel kind hints

Fields to remove from this type:

- `name`
- `subtitle`
- `joined`
- `archived`
- `permissions`
- `jointChannelId`
- `jointRole`
- any avatar, presence, preview, draft, unread, or member-count fields

During the transition, keep `ConversationSummary.toThreadLaunchConversationIdentity()`
as a compatibility adapter, but make it drop display/access fields.

### ConversationHeaderCandidate

Prefer reusing `SharedConversationHeaderEntry` rather than creating another
header type.

Rules:

- It must carry ids used for matching.
- It may carry title/subtitle/avatar label/avatar wire value for display.
- It may be derived from `SharedConversationDirectoryProjection`.
- It must be accepted only when route lookup ids match and surface kind matches.
- It must not be serialized into route/pageData.

### ConversationAccessCandidate

Introduce a narrow access fallback type instead of using `ConversationSummary`.

Suggested shape:

```kotlin
internal data class ConversationAccessCandidate(
    val ids: Set<String>,
    val surfaceKind: String,
    val joined: Boolean,
    val archived: Boolean,
    val canRead: Boolean,
    val canSend: Boolean,
    val canCreateTask: Boolean
)
```

Rules:

- Match by route lookup ids before applying.
- Ignore for reply-thread payloads unless the candidate explicitly matches the
  reply stream and the consuming path needs stream access state.
- Use it only to fill missing access/capability state before canonical Thread
  data arrives.
- When canonical `ThreadSnapshot.conversation` exists, canonical snapshot state
  wins.

### SharedConversationDirectoryProjection

Keep this as the directory/header projection currency.

Add direct builders from `HomeConversationRow` so the directory no longer depends
on `ConversationSummary` for projection.

### ThreadLaunchOriginFallback

Keep the existing separate origin fallback. It is already a scoped candidate and
should not be merged into `ConversationSummary` or route/pageData.

## Migration Phases

### Phase 0: Add Guard Tests First

No production behavior changes.

Add or strengthen tests around the current boundaries:

- `WireThreadLaunchPayload` serializer descriptor excludes product state fields:
  `name`, `title`, `subtitle`, `joined`, `archived`, `permissions`,
  `members`, `messages`, `tasks`, `files`, `avatar`, `presence`,
  `lastMessagePreview`, and unread/draft counters.
- `ThreadLaunchConversationIdentity` has no display/access/row fields. Because
  it is not serialized, use a focused unit test over Kotlin property names or a
  narrow construction test in the same package.
- `ThreadLaunchOriginFallback` remains the only origin fallback passed by
  `HomeMessageOpenRequest`.
- Existing header projection tests prove mismatched projection ids are rejected.
- Add a parity fixture for the future direct directory projection builder before
  switching production code.

Validation:

- Focused shared tests for `ThreadPayloadTest`,
  `SharedConversationDirectoryStoreTest`, and `SharedInboxPanelTest`.
- `:shared:compileDebugKotlinAndroid` when feasible.

Rollback:

- Remove only tests if a descriptor/property assertion turns out too strict
  before production behavior changes.

### Phase 1: Narrow ThreadLaunchConversationIdentity

Production behavior should remain unchanged.

Steps:

- Remove display/access fields from `ThreadLaunchConversationIdentity`.
- Reimplement `resolveThreadRouteIdentity()` without converting back to
  `ConversationSummary`.
- Update `buildStandaloneConversationThreadLaunchPayload()` so it derives
  surface kind, stream id, parent ids, and route channel kind directly from the
  narrowed identity.
- Keep `ConversationSummary.toThreadLaunchConversationIdentity()` as an adapter
  for Home/Inbox/Search row sources.
- Stop using `ThreadLaunchConversationIdentity.toConversationSummary()` in new
  code. If a temporary compatibility method must remain, mark it as migration
  debt and keep its call sites shrinking.

Validation:

- Standalone channel, DM, joint, and reply-thread launch payload tests.
- Focused-message anchor tests.
- Serializer descriptor test from Phase 0 still passes.

Rollback:

- Revert this phase only. It should be isolated to launch identity helpers and
  launch payload builders.

### Phase 2: Split Header and Access Fallbacks

Replace `ConversationSummary` fallback currency with narrow candidates.

Steps:

- Change `resolveConversationHeaderFallback()` into either:
  - `resolveConversationHeaderCandidate(): SharedConversationHeaderEntry?`, or
  - `resolveConversationDirectoryProjection(): SharedConversationDirectoryProjection?`
    plus local conversion to header entry.
- Change `conversationHeaderEntryFrom(conversation: ConversationSummary?)` to
  accept `SharedConversationHeaderEntry?` or
  `SharedConversationDirectoryProjection?`.
- Introduce `ConversationAccessCandidate` for access fallback.
- Change `withConversationAccessFallback(fallback: ConversationSummary?)` to
  consume `ConversationAccessCandidate?`.
- Change `ThreadSnapshot.withConversationAccessFallback()` to consume the same
  access candidate and apply it only when the candidate matches the current
  conversation identity.
- Keep display header fallback and access fallback as two values. Do not create
  a single "conversation fallback" object that recreates the old summary role.

Validation:

- Header mismatch tests reject wrong channel/thread ids.
- Header candidate can fill title/subtitle/avatar while route payload remains
  loading/identity-only.
- Access candidate can disable composer for a matching unjoined channel.
- Access candidate cannot disable composer for an unrelated route.
- Canonical `ThreadSnapshot.conversation` state wins over candidate data.

Rollback:

- Revert candidate type consumers while preserving Phase 0 tests. The fallback
  path is local to Thread store/header projection code.

### Phase 3: Build Directory Projection Directly

Remove `ConversationSummary` from the Home directory projection path.

Steps:

- Add `HomeConversationRow.toSharedConversationDirectoryProjection(serverId)`.
- Add direct alias construction from row identity fields:
  - canonical channel/stream id
  - route thread id
  - channel id
  - reply stream id
  - parent channel/message ids for reply threads
  - presence member id for DM lookup
- Make `sharedConversationDirectoryState()` use direct projection builders.
- Replace `HomeSnapshot.resolveHomeConversationSummary()` with a direct
  projection/identity resolver where possible. Keep the old method only for row
  callers that still truly need `ConversationSummary`.
- Add parity tests comparing old summary-based projection to the direct builder
  for channel, DM, joint, and reply-thread rows before deleting the old path.

Validation:

- Existing directory alias tests pass.
- Parity fixture proves canonical id, aliases, surface kind, channel kind,
  title/subtitle, peer member id, parent ids, joined, and archived match the old
  projection.
- Home row rendering remains stable because row rendering still owns its row
  aggregate.

Rollback:

- Revert directory builder swap only. Route/header Phase 1/2 changes do not
  depend on the direct builder once candidate APIs are typed.

### Phase 4: Clean Open Request Signatures

Stop asking callers to provide a full `ConversationSummary` where a Thread
launch only needs identity.

Steps:

- Prefer `HomeMessageOpenRequest(conversationIdentity = ..., anchor = ...,
  originFallback = ...)`.
- Let Inbox/Search/Saved construct identity directly from their row models.
- Keep `ConversationSummary` adapters only at the row/cache layer.
- Remove or deprecate overloads:
  - `buildAppShellThreadPayload(conversation: ConversationSummary, ...)`
  - `buildStandaloneConversationThreadLaunchPayload(conversation: ConversationSummary, ...)`
  - `openStandaloneHomeConversation(conversation: ConversationSummary, ...)`
    where direct identity call sites exist.

Validation:

- Activity, Inbox, Search, Saved, and Task open tests still produce the same
  route identity and focused-message intent.
- `ThreadLaunchOriginFallback` is still carried outside the conversation
  identity.

Rollback:

- Restore only the removed overloads. The narrowed identity should remain.

### Phase 5: Delete Compatibility Helpers

Final cleanup after all call sites are off the compatibility path.

Delete:

- `ThreadLaunchConversationIdentity.toConversationSummary()`
- summary-based `conversationHeaderEntryFrom(...)`
- summary-based access fallback methods
- summary-based directory projection builder

At this point `ConversationSummary` remains only as a row/directory aggregate
for legacy Home/Inbox/cache code, not as a Thread launch/header/access fallback
currency.

## Identity Matching Rules

Header/access/directory candidates may match only these identity keys:

- Channel/DM route:
  - route `streamChannelId`
  - route `sourceThreadId`
  - route `parentChannelId` only when it represents the visible channel route
  - candidate canonical channel id
  - candidate channel id aliases
- Reply-thread route:
  - reply stream channel id
  - parent channel id plus parent message id
  - route thread id/source thread id
  - candidate reply stream aliases
- DM route:
  - DM channel id/thread id aliases
  - peer member id only as a secondary lookup key, not as sole proof for access
    state

Mismatches must be ignored. Missing candidates should leave the payload in its
loading/default state and let the MVI store/repository load canonical state.

## Risk Register

- Header flicker: If the candidate is rejected too aggressively, Thread may show
  the loading title longer. This is acceptable for a migration phase and safer
  than showing a wrong title.
- Composer access: If access fallback is lost, an unjoined channel may briefly
  show an enabled composer until canonical state arrives. Guard with matching
  access tests before swapping the fallback.
- Reply-thread parent identity: Parent message id must not be confused with a
  latest reply anchor. Keep existing anchor-chain tests.
- Joint channels: Preserve joint route/channel kind and permissions in access
  candidate tests before deleting summary conversion.
- Directory aliases: Direct projection must preserve all aliases before
  removing the summary-based builder.

## Suggested Commit Order

1. Tests and descriptors only.
2. Narrow `ThreadLaunchConversationIdentity`.
3. Header/access candidate split.
4. Direct directory projection with parity tests.
5. Open-request signature cleanup.
6. Delete compatibility helpers.

Each commit should be independently revertible and should avoid broad
format-only churn.
