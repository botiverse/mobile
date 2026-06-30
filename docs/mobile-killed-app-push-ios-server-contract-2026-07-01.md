# iOS Killed-App Push Server Contract

Date: 2026-07-01

## Background

This document records the iOS side of the killed-app offline push contract
requested in `#raft-mobile-reconcile:ad844ed9`.

The prior killed-app push research concluded that iOS must use APNs. iOS does
not allow a general-purpose always-running app process for receiving chat
messages. APNs notification delivery and `aps.badge` are the reliable system
entry points when the app is suspended or killed.

The existing cross-platform analysis is
`docs/mobile-native-push-notification-analysis-2026-06-25.md`.

## Current Server Baseline

The existing server push chain is browser Web Push only:

- target resolution: `third_party/slock-source/packages/server/src/services/messageService.ts`
- web push sender: `third_party/slock-source/packages/server/src/services/pushService.ts`
- web routes: `third_party/slock-source/packages/server/src/routes/push.ts`
- browser subscription table: `push_subscriptions`

iOS must reuse the same target resolution but needs an APNs provider and APNs
device-token storage. It must not reuse browser `/api/push/subscribe`.

## Server Refactor Required

Add APNs as another transport behind the same push-target map:

```text
message persisted
  -> mention facts persisted
  -> buildPushTargets*()
  -> WebPushProvider
  -> ApnsPushProvider
```

Do not calculate iOS recipients separately from web recipients. Platform
transports may differ, but user eligibility must remain one server-side
contract.

## Native Device Registration

Add native-device rows for iOS APNs tokens.

Suggested table additions:

```text
native_push_devices
  id
  user_id
  platform              -- "ios"
  provider              -- "apns"
  device_id             -- stable install id
  token_hash
  encrypted_token       -- APNs device token
  bundle_id             -- current iOS bundle id, expected build.raft.app
  environment           -- "sandbox" | "production"
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

Important APNs-specific requirements:

- sandbox and production tokens are distinct; store `environment`
- token can change after reinstall/restore; registration must be idempotent and
  replace old token for the same install
- APNs feedback such as `Unregistered` must revoke the token
- never log raw APNs tokens

## API Contract

Reuse the native push API family, with iOS-specific provider fields:

```http
POST /api/push/native/devices
DELETE /api/push/native/devices/:deviceId
PATCH /api/push/native/devices/:deviceId
POST /api/push/native/test
```

`POST /api/push/native/devices` request:

```json
{
  "platform": "ios",
  "provider": "apns",
  "deviceId": "stable-install-id",
  "token": "apns-device-token",
  "bundleId": "build.raft.app",
  "environment": "sandbox",
  "appVersion": "1.2.3",
  "osVersion": "18.0",
  "deviceModel": "iPhone",
  "serverIds": ["65598229-cd0a-4e92-96f3-c33e7e2e4cc1"],
  "enabled": true
}
```

## APNs Payload Contract

For visible notifications, send an APNs alert payload with routing identity in
custom fields.

Example:

```json
{
  "aps": {
    "alert": {
      "title": "Raft",
      "body": "Message preview"
    },
    "badge": 12,
    "sound": "default",
    "thread-id": "server:channel-or-thread",
    "category": "RAFT_MESSAGE"
  },
  "type": "message",
  "serverId": "server-id",
  "channelId": "channel-id",
  "threadId": "thread-or-channel-stream-id",
  "parentMessageId": "optional-parent-message-id",
  "messageId": "message-id",
  "category": "dm|mention|channel|thread_reply|task_mention",
  "notificationKey": "server:channel-or-thread:category",
  "url": "/s/server-slug/c/channel-or-thread",
  "serverSeq": "12345"
}
```

Rules:

- server owns `aps.alert.title`, `aps.alert.body`, and `aps.badge`
- use `aps.badge = 0` only when intentionally clearing the app badge
- include route identity in custom payload fields; iOS should route by ids, not
  parse text
- use APNs `thread-id` for notification grouping
- keep payload below APNs size limits
- add a privacy mode later by replacing body with a generic string while
  retaining route identity

Silent push is not the killed-app notification path. It can be a future
best-effort sync hint, but must not be the only way to alert the user.

## APNs Provider Requirements

Server needs:

- APNs auth key / team id / key id / bundle id configuration
- environment routing: sandbox for development builds, production for release
- collapse/thread strategy for replaceable unread notifications
- response handling:
  - `BadDeviceToken` / `DeviceTokenNotForTopic` -> mark token invalid for that
    environment or bundle
  - `Unregistered` -> revoke token
  - transient 5xx / rate limits -> retry with backoff
- metrics: sent, failed, revoked, skipped by mute/settings, skipped self

Message send must not wait on APNs success. Delivery should be queued or fired
as a post-persist side effect.

## Badge Semantics

iOS badge is server-authoritative. The server must choose one product policy:

- global unread count across all joined servers; or
- current/last-selected server unread count.

The killed-app research recommends global unread for app icon badge because the
OS badge is app-level, not server-level. If product chooses selected-server
badge anyway, document how the server knows which server is selected on each
device.

On app resume, iOS must refresh unread state and call the local badge API to
correct stale counts after reads from another device or missed APNs delivery.

## Client Boundary

iOS client responsibilities:

- request notification permission
- obtain APNs device token and register it
- revoke on logout/account switch/notification disable
- handle notification click and route by `serverId/channelId/threadId/messageId`
- refresh unread and badge state on resume

Server responsibilities:

- calculate recipients from canonical message/mention/follow/mute state
- send APNs alert payloads with route identity and badge count
- own token lifecycle and provider feedback

## Acceptance Criteria

Server V1 is ready for iOS killed-app push when:

1. APNs tokens are stored in native-device storage, not browser
   `push_subscriptions`.
2. Sandbox/production tokens are separated by environment and bundle id.
3. APNs provider shares `buildPushTargets*()` with web/native push.
4. Test push can deliver an alert to an installed iOS build and route into the
   app by ids.
5. Badge count is server-authoritative and corrected on app resume.
6. APNs invalid-token responses revoke token rows.
7. Logs/metrics do not expose raw APNs tokens.
