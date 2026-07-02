# Mobile Native Push Server Requirements

Date: 2026-07-02

Status: Draft for server review. This document is a design and implementation
path request only; it does not start implementation.

Related platform contracts:

- `docs/mobile-killed-app-push-android-server-contract-2026-07-01.md`
- `docs/mobile-killed-app-push-ios-server-contract-2026-07-01.md`
- `docs/mobile-killed-app-push-ohos-server-contract-2026-07-01.md`
- `docs/mobile-native-push-notification-analysis-2026-06-25.md`

## Goal

Enable killed-app offline push notifications for Android, iOS, and OHOS by
adding native push transports on the server side while keeping notification
eligibility anchored in the existing durable message pipeline.

The key product requirement is: when the mobile app process is killed or
suspended, notification delivery must not depend on Socket.IO, Kuikly runtime,
or any always-running client background process. The server must fan out through
platform push providers after a message is durably persisted.

## Non-Goals

- Do not implement platform clients in this server task.
- Do not replace existing browser Web Push.
- Do not create separate recipient rules per mobile platform.
- Do not rely on client-side socket state to decide whether a message should
  notify.
- Do not promise delivery after OS-level force stop. Android force stop and
  provider-specific suppression remain platform limits until the user opens the
  app again.

## Current Baseline

Current push logic lives in `third_party/slock-source`:

- `packages/server/src/services/messageService.ts`
  - persists messages
  - writes mention facts
  - builds notification targets through `buildPushTargets()` /
    `buildPushTargetsFromContext()`
  - calls `sendPushNotifications()`
- `packages/server/src/services/pushService.ts`
  - sends VAPID browser Web Push payloads
- `packages/server/src/routes/push.ts`
  - exposes browser push subscription/test routes
- `packages/server/src/db/schema.ts`
  - stores browser `push_subscriptions`

This chain is the right source of truth because target resolution happens after
durable database writes and mention facts. Native mobile push should reuse that
target map and add providers behind it.

## Required Server Architecture

Split the current push service into three layers:

1. Target resolution: one durable recipient decision path.
2. Provider-neutral payload construction: one logical native/web notification
   payload contract.
3. Provider delivery: Web Push, FCM, APNs, and OHOS provider transports.

The existing `buildPushTargets*()` output is currently coupled to the browser
Web Push payload shape (`title`, `body`, `tag`, `url`). Phase 1 must introduce a
provider-neutral `PushTargetResult` / `PushPayload` boundary and then adapt Web
Push from that boundary. Native providers should be added only after Web Push
parity tests prove that refactor did not change current browser behavior.

Recommended shape:

```ts
type PushProviderName = "web-push" | "fcm" | "apns" | "ohos";

interface PushProvider {
  readonly name: PushProviderName;

  send(
    targets: Array<PushDeliveryTarget>,
    payload: NativePushPayload
  ): Promise<PushProviderResult>;
}
```

Keep these rules centralized in the existing target-resolution path:

- message row is persisted before delivery
- mention facts are written before delivery
- sender is excluded
- server mute and notification settings are applied
- thread replies target followed users only
- channel/private/joint messages target eligible server members only
- visible public-channel structured mentions keep current server behavior

Server review request: confirm whether `buildPushTargets*()` already encodes the
full desired native eligibility surface, or whether product copy should narrow
or expand the documented promise before native push ships.

Before implementation, Phase 0 must explicitly audit and lock this target
contract with regression tests. Do not assume the current target map is already
complete for native push. The audit should cover:

- server/channel membership and visibility
- private and joint channel visibility boundaries
- mute and notification preference filtering
- thread follow and thread reply targeting
- mention facts write timing
- task mention category behavior
- sender self-exclusion
- same-user multi-device fanout

Native push must promise exactly the `buildPushTargets*()` result. Provider
code may filter disabled devices or invalid tokens, but it must not recalculate
message eligibility.

## Native Device Storage

Add native-device storage separate from browser `push_subscriptions`. Browser
rows store service-worker `endpoint + p256dh + auth`; native apps need provider
tokens and platform metadata.

Suggested table:

```text
native_push_devices
  id                    -- server-generated native device id
  user_id
  platform              -- "android" | "ios" | "ohos"
  provider              -- "fcm" | "apns" | "huawei-push" | "ohos-push"
  client_device_id      -- stable app install id from the client
  token_hash            -- unique lookup / safe logging key
  encrypted_token       -- provider token
  app_id                -- optional provider app/project id if needed
  package_name          -- Android package, if platform = android
  bundle_id             -- iOS bundle id, if platform = ios
  bundle_name           -- OHOS bundle name, if platform = ohos
  environment           -- "sandbox" | "production" where applicable
  app_version
  os_version
  device_model
  locale
  timezone
  enabled
  last_seen_at
  revoked_at
  created_at
  updated_at
```

If notification enablement is server-scoped, add either `server_id` to the
registration row for V1 or a separate scope table:

```text
native_push_device_scopes
  device_id
  server_id
  enabled
  last_seen_at
  revoked_at
```

Preferred shape: keep scopes in `native_push_device_scopes`. Provider token
identity is app-install/provider scoped; server enablement is workspace/server
scoped. Splitting them avoids duplicating encrypted tokens for every server and
keeps token rotation independent from server-level enable/disable state.

If a faster V1 puts `server_id` directly on `native_push_devices`, document it
as temporary and define the uniqueness and rotation semantics up front, for
example `user_id + platform + provider + client_device_id + server_id`.

Security requirements:

- never log raw provider tokens
- store the provider token in `encrypted_token` using reversible encryption at
  rest because FCM/APNs/OHOS sends need the plaintext destination token at
  delivery time
- never use hash-only storage as the primary storage for device push tokens
- use `token_hash` only for uniqueness, idempotent refresh, lookup, diagnostics,
  and log correlation without plaintext
- compute `token_hash` with a server-secret HMAC-SHA256 or equivalent keyed
  hash; do not expose a raw digest as a public identifier
- revoke tokens on provider invalidation, logout, account switch, or
  notification disable
- provider credentials must come from server configuration allowlists; client
  fields such as platform/provider/package/bundle/app id are inputs to validate
  and record, not inputs that select arbitrary credentials

Provider API credentials are a separate secret class from device tokens and must
have their own Phase 0 decision:

- FCM service-account JSON or server key
- APNs `.p8` auth key, key id, team id, and bundle topic or certificate
- OHOS/Huawei app secret and provider app id/project id

These credentials must live in the approved server secret store, such as Fly
secrets or the production secret manager. They must not be committed to the
repo, stored in plaintext config, printed in logs, included in traces, or echoed
into task comments. The implementation plan must define credential owner,
environment separation, rollout, and rotation, with special attention to
long-lived APNs `.p8` keys.

Implementation gate before native storage or provider work:

1. Provider API credentials: secret-store, environment separation, rotation,
   and scrub policy for FCM service account/server key, APNs `.p8` or
   certificate material, and OHOS/Huawei secrets.
2. Device push tokens: reversible encryption at rest, KMS/key selection,
   rotation strategy, and HMAC `token_hash` lookup semantics.
3. Provider-neutral target/payload contract: `PushTargetResult` /
   `PushPayload` split plus Web Push parity regression tests.
4. Async outbox/worker: bounded provider fanout with per-provider metrics,
   limits, retry/backoff, circuit breaker behavior, dead-letter handling, and
   observability scrub.

## Native Push API

Add a native push route family. Exact route naming is up to server owners, but
the platform contracts assume:

```http
POST /api/push/native/devices
DELETE /api/push/native/devices/:deviceId
PATCH /api/push/native/devices/:deviceId
POST /api/push/native/test
```

Registration request:

```json
{
  "platform": "android",
  "provider": "fcm",
  "clientDeviceId": "stable-install-id",
  "token": "provider-token",
  "packageName": "build.raft.app",
  "bundleId": "build.raft.app",
  "bundleName": "build.raft.app",
  "environment": "sandbox",
  "appVersion": "1.2.3",
  "osVersion": "35",
  "deviceModel": "Pixel 9a",
  "serverIds": ["65598229-cd0a-4e92-96f3-c33e7e2e4cc1"],
  "enabled": true
}
```

Required semantics:

- authenticated user only
- idempotent create or refresh
- token rotation replaces old token for the same user/device/provider
- registration updates `last_seen_at`
- logout/account switch/disable deletes or revokes the device row
- server generates the internal native device id; the client supplies only
  `clientDeviceId`
- `DELETE` and `PATCH` must scope by authenticated user plus internal device id
  or `platform + provider + clientDeviceId`; same client ids from other users
  must not collide
- test endpoint sends a fixed or generic payload to the caller's own selected
  device only, is rate-limited, and does not accept arbitrary title/body
  injection

## Shared Payload Contract

All native providers should receive the same logical route identity. Provider
wire format can differ, but the native host must not parse display text to
route a notification click.

Required logical fields:

```json
{
  "type": "message",
  "serverId": "server-id",
  "channelId": "channel-id",
  "threadId": "thread-or-channel-stream-id",
  "parentMessageId": "optional-parent-message-id",
  "messageId": "message-id",
  "category": "dm|mention|channel|thread_reply|task_mention",
  "notificationKey": "server-id:channel-or-thread-id:category",
  "title": "Raft",
  "body": "Message preview",
  "url": "/s/server-slug/c/channel-or-thread",
  "badgeCount": 12,
  "serverSeq": "12345"
}
```

Provider mapping:

- Android FCM V1 can use data messages, optionally with notification fallback.
- iOS APNs must include `aps.alert`, `aps.badge`, `aps.sound`, and
  `aps.thread-id` for visible notifications, plus the route fields as custom
  payload.
- OHOS/Huawei Push Kit should map the same logical fields into provider data,
  intent, or extras.

Payload rules:

- server owns `title`, `body`, `category`, and badge count
- payload remains privacy-safe and within provider size limits
- future privacy mode can replace `body` with generic text while preserving
  route ids
- `badgeCount` is a number at the logical contract layer; providers that require
  string data fields can convert at the wire boundary
- `notificationKey` should be stable enough for platform grouping/replacement
  and should use ids rather than slugs or display names
- `serverSeq` helps clients reconcile stale or out-of-order push; document
  whether it is a per-server timeline sequence or another canonical server-side
  sequence before implementation
- `threadId` must distinguish a real thread from the root channel stream; root
  channel messages should leave `parentMessageId` empty

## Badge Policy

Server must choose and document one badge policy before launch:

1. Global unread count across all joined servers.
2. Current or last-selected server unread count per device.

Global unread is the safer default because OS app badges are app-level. If
selected-server badge is chosen, server needs per-device selected-server state
and clear rules for devices that have not reported one.

Review recommendation: V1 should use global app unread. Selected-server unread
requires extra per-device state and is easy to make stale across cold starts and
cross-device reads.

All platforms must reconcile badge and unread state from canonical server APIs
when the app resumes, because push can be delayed, dropped, duplicated, or read
from another device.

## Provider Requirements

### Web Push

- Keep current VAPID Web Push behavior.
- Move it behind `WebPushProvider` if the service is refactored.
- Existing browser `push_subscriptions` remain browser-only.

### Android FCM

- Add `FcmPushProvider`.
- Configure server-side FCM credentials outside client-controlled fields.
- Store Android package name and token health.
- Handle invalid/expired tokens by revoking device rows.
- Retry transient provider failures outside the message-send transaction.

### iOS APNs

- Add `ApnsPushProvider`.
- Store sandbox and production tokens separately by environment and bundle id.
- Configure APNs auth key, team id, key id, bundle id/topic.
- Handle `BadDeviceToken`, `DeviceTokenNotForTopic`, and `Unregistered`.
- Use APNs headers and `aps.thread-id` consistently for grouping.

### OHOS

- Add an OHOS provider abstraction rather than hardcoding Android semantics.
- Initial provider may be Huawei Push Kit or an OpenHarmony-compatible provider,
  depending on distribution target.
- Store platform/provider explicitly so multiple OHOS distribution channels can
  coexist.
- Provider credentials live in server configuration, not client payload.

## Delivery Semantics

Message send must not block on native provider success. Push delivery is a
post-persist side effect:

```text
persist message
  -> write mention facts
  -> compute push targets once
  -> enqueue provider fanout
  -> emit realtime separately
```

Minimum delivery contract: after the message write commits, enqueue provider
work synchronously. Provider sends run in a queue or worker with timeout,
retry/backoff, invalid-token revoke, and provider-result metrics. If a no-queue
V1 is used temporarily, provider failure or timeout still must not affect the
message send result and must be recorded as provider delivery output.

Review recommendation: native killed-app push should use an outbox/worker or
equivalent durable async boundary before any platform provider ships. The worker
must have bounded concurrency, per-provider timeout, circuit breaker behavior,
backoff retry, dead-letter handling, and provider quota/backpressure controls.
This avoids adding provider latency or outages to the message-write request
path.

Use `notificationKey` and `serverSeq` for idempotency and duplicate suppression
where provider semantics require it.

Failure behavior:

- invalid token: revoke row and continue
- muted/disabled/self: skip before provider call
- transient provider error: retry with backoff
- rate limit: retry or queue; do not rollback message send
- provider outage: expose metrics/alerts; app resume still reconciles state
- provider throttle or outage: open a circuit or slow the worker; do not allow
  push backlog to exhaust message-write resources
- duplicate retry: preserve idempotency with stable target and notification keys

Observability:

- target count by category
- provider target count
- sent/failure/retry/revoked counts by provider/platform
- skipped mute/disabled/self counts
- latency histogram for provider delivery
- queue depth and lag
- provider quota/backpressure events
- dead-letter count and reason
- no raw device tokens, provider credentials, title/body previews, or other
  sensitive payload fields in logs or traces; apply the existing observability
  scrub contract before emitting structured events

## Implementation Path

### Phase 0: Server review

- Confirm hard gate 1a: provider API credential secret-store, environment,
  rollout, rotation, and scrub policy; SRE should own or explicitly approve
  provisioning.
- Confirm hard gate 1b: device push token reversible encryption at rest,
  KMS/key selection, rotation strategy, and HMAC `token_hash` semantics.
- Audit and test target eligibility semantics for native push:
  server/channel membership, private/joint visibility, mute/preferences, thread
  follow/reply, mention facts timing, task mentions, self-exclusion, and
  same-user multi-device fanout.
- Confirm V1 badge policy; recommendation is global app unread.
- Confirm route naming for native push endpoints.
- Confirm whether native device scopes use a separate
  `native_push_device_scopes` table; recommendation is yes.
- Confirm hard gate 2: provider-neutral target/payload contract plus Web Push
  parity test ownership.
- Confirm hard gate 3: queue/outbox/worker boundary, retry/backoff ownership,
  dead-letter policy, provider quota handling, metrics, limits, and circuit
  breaker behavior.

### Phase 1: Refactor without behavior change

- Extract a provider-neutral `PushTargetResult` / `PushPayload` boundary from
  the current Web Push-shaped output.
- Extract current VAPID send path into `WebPushProvider`.
- Keep existing browser routes and table unchanged.
- Add tests proving current Web Push target behavior is unchanged.
- Add provider result logging/metrics around the existing transport.
- Do not change current browser push behavior while extracting providers.

### Phase 2: Native device registration

- Add `native_push_devices` and preferably `native_push_device_scopes`.
- Add native register/delete/patch/test route shells.
- Add reversible encrypted token storage, HMAC token hashing, idempotent refresh,
  redaction, and revocation.
- Add API tests for auth, token rotation, logout/disable revoke, and no raw
  token logging.
- Add API tests for same-user multi-device, disabled/revoked/stale token skip,
  scope disabled skip, account switch revoke, and test endpoint caller-device
  restriction.

### Phase 3: Provider fanout framework

- Change `sendPushNotifications()` to fan out the same target map to enabled
  providers.
- Add feature flags/provider config so FCM/APNs/OHOS can ship independently.
- Add queue/outbox/worker retry/backoff boundary outside message persistence,
  with bounded concurrency, per-provider timeout, circuit breaker, dead-letter,
  and quota/backpressure controls.
- Add tests proving native providers reuse `buildPushTargets*()`.
- Add tests proving provider failure does not roll back message creation.
- Add metrics tests or instrumentation points for provider results, queue depth,
  queue lag, token invalidation, and scrubbed payload logging.

### Phase 4: Platform providers

- Implement FCM V1 first for Android.
- Implement APNs for iOS with sandbox/production separation.
- Implement OHOS provider once the distribution provider is confirmed.
- Each provider needs a test-send path, invalid-token handling, and metrics.

### Phase 5: Mobile integration and reconcile

- Mobile clients register/revoke tokens.
- Notification click routes by `serverId/channelId/threadId/messageId`.
- App resume refreshes unread/thread state and corrects local badge.
- End-to-end tests validate killed/suspended app delivery where platform test
  environments allow it.

## Acceptance Criteria

Server-side design is ready to implement when reviewers agree on:

1. Native push devices are separate from browser `push_subscriptions`.
2. Web, Android, iOS, and OHOS share one provider-neutral target result and
   payload boundary.
3. Native payload includes explicit route ids and server-authoritative category,
   title/body, and badge count.
4. Target contract audit tests cover membership, visibility, mute/preferences,
   thread follow/reply, mention facts timing, task mentions, self-exclusion, and
   same-user multi-device fanout.
5. Provider sends are queued/outbox side effects after durable message writes
   and do not rollback message creation.
6. Invalid token, retry, muted/disabled/self, and provider outage behaviors are
   specified and observable.
7. Raw device tokens and provider credentials are never printed in logs,
   traces, task comments, or public channels.
8. Provider credentials are selected only from server-side secret configuration,
   not client-supplied fields, and have owner-approved provisioning, rotation,
   and scrub policies.
9. Queue depth, lag, provider latency, retry, revoked-token, quota, and
   dead-letter metrics are defined.
10. Mobile resume reconciliation remains mandatory even after push ships.
