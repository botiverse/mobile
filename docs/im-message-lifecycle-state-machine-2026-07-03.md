# IM Message Lifecycle State Machine

Date: 2026-07-03
Owner: Android-Developer-3
Status: proposal

## Problem

KMP already has pieces of message state:

- `Message.deliveryState = Sending | Sent | Failed`
- `SharedThreadIntent.ApplyLocalSendStarted`
- `SharedThreadIntent.ApplyLocalSendFailure`
- `SharedThreadIntent.ReplaceLocalSendWithSentPage`
- `ReadReceiptVisibilityGate`
- persisted unread snapshots and app badge source state
- realtime `MessageNew`, `MessageUpdated`, `SyncResumeResponse`, heartbeat gap
  recovery, and foreground reconciliation

The missing part is a named product state machine that ties those pieces
together. Without that, Home, Thread, Activity, notification, app badge, and
Composer can all be individually plausible but still disagree during weak
network, reconnect, cross-device read, or send retry.

## State Identity

Every lifecycle record must be keyed by stable identity:

```text
serverId
streamChannelId          -- channel, DM, or resolved reply-thread stream
messageId                -- server id when available
localOperationId         -- local send id before server confirmation
parentMessageId?         -- reply context
clientNonce?             -- optional bridge between local send and server ack
```

Route/pageData may carry only identity and intent. Message lifecycle state must
live in the shared store/repository layer, not in production route payloads.

## Message Send States

Recommended shared states:

```text
Draft
Queued
UploadingAttachments
Sending
SentPendingReconcile
Sent
FailedRetryable
FailedBlocked
Dismissed
```

Mapping to current row UI can remain coarse:

- `Queued`, `UploadingAttachments`, `Sending`, `SentPendingReconcile` render as
  current `Sending` until UI adds richer copy.
- `Sent` renders as current `Sent`.
- `FailedRetryable` and `FailedBlocked` render as current `Failed`, with
  different retry affordance once UI supports it.

### Transitions

```text
Draft
  -> Queued                         user taps send
Queued
  -> UploadingAttachments           attachment upload needed
  -> Sending                        text-only or uploads already complete
UploadingAttachments
  -> Sending                        all uploads complete
  -> FailedRetryable                transient upload/network failure
  -> FailedBlocked                  permission/file/security failure
Sending
  -> SentPendingReconcile           server accepted but timeline page not merged
  -> Sent                           server response contains final message
  -> FailedRetryable                timeout, offline, 5xx
  -> FailedBlocked                  no permission, archived channel, auth expired
SentPendingReconcile
  -> Sent                           realtime/sync/page returns matching message
FailedRetryable
  -> Queued                         retry
  -> Dismissed                      user dismisses failed local row
FailedBlocked
  -> Dismissed                      user dismisses after reading reason
```

## Server Message States

Server-backed timeline rows should be treated separately from local send rows:

```text
ReceivedLocalSnapshot
ReceivedRealtime
ReceivedSyncResume
ReceivedVisibleGapSync
ReceivedPageLoad
Mutated
DeletedOrRedacted
```

Merge rules:

- merge by server `messageId` first
- if a local row has `clientNonce` or preserved pending send context, replace it
  with the server row when the ack/sync result matches
- do not drop a socket message only because global `lastSeq` is already higher
  than the message seq; a visible stream window may still need it
- failed local rows that have not been dismissed should survive refresh until a
  successful retry or explicit dismissal

## Read / Unread States

Read state should expose provenance, not only a number:

```text
Unknown
LocalObserved
MarkingRead
ServerConfirmed
Reconciling
StaleHeld
Failed
```

Surfaces can project this differently:

- Thread row: unread separator, first unread anchor, reply unread chip
- Home row: unread badge and bold title
- Activity/Inbox: unread filter and item count
- app badge: selected-server unread total
- notification: presentation intent, not raw unread count alone

Required provenance fields:

```text
source = server_unread_summary | channel_unread_counts | realtime_delta |
         read_updated | foreground_refresh | local_optimistic | cache
lastUpdatedAt
serverSeq?
streamChannelId?
isStale
isReconciling
```

## Mention And Reply State

Mentions and reply unread are not just badge variants. They need separate
projection because they answer different questions:

- channel unread: "there is unread activity in this stream"
- mention unread: "there is unread activity targeted at me"
- reply unread: "a reply thread I follow has unread activity"
- task/action mention: "there is an actionable mention with side effects"

The state machine should not collapse these into one count. It should expose a
small typed projection:

```text
UnreadProjection(
  unreadCount,
  mentionCount,
  firstUnreadMessageId,
  firstMentionMessageId,
  replyUnreadByParentMessageId,
  source,
  freshness
)
```

## Weak Network Recovery

Recovery inputs:

- `sync:resume:response`
- heartbeat `seq`
- visible stream `/messages/sync`
- foreground refresh
- explicit retry
- network outbox drain

Recovery phases:

```text
Healthy
SocketDisconnected
GlobalResumeRunning
VisibleStreamSyncRunning
OutboxDrainRunning
ReconcilingUnread
Recovered
PartiallyRecovered
Blocked
```

Rules:

- global resume is server-scoped and catches missed durable messages
- visible stream sync is stream-scoped and starts from the visible stream max
  seq, not global max seq
- unread reconciliation should prefer server summary endpoints over local math
  when foregrounding or recovering from long disconnect
- notification presentation must wait for durable eligibility; socket-only
  local notification is not a killed-app guarantee

## Store Boundary

Recommended shared owner:

```text
SharedMessageLifecycleStore
```

Responsibilities:

- local send operation registry
- local row to server row replacement
- lifecycle diagnostics
- read/unread projection freshness
- retry/dismiss policy

Non-responsibilities:

- rendering message rows
- native notification provider delivery
- full attachment preview UI
- route parsing

It can be introduced incrementally by extracting policy functions first:

- `mergeLocalSendWithServerMessage(...)`
- `nextDeliveryState(...)`
- `nextUnreadProjection(...)`
- `recoveryPhaseAfterEvent(...)`

## Acceptance Tests

Shared unit tests:

- local text send creates one optimistic row and clears draft only after send
  begins
- server ack replaces the optimistic row without duplicate timeline rows
- failed local send survives refresh and can be dismissed
- retry creates a new operation but preserves user-visible body and attachments
- global resume applies messages and advances server-scoped cursor
- visible stream sync starts from visible stream max seq, not global seq
- read-all optimistic clear is reconciled by `read:updated` or foreground
  unread refresh
- stale unread summary does not advance `lastUpdatedAt`
- reply unread is cleared only for the matching parent/thread identity

Android connected probes:

- send failure row remains after foreground refresh
- background/foreground after missed message triggers visible stream recovery
- mark read on Thread updates Home/AppBadge after reconciliation

Telemetry expectations:

- log state category and ids, not message bodies or attachment URLs
- include `serverId`, `streamChannelId`, `messageId/localOperationId`, state,
  source, and reason code
- avoid high-cardinality raw error text in diagnostics

## Open Questions

- Does the server expose a stable client nonce for send acknowledgements, or do
  we keep matching by local pending context and response payload?
- Should failed send rows persist across app restart for all messages or only
  for messages that reached `Sending`?
- Should read-state freshness be visible in UI, or only diagnostics for V1?
