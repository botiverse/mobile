# IM Core Cross-Platform Fixture Matrix

Date: 2026-07-03
Owner: Android-Developer-3
Status: proposal

## Goal

Define a small executable matrix for IM correctness across shared reducers,
Android connected tests, React provider captures, and OHOS/iOS smoke coverage.

The goal is not maximum visual coverage. It is to pin the core IM state
transitions that users notice immediately:

- open conversation
- receive message
- send / fail / retry
- reply context
- read/unread convergence
- media preview
- notification click
- foreground recovery

## Test Layers

### Shared Reducer / Store Tests

These are the first line of defense. They should run without native UI.

Use for:

- lifecycle state transitions
- route identity checks
- draft key scoping
- unread projection freshness
- gap-sync cursor selection
- failed-send retention

### Android Connected Visual / Probe

Use for:

- Kuikly host route/pageData behavior
- keyboard/safe-area/composer layout
- native bridge effects such as app badge and notification click routing
- real rendered message row/media/composer states

Probe tests can assert bridge state even when a screenshot is not useful.

### React Provider Capture

Use for:

- visual parity with the web source of truth
- stable fixture data for cross-render comparison
- validating user-facing grouping/copy/action placement

### OHOS / iOS Smoke

Use for:

- route payload shape
- safe-area and host bridge contracts
- notification click identity
- media asset loading smoke
- no production visual fixture leakage

## Matrix

| Path | Shared test | Android connected | React provider | OHOS/iOS smoke |
| --- | --- | --- | --- | --- |
| Open channel by identity | route accepts id only, rejects content payload | opens Thread from Home/Search/Notification identity | channel screen fixture | route payload decode smoke |
| Open DM by identity | DM stream identity resolves sendChannelId | Members/DM open lands in correct thread | DM screen fixture | route payload decode smoke |
| Open reply thread | parent + reply stream identity retained | focused parent/reply anchor visible | reply thread fixture | route payload decode smoke |
| Receive realtime message | store merges by id/seq | visible Thread updates without refresh | message row new state | realtime mapper smoke |
| Recover seq gap | visible stream uses stream max seq | foreground after missed message fills gap | recovered timeline fixture | API parser smoke |
| Send text success | optimistic row replaced by server row | send row becomes sent, no duplicate | sent row fixture | network mock smoke |
| Send failure retry | failed row retained/retryable | failed row after refresh + retry | failed composer/row fixture | none required V1 |
| Draft restore | draft scoped by send channel + parent | root/reply drafts do not leak | composer draft fixture | persistence smoke |
| Mark read | read gate idempotent; unread projection fresh | Thread read clears Home/AppBadge after reconcile | unread badge fixture | app badge bridge smoke |
| Mention unread | mention count separate from unread count | Home/Activity mention badge stable | mention row fixture | parser smoke |
| Media group preview | group stable, permissions/cache state typed | multi-image gallery opens from group | media row fixture | asset loading smoke |
| Notification click | intent route identity only | click opens focused message | not applicable | native click route smoke |
| Foreground recovery | foreground request dispatches store refresh | background/foreground refreshes Thread + status | not applicable | app lifecycle smoke |
| Presence budget | non-visible presence does not mutate visible rows | large Home list no jank on status refresh | presence row fixture | member refresh smoke |

## Required Fixture Data

Use one canonical server:

```text
serverId = srv-im-core
currentUserId = user-me
channelId = ch-general
dmChannelId = dm-agent-1
replyParentMessageId = msg-parent-1
replyThreadChannelId = th-parent-1
```

Messages:

- `msg-1`: old read human message
- `msg-2`: unread mention to current user
- `msg-3`: agent message with two image attachments
- `msg-4`: reply parent with unread replies
- `local-send-1`: optimistic sending row
- `local-send-2`: failed retryable row

State variants:

- normal loaded
- reconciling gap
- stale unread held
- foreground recovered
- send failed
- media expired

## Naming

Suggested shared case ids:

```text
screens.thread.im.channel.loaded
screens.thread.im.channel.unread
screens.thread.im.reply.context
components.thread.im.message.sending
components.thread.im.message.failed
components.thread.im.message.media-group
components.thread.im.composer.baseline
components.thread.im.composer.failure
flows.thread.im.notification-click
flows.thread.im.foreground-recovery
```

Use `screens.*` when the full page matters. Use `components.*` when comparing
one row/composer/media group. Use `flows.*` for probes that validate state but
do not have a meaningful static screenshot.

## Guardrails

- Visual fixtures may construct rich state directly, but production routes may
  not pass that state through pageData.
- React and KMP fixtures should share identifiers and semantic fixture data, but
  do not need byte-identical implementation mocks.
- Android connected probes should write artifact metadata with state summary so
  failures are debuggable without private content.
- Do not add giant fixture matrices before the state machine is named; a small
  matrix with strong semantics is better than many shallow screenshots.

## Acceptance

The IM core matrix is ready when:

- all case ids exist in the shared visual-testing manifest or an explicit probe
  list
- each case documents its canonical state setup
- Android can run the core subset independently from the full visual suite
- React provider can render the matching visual cases
- OHOS/iOS smoke has an owner and command for route/host checks
- failing any P0 state transition blocks IM mainline release work
