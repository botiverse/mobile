# HarmonyOS / OHOS Killed-App Push Server Plan (Push Kit)

Date: 2026-07-01
Status: draft for server/mobile alignment
Related docs:

- `docs/mobile-native-push-notification-analysis-2026-06-25.md`
- `docs/killed-app-launcher-badge-spec-2026-06-24.md`

Source discussions:

- `#native:7450907d` / `db99c81a` and `f5f06005`: OHOS should use a native push/background entry, not a permanent Kuikly/shared process.
- `#native:179028d6` / `a464904a`: server push must become provider-based; current VAPID browser push is not enough for native.
- `#native:e5c4e13b` / `8d5a586e`: keepalive/foreground execution is not killed-app delivery; killed-app remains a platform push/provider task.

Official references:

- Huawei Push Kit overview: <https://developer.huawei.com/consumer/en/hms/huawei-pushkit/>
- Huawei Push Kit server development: <https://developer.huawei.com/consumer/en/doc/harmonyos-guides/push-server-send>
- HarmonyOS notification overview: <https://developer.huawei.com/consumer/en/doc/harmonyos-guides/notification-overview>

## Executive Summary

HarmonyOS/OHOS killed-app delivery should be implemented as a native Push Kit
provider. It should not reuse Android FCM implementation details and should not
depend on a long-running Kuikly/shared process.

Server-side, OHOS should be a first-class provider behind the same fanout
interface:

```text
message write + mention facts
  -> buildPushTargets*()
  -> PushFanoutService
       -> WebPushProvider        existing browser VAPID
       -> FcmPushProvider        Android
       -> ApnsPushProvider       iOS
       -> OhosPushProvider       HarmonyOS/OHOS Push Kit
```

V1 can land after Android FCM because the generic server shape is the same:
native token registration, provider config, provider send, invalid-token
cleanup, route payload, badge/unread reconciliation.

## Server Data Model

Use the shared native device table with OHOS-specific fields.

Recommended columns:

| Column | Purpose |
|---|---|
| `id` | server UUID |
| `user_id` | authenticated user |
| `server_id` | Slock server scope |
| `platform` | `ohos` or `harmonyos` |
| `provider` | `huawei_push_kit` |
| `token_hash` | stable hash for uniqueness and logs |
| `token_ciphertext` or `token` | Push Kit token; treat as secret |
| `app_id` | Huawei/Harmony app id |
| `bundle_name` | app bundle name |
| `device_id` | optional native device id |
| `installation_id` | stable app install id |
| `app_version` / `app_build` | rollout/debugging |
| `notifications_enabled` | latest native permission/preferences state |
| `badge_enabled` | optional if platform exposes badge separately |
| `push_scope` | optional region/environment marker if needed |
| `last_seen_at` | token refresh/app resume |
| `revoked_at` | logout/revoke |
| `created_at` / `updated_at` | timestamps |

Indexes:

- Unique active install:
  `(user_id, server_id, platform, provider, installation_id)` where
  `revoked_at IS NULL`.
- Token cleanup: `(provider, app_id, token_hash)`.
- Fanout lookup:
  `(server_id, user_id, platform, provider, revoked_at, notifications_enabled)`.

## Server APIs

Use the same native endpoint family and branch on `platform/provider`.

### `POST /push/native/subscribe`

Request:

```json
{
  "platform": "ohos",
  "provider": "huawei_push_kit",
  "token": "push-kit-device-token",
  "appId": "Harmony app id",
  "bundleName": "ai.slock.harmony",
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
- Validate `appId`/`bundleName` against server config. Do not trust arbitrary
  app ids from clients.
- Upsert active row by install identity.
- Revoke superseded token rows on token refresh.

### `DELETE /push/native/subscribe`

Used on logout/account switch/notification disable. Mark `revoked_at`.

### `POST /push/native/test`

Sends a Push Kit test payload to the current OHOS device.

## Provider Configuration

Add server config:

- `OHOS_PUSH_ENABLED=true`
- `OHOS_PUSH_APP_ID`
- `OHOS_PUSH_CLIENT_ID`
- `OHOS_PUSH_CLIENT_SECRET` or secret reference
- `OHOS_PUSH_PROJECT_ID` if required by deployed Push Kit API
- `OHOS_PUSH_REGION` if the API endpoint varies by region

The provider should own Push Kit access-token acquisition and refresh. Business
logic should never build Push Kit HTTP requests directly.

## OHOS Payload Contract

Keep the custom data shape aligned with Android/iOS route fields:

```json
{
  "type": "slock.message",
  "provider": "huawei_push_kit",
  "serverId": "server uuid",
  "channelId": "channel uuid",
  "threadId": "thread/channel uuid",
  "parentMessageId": "optional parent id",
  "messageId": "message uuid",
  "category": "dm|mention|channel|thread_reply|task_mention",
  "notificationKey": "server:thread:message",
  "collapseKey": "server:thread",
  "title": "Raft",
  "body": "Message preview",
  "routeUrl": "/s/<server>/channels/<channel>/...",
  "badgeCount": "12",
  "serverSeq": "8099915",
  "createdAt": "2026-07-01T00:00:00.000Z"
}
```

Provider-specific send request should map this into the Push Kit notification
and data/custom fields supported by HarmonyOS. Keep the canonical server payload
platform-neutral and let `OhosPushProvider` translate.

## Recipient and Privacy Rules

Reuse the same target-resolution rules as Web Push/Android/iOS:

- message persisted first
- mention facts persisted first
- no sender/self push
- server/channel/thread mutes respected
- membership/private/joint visibility respected
- followed-thread semantics respected
- notification preferences respected

Server owns alert title/body. If privacy mode is enabled later, server sends
generic text and keeps route identifiers in custom fields.

## Badge and Unread Count

OHOS launcher badge behavior depends on the platform notification and launcher
capabilities available to the app. Server should still send authoritative
`badgeCount` for every unread-affecting event:

- message/new unread increase
- read/mark-read count decrease or zero
- mute/notification preference changes

Native OHOS host decides whether that count becomes:

- a notification badge/number
- a local notification with badge metadata
- a badge-only local API call if available
- a no-op when user/system settings disable badge display

Server must not assume Android `setNumber()` or iOS `aps.badge` semantics for
OHOS. Keep `badgeCount` as a transport-neutral field and require native OHOS to
reconcile on app resume with shared `SharedHomeStore.appBadgeCount`.

## Delivery Failure Handling

`OhosPushProvider` should normalize provider errors into:

- permanent invalid token -> revoke row
- unauthorized/expired provider credential -> refresh provider access token and retry once
- rate limit/transient error -> bounded retry/backoff
- malformed payload -> fail fast and alert server logs/metrics

Do not block message write latency on provider sends. Fanout should run after
durable message commit in a worker/background job.

## Rollout Plan

1. Land shared native push device table/endpoints if Android/iOS have not
   already done so.
2. Add `OhosPushProvider` skeleton behind `OHOS_PUSH_ENABLED`.
3. Implement provider auth/token refresh.
4. Translate canonical `NativePushPayload` into Push Kit send requests.
5. Fanout existing `buildPushTargets*()` results to OHOS provider.
6. Add badge-only fanout for read/unread count changes.
7. Add tests:
   - token upsert/revoke for OHOS install
   - route payload includes canonical fields
   - provider request contains configured app id/bundle name only
   - invalid token revokes row
   - mutes/self/non-member filters match Web Push
   - provider credential refresh path does not duplicate notifications
8. OHOS client registers Push Kit token and handles push open/routing.

## Acceptance Criteria

- Server exposes native subscribe/revoke/test for `platform=ohos`.
- Server can fan out a message target to `OhosPushProvider` without duplicating
  target resolution.
- Push payload includes route identifiers and authoritative `badgeCount`.
- Provider secrets and raw device tokens are never logged.
- Invalid tokens are revoked.
- Existing browser Web Push behavior remains unchanged.
- App resume reconciliation remains the source of final badge truth.

## Open Questions

- Which exact provider name should the API expose: `ohos_push`, `huawei_push`,
  or `huawei_push_kit`? Pick one before client implementation.
- Which app id/bundle names exist for dev/staging/prod Harmony builds?
- Does product require all-server app icon badge on OHOS, or selected-server
  count matching #81?
- Do we need to support non-Huawei OpenHarmony distributions that cannot use
  Huawei Push Kit? If yes, add a second provider interface later rather than
  overloading the Push Kit provider.
- What is the expected privacy copy for locked-screen notifications on OHOS?
