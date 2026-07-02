# IM Mainline KMP Execution Plan

Date: 2026-07-03
Owner: Android-Developer-3
Status: proposal / execution index

## Goal

This document extends `docs/im-shortcomings-kmp-analysis-2026-07-02.md`
without editing it. The earlier document is a broad shortcomings analysis. This
document turns that direction into a KMP/shared execution index and points each
workstream at a concrete contract document.

The product target is simple:

- opening a conversation is identity-only and loads canonical state
- messages have observable lifecycle state from local compose through server
  confirmation, read/unread convergence, and weak-network recovery
- existing Composer stability remains protected as high-frequency chat work
  continues; advanced collaboration actions stay layered on top
- attachments, notifications, and presence are projected as IM state, not
  scattered per-feature UI state
- cross-platform validation proves the core path before regressions reach
  screenshots from users

## Relation To Existing Documents

Existing documents remain the source for their narrower topics:

- `docs/conversation-summary-boundary-spec-2026-06-27.md`
  - `ConversationSummary` stays a directory/list index, not Thread UI state.
- `docs/mobile-message-seq-gap-sync-spec-2026-06-25.md`
  - defines global resume cursor and visible stream gap sync.
- `docs/realtime-read-state-sync-spec-2026-06-23.md`
  - defines the proposed `read:updated` realtime event and read-state sync.
- `docs/mobile-foreground-reconciliation-spec-2026-06-29.md`
  - defines foreground refresh and room rejoin requirements.
- `docs/mobile-native-push-notification-analysis-2026-06-25.md`
  and `docs/mobile-native-push-server-requirements-2026-07-02.md`
  - define native push transport and server requirements.
- `docs/react-kmp-thread-alignment-2026-06-26.md`
  - tracks React/KMP Thread parity gaps.
- `docs/react-kmp-visual-testing-alignment-2026-06-26.md`
  and `docs/visual-testing-android-provider.md`
  - define visual-testing infrastructure.

The missing layer is the IM mainline composition: which subset of those specs
must be implemented together so chat feels reliable.

## New Extension Documents

This proposal adds four concrete follow-up specs:

- `docs/im-message-lifecycle-state-machine-2026-07-03.md`
  - one shared state machine for send, ack, retry, seq gap, read/unread,
    mention, and weak-network convergence.
- `docs/im-composer-baseline-contract-2026-07-03.md`
  - a current-state-aware baseline Composer contract for keyboard, draft, send,
    failure recovery, attachments, mention actions, and route scoping. This is a
    regression/normalization contract, not a claim that those capabilities are
    absent.
- `docs/im-media-notification-presence-contract-2026-07-03.md`
  - a shared projection contract for media groups, notification presentation,
    and low-noise presence/activity signals.
- `docs/im-core-cross-platform-fixture-matrix-2026-07-03.md`
  - the shared reducer / Android connected / React provider / OHOS+iOS smoke
    matrix for IM core paths.

## Execution Phases

### Phase 0: Classify Current State

Do not start by changing UI. First classify the current code paths into:

- canonical store state
- candidate snapshots from route/pageData/cache/realtime
- view-only projections
- native host delivery state
- visual/test fixture state

Acceptance:

- no new production route or Kuikly `pageData` payload passes message lists,
  header display state, attachment lists, composer UI state, or unread totals
- existing candidate snapshots are matched to `serverId + conversation identity`
  before they are allowed to fill missing UI state
- visual fixture payloads remain allowed only behind fixture source sets or test
  providers

### Phase 1: Message Lifecycle State Machine

Build the shared state machine first. It is the contract that lets Home,
Thread, Activity, notification, and app badge surfaces agree.

Required inputs:

- local composer send
- attachment upload result
- network send response
- server `message:new` / `message:updated`
- `sync:resume:response`
- visible stream `/messages/sync`
- `read:updated` or foreground unread refresh
- weak-network retry/backfill events

Required outputs:

- timeline row delivery state
- read/unread projection state
- app badge state
- notification presentation intent
- diagnostics for stale, reconciling, or blocked state

Acceptance:

- optimistic rows can be replaced by server rows without duplicates
- failed sends survive refresh until dismissed or retried
- visible stream sync uses stream max seq, not global last seq
- Home unread, Thread read marker, Activity unread, and app badge converge after
  foreground refresh

### Phase 2: Composer Baseline

Preserve the Composer work that already exists, then normalize the remaining
state boundaries as a high-frequency IM surface.

Acceptance:

- current draft persistence, staged uploaded attachments, pending mention
  actions, failed-send restore, and local failed-row retention keep working
- keyboard/safe-area changes do not regress timeline anchoring or detach
  attachment / quote bars
- draft restore remains keyed by send identity and parent reply identity
- send operation state is named enough for retry/failure tests to target it
  directly
- attachments and mention actions remain scoped to the correct conversation or
  reply context
- advanced actions such as task conversion stay additive and cannot break plain
  text send

### Phase 3: Media, Notification, Presence

Treat these as projections of the message lifecycle, not separate feature
islands.

Acceptance:

- a message with multiple images/files has a stable media group identity
- notification delivery and in-app banners derive from one presentation intent
- presence/activity subscriptions are scoped to visible rows, visible thread
  authors, active detail surfaces, or explicit foreground reconciliation
- large servers do not refresh durable caches only because background presence
  changed

### Phase 4: Cross-Platform IM Fixture Matrix

Only after the contracts are named should UI parity be expanded.

Acceptance:

- each core IM path has at least one shared reducer/store test
- each visible Android path has a connected visual or probe
- React provider has a matching canonical case for KMP comparison
- OHOS/iOS have either smoke route validation or native host bridge validation
  for the same state transition

## What The Earlier Shortcomings Document Did Not Cover

These items should be considered when specs turn into tasks:

- edit/delete retention semantics: whether deleted, edited, or redacted
  messages change unread and notification state
- privacy and access revocation: what happens to cached messages/attachments
  after channel membership changes
- spam/abuse backpressure: high-volume channels need throttled unread,
  notification, and presence work
- accessibility: message row status, failed send action, unread separators, and
  attachment groups need screen-reader names, not only visual badges
- observability: lifecycle state must produce low-cardinality diagnostics so
  field bugs can be classified without dumping private content
- migration: legacy route/pageData and cache snapshots need compatibility
  guards while the state machine rolls out

## Non-Goals

- Do not replace the earlier shortcomings analysis.
- Do not implement native push transport from this document.
- Do not make notification provider details part of KMP product state.
- Do not introduce new full-content route payloads to accelerate migration.
- Do not require all collaboration features to become IM-first; task/agent
  workflows can remain contextual enhancements.
