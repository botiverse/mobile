# Android Killed-App Push Server Contract

Date: 2026-07-01

## Background

This document records the Android side of the killed-app offline push contract
requested in `#raft-mobile-reconcile:ad844ed9`.

Prior research in `#native:7450907d` and `#native:179028d6` reached the same
conclusion:

- `message:new` / Socket.IO is best-effort and is not reliable enough for
  killed-app notification delivery.
- A normal always-running Android background process is not the right model.
- Killed-app delivery must use a platform push transport, with FCM as Android
  V1 and domestic vendor channels as later providers.
- App resume must reconcile through canonical server state because push can be
  delayed, dropped, reordered, or suppressed by OS policy.

The existing cross-platform analysis is
`docs/mobile-native-push-notification-analysis-2026-06-25.md`.

## Current Server Baseline

Current web push code lives in `third_party/slock-source`:

- `packages/server/src/services/messageService.ts`
  - persists messages and mention facts
  - builds notification targets through `buildPushTargets()` /
    `buildPushTargetsFromContext()`
  - calls `sendPushNotifications()`
- `packages/server/src/services/pushService.ts`
  - currently owns VAPID Web Push delivery and `PushPayload`
- `packages/server/src/routes/push.ts`
  - exposes browser Web Push subscription routes under `/api/push`
- `packages/server/src/db/schema.ts`
  - has `push_subscriptions` for browser `PushSubscription`

Native Android must reuse message target resolution but cannot reuse browser
`/api/push/subscribe`, because that endpoint stores service-worker
`endpoint + keys.p256dh + keys.auth`, not an FCM token.

## Server Refactor Required

Split the push service into a target-resolution layer and provider transports:

```ts
interface PushProvider {
  readonly name: "web-push" | "fcm" | "apns" | "ohos";
  send(targets: Array<NativePushDeliveryTarget>, payload: NativePushPayload): Promise<PushProviderResult>;
}
```

Recommended shape:

- keep `buildPushTargets*()` in `messageService.ts` as the source of truth for
  recipient eligibility
- keep existing VAPID delivery as `WebPushProvider`
- add `FcmPushProvider`
- make `sendPushNotifications()` fan out the same target map to web and native
  providers
- do not add a second Android-only recipient calculation path

Eligibility must continue to be based on durable server state:

- message row is persisted
- mention facts are written
- sender is excluded
- server mute and notification settings are applied
- thread replies target followed users only
- joint-channel messages target the local server member projection only

## Native Device Registration

Add native-device storage separate from browser push subscriptions.

Suggested table:

```text
native_push_devices
  id
  user_id
  platform              -- "android"
  provider              -- "fcm" for V1
  device_id             -- stable app install/device id from client
  token_hash            -- unique lookup / safe logging key
  encrypted_token       -- FCM registration token
  package_name          -- "build.raft.app" for current Android app
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

If notification preferences are server-scoped, add either `server_id` to the
registration row for V1 or a join table:

```text
native_push_device_scopes
  device_id
  server_id
  enabled
  last_seen_at
  revoked_at
```

Do not overload the existing browser `push_subscriptions` table.

## API Contract

Add native routes under `/api/push/native` or an equivalent versioned prefix.

V1 endpoints:

```http
POST /api/push/native/devices
DELETE /api/push/native/devices/:deviceId
PATCH /api/push/native/devices/:deviceId
POST /api/push/native/test
```

`POST /api/push/native/devices` request:

```json
{
  "platform": "android",
  "provider": "fcm",
  "deviceId": "stable-install-id",
  "token": "fcm-registration-token",
  "packageName": "build.raft.app",
  "appVersion": "1.2.3",
  "osVersion": "35",
  "deviceModel": "Pixel 9a",
  "serverIds": ["65598229-cd0a-4e92-96f3-c33e7e2e4cc1"],
  "enabled": true
}
```

Semantics:

- create or refresh token idempotently
- rotate token by replacing the old token for the same `deviceId + provider`
- update `lastSeenAt` on every successful registration
- revoke on logout, account switch, notification preference disable, or token
  invalidation
- never log full tokens in server logs

## FCM Payload Contract

Use FCM data messages for Android V1 so the native receiver can construct the
same local notification shape used by the current app. If notification payloads
are also used for system tray fallback, keep the same data fields present.

Required data fields:

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

Rules:

- server generates title/body/category; Android should not infer eligibility
  from socket state
- `notificationKey` must be stable enough for Android notification replacement
  / grouping
- include explicit ids even when `url` is present, so native launch can route
  by identity, not parse display text
- `badgeCount` should be server-authoritative for the target server or the
  chosen global badge policy
- payload should remain small and privacy-safe; add a server-side privacy mode
  later if notification previews must be hidden

## Delivery Behavior

Android server delivery must handle:

- expired / invalid FCM token -> mark token revoked
- transient provider failure -> retry with backoff
- provider rate limit -> retry queue, not message send rollback
- muted server / disabled native scope -> skip before provider call
- self-sent message -> skip
- force stop caveat: Android may not deliver FCM to a force-stopped package
  until the user opens the app again

Message send must not block on FCM success. Push delivery is a side effect after
durable message write and should be observable through logs/metrics.

## Client Boundary

Android client responsibilities:

- obtain and refresh FCM token
- register/revoke token with the native endpoints
- request and honor `POST_NOTIFICATIONS`
- receive FCM and call the existing local notification poster
- on notification click, launch by `serverId/channelId/threadId/messageId`
- on app resume, refresh unread/thread state and clear or correct stale badge

Server responsibilities:

- decide recipients
- decide title/body/category/badge count
- send FCM to all enabled Android devices for target users
- track provider results and token health

## Acceptance Criteria

Server V1 is ready for Android killed-app push when:

1. Native device registration endpoints exist and are authenticated.
2. FCM tokens are stored separately from browser Web Push subscriptions.
3. Existing `buildPushTargets*()` tests still pass and native fanout reuses the
   same target map.
4. A test send can deliver a data payload to an Android device and route into
   the existing notification launch path.
5. Invalid tokens are revoked automatically.
6. Server logs/metrics expose target count, provider count, success/failure,
   and skipped muted/disabled/self cases without printing raw tokens.
7. App resume reconciles unread/badge state from canonical server APIs.
