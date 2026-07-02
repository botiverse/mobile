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

Split the current push service into two layers:

1. Target resolution: one durable recipient and payload decision path.
2. Provider delivery: Web Push, FCM, APNs, and OHOS provider transports.

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

## Native Device Storage

Add native-device storage separate from browser `push_subscriptions`. Browser
rows store service-worker `endpoint + p256dh + auth`; native apps need provider
tokens and platform metadata.

Suggested table:

```text
native_push_devices
  id
  user_id
  platform              -- "android" | "ios" | "ohos"
  provider              -- "fcm" | "apns" | "huawei-push" | "ohos-push"
  device_id             -- stable app install id
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

Security requirements:

- never log raw provider tokens
- store raw tokens encrypted or in the existing secret-safe storage pattern
- use `token_hash` for uniqueness, lookup, and diagnostics
- revoke tokens on provider invalidation, logout, account switch, or
  notification disable

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
  "deviceId": "stable-install-id",
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
- test endpoint sends a payload to the caller's selected device only

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
  "notificationKey": "server:channel-or-thread:category",
  "title": "Raft",
  "body": "Message preview",
  "url": "/s/server-slug/c/channel-or-thread",
  "badgeCount": "12",
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
- `notificationKey` should be stable enough for platform grouping/replacement
- `serverSeq` helps clients reconcile stale or out-of-order push

## Badge Policy

Server must choose and document one badge policy before launch:

1. Global unread count across all joined servers.
2. Current or last-selected server unread count per device.

Global unread is the safer default because OS app badges are app-level. If
selected-server badge is chosen, server needs per-device selected-server state
and clear rules for devices that have not reported one.

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
  -> enqueue or fire provider fanout
  -> emit realtime separately
```

Failure behavior:

- invalid token: revoke row and continue
- muted/disabled/self: skip before provider call
- transient provider error: retry with backoff
- rate limit: retry or queue; do not rollback message send
- provider outage: expose metrics/alerts; app resume still reconciles state

Observability:

- target count by category
- provider target count
- sent/failure/retry/revoked counts by provider/platform
- skipped mute/disabled/self counts
- latency histogram for provider delivery
- no raw tokens in logs or traces

## Implementation Path

### Phase 0: Server review

- Confirm target eligibility semantics for native push.
- Confirm badge policy.
- Confirm route naming for native push endpoints.
- Confirm token storage encryption and migration pattern.
- Confirm provider credential ownership and environments.

### Phase 1: Refactor without behavior change

- Extract current VAPID send path into `WebPushProvider`.
- Keep existing browser routes and table unchanged.
- Add tests proving current Web Push target behavior is unchanged.
- Add provider result logging/metrics around the existing transport.

### Phase 2: Native device registration

- Add `native_push_devices` and optional scope table.
- Add native register/delete/patch/test route shells.
- Add token redaction, hashing, encryption, idempotent refresh, and revocation.
- Add API tests for auth, token rotation, logout/disable revoke, and no raw
  token logging.

### Phase 3: Provider fanout framework

- Change `sendPushNotifications()` to fan out the same target map to enabled
  providers.
- Add feature flags/provider config so FCM/APNs/OHOS can ship independently.
- Add retry/backoff boundary outside message persistence.
- Add tests proving native providers reuse `buildPushTargets*()`.

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
2. Web, Android, iOS, and OHOS share one target-resolution result.
3. Native payload includes explicit route ids and server-authoritative category,
   title/body, and badge count.
4. Provider sends are side effects after durable message writes and do not
   rollback message creation.
5. Invalid token, retry, muted/disabled/self, and provider outage behaviors are
   specified and observable.
6. Raw provider tokens are never printed in logs, traces, task comments, or
   public channels.
7. Mobile resume reconciliation remains mandatory even after push ships.
