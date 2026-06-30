# iOS Killed-App Push Server Plan (APNs)

Date: 2026-07-01
Status: draft for server/mobile alignment
Related docs:

- `docs/mobile-native-push-notification-analysis-2026-06-25.md`
- `docs/killed-app-launcher-badge-spec-2026-06-24.md`

Source discussions:

- `#native:7450907d` / `db99c81a`: iOS/APNs badge is server authoritative through `aps.badge`; no always-running app process.
- `#native:179028d6` / `a464904a`: existing server push is Web Push/VAPID; native platforms need provider abstraction.

Official references:

- APNs provider API: <https://developer.apple.com/documentation/usernotifications/setting-up-a-remote-notification-server>
- APNs payload keys: <https://developer.apple.com/library/archive/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/PayloadKeyReference.html>
- Creating notification payloads: <https://developer.apple.com/library/archive/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/CreatingtheNotificationPayload.html>
- UserNotifications framework: <https://developer.apple.com/documentation/usernotifications>

## Executive Summary

iOS killed-app notification and badge delivery should use APNs. The server must
own badge count in the APNs payload. Unlike Android, APNs can directly update
the app icon badge with `aps.badge` even when the app is not running.

The existing server Web Push/VAPID service is not reusable as-is. Server should
share target resolution across transports, then send via an `ApnsPushProvider`.

```text
message write + mention facts
  -> buildPushTargets*()
  -> PushFanoutService
       -> WebPushProvider        existing browser VAPID
       -> FcmPushProvider        Android
       -> ApnsPushProvider       iOS
       -> OhosPushProvider       HarmonyOS/OHOS
```

## Server Data Model

Use the same `native_push_devices` / `push_devices` table proposed for Android,
with iOS-specific fields.

Recommended columns:

| Column | Purpose |
|---|---|
| `id` | server UUID |
| `user_id` | authenticated user |
| `server_id` | Slock server scope |
| `platform` | `ios` |
| `provider` | `apns` |
| `token_hash` | stable hash for logs/unique keys |
| `token_ciphertext` or `token` | APNs device token; treat as secret |
| `apns_environment` | `sandbox` or `production` |
| `bundle_id` | iOS app bundle id / APNs topic |
| `device_id` | optional native device id |
| `installation_id` | stable app install id |
| `app_version` / `app_build` | rollout/debugging |
| `notifications_enabled` | latest native opt-in state |
| `badge_enabled` | if badge can be toggled separately |
| `last_seen_at` | token refresh/app resume |
| `revoked_at` | logout/revoke |
| `created_at` / `updated_at` | timestamps |

Indexes:

- Unique active install:
  `(user_id, server_id, platform, provider, installation_id, apns_environment)`
  where `revoked_at IS NULL`.
- Token cleanup: `(provider, apns_environment, token_hash)`.
- Fanout lookup:
  `(server_id, user_id, platform, provider, revoked_at, notifications_enabled)`.

## Server APIs

Use the same native endpoints as Android and branch on platform/provider.

### `POST /push/native/subscribe`

Request:

```json
{
  "platform": "ios",
  "provider": "apns",
  "token": "apns-device-token",
  "apnsEnvironment": "production",
  "bundleId": "ai.slock.ios",
  "deviceId": "optional-native-device-id",
  "installationId": "stable-app-install-id",
  "appVersion": "1.2.3",
  "appBuild": "123",
  "notificationsEnabled": true,
  "badgeEnabled": true
}
```

Behavior:

- Verify auth, email verification, and server membership.
- Validate `bundleId` against server config; do not accept arbitrary APNs
  topics from clients.
- Upsert by install identity.
- Revoke superseded token rows on token refresh.

### `DELETE /push/native/subscribe`

Used on logout/account switch/notification disable. Set `revoked_at`; do not
hard-delete immediately.

### `POST /push/native/test`

Sends an APNs test push to the current iOS device.

## APNs Provider Configuration

Add server config:

- `APNS_KEY_ID`
- `APNS_TEAM_ID`
- `APNS_BUNDLE_ID`
- `APNS_PRIVATE_KEY` or path/secret reference
- `APNS_ENVIRONMENT=sandbox|production`
- `NATIVE_PUSH_APNS_ENABLED=true`

Use token-based APNs auth (JWT) rather than certificate auth for server
operations.

## iOS Payload Contract

APNs payload:

```json
{
  "aps": {
    "alert": {
      "title": "Raft",
      "body": "Message preview"
    },
    "badge": 12,
    "sound": "default",
    "thread-id": "server:thread",
    "category": "SLOCK_MESSAGE",
    "mutable-content": 0,
    "content-available": 0
  },
  "type": "slock.message",
  "serverId": "server uuid",
  "channelId": "channel uuid",
  "threadId": "thread/channel uuid",
  "parentMessageId": "optional parent id",
  "messageId": "message uuid",
  "category": "dm|mention|channel|thread_reply|task_mention",
  "notificationKey": "server:thread:message",
  "routeUrl": "/s/<server>/channels/<channel>/...",
  "serverSeq": "8099915",
  "createdAt": "2026-07-01T00:00:00.000Z"
}
```

Required APNs headers:

| Header | Value |
|---|---|
| `apns-topic` | configured iOS bundle id |
| `apns-push-type` | `alert` for visible notifications |
| `apns-priority` | `10` for user-visible messages |
| `apns-collapse-id` | deterministic collapse key for badge-only/read updates |
| `apns-expiration` | short TTL for message previews; zero/short for tests |

For badge-only updates after read state changes:

```json
{
  "aps": {
    "badge": 0,
    "content-available": 1
  },
  "type": "slock.badge",
  "serverId": "server uuid",
  "badgeCount": 0,
  "serverSeq": "8099916"
}
```

Use this carefully. Silent pushes are not a reliable background execution
channel, but `aps.badge` remains the authoritative app icon badge value.

## Recipient and Privacy Rules

Use the same target-resolution rules as Web Push and Android:

- message persisted first
- mention facts persisted first
- no sender/self push
- server/channel/thread mutes respected
- membership/private/joint visibility respected
- followed-thread semantics respected
- notification preferences respected

Server owns alert copy. If privacy mode is enabled later, send generic alert
copy while retaining route identifiers in custom payload fields.

## Badge Semantics

iOS is the strongest case for server-authoritative badge:

- `aps.badge = N` sets the icon badge to `N`.
- `aps.badge = 0` clears it.
- If `badge` is omitted, the system leaves the badge unchanged.

Therefore server must send badge count on:

- new unread-creating message events
- read/mark-read events from other devices
- mute/unmute or notification setting changes that affect badge visibility
- logout/revoke cleanup where feasible; client also clears locally on logout

The count scope must match product. Prior #81 in-app alive path was
selected-server scoped; if iOS app icon should be all-server global, server must
explicitly define and test that different scope.

## Error Handling

APNs response handling should revoke invalid tokens:

- `BadDeviceToken`
- `Unregistered`
- environment/topic mismatch after verification

Transient APNs errors should retry with bounded exponential backoff. Push fanout
must happen after durable message commit and should not block message write
latency.

## Rollout Plan

1. Add shared native device table/endpoints if not already added for Android.
2. Add APNs provider config and `ApnsPushProvider`.
3. Refactor fanout to call Web Push + APNs from the same target-resolution
   result.
4. Add iOS subscribe/revoke/test endpoint support.
5. Add badge-only fanout for read/unread count changes.
6. Add tests:
   - token upsert/revoke by iOS install
   - APNs payload includes `aps.badge`
   - APNs headers include correct `apns-topic` and `apns-push-type`
   - invalid token revokes row
   - mutes/self/non-member filters match Web Push
7. iOS client registers APNs token and handles notification click routing.

## Acceptance Criteria

- Server can send APNs alert payload with route fields and `aps.badge`.
- Server can send badge-only update with `aps.badge=0` or latest count.
- Existing Web Push target resolution remains unchanged.
- APNs token values are redacted in logs.
- APNs invalid token responses revoke rows.
- Notification click can route into the same thread identity used by Android and
  web.

## Open Questions

- Is badge count selected-server scoped or all-server global on iOS app icon?
  iOS users normally expect app-icon badge to be global across the app.
- Should iOS visible notifications include full message body by default, or
  generic privacy text?
- Do we need a Notification Service Extension for future attachment previews?
  V1 should not require it.
- Do we support both sandbox and production tokens simultaneously during TestFlight
  rollout, or separate environments by deployment?
