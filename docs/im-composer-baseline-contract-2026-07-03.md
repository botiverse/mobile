# IM Composer Baseline Contract

Date: 2026-07-03
Owner: Android-Developer-3
Status: proposal

## Problem

The Composer currently carries plain text, Markdown, attachments, reply seed,
task/action affordances, mention insertion, pending mention actions, diagnostics
feedback, draft persistence, keyboard/safe-area behavior, and send failure
recovery.

Many of those capabilities already exist in KMP. This document is not saying
Composer is mostly missing. It is a baseline and regression contract for the
parts that already work, plus a narrow list of remaining normalization gaps.

The IM path that must stay protected is:

```text
focus -> type -> attach optionally -> send -> see result -> retry if needed
```

This document defines the KMP/shared baseline that must stay stable while
advanced collaboration features are layered above it.

## Current Implemented Baseline

As of 2026-07-03, the KMP Composer already has meaningful coverage:

- `SharedThreadComposerDraftStore`
  - persists draft text and `asTask`
  - keys active draft records by send channel, parent message, and mention
    channel
  - projects active root drafts back to Home rows
  - restores drafts on start
  - clears draft persistence on completed send
  - stages uploaded attachments by draft key and consumes them on restore
- Composer intents already model:
  - text changes
  - mention insertion
  - begin send / complete send
  - restore after failure
  - uploaded/local attachments
  - attachment failure, removal, and retry
  - pending mention actions and action execution notices
- `SharedThreadStore` already models local send start, local send failure,
  local send cancellation, replacement with sent page, and failed local row
  dismissal.
- `ThreadPage.kt` already owns the rendered Composer chrome, including text,
  attachment strip, quote/reply context, pending-action notices, and keyboard /
  safe-area layout behavior.

So the remaining work is not "build Composer from scratch". It is to make the
existing behavior easier to reason about, test, and keep stable across route
changes, foreground recovery, and visual parity work.

## Remaining Normalization Gaps

The gaps this document is concerned with are:

- there is no single named send-operation lifecycle that ties Composer draft,
  local failed row, server ack, and retry together
- keyboard/safe-area behavior exists but needs a small regression matrix so it
  does not regress during Thread chrome changes
- attachment states exist in Composer, but media-group/message lifecycle docs
  should define how uploaded attachment state joins final message media state
- failure copy is mostly represented as labels/error codes; product categories
  should be typed so UI/tests do not infer behavior from ad hoc strings
- advanced extensions should continue using Composer seed/banner/send metadata
  boundaries and should not start owning draft persistence or send target
  directly

## Identity

Composer state must be keyed by send scope, not by route instance alone:

```text
serverId
sendChannelId
parentMessageId?       -- reply composer
mentionChannelId?      -- mention-action context
draftKey
composerInstanceId
```

Allowed in route/pageData:

- selected conversation identity
- focused message id
- draft seed intent
- diagnostics feedback seed intent

Not allowed in production route/pageData:

- current typed text after the page starts
- uploaded attachment lists
- pending attachment state
- pending send queue
- current keyboard/safe-area state

## Baseline State Model

The current implementation is split across `SharedThreadComposerDraftStore`,
`SharedThreadStore`, and `ThreadPage.kt`. A future normalized model could be
described as:

```text
ComposerSession(
  identity,
  text,
  mode,
  focus,
  keyboard,
  attachmentGroup,
  quoteContext,
  sendQueue,
  failure,
  pendingMentionActions
)
```

### Text State

Already-covered or target fields:

- raw text
- normalized send body
- selection/cursor when platform can provide it
- dirty flag
- last persisted draft time
- seed source

Rules:

- blank text plus no attachments is not sendable
- text edits clear inline failure only for the current composer identity
- seed text should be consumed once after send starts, not repeatedly on
  recomposition
- draft persistence must debounce, but a send tap should flush the latest text
  before clearing

### Keyboard And Layout State

Regression invariants:

- opening keyboard must not change the send target
- bottom safe area and keyboard inset must compose additively, not fight
- attachment strip, quote bar, pending action banner, and failure banner must be
  measured as composer chrome, not timeline content
- the timeline should keep the user's visible anchor stable when composer height
  changes

Validation:

- Android connected probe for keyboard open/close with multiline draft
- visual fixture for empty, one-line, multiline, with quote, with attachments,
  with failure, and with pending mention actions

### Attachment State

Composer already tracks uploaded/local attachments, staged uploaded attachments,
removed attachment ids, and attachment failures. The normalization target is to
describe those states as part of the send operation:

```text
LocalSelected
Uploading
Uploaded
UploadFailedRetryable
UploadFailedBlocked
Removed
```

Rules:

- attachment ids must be scoped to the draft/send identity
- uploaded attachments staged for a draft must be consumed exactly once
- removing an attachment before send should not leave it in staged upload state
- retrying an attachment must not duplicate the message send operation
- failed attachments should block send only when they are still included

### Send Queue

KMP already supports the practical V1 behavior: one active local send path, a
local sending row, failure restoration, and failed-row dismissal. The remaining
gap is naming the operation state so retry/failure tests can target it directly.

A future state shape can allow multiple queued operations later, but that is not
a V1 requirement.

Target fields:

```text
operationId
body
attachmentIds
createdAt
state
retryCount
lastErrorCode?
lastErrorCategory?
```

Rules:

- tapping send snapshots current body and attachments into an operation
- the visible composer can clear only after the operation leaves `Draft` and
  enters `Queued`/`Sending`
- if send fails before server confirmation, restore the body/attachments or keep
  an inline failed row with retry, but do not silently drop user input
- retry must use the original operation payload unless the user explicitly edits
  before retry

## Collaboration Features As Extensions

These are not part of the IM baseline and must not break it:

- create task from message
- slash commands
- action-card execution
- pending mention action resolution
- diagnostics feedback composer seed
- selected-text quote

Each extension should provide an adapter into the baseline:

```text
ExtensionIntent -> ComposerSeed | ComposerBanner | SendMetadata
```

No extension should own the send target or draft persistence directly.

## UI Contract

User-visible states already present or expected to stay covered:

- idle empty composer
- draft restored
- sending
- send failed retryable
- send blocked with reason
- attachment uploading
- attachment failed
- quote/reply context attached
- pending mention action notice

Recommended copy categories:

- `sending`
- `offline_retry_available`
- `permission_blocked`
- `attachment_upload_failed`
- `session_expired`
- `server_switch_in_progress`

The UI may localize copy, but the state categories should become typed over
time. This is a cleanup/testability goal, not proof that current failure UI is
absent.

## Existing Code Touchpoints

Current KMP paths to preserve while refactoring:

- `SharedThreadComposerDraftStore`
  - draft text/asTask persistence
  - active draft projection for Home rows
  - staged uploaded attachments
- `SharedThreadStore`
  - local send started/failure/replacement
  - failed send retention/dismissal
- `ThreadPage.kt`
  - keyboard, quote, attachment strip, and send UI rendering

## Acceptance Tests

Shared tests:

- draft key encodes send channel and reply parent identity
- restoring one conversation draft does not restore another conversation draft
- staged uploaded attachments are consumed once
- failed send restore keeps body/asTask/attachments for the same identity
- changing text after a failure clears only that failure
- send operation snapshot is immutable after retry starts

Android connected tests:

- keyboard open/close with multiline composer does not hide send button
- failed send row remains after Thread refresh
- reply composer draft is separate from root composer draft
- attachment upload failure renders a retryable state without sending

Visual cases:

- `components.thread.composer.empty`
- `components.thread.composer.multiline`
- `components.thread.composer.reply-context`
- `components.thread.composer.attachments-uploading`
- `components.thread.composer.send-failed`
- `components.thread.composer.pending-actions`

## Non-Goals

- Do not implement rich text selection internals here.
- Do not require multiple concurrent sends in V1.
- Do not move task/action-card business logic into the composer baseline.
- Do not pass active composer state through production route payloads.
